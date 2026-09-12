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
    Map<String, String> varType = new LinkedHashMap<>();
    Map<String, String> varElem = new LinkedHashMap<>();
    Map<String, String> methodRet = new LinkedHashMap<>();
    Map<String, String> nativeMangle = new LinkedHashMap<>();
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

    int tag(Java.Locatable n) {
        int id = dbgSeq++;
        if (n != null) try {
            org.codehaus.commons.compiler.Location loc = n.getLocation();
            if (loc != null) prog.debug.positions.put(id, new int[]{loc.getLineNumber(), loc.getColumnNumber()});
        } catch (Throwable t) {}
        return id;
    }
    Ir.Value emit(String op, String type, Ir.Value... args) { Ir.Value v = new Ir.Value(op, type, args); cur.ins.add(v); return v; }
    Ir.Value konst(long v, String type, int dbg) { Ir.Value x = new Ir.Value("const", type); x.imm=v; x.dbg=dbg; cur.ins.add(x); return x; }
    Ir.Value blockRef(Ir.Block b) { Ir.Value v = new Ir.Value("block","ptr"); v.imm=b.hashCode(); v.name=b.name; return v; }

    // ─── type mapping (MiniJ scalar types → IR) ─────────────────────────────
    static String mapType(String jt) {
        if (jt == null) return "i32";
        if (jt.endsWith("[]")) return "ptr";
        switch (jt) {
            case "int": case "boolean": case "byte": case "short": case "char": return "i32";
            case "long": return "i64";
            case "double": return "f64";
            case "float": return "f64";   // P1: преобладаване на float → double (запазва динамиката, документирано)
            default: return "i32";
        }
    }
    /** Елементен тип на 1-D примитивен масив от Java-типа "int[]"/"long[]"/"double[]". */
    static String elemOf(String jt) {
        if (jt == null || !jt.endsWith("[]")) return null;
        String c = jt.substring(0, jt.length() - 2);
        if (c.endsWith("[]")) throw new RuntimeException("nested arrays not supported yet: " + jt);
        String t = mapType(c);
        if (t.equals("ptr")) throw new RuntimeException("unsupported array element type: " + jt);
        return t;
    }
    /** Елементен тип на базата на индексен достъп (VariableDeclarator/param имена). */
    String elemOfRval(Java.Rvalue e) {
        if (e instanceof Java.AmbiguousName an) { String t = varElem.get(an.identifiers[0]); if (t != null) return t; }
        return null;
    }
    static boolean isInt(String t) { return t != null && (t.equals("i32") || t.equals("i64")); }
    static boolean isFp(String t)  { return t != null && t.equals("f64"); }
    static String wide(String a, String b) {
        if (isFp(a) || isFp(b)) return "f64";
        if (a.equals("i64") || b.equals("i64")) return "i64";
        return "i32";
    }
    /** Транспонира товар значение `v` към целития тип `to` (доколкото се налага). */
    Ir.Value conv(Ir.Value v, String to) {
        String from = v.type;
        if (from.equals(to)) return v;
        if (isFp(from) && isInt(to)) {
            Ir.Value c = to.equals("i64") ? emit("FTOI_64", "i64", v) : emit("FTOI", "i32", v);
            c.dbg = v.dbg; return c;
        }
        if (isInt(from) && isFp(to)) {
            Ir.Value c = from.equals("i64") ? emit("ITOF_64", "f64", v) : emit("ITOF", "f64", v);
            c.dbg = v.dbg; return c;
        }
        // int↔i64 retype: int→long sign-extends (MOVSXT_i64); long→int truncates
        Ir.Value c = to.equals("i64") ? emit("MOVSXT_i64", "i64", v) : emit("MOV_i32", "i32", v);
        c.dbg = v.dbg; return c;
    }
    /** Чиста типова проверка на израз (без lowering) — за alloca типове в ternary. */
    static String inferType(Java.Rvalue e, AstLowerMain m) {
        if (e == null) return "i32";
        if (e instanceof Java.ParenthesizedExpression pe) return inferType((Java.Rvalue) pe.value, m);
        if (e instanceof Java.IntegerLiteral lit)
            return (lit.value.endsWith("L") || lit.value.endsWith("l")) ? "i64" : "i32";
        if (e instanceof Java.BooleanLiteral) return "i32";
        if (e instanceof Java.FloatingPointLiteral) return "f64";
        if (e instanceof Java.AmbiguousName an) {
            String s = m.varType.get(an.identifiers[0]);
            return s == null ? "i32" : s;
        }
        if (e instanceof Java.BinaryOperation b)
            return wide(inferType((Java.Rvalue) b.lhs, m), inferType((Java.Rvalue) b.rhs, m));
        if (e instanceof Java.Cast c) return mapType(getStr(c, "targetType"));
        if (e instanceof Java.UnaryOperation u)
            return u.operator.equals("!") ? "i32" : inferType(u.operand, m);
        if (e instanceof Java.MethodInvocation mi) {
            String r = m.methodRet.get(mi.methodName);
            return r == null ? "i32" : r;
        }
        return "i32";
    }

    void cls(Java.ClassDeclaration cd) {
        List<?> methods = getList(cd, "declaredMethods");
        if (methods == null) return;
        String cn = getStr(cd, "name");
        if (cn == null) cn = "T";
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator md)) continue;
            methodRet.put(md.name, mapType(getStr(md, "type")));
            if (getStr(md,"type") != null && !getStr(md,"type").isEmpty()) System.err.println("#dbg md=" + md.name + " type=" + getStr(md,"type") + " native=" + md.isNative());
            if (md.isNative()) {
                int ar = (md.formalParameters != null && md.formalParameters.parameters != null)
                        ? md.formalParameters.parameters.length : 0;
                nativeMangle.put(md.name, "k_native_" + cn + "_" + md.name + "_" + ar);
            }
        }
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator m)) continue;
            if (m.isNative()) continue;
            method(m);
        }
    }

    void method(Java.MethodDeclarator m) {
        Ir.Func f = new Ir.Func(); f.name = m.name;
        f.retType = mapType(getStr(m, "type"));
        curFunc = f; allocaOf.clear(); varType.clear(); varElem.clear(); breaks.clear(); conts.clear();
        for (Object p : m.formalParameters.parameters) {
            if (!(p instanceof Java.FunctionDeclarator.FormalParameter fp)) continue;
            String pt = mapType(getStr(fp, "type"));
            if (pt.equals("ptr")) varElem.put(fp.name, elemOf(getStr(fp, "type")));
            f.params.add(new String[]{fp.name, pt});
        }
        Ir.Block e = new Ir.Block("entry"); cur = e; f.blocks.add(e);
        for (int i = 0; i < f.params.size(); i++) {
            String name = f.params.get(i)[0], pt = f.params.get(i)[1];
            Ir.Value a = emit("alloca", pt); a.dbg = dbgSeq++;
            allocaOf.put(name, a); varType.put(name, pt);
            prog.debug.declNames.put(a.dbg, name);
            prog.debug.declTypes.put(a.dbg, pt);
            Ir.Value pm = new Ir.Value("param", pt); pm.imm = i;
            Ir.Value st = emit("store","void",a,pm); st.dbg = a.dbg;
        }
        for (Object s : m.statements) if (s instanceof Java.BlockStatement bs) stmt(bs);
        if (cur.term()==null || !Ir.isTerm(cur.term().op))
            emit("return","void", f.retType.equals("void") ? null : konst(0, f.retType.equals("void") ? "i32" : f.retType, -1));
        prog.funcs.add(f);
    }

    void stmt(Java.BlockStatement s) {
        if (s instanceof Java.LocalVariableDeclarationStatement d) {
            for (Java.VariableDeclarator vd : d.variableDeclarators) {
                String dt = mapType(getStr(d, "type"));
                if (dt.equals("ptr")) varElem.put(vd.name, elemOf(getStr(d, "type")));
                Ir.Value a = emit("alloca", dt); a.dbg = tag(s);
                allocaOf.put(vd.name, a); varType.put(vd.name, dt);
                prog.debug.declNames.put(a.dbg, vd.name);
                prog.debug.declTypes.put(a.dbg, dt);
                Object init = vd.initializer;
                if (init instanceof Java.Rvalue rv) {
                    Ir.Value v = conv(expr(rv), dt);
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
                    Ir.Value kn = konst(lv.get(j), "i32", tag(sw));
                    Ir.Value eq = emit("cmpeq", "i32", v, kn); eq.dbg = tag(sw);
                    Ir.Block elseT = (j + 1 < m) ? cblk[j+1] : dflt;
                    emit("branch","void",eq,blockRef(gblk[lg.get(j)]),blockRef(elseT));
                }
            }
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
            if (rv instanceof Java.Rvalue rvv) {
                String rt = curFunc.retType;
                if (rt.equals("void")) emit("return","void",expr(rvv));
                else emit("return","void",conv(expr(rvv), rt));
            } else emit("return","void");
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
        if (a.lhs instanceof Java.ArrayAccessExpression aa) {
            Ir.Value b = expr(aa.lhs);
            Ir.Value i = conv(expr(aa.index), "i32");
            String el = elemOfRval(aa.lhs);
            if (el == null) throw new RuntimeException("array element type unknown at assign");
            Ir.Value ck = emit("chk", "void", b, i); ck.dbg = tag(a);
            Ir.Value ad = emit("lea_" + el, "ptr", b, i); ad.dbg = tag(a);
            Ir.Value v = conv(expr(a.rhs), el);
            Ir.Value st = emit("st_" + el, "void", ad, v); st.dbg = tag(a);
            return;
        }
        String name = null;
        if (a.lhs instanceof Java.AmbiguousName an) name = an.identifiers[0];
        if (name == null) { System.err.println("# assign target unsupported"); return; }
        Ir.Value al = allocaOf.get(name);
        if (al == null) { System.err.println("# undefined: " + name); return; }
        Ir.Value v = conv(expr(a.rhs), varType.getOrDefault(name, al.type));
        Ir.Value st = emit("store","void",al,v); st.dbg = tag(a);
    }

    Ir.Value expr(Java.Rvalue e) {
if (e == null) return konst(0, "i32", -1);
        if (e instanceof Java.ParenthesizedExpression pe) return expr(pe.value);
        if (e instanceof Java.IntegerLiteral lit) {
            String vs = lit.value;
            boolean isL = vs.endsWith("L") || vs.endsWith("l");
            if (isL) vs = vs.substring(0, vs.length()-1);
            return konst(Long.parseLong(vs), isL ? "i64" : "i32", tag(e));
        }
        if (e instanceof Java.FloatingPointLiteral lit)
            return konst(Double.doubleToRawLongBits(Double.parseDouble(String.valueOf(get(lit, "value")))), "f64", tag(e));
        if (e instanceof Java.BooleanLiteral lit) return konst(lit.value.equals("true")?1:0, "i32", tag(e));
        if (e instanceof Java.AmbiguousName an) {
            if (an.identifiers.length > 1) {
                if (!an.identifiers[1].equals("length"))
                    throw new RuntimeException("member access on non-array '" + an.identifiers[0] + "' (fields unsupported)");
                Ir.Value al = allocaOf.get(an.identifiers[0]);
                if (al == null) throw new RuntimeException("undefined: " + an.identifiers[0]);
                Ir.Value b = emit("load", al.type, al); b.dbg = tag(e);
                Ir.Value l = emit("len", "i32", b); l.dbg = tag(e);
                return l;
            }
            String n = an.identifiers[0];
            Ir.Value al = allocaOf.get(n);
            if (al != null) { Ir.Value l = emit("load", al.type, al); l.dbg = tag(e); return l; }
            throw new RuntimeException("undefined: " + n);
        }
        if (e instanceof Java.NewArray na) {
            Object de = get(na, "dimExprs");
            Java.Rvalue[] dims = de instanceof Java.Rvalue[] ? (Java.Rvalue[]) de : null;
            int nd = dims == null ? 0 : dims.length;
            int trailing = get(na, "dims") instanceof Integer i ? i : 0;
            if (nd != 1 || trailing > 0) throw new RuntimeException("only 1-D arrays supported (" + nd + " dims)");
            String el = mapType(getStr(na, "type"));
            if (el.equals("ptr")) throw new RuntimeException("only primitive 1-D arrays supported");
            Ir.Value c = conv(expr(dims[0]), "i32");
            Ir.Value a = emit("alloc_" + el, "ptr", c); a.dbg = tag(e);
            Ir.Value h = emit("st_hdr", "void", a, c); h.dbg = tag(e);
            return a;
        }
        if (e instanceof Java.ArrayAccessExpression aa) {
            Ir.Value b = expr(aa.lhs);
            Ir.Value i = conv(expr(aa.index), "i32");
            String el = elemOfRval(aa.lhs);
            if (el == null) throw new RuntimeException("array element type unknown at access");
            Ir.Value ck = emit("chk", "void", b, i); ck.dbg = tag(e);
            Ir.Value ad = emit("lea_" + el, "ptr", b, i); ad.dbg = tag(e);
            Ir.Value l = emit("ld_" + el, el, ad); l.dbg = tag(e);
            return l;
        }
        if (e instanceof Java.BinaryOperation b) {
            Ir.Value l = expr(b.lhs), r = expr(b.rhs);
            String w = wide(l.type, r.type);
            if (!w.equals("i32")) { l = conv(l, w); r = conv(r, w); }
            String base = mapOp(b.operator);
            boolean cmp = base.startsWith("cmp");
            Ir.Value v = emit(base, cmp ? "i32" : w, l, r); v.dbg = tag(b); return v;
        }
        if (e instanceof Java.UnaryOperation u) {
            Ir.Value a = expr(u.operand);
            if (u.operator.equals("-")) {
                if (isFp(a.type)) { Ir.Value v = emit("NEG_f64", "f64", a); v.dbg=tag(u); return v; }
                Ir.Value z = konst(0, a.type, tag(u));
                Ir.Value v = emit("sub", a.type, z, a); v.dbg=tag(u); return v;
            }
            if (u.operator.equals("!")) { Ir.Value v = emit("cmpeq","i32",a,konst(0,"i32",tag(u))); v.dbg=tag(u); return v; }
        }
        if (e instanceof Java.Cast c) {
            Ir.Value v = expr((Java.Rvalue) get(c, "value"));
            return conv(v, mapType(getStr(c, "targetType")));
        }
        if (e instanceof Java.ConditionalExpression te) {
            Ir.Value c = expr(te.lhs);
            String tt = inferType((Java.Rvalue) te.mhs, this);
            int id = dbgSeq++;
            Ir.Block t = new Ir.Block("t_"+id), f = new Ir.Block("f_"+id), j = new Ir.Block("tj_"+id);
            Ir.Value tmp = emit("alloca", tt); tmp.dbg = tag(te);
            Ir.Value br = emit("branch","void",c,blockRef(t),blockRef(f));
            curFunc.blocks.add(t); cur = t;
            Ir.Value tv = conv(expr(te.mhs), tt);
            Ir.Value st1 = emit("store","void",tmp,tv); st1.dbg = tag(te);
            emit("jump","void",blockRef(j));
            curFunc.blocks.add(f); cur = f;
            Ir.Value fv = conv(expr(te.rhs), tt);
            Ir.Value st2 = emit("store","void",tmp,fv); st2.dbg = tag(te);
            emit("jump","void",blockRef(j));
            curFunc.blocks.add(j); cur = j;
            Ir.Value l = emit("load",tmp.type,tmp); l.dbg = tag(te);
            return l;
        }
        if (e instanceof Java.MethodInvocation mi) {
            List<Ir.Value> args = new ArrayList<>();
            for (Java.Rvalue a : mi.arguments) if (a != null) args.add(expr(a));
            String rt = methodRet.getOrDefault(mi.methodName, "i32");
            if (true) System.err.println("#dbg call " + mi.methodName + " -> rt=" + rt + " mangle=" + nativeMangle.getOrDefault(mi.methodName, "-"));
            if (rt.equals("void")) rt = "i32";
            Ir.Value call = emit("call", rt);
            call.name = nativeMangle.getOrDefault(mi.methodName, mi.methodName);
            call.args.addAll(args);
            call.dbg = tag(mi);
            return call;
        }
        System.err.println("# unsupported expr: " + e.getClass().getSimpleName());
        return konst(0,"i32",-1);
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