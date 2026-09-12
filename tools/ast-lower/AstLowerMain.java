import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Java;

public class AstLowerMain {
    Ir.Program prog; Ir.Func curFunc; Ir.Block cur;
    Map<String, Ir.Value> allocaOf = new LinkedHashMap<>();
    Deque<Ir.Block> breaks = new ArrayDeque<>(), conts = new ArrayDeque<>();
    int dbgSeq = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ast-lower <in.mj> <out.lir>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String base = args[0].replaceAll(".*/","").replaceAll("\\.(mj|ast)$","");
        Scanner sc = new Scanner(base + ".mj", new StringReader(src));
        Java.AbstractCompilationUnit cu = new Parser(sc).parseAbstractCompilationUnit();
        AstLowerMain m = new AstLowerMain();
        m.prog = new Ir.Program(); m.prog.module = base;
        List<?> types = (List<?>) get(cu, "packageMemberTypeDeclarations");
        if (types == null) types = (List<?>) get(cu, "types");
        if (types != null) for (Object td : types) {
            if (td instanceof Java.ClassDeclaration cd) m.cls(cd);
        }
        String out = Ir.Writer.print(m.prog);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }

    // ─── reflection helper ───
    static Object get(Object o, String field) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }
    static String getStr(Object o, String field) { Object v = get(o, field); return v == null ? null : v.toString(); }
    static List<?> getList(Object o, String field) { Object v = get(o, field); return v instanceof List ? (List<?>) v : null; }
    static Optional<?> getOpt(Object o, String field) { Object v = get(o, field); return v instanceof Optional ? (Optional<?>) v : null; }

    int tag(Java.Locatable n) {
        int id = dbgSeq++;
        if (n != null) try {
            org.codehaus.commons.compiler.Location loc = n.getLocation();
            if (loc != null) prog.debug.positions.put(id, new int[]{loc.getLineNumber(), loc.getColumnNumber()});
        } catch (Throwable t) {}
        return id;
    }
    Ir.Value emit(String op, String type, Ir.Value... args) { Ir.Value v = new Ir.Value(op, type, args); cur.ins.add(v); return v; }
    Ir.Value konst(long v, int dbg) { Ir.Value x = new Ir.Value("const","i32"); x.imm=v; x.dbg=dbg; cur.ins.add(x); return x; }
    Ir.Value blockRef(Ir.Block b) { Ir.Value v = new Ir.Value("block","ptr"); v.imm=b.hashCode(); v.name=b.name; return v; }

    void cls(Java.ClassDeclaration cd) {
        List<?> methods = getList(cd, "declaredMethods");
        if (methods == null) return;
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator m)) continue;
            method(m);
        }
    }

    void method(Java.MethodDeclarator m) {
        Ir.Func f = new Ir.Func(); f.name = m.name;
        for (Object p : m.formalParameters.parameters) {
            String name = p instanceof Java.FunctionDeclarator.FormalParameter fp ? fp.name : "?";
            f.params.add(new String[]{name, "i32"});
        }
        curFunc = f; allocaOf.clear(); breaks.clear(); conts.clear();
        Ir.Block e = new Ir.Block("entry"); cur = e; f.blocks.add(e);
        for (int i = 0; i < f.params.size(); i++) {
            String name = f.params.get(i)[0];
            Ir.Value a = emit("alloca","i32"); a.dbg = dbgSeq++;
            allocaOf.put(name, a);
            prog.debug.declNames.put(a.dbg, name);
            prog.debug.declTypes.put(a.dbg, "i32");
            Ir.Value pm = new Ir.Value("param","i32"); pm.imm = i;
            Ir.Value st = emit("store","void",a,pm);
        }
        for (Object s : m.statements) if (s instanceof Java.BlockStatement bs) stmt(bs);
        if (cur.term()==null || !Ir.isTerm(cur.term().op)) emit("return","void",konst(0,-1));
        prog.funcs.add(f);
    }

    void stmt(Java.BlockStatement s) {
        if (s instanceof Java.LocalVariableDeclarationStatement d) {
            for (Java.VariableDeclarator vd : d.variableDeclarators) {
                Ir.Value a = emit("alloca","i32"); a.dbg = tag(s);
                allocaOf.put(vd.name, a);
                prog.debug.declNames.put(a.dbg, vd.name);
                prog.debug.declTypes.put(a.dbg, "i32");
                Object init = vd.initializer;
                if (init instanceof Java.Rvalue rv) {
                    Ir.Value v = expr(rv);
                    Ir.Value st = emit("store","void",a,v); st.dbg = a.dbg;
                }
            }
        } else if (s instanceof Java.ExpressionStatement es) {
            if (es.rvalue instanceof Java.Assignment a) handleAssign(a);
            else expr(es.rvalue);
        } else if (s instanceof Java.IfStatement is) {
            Ir.Value c = expr(is.condition);
            int id = dbgSeq++;
            Ir.Block t = new Ir.Block("then_"+id), e = new Ir.Block("else_"+id), j = new Ir.Block("join_"+id);
            emit("branch","void",c,blockRef(t),blockRef(e));
            curFunc.blocks.add(t); cur = t; stmt(is.thenStatement);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(j));
            curFunc.blocks.add(e); cur = e;
            if (is.elseStatement != null) stmt(is.elseStatement);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(j));
            curFunc.blocks.add(j); cur = j;
        } else if (s instanceof Java.WhileStatement w) {
            int id = dbgSeq++;
            Ir.Block h = new Ir.Block("head_"+id), b = new Ir.Block("body_"+id), x = new Ir.Block("exit_"+id);
            emit("jump","void",blockRef(h));
            curFunc.blocks.add(h); cur = h;
            Ir.Value c = expr(w.condition);
            emit("branch","void",c,blockRef(b),blockRef(x));
            breaks.push(x); conts.push(h);
            curFunc.blocks.add(b); cur = b; stmt(w.body);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(h));
            breaks.pop(); conts.pop();
            curFunc.blocks.add(x); cur = x;
        } else if (s instanceof Java.DoStatement dw) {
            int id = dbgSeq++;
            Ir.Block b = new Ir.Block("body_"+id), h = new Ir.Block("head_"+id), x = new Ir.Block("exit_"+id);
            emit("jump","void",blockRef(b));   // body runs at least once
            breaks.push(x); conts.push(h);
            curFunc.blocks.add(b); cur = b;
            stmt((Java.BlockStatement) get(dw, "body"));
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(h));
            curFunc.blocks.add(h); cur = h;
            Ir.Value c = expr(dw.condition);
            emit("branch","void",c,blockRef(b),blockRef(x));
            breaks.pop(); conts.pop();
            curFunc.blocks.add(x); cur = x;
        } else if (s instanceof Java.ForStatement f) {
            if (f.init != null) stmt(f.init);
            int id = dbgSeq++;
            Ir.Block h = new Ir.Block("head_"+id), b = new Ir.Block("body_"+id), st = new Ir.Block("step_"+id), x = new Ir.Block("exit_"+id);
            emit("jump","void",blockRef(h));
            curFunc.blocks.add(h); cur = h;
            Object cond = f.condition;
            if (cond instanceof Java.Rvalue rv) {
                Ir.Value c = expr(rv);
                emit("branch","void",c,blockRef(b),blockRef(x));
            } else emit("jump","void",blockRef(b));
            breaks.push(x); conts.push(st);
            curFunc.blocks.add(b); cur = b;
            Object body = f.body;
            if (body instanceof Java.BlockStatement bs) stmt(bs);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(st));
            curFunc.blocks.add(st); cur = st;
            for (Java.Rvalue u : f.update) if (u != null) {
                if (u instanceof Java.Assignment as) handleAssign(as);
                else expr(u);
            }
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(h));
            breaks.pop(); conts.pop();
            curFunc.blocks.add(x); cur = x;
        } else if (s instanceof Java.SwitchStatement sw) {
            int id = dbgSeq++;
            Ir.Block exit = new Ir.Block("swx_"+id);
            Ir.Value v = expr((Java.Rvalue) get(sw, "condition"));
            List<?> groups = getList(sw, "sbsgs");
            int n = groups == null ? 0 : groups.size();
            int realDefault = -1;
            Ir.Block[] gblk = new Ir.Block[n];
            for (int i = 0; i < n; i++) {
                gblk[i] = new Ir.Block("swg_"+id+"_"+i);
                if (Boolean.TRUE.equals(get(groups.get(i), "hasDefaultLabel"))) realDefault = i;
            }
            Ir.Block dflt = realDefault >= 0 ? gblk[realDefault] : new Ir.Block("swd_"+id); // micro-block if no `default:`
            // flatten case labels (a default group may also carry labels → they target that same group)
            List<Long> lv = new ArrayList<>();
            List<Integer> lg = new ArrayList<>();
            for (int i = 0; i < n; i++)
                for (Object cl : getList(groups.get(i), "caseLabels")) {
                    lv.add(Long.parseLong(String.valueOf(get(cl, "value")).trim()));
                    lg.add(i);
                }
            int m = lv.size();
            if (m == 0) {
                emit("jump","void",blockRef(realDefault >= 0 ? gblk[realDefault] : exit));
            } else {
                Ir.Block[] cblk = new Ir.Block[m];
                for (int j = 0; j < m; j++) cblk[j] = new Ir.Block("swi_"+id+"_"+j);
                emit("jump","void",blockRef(cblk[0]));
                for (int j = 0; j < m; j++) {
                    curFunc.blocks.add(cblk[j]); cur = cblk[j];
                    Ir.Value kn = konst(lv.get(j), tag(sw));
                    Ir.Value eq = emit("cmpeq","i32",v,kn); eq.dbg = tag(sw);
                    Ir.Block elseT = (j + 1 < m) ? cblk[j+1] : dflt;
                    emit("branch","void",eq,blockRef(gblk[lg.get(j)]),blockRef(elseT));
                }
            }
            // group bodies (textual order) with fallthrough; break → exit
            breaks.push(exit);
            for (int i = 0; i < n; i++) {
                curFunc.blocks.add(gblk[i]); cur = gblk[i];
                for (Object bs : getList(groups.get(i), "blockStatements"))
                    if (bs instanceof Java.BlockStatement bss) stmt(bss);
                if (cur.term()==null||!Ir.isTerm(cur.term().op))
                    emit("jump","void",blockRef(i + 1 < n ? gblk[i+1] : exit));
            }
            breaks.pop();
            if (realDefault < 0 && m > 0) {
                curFunc.blocks.add(dflt); cur = dflt;
                emit("jump","void",blockRef(exit));
            }
            curFunc.blocks.add(exit); cur = exit;
        } else if (s instanceof Java.ReturnStatement r) {
            Object rv = get(r, "returnValue");
            if (rv instanceof Java.Rvalue rvv) emit("return","void",expr(rvv));
            else emit("return","void");
        } else if (s instanceof Java.Block b) {
            if (b.statements != null) for (Java.BlockStatement x : b.statements) stmt(x);
        } else if (s instanceof Java.BreakStatement) {
            if (!breaks.isEmpty()) emit("jump","void",blockRef(breaks.peek()));
        } else if (s instanceof Java.ContinueStatement) {
            if (!conts.isEmpty()) emit("jump","void",blockRef(conts.peek()));
        } else if (s instanceof Java.EmptyStatement) {
        } else System.err.println("# unsupported stmt: " + s.getClass().getSimpleName());
    }

    void handleAssign(Java.Assignment a) {
        String name = null;
        if (a.lhs instanceof Java.AmbiguousName an) name = an.identifiers[0];
        if (name == null) { System.err.println("# assign target unsupported"); return; }
        Ir.Value al = allocaOf.get(name);
        if (al == null) { System.err.println("# undefined: " + name); return; }
        Ir.Value v = expr(a.rhs);
        Ir.Value st = emit("store","void",al,v); st.dbg = tag(a);
    }

    Ir.Value expr(Java.Rvalue e) {
if (e == null) return konst(0, -1);
        if (e instanceof Java.ParenthesizedExpression pe) return expr(pe.value);
        if (e instanceof Java.IntegerLiteral lit) return konst(Long.parseLong(lit.value), tag(e));
        if (e instanceof Java.BooleanLiteral lit) return konst(lit.value.equals("true")?1:0, tag(e));
        if (e instanceof Java.AmbiguousName an) {
            String n = an.identifiers[0];
            Ir.Value al = allocaOf.get(n);
            if (al != null) { Ir.Value l = emit("load",al.type,al); l.dbg = tag(e); return l; }
            throw new RuntimeException("undefined: " + n);
        }
        if (e instanceof Java.BinaryOperation b) {
            Ir.Value l = expr(b.lhs), r = expr(b.rhs);
            Ir.Value v = emit(mapOp(b.operator), "i32", l, r); v.dbg = tag(b); return v;
        }
        if (e instanceof Java.UnaryOperation u) {
            Ir.Value a = expr(u.operand);
            if (u.operator.equals("-")) { Ir.Value v = emit("sub","i32",konst(0,tag(u)),a); v.dbg=tag(u); return v; }
            if (u.operator.equals("!")) { Ir.Value v = emit("cmpeq","i32",a,konst(0,tag(u))); v.dbg=tag(u); return v; }
        }
        if (e instanceof Java.ConditionalExpression te) {
            Ir.Value c = expr(te.lhs);
            int id = dbgSeq++;
            Ir.Block t = new Ir.Block("t_"+id), f = new Ir.Block("f_"+id), j = new Ir.Block("tj_"+id);
            Ir.Value tmp = emit("alloca","i32"); tmp.dbg = tag(te);
            Ir.Value br = emit("branch","void",c,blockRef(t),blockRef(f));
            curFunc.blocks.add(t); cur = t;
            Ir.Value tv = expr(te.mhs);
            Ir.Value st1 = emit("store","void",tmp,tv); st1.dbg = tag(te);
            emit("jump","void",blockRef(j));
            curFunc.blocks.add(f); cur = f;
            Ir.Value fv = expr(te.rhs);
            Ir.Value st2 = emit("store","void",tmp,fv); st2.dbg = tag(te);
            emit("jump","void",blockRef(j));
            curFunc.blocks.add(j); cur = j;
            Ir.Value l = emit("load",tmp.type,tmp); l.dbg = tag(te);
            return l;
        }
        if (e instanceof Java.MethodInvocation mi) {
            List<Ir.Value> args = new ArrayList<>();
            for (Java.Rvalue a : mi.arguments) if (a != null) args.add(expr(a));
            Ir.Value call = emit("call","i32");
            call.name = mi.methodName;
            call.args.addAll(args);
            call.dbg = tag(mi);
            return call;
        }
        System.err.println("# unsupported expr: " + e.getClass().getSimpleName());
        return konst(0,-1);
    }

    static String mapOp(String op) {
        switch (op) {
            case "+": return "add"; case "-": return "sub"; case "*": return "mul";
            case "/": return "div"; case "%": return "mod";
            case "<": return "cmplt"; case ">": return "cmpgt";
            case "<=": return "cmple"; case ">=": return "cmpge";
            case "==": return "cmpeq"; case "!=": return "cmpne";
            case "&&": return "and"; case "||": return "or";
        }
        throw new RuntimeException("op " + op);
    }
}
