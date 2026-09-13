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
    Map<String, String> varJType = new LinkedHashMap<>();
    Map<String, String> methodRet = new LinkedHashMap<>();
    Map<String, String> methodRetJt = new LinkedHashMap<>();
    Map<String, String> nativeMangle = new LinkedHashMap<>();
    Map<String, Integer> classIndex = new LinkedHashMap<>();      // className → малко int (в header-а)
    Map<String, Integer> classSizes = new LinkedHashMap<>();      // className → общ byte size с header
    Map<String, Map<String, Object[]>> classFields = new LinkedHashMap<>(); // className → field → {irType, byteOffset, javaType}
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
            if (td instanceof Java.ClassDeclaration cd) m.collectClass(cd);
        }
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
            case "String": return "ptr";  // P2: lean String = char[]
            default: return "i32";
        }
    }
    /** Java тип на обект-клас → IR ptr; останалото − по mapType. */
    String irType(String jt) {
        if (jt != null && classIndex.containsKey(jt)) return "ptr";
        return mapType(jt);
    }

    /** Pre-pass преди lowering: layout на instance-полетата на всеки клас.
     *  Object = 8-byte header (class index + pad/GC bits), полетата от +8,
     *  aligned по тип (i32→4, i64/f64/ptr→8). */
    void collectClass(Java.ClassDeclaration cd) {
        String cn = getStr(cd, "name");
        if (cn == null || classIndex.containsKey(cn)) return;
        int idx = classIndex.size();
        classIndex.put(cn, idx);
        int off = 8;
        Map<String, Object[]> fs = new LinkedHashMap<>();
        Object members = invoke0(cd, "getVariableDeclaratorsAndInitializers");
        if (members instanceof List<?> ml) for (Object mb : ml) {
            if (!(mb instanceof Java.FieldDeclarationOrInitializer)) continue;
            String jt = getStr(mb, "type");
            if (jt == null) continue;
            String ft = irType(jt);
            int a = ft.equals("i32") ? 4 : 8;
            Object vds = get(mb, "variableDeclarators");
            if (vds instanceof Object[] arr) for (Object vd : arr) {
                String name = getStr(vd, "name");
                off = (off + a - 1) & ~(a - 1);
                fs.put(name, new Object[]{ ft, off, jt });
                off += a;
            }
        }
        classSizes.put(cn, Math.max(16, (off + 7) & ~7));
        classFields.put(cn, fs);
    }

    static Object invoke0(Object o, String method) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Method mm = c.getDeclaredMethod(method); mm.setAccessible(true); return mm.invoke(o); }
            catch (Throwable t) { }
        }
        return null;
    }

    /** Елементен тип на 1-D примитивен масив от Java-типа "int[]"/"long[]"/"double[]".
     *  Многомерен ("int[][]", "int[][][]", …) и "String[]" дават "ptr" (клетките пазят указатели). */
    String elemOf(String jt) {
        if (jt == null || !jt.endsWith("[]")) return null;
        String c = jt.substring(0, jt.length() - 2);
        if (c.endsWith("[]") || c.equals("String") || classIndex.containsKey(c)) return "ptr";
        String t = mapType(c);
        if (t.equals("ptr")) throw new RuntimeException("unsupported array element type: " + jt);
        return t;
    }
    /** Тип на клетките при индексиран достъп (String се третира като char[]). */
    String elemOfAccess(String jt) {
        if ("String".equals(jt)) return "i32";
        return elemOf(jt);
    }
    /** Java тип (string) на израз — за рекурсивно извеждане на масивен елемент. */
    String javaTypeOf(Java.Rvalue e) {
        if (e == null) return "int";
        if (e instanceof Java.ParenthesizedExpression pe) return javaTypeOf((Java.Rvalue) pe.value);
        if (e instanceof Java.AmbiguousName an) {
            if (an.identifiers.length > 1 && an.identifiers[1].equals("length")) return "int";  // x.length
            String t = varJType.get(an.identifiers[0]); return t == null ? "int" : t;
        }
        if (e instanceof Java.ArrayAccessExpression aa) {
            String b = javaTypeOf(aa.lhs);
            return b != null && b.endsWith("[]") ? b.substring(0, b.length() - 2) : "int";
        }
        if (e instanceof Java.NewClassInstance nci) {
            Object t = get(nci, "type");
            return t == null ? "int" : t.toString();
        }
        if (e instanceof Java.Cast c) { String t = getStr(c, "targetType"); return t == null ? "int" : t; }
        if (e instanceof Java.BooleanLiteral) return "boolean";
        if (e instanceof Java.CharacterLiteral) return "char";
        if (e instanceof Java.StringLiteral) return "String";
        if (e instanceof Java.IntegerLiteral lit)
            return (lit.value.endsWith("L") || lit.value.endsWith("l")) ? "long" : "int";
        if (e instanceof Java.FloatingPointLiteral) return "double";
        if (e instanceof Java.BinaryOperation b)
            return wideJ(javaTypeOf((Java.Rvalue) b.lhs), javaTypeOf((Java.Rvalue) b.rhs));
        if (e instanceof Java.UnaryOperation u) return u.operator.equals("!") ? "boolean" : javaTypeOf(u.operand);
        if (e instanceof Java.MethodInvocation mi) {
            if (mi.methodName.equals("concat")) return "String";
            String r = methodRetJt.get(mi.methodName); return r == null ? "int" : r;
        }
        if (e instanceof Java.FieldAccessExpression fa) { Object lh = get(fa, "lhs"); return lh instanceof Java.Rvalue lv ? javaTypeOf(lv) : "int"; }
        return "int";
    }
    static String wideJ(String a, String b) {
        if (a.equals("double") || b.equals("double")) return "double";
        if (a.equals("long") || b.equals("long")) return "long";
        return "int";
    }
    /** Janino държи StringLiteral/CharacterLiteral.value като суров текст с кавички
     *  и escapes; сваля кавичките и декодира escapes, за да получи реалните char-ове. */
    static String decodeString(String raw, char q) {
        if (raw.length() >= 2 && raw.charAt(0) == q && raw.charAt(raw.length() - 1) == q)
            raw = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (++i >= raw.length()) break;
            char e = raw.charAt(i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '0': sb.append('\0'); break;
                case '\\': sb.append('\\'); break;
                case '"': sb.append('"'); break;
                case '\'': sb.append('\''); break;
                case 'u': {
                    int cp = 0, j = i + 1;
                    while (j < raw.length() && j < i + 5 && Character.digit(raw.charAt(j), 16) >= 0)
                        cp = cp * 16 + Character.digit(raw.charAt(j++), 16);
                    sb.append((char) cp); i = j - 1;
                    break;
                }
                default: sb.append(e); break;
            }
        }
        return sb.toString();
    }

    static String decodeString(String raw) { return decodeString(raw, '"'); }
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
        if (e instanceof Java.StringLiteral) return "ptr";
        if (e instanceof Java.FloatingPointLiteral) return "f64";
        if (e instanceof Java.AmbiguousName an) {
            if (an.identifiers.length > 1) return "i32";  // x.length
            String s = m.varType.get(an.identifiers[0]);
            return s == null ? "i32" : s;
        }
        if (e instanceof Java.ArrayAccessExpression aa) {
            String el = m.elemOfAccess(m.javaTypeOf(aa.lhs));
            return el == null ? "i32" : el;
        }
        if (e instanceof Java.BinaryOperation b)
            return wide(inferType((Java.Rvalue) b.lhs, m), inferType((Java.Rvalue) b.rhs, m));
        if (e instanceof Java.Cast c) return mapType(getStr(c, "targetType"));
        if (e instanceof Java.NewClassInstance nci) return "ptr";
        if (e instanceof Java.UnaryOperation u)
            return u.operator.equals("!") ? "i32" : inferType(u.operand, m);
        if (e instanceof Java.MethodInvocation mi) {
            if (mi.methodName.equals("concat")) return "ptr";
            if (mi.methodName.equals("equals")) return "i32";
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
            methodRet.put(md.name, irType(getStr(md, "type")));
            methodRetJt.put(md.name, getStr(md, "type"));
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
        f.retType = irType(getStr(m, "type"));
        curFunc = f; allocaOf.clear(); varType.clear(); varJType.clear(); breaks.clear(); conts.clear();
        for (Object p : m.formalParameters.parameters) {
            if (!(p instanceof Java.FunctionDeclarator.FormalParameter fp)) continue;
            String pt = irType(getStr(fp, "type"));
            varJType.put(fp.name, getStr(fp, "type"));
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
                String jt = getStr(d, "type");
                String dt = irType(jt);
                varJType.put(vd.name, jt);
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
            String el = elemOfAccess(javaTypeOf(aa.lhs));
            if (el == null) throw new RuntimeException("array element type unknown at assign");
            Ir.Value ck = emit("chk", "void", b, i); ck.dbg = tag(a);
            Ir.Value ad = emit("lea_" + el, "ptr", b, i); ad.dbg = tag(a);
            Ir.Value v = conv(expr(a.rhs), el);
            Ir.Value st = emit("st_" + el, "void", ad, v); st.dbg = tag(a);
            return;
        }
        if (a.lhs instanceof Java.AmbiguousName fa && fa.identifiers.length > 1) {
            if (fa.identifiers[1].equals("length"))
                throw new RuntimeException("cannot assign to .length");
            FieldAddr f = fieldAddr(fa);
            if (f == null) throw new RuntimeException("cannot assign field target: " + fa.identifiers[0]);
            Ir.Value v = conv(expr(a.rhs), f.ir);
            Ir.Value st = emit("st_" + f.ir, "void", f.addr, v); st.dbg = tag(a);
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

    /** `new T[m][n]` → външен ptr-масив (редовете са масиви от елементи) + цикъл, който
     *  алокира всеки ред (alloc_<el>) и го записва в клетката (st_ptr). */
    Ir.Value newArray2D(Java.NewArray na, Java.Rvalue d0, Java.Rvalue d1, String baseJt) {
        int t = tag(na);
        String el = elemOfAccess(baseJt + "[]");
        Ir.Value cn = conv(expr(d0), "i32");
        Ir.Value a = emit("alloc_ptr", "ptr", cn); a.dbg = t;
        Ir.Value h = emit("st_hdr", "void", a, cn); h.dbg = t;
        Ir.Value en = conv(expr(d1), "i32");
        int id = dbgSeq++;
        Ir.Value cnt = emit("alloca", "i32"); cnt.dbg = id;
        Ir.Value z0 = emit("store", "void", cnt, konst(0, "i32", -1)); z0.dbg = cnt.dbg;
        Ir.Block mh = new Ir.Block("mtxh_"+id), mb = new Ir.Block("mtxb_"+id), mx = new Ir.Block("mtxx_"+id);
        emit("jump", "void", blockRef(mh));
        curFunc.blocks.add(mh); cur = mh;
        Ir.Value cv = emit("load", "i32", cnt); cv.dbg = id;
        Ir.Value lt = emit("cmplt", "i32", cv, cn); lt.dbg = id;
        emit("branch", "void", lt, blockRef(mb), blockRef(mx));
        curFunc.blocks.add(mb); cur = mb;
        Ir.Value cvi = emit("load", "i32", cnt); cvi.dbg = id;
        Ir.Value row = emit("alloc_" + el, "ptr", en); row.dbg = t;
        Ir.Value rh = emit("st_hdr", "void", row, en); rh.dbg = t;
        Ir.Value cell = emit("lea_ptr", "ptr", a, cvi); cell.dbg = t;
        Ir.Value rs = emit("st_ptr", "void", cell, row); rs.dbg = t;
        Ir.Value nx = emit("add", "i32", cvi, konst(1, "i32", -1)); nx.dbg = id;
        Ir.Value sn = emit("store", "void", cnt, nx); sn.dbg = id;
        emit("jump", "void", blockRef(mh));
        curFunc.blocks.add(mx); cur = mx;
        return a;
    }

    static class FieldAddr { Ir.Value addr; String ir; String jt; FieldAddr(Ir.Value a, String i, String j){addr=a; ir=i; jt=j;} }

    /** Резолвира верига `<var>.<fld>…` до АДРЕСА на последното поле: load-ва базовия
     *  указател на var-а и прекосява prefix-полетата с lea_field+ld_ptr. */
    FieldAddr fieldAddr(Java.AmbiguousName an) {
        if (an.identifiers.length < 2) return null;
        String[] ids = new String[an.identifiers.length];
        for (int i = 0; i < ids.length; i++) ids[i] = an.identifiers[i];
        String jt = varJType.get(ids[0]);
        if (jt == null || !classIndex.containsKey(jt)) return null;
        Ir.Value v = null;
        Ir.Value al = allocaOf.get(ids[0]);
        if (al != null) { v = emit("load", al.type, al); v.dbg = tag(an); }
        for (int k = 1; k < ids.length; k++) {
            Map<String, Object[]> fs = classFields.get(jt);
            if (fs == null) throw new RuntimeException("member access on non-class '" + ids[0] + "' (" + jt + ")");
            Object[] f = fs.get(ids[k]);
            if (f == null) throw new RuntimeException("no field " + ids[k] + " on " + jt);
            int off = (Integer) f[1];
            Ir.Value o = konst(off, "i64", tag(an));
            Ir.Value ad = emit("lea_field", "ptr", v, o); ad.dbg = tag(an);
            if (k == ids.length - 1) return new FieldAddr(ad, (String) f[0], (String) f[2]);
            v = emit("ld_ptr", "ptr", ad); v.dbg = tag(an);
            jt = (String) f[2];
            if (!classIndex.containsKey(jt))
                throw new RuntimeException("cannot chain field '" + ids[k] + "' (not an object): " + jt);
        }
        return null;
    }

    /** String-метод receiver: `s.equals(t)` идва като AmbiguousName [s, equals]
     *  (expr() не го обхожда — методите са ids[1]); сваляме само стойността на
     *  променливата. Други receiver-и (литерали, извиквания) → expr(). */
    Ir.Value recvValue(Java.MethodInvocation mi, Java.Rvalue tgt) {
        if (tgt instanceof Java.AmbiguousName an && an.identifiers.length == 2
                && an.identifiers[1].equals(mi.methodName)) {
            String n = an.identifiers[0];
            Ir.Value al = allocaOf.get(n);
            if (al == null) throw new RuntimeException("undefined: " + n);
            Ir.Value l = emit("load", al.type, al); l.dbg = tag(tgt);
            return l;
        }
        return expr(tgt);
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
        if (e instanceof Java.CharacterLiteral cl) {
            Object cv = get(cl, "value");
            String cvs = cv == null ? "" : cv.toString();
            String dec = decodeString(cvs, '\'');
            long c = dec.isEmpty() ? 0 : (long) dec.charAt(0);
            return konst(c, "i32", tag(e));
        }
        if (e instanceof Java.StringLiteral sl) {
            Object sv = get(sl, "value");
            String sval = decodeString(sv == null ? "" : sv.toString());
            Ir.Value c = konst(sval.length(), "i32", tag(e));
            Ir.Value a = emit("alloc_i32", "ptr", c); a.dbg = tag(e);
            Ir.Value h = emit("st_hdr", "void", a, c); h.dbg = tag(e);
            for (int k = 0; k < sval.length(); k++) {
                Ir.Value ik = konst(k, "i32", tag(e));
                Ir.Value ad = emit("lea_i32", "ptr", a, ik); ad.dbg = tag(e);
                Ir.Value ch = konst(sval.charAt(k), "i32", tag(e));
                Ir.Value st = emit("st_i32", "void", ad, ch); st.dbg = tag(e);
            }
            return a;
        }
        if (e instanceof Java.AmbiguousName an) {
            if (an.identifiers.length > 1) {
                if (an.identifiers[1].equals("length")) {
                    Ir.Value al = allocaOf.get(an.identifiers[0]);
                    if (al == null) throw new RuntimeException("undefined: " + an.identifiers[0]);
                    Ir.Value b = emit("load", al.type, al); b.dbg = tag(e);
                    Ir.Value l = emit("len", "i32", b); l.dbg = tag(e);
                    return l;
                }
                FieldAddr fa = fieldAddr(an);
                if (fa != null) {
                    Ir.Value l = emit("ld_" + fa.ir, fa.ir, fa.addr); l.dbg = tag(e);
                    return l;
                }
                throw new RuntimeException("member access on non-array/object '" + an.identifiers[0] + "' (fields unsupported)");
            }
            String n = an.identifiers[0];
            Ir.Value al = allocaOf.get(n);
            if (al != null) { Ir.Value l = emit("load", al.type, al); l.dbg = tag(e); return l; }
            throw new RuntimeException("undefined: " + n);
        }
        if (e instanceof Java.NewClassInstance nci) {
            Object t = get(nci, "type");
            String cn = t == null ? null : t.toString();
            if (cn != null && classIndex.containsKey(cn)) {
                Object argsO = get(nci, "arguments");
                int na = argsO instanceof Object[] o ? o.length : 0;
                if (na != 0) throw new RuntimeException("constructor with args unsupported: new " + cn);
                int sz = classSizes.get(cn);
                Ir.Value c = konst(sz, "i32", tag(e));
                Ir.Value a = emit("alloc_obj", "ptr", c); a.dbg = tag(e);
                Ir.Value h = emit("st_hdr", "void", a, konst(classIndex.get(cn), "i32", tag(e))); h.dbg = tag(e);
                return a;
            }
            throw new RuntimeException("new " + cn + ": unsupported type");
        }
        if (e instanceof Java.NewArray na) {
            Object de = get(na, "dimExprs");
            Java.Rvalue[] dims = de instanceof Java.Rvalue[] ? (Java.Rvalue[]) de : null;
            int nd = dims == null ? 0 : dims.length;
            int trailing = get(na, "dims") instanceof Integer i ? i : 0;
            String base = getStr(na, "type");
            int total = nd + trailing;
            if (nd == 1) {
                String el = elemOfAccess(base + "[]".repeat(total));
                Ir.Value c = conv(expr(dims[0]), "i32");
                Ir.Value a = emit("alloc_" + el, "ptr", c); a.dbg = tag(e);
                Ir.Value h = emit("st_hdr", "void", a, c); h.dbg = tag(e);
                return a;
            }
            if (nd == 2 && trailing == 0) return newArray2D(na, dims[0], dims[1], base);
            throw new RuntimeException("new with " + total + " dims not supported yet (1-D, 2-D или T[n][] са покрити)");
        }
        if (e instanceof Java.ArrayAccessExpression aa) {
            Ir.Value b = expr(aa.lhs);
            Ir.Value i = conv(expr(aa.index), "i32");
            String el = elemOfAccess(javaTypeOf(aa.lhs));
            if (el == null) throw new RuntimeException("array element type unknown at access");
            Ir.Value ck = emit("chk", "void", b, i); ck.dbg = tag(e);
            Ir.Value ad = emit("lea_" + el, "ptr", b, i); ad.dbg = tag(e);
            Ir.Value l = emit("ld_" + el, el, ad); l.dbg = tag(e);
            return l;
        }
        if (e instanceof Java.FieldAccessExpression fa) {
            String nm = getStr(fa, "fieldName");
            if (nm != null && nm.equals("length")) {
                Object lh = get(fa, "lhs");
                Ir.Value b = expr((Java.Rvalue) lh);
                Ir.Value l = emit("len", "i32", b); l.dbg = tag(e);
                return l;
            }
            throw new RuntimeException("field access on non-array (fields unsupported): " + nm);
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
            Java.Rvalue tgt = (Java.Rvalue) get(mi, "target");
            // String methods (P2 tail): s.equals(t) / s.concat(t) → runtime helpers
            if (tgt != null && (mi.methodName.equals("equals") || mi.methodName.equals("concat"))
                    && javaTypeOf(tgt).equals("String")) {
                if (mi.arguments.length != 1)
                    throw new RuntimeException("String." + mi.methodName + ": expected 1 argument, got " + mi.arguments.length);
                List<Ir.Value> sargs = new ArrayList<>();
                sargs.add(recvValue(mi, tgt));
                sargs.add(expr(mi.arguments[0]));
                boolean isConcat = mi.methodName.equals("concat");
                Ir.Value call = emit("call", isConcat ? "ptr" : "i32");
                call.name = isConcat ? "k_string_concat" : "k_string_equals";
                call.args.addAll(sargs);
                call.dbg = tag(mi);
                return call;
            }
            boolean sysout = false;
            if (tgt instanceof Java.AmbiguousName tan) {
                Object[] idsO = (Object[]) get(tan, "identifiers");
                String[] ids = idsO == null ? null : Arrays.stream(idsO).map(String::valueOf).toArray(String[]::new);
                sysout = ids != null && ids.length >= 2 && ids[0].equals("System") && ids[1].equals("out");
            }
            List<Ir.Value> args = new ArrayList<>();
            for (Java.Rvalue a : mi.arguments) if (a != null) args.add(expr(a));
            if (sysout) {
                String nm;
                if (mi.methodName.equals("println") && mi.arguments.length == 0) nm = "k_newline";
                else if (mi.methodName.equals("println"))
                    nm = inferType((Java.Rvalue) mi.arguments[0], this).equals("ptr") ? "k_println" : "k_println_i32";
                else if (mi.methodName.equals("print"))
                    nm = inferType((Java.Rvalue) mi.arguments[0], this).equals("ptr") ? "k_print" : "k_print_i32";
                else throw new RuntimeException("unsupported System.out method: " + mi.methodName);
                Ir.Value call = emit("call","i32"); call.name = nm;
                call.args.addAll(args);
                call.dbg = tag(mi);
                return call;
            }
            String rt = methodRet.getOrDefault(mi.methodName, "i32");
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