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
    List<Ir.Static> statics = new ArrayList<>();         // статични полета (data symbols) за .bss
    Map<String, Map<String, Object[]>> staticFields = new LinkedHashMap<>(); // className → field → {irType, symbol, javaType}
    Map<String, List<MethSig>> classMethods = new LinkedHashMap<>(); // "cls::name" → сигнатури (overloads)
    Map<String, String> classSuper = new LinkedHashMap<>();       // className → superclassName (null за root)
    Map<String, List<String>> classSlots = new LinkedHashMap<>(); // className → ordered vtable slot keys (super-first)
    Map<String, Java.ClassDeclaration> allDecls = new LinkedHashMap<>(); // className → декларация (за super-резолюция)
    Set<String> visiting = new HashSet<>();                       // guard за cyclic extends
    String curClass = null;                                       // текущия клас на lowering (инстанс методи); null = static/native
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
            if (td instanceof Java.ClassDeclaration cd) m.allDecls.put(getStr(cd, "name"), cd);
        }
        if (types != null) for (Object td : types) {
            if (td instanceof Java.ClassDeclaration cd) m.collectClass(cd);
        }
        if (types != null) for (Object td : types) {
            if (td instanceof Java.ClassDeclaration cd) m.collectSigs(cd);
        }
        m.buildVtables();
        if (types != null) for (Object td : types) {
            if (td instanceof Java.ClassDeclaration cd) m.emitMethods(cd);
        }
        m.prog.statics.addAll(m.statics);
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
            case "void": return "void";   // P3: void-методи (пуста retType → Reader оставя return гол)
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
        Object ext = get(cd, "extendedType");
        String superName = null;
        if (ext != null) {
            Object ids = get(ext, "identifiers");
            if (ids instanceof Object[] arr && arr.length > 0) superName = String.valueOf(arr[arr.length - 1]);
        }
        Java.ClassDeclaration sup = superName == null ? null : allDecls.get(superName);
        if (sup != null) {
            if (visiting.contains(cn)) throw new RuntimeException("cyclic extends at class " + cn);
            visiting.add(cn);
            try { collectClass(sup); } finally { visiting.remove(cn); }
        }
        int idx = classIndex.size();
        classIndex.put(cn, idx);
        classSuper.put(cn, superName);
        int off = 8;
        Map<String, Object[]> fs = new LinkedHashMap<>();
        if (superName != null) {
            Map<String, Object[]> supf = classFields.get(superName);
            if (supf != null) fs.putAll(supf);
            Integer ss = classSizes.get(superName);
            off = ss == null ? 8 : ss;
        }
        Object members = invoke0(cd, "getVariableDeclaratorsAndInitializers");
        if (members instanceof List<?> ml) for (Object mb : ml) {
            if (!(mb instanceof Java.FieldDeclarationOrInitializer)) continue;
            String jt = getStr(mb, "type");
            if (jt == null) continue;
            String ft = irType(jt);
            boolean isStatic = mb instanceof Java.FieldDeclaration fd && fd.isStatic();
            Object vds = get(mb, "variableDeclarators");
            if (vds instanceof Object[] arr) for (Object vd : arr) {
                String name = getStr(vd, "name");
                if (isStatic) {
                    Object ini = get(vd, "initializer");
                    if (ini != null)
                        throw new RuntimeException("static field initializers not supported yet: " + cn + "." + name);
                    String sym = cn + "_" + name;
                    statics.add(new Ir.Static(sym, ft.equals("i32") ? 4 : 8));
                    staticFields.computeIfAbsent(cn, k -> new LinkedHashMap<>()).put(name, new Object[]{ ft, sym, jt });
                    continue;
                }
                int a = ft.equals("i32") ? 4 : 8;
                off = (off + a - 1) & ~(a - 1);
                fs.put(name, new Object[]{ ft, off, jt });
                off += a;
            }
        }
        int sz = Math.max(16, (off + 7) & ~7);
        Integer ss = superName == null ? null : classSizes.get(superName);
        if (ss != null) sz = Math.max(sz, ss);
        classSizes.put(cn, sz);
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
        if (e instanceof Java.Instanceof) return "boolean";
        if (e instanceof Java.BooleanLiteral) return "boolean";
        if (e instanceof Java.NullLiteral) return "null";
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
            Java.Rvalue tgt = (Java.Rvalue) get(mi, "target");
            if (tgt != null) {
                boolean[] sc = { false };
                List<MethSig> sigs = classSigs(mi, sc);
                if (sigs != null && !sigs.isEmpty()) {
                    MethSig ms = resolveSig(sigs, mi.arguments);
                    if (ms != null && ms.retJt != null) return ms.retJt;
                }
            }
            String r = methodRetJt.get(mi.methodName); return r == null ? "int" : r;
        }
        if (e instanceof Java.FieldAccessExpression fa) {
            String nm = getStr(fa, "fieldName");
            if (nm != null && nm.equals("length")) return "int";
            Object lh = get(fa, "lhs");
            if (lh instanceof Java.Rvalue lv) {
                String bt = javaTypeOf(lv);
                Map<String, Object[]> fs = bt == null ? null : classFields.get(bt);
                if (fs != null) { Object[] f = fs.get(nm); if (f != null) return (String) f[2]; }
            }
            return "int";
        }
        if (e instanceof Java.ThisReference) { String t = varJType.get("this"); return t == null ? "int" : t; }
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
        if (isPtr(a) || isPtr(b) || a.equals("i64") || b.equals("i64")) return "i64";
        return "i32";
    }
    static boolean isPtr(String t) { return t != null && (t.equals("ptr") || t.equals("address")); }
    /** Транспонира товар значение `v` към целития тип `to` (доколкото се налага). */
    Ir.Value conv(Ir.Value v, String to) {
        String from = v.type;
        if (from.equals(to)) return v;
        if (isPtr(from) && to.equals("i64")) return v;      // ptr вече е 64-битов
        if (isPtr(to) && isPtr(from)) return v;
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
        if (e instanceof Java.NullLiteral) return "ptr";
        if (e instanceof Java.StringLiteral) return "ptr";
        if (e instanceof Java.FloatingPointLiteral) return "f64";
        if (e instanceof Java.AmbiguousName an) {
            if (an.identifiers.length > 1 && an.identifiers[1].equals("length")) return "i32";
            if (an.identifiers.length > 1) {
                Object[] sf = m.ambigStatic(an);
                if (sf != null) return m.irType((String) sf[2]);
            }
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
        if (e instanceof Java.Instanceof) return "i32";
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

    /** Pre-pass: строим сигнатурната DB на ВСИЧКИ класове ПРЕДИ lowering (иначе super-клас
     *  деклариран след наследника няма `::<init>`/методи по време на resolve). */
    void collectSigs(Java.ClassDeclaration cd) {
        List<?> methods = getList(cd, "declaredMethods");
        List<?> cstrs = getList(cd, "constructors");
        if (methods == null) return;
        String cn = getStr(cd, "name");
        if (cn == null) cn = "T";
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator md)) continue;
            String jt = getStr(md, "type");
            methodRet.put(md.name, irType(jt));
            methodRetJt.put(md.name, jt);
            if (md.isNative()) {
                int ar = (md.formalParameters != null && md.formalParameters.parameters != null)
                        ? md.formalParameters.parameters.length : 0;
                nativeMangle.put(md.name, "k_native_" + cn + "_" + md.name + "_" + ar);
                continue;
            }
            MethSig s = buildSig(md, cn, md.name, md.name.equals("main") ? "main" : buildSigMangle(cn, md.name, md));
            classMethods.computeIfAbsent(cn + "::" + md.name, k -> new ArrayList<>()).add(s);
        }
        if (cstrs != null) for (Object co : cstrs) {
            if (!(co instanceof Java.ConstructorDeclarator cdf)) continue;
            MethSig s = buildSig(cdf, cn, "<init>", ctorSym(cn, cdf));
            s.retIr = "void"; s.retJt = "void"; s.isStatic = false;
            classMethods.computeIfAbsent(cn + "::<init>", k -> new ArrayList<>()).add(s);
        }
    }

    void emitMethods(Java.ClassDeclaration cd) {
        List<?> methods = getList(cd, "declaredMethods");
        List<?> cstrs = getList(cd, "constructors");
        String cn = getStr(cd, "name");
        if (cn == null) cn = "T";
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator m)) continue;
            if (m.isNative()) continue;
            method(m, matchSig(m, cn, m.name));
        }
        if (cstrs != null) for (Object co : cstrs) {
            if (!(co instanceof Java.ConstructorDeclarator cdf)) continue;
            method(cdf, matchSig(cdf, cn, "<init>"));
        }
    }

    /** Сигнатура на метод/ctor — params от формалните параметри. */
    MethSig buildSig(Java.FunctionDeclarator fd, String cn, String name, String symbol) {
        MethSig s = new MethSig();
        s.cn = cn; s.name = name;
        s.isStatic = fd instanceof Java.MethodDeclarator md && methodStatic(md);
        s.retIr = irType(getStr(fd, "type")); s.retJt = getStr(fd, "type");
        int ar = fd.formalParameters != null && fd.formalParameters.parameters != null
                ? fd.formalParameters.parameters.length : 0;
        s.pjts = new String[ar]; s.pirs = new String[ar];
        for (int i = 0; i < ar; i++) {
            Object fp = fd.formalParameters.parameters[i];
            if (fp instanceof Java.FunctionDeclarator.FormalParameter f) {
                s.pjts[i] = getStr(f, "type");
                s.pirs[i] = irType(s.pjts[i]);
            }
        }
        s.symbol = symbol;
        return s;
    }
    String buildSigMangle(String cn, String nm, Java.FunctionDeclarator fd) {
        StringBuilder t = new StringBuilder();
        Object fpp = fd.formalParameters;
        Object[] ps = fpp == null ? null : (Object[]) get(fpp, "parameters");
        if (ps != null) for (Object p : ps)
            if (p instanceof Java.FunctionDeclarator.FormalParameter f)
                t.append('_').append(irType(getStr(f, "type")));
        return cn + "_" + nm + t;
    }
    String ctorSym(String cn, Java.ConstructorDeclarator cdf) {
        StringBuilder t = new StringBuilder();
        Object fpp = cdf.formalParameters;
        Object[] ps = fpp == null ? null : (Object[]) get(fpp, "parameters");
        if (ps != null) for (Object p : ps)
            if (p instanceof Java.FunctionDeclarator.FormalParameter f)
                t.append('_').append(irType(getStr(f, "type")));
        return cn + "_init" + t;
    }

    /** Match сигнатурата от DB към конретния declarator (по arity + java типове). */
    MethSig matchSig(Java.FunctionDeclarator m, String cn, String dbName) {
        MethSig sig = null;
        List<MethSig> ls = classMethods.get(cn + "::" + dbName);
        if (ls != null && !ls.isEmpty()) {
            int ar = m.formalParameters != null && m.formalParameters.parameters != null
                    ? m.formalParameters.parameters.length : 0;
            for (MethSig s : ls) {
                if (s.arity() != ar) continue;
                boolean ok = true;
                for (int i = 0; i < ar; i++) {
                    Object fp = m.formalParameters.parameters[i];
                    String fj = fp instanceof Java.FunctionDeclarator.FormalParameter f ? getStr(f, "type") : null;
                    if (fj == null || !fj.equals(s.pjts[i])) { ok = false; break; }
                }
                if (ok) { sig = s; break; }
            }
        }
        return sig;
    }

    void method(Java.FunctionDeclarator m, MethSig sig) {
        Ir.Func f = new Ir.Func();
        f.name = sig != null ? sig.symbol : m.name;
        f.retType = sig != null ? sig.retIr : irType(getStr(m, "type"));
        curFunc = f; allocaOf.clear(); varType.clear(); varJType.clear(); breaks.clear(); conts.clear();
        curClass = sig != null ? sig.cn : null;
        if (sig != null && !sig.isStatic) {
            varJType.put("this", sig.cn);
            f.params.add(new String[]{"this", "ptr"});
        }
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
        // ctor chaining: this(...) / super(...) и имплицитния super(), преди тялото
        if (m instanceof Java.ConstructorDeclarator cdf && curClass != null) {
            Java.ConstructorInvocation ci = cdf.constructorInvocation;
            if (ci instanceof Java.AlternateConstructorInvocation aci) {
                Java.Rvalue[] ciArgs = aci.arguments;
                List<MethSig> cs = classMethods.get(curClass + "::<init>");
                MethSig csig = cs == null ? null : resolveSig(cs, ciArgs);
                if (csig == null) throw new RuntimeException("no matching constructor for this(...) in " + curClass);
                List<Ir.Value> cargs = new ArrayList<>();
                Ir.Value al = allocaOf.get("this");
                Ir.Value th = emit("load", al.type, al); th.dbg = -1;
                cargs.add(th);
                for (int i = 0; i < ciArgs.length; i++)
                    if (ciArgs[i] != null)
                        cargs.add(conv(expr(ciArgs[i]), csig.pirs[i] == null ? "i32" : csig.pirs[i]));
                Ir.Value call = emit("call", "i32");
                call.name = csig.symbol;
                call.args.addAll(cargs);
                call.dbg = -1;
            } else if (ci == null) {
                // имплицитен super() към 0-арг ctor на super-класа (ако съществува)
                String scn = classSuper.get(curClass);
                if (scn != null) {
                    List<MethSig> cs = classMethods.get(scn + "::<init>");
                    if (cs != null && !cs.isEmpty()) {
                        MethSig csig = resolveSig(cs, new Java.Rvalue[0]);
                        if (csig != null) {
                            List<Ir.Value> cargs = new ArrayList<>();
                            Ir.Value al = allocaOf.get("this");
                            Ir.Value th = emit("load", al.type, al); th.dbg = -1;
                            cargs.add(th);
                            Ir.Value call = emit("call", "i32");
                            call.name = csig.symbol;
                            call.args.addAll(cargs);
                            call.dbg = -1;
                        }
                    }
                }
            } else if (ci instanceof Java.SuperConstructorInvocation sci) {
                String scn = classSuper.get(curClass);
                if (scn != null) {
                    List<MethSig> cs = classMethods.get(scn + "::<init>");
                    MethSig csig = cs == null ? null : resolveSig(cs, sci.arguments);
                    if (csig == null)
                        throw new RuntimeException("no matching super constructor in " + scn + " for " + curClass
                            + " (" + (sci.arguments == null ? 0 : sci.arguments.length) + " args)");
                    List<Ir.Value> cargs = new ArrayList<>();
                    Ir.Value al = allocaOf.get("this");
                    Ir.Value th = emit("load", al.type, al); th.dbg = -1;
                    cargs.add(th);
                    for (int i = 0; i < sci.arguments.length; i++)
                        if (sci.arguments[i] != null)
                            cargs.add(conv(expr(sci.arguments[i]), csig.pirs[i] == null ? "i32" : csig.pirs[i]));
                    Ir.Value call = emit("call", "i32");
                    call.name = csig.symbol;
                    call.args.addAll(cargs);
                    call.dbg = -1;
                }
            }
        }
        for (Object s : m.statements) if (s instanceof Java.BlockStatement bs) stmt(bs);
        if (cur.term()==null || !Ir.isTerm(cur.term().op)) {
            if (f.retType.equals("void")) emit("return","void");
            else emit("return","void", konst(0, f.retType, -1));
        }
        prog.funcs.add(f);
        curClass = null;
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
        if (name == null) {
            if (isSuperNode(a.lhs, "SuperclassFieldAccessExpression")) {
                FieldAddr f = superFieldAddr(getStr(a.lhs, "fieldName"));
                if (f != null) {
                    Ir.Value v = conv(expr(a.rhs), f.ir);
                    Ir.Value st = emit("st_" + f.ir, "void", f.addr, v); st.dbg = tag(a);
                    return;
                }
            }
            if (a.lhs instanceof Java.FieldAccessExpression fe) {
                String nm = getStr(fe, "fieldName");
                Object lh = get(fe, "lhs");
                if (lh instanceof Java.Rvalue lv) {
                    FieldAddr f = fieldAddrFrom(lv, nm);
                    if (f != null) {
                        Ir.Value v = conv(expr(a.rhs), f.ir);
                        Ir.Value st = emit("st_" + f.ir, "void", f.addr, v); st.dbg = tag(a);
                        return;
                    }
                }
            }
            System.err.println("# assign target unsupported"); return;
        }
        Ir.Value al = allocaOf.get(name);
        if (al == null) {
            FieldAddr ft = fieldThis(name);
            if (ft != null) {
                Ir.Value v = conv(expr(a.rhs), ft.ir);
                Ir.Value st = emit("st_" + ft.ir, "void", ft.addr, v); st.dbg = tag(a);
                return;
            }
            System.err.println("# undefined: " + name); return;
        }
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

    /** Статично поле <name> на клас <cls> (или негов наследник през super-веригата). */
    Object[] staticField(String cls, String name) {
        while (cls != null) {
            Map<String, Object[]> fs = staticFields.get(cls);
            if (fs != null) { Object[] f = fs.get(name); if (f != null) return f; }
            cls = classSuper.get(cls);
        }
        return null;
    }
    /** Адрес на статично поле: lea_static <sym> (не зависи от обект). */
    Ir.Value staticAddr(Object[] f, int dbg) {
        Ir.Value v = emit("lea_static", "ptr"); v.name = (String) f[1]; v.dbg = dbg;
        return v;
    }
    FieldAddr staticAddrField(Object[] f, int dbg) {
        return new FieldAddr(staticAddr(f, dbg), (String) f[0], (String) f[2]);
    }
    /** Статично поле зад 2-иден AmbiguousName: `Foo.count` (клас) или `f.count` (обектна променлива). */
    Object[] ambigStatic(Java.AmbiguousName an) {
        if (an.identifiers.length != 2) return null;
        Object[] f = classIndex.containsKey(an.identifiers[0]) ? staticField(an.identifiers[0], an.identifiers[1]) : null;
        if (f == null) { String cj = varJType.get(an.identifiers[0]); if (cj != null) f = staticField(cj, an.identifiers[1]); }
        return f;
    }

    /** Явен `super.m(args…)` в instance метод → ДИРЕКТЕН call на super-символа (без vtable:
     *  super винаги обхожда статичния super-тип, дори ако потомък го override-ва). */
    Ir.Value superCall(Object e, int dbg) {
        String mn = getStr(e, "methodName");
        Object[] aa = get(e, "arguments") instanceof Object[] a ? a : new Object[0];
        Java.Rvalue[] args = new Java.Rvalue[aa.length];
        for (int i = 0; i < aa.length; i++) if (aa[i] instanceof Java.Rvalue r) args[i] = r;
        MethSig ms = null;
        String c = curClass == null ? null : classSuper.get(curClass);
        while (c != null && ms == null) {
            List<MethSig> ls = classMethods.get(c + "::" + mn);
            if (ls != null && !ls.isEmpty()) {
                MethSig r = resolveSig(ls, args);
                if (r != null && !r.isStatic) ms = r;
            }
            c = classSuper.get(c);
        }
        if (ms == null) throw new RuntimeException("super method not found: super." + mn);
        Ir.Value al = allocaOf.get("this");
        if (al == null) throw new RuntimeException("super." + mn + " outside instance method");
        Ir.Value th = emit("load", al.type, al); th.dbg = -1;
        List<Ir.Value> cargs = new ArrayList<>();
        cargs.add(th);
        for (int i = 0; i < args.length; i++)
            if (args[i] != null)
                cargs.add(conv(expr(args[i]), ms.pirs[i] == null ? "i32" : ms.pirs[i]));
        String crt = ms.retIr == null ? "i32" : ms.retIr.equals("void") ? "i32" : ms.retIr;
        Ir.Value call = emit("call", crt);
        call.name = ms.symbol;
        call.args.addAll(cargs);
        call.dbg = dbg;
        return call;
    }

    /** `super.<field>` read/write: същият layout като this (subclass наследява super-полетата),
     *  плюс статичен fallback в super-веригата. */
    FieldAddr superFieldAddr(String name) {
        if (curClass == null) return null;
        Ir.Value al = allocaOf.get("this");
        if (al == null) return null;
        Map<String, Object[]> fs = classFields.get(curClass);
        Object[] f = fs == null ? null : fs.get(name);
        if (f != null) {
            Ir.Value v = emit("load", al.type, al); v.dbg = -1;
            Ir.Value ad = emit("lea_field", "ptr", v, konst((Integer) f[1], "i64", -1)); ad.dbg = -1;
            return new FieldAddr(ad, (String) f[0], (String) f[2]);
        }
        Object[] sf = staticField(classSuper.get(curClass), name);
        if (sf != null) return staticAddrField(sf, -1);
        return null;
    }
    static boolean isSuperNode(Object o, String simple) {
        return o != null && o.getClass().getSimpleName().equals(simple);
    }

    static class MethSig {
        String cn, name, symbol, retIr, retJt;
        String[] pjts, pirs;
        boolean isStatic;
        int arity() { return pirs == null ? 0 : pirs.length; }
    }

    static boolean methodStatic(Java.MethodDeclarator md) {
        for (Class<?> c = md.getClass(); c != null; c = c.getSuperclass()) {
            try { Method mm = c.getDeclaredMethod("isStatic"); mm.setAccessible(true); return (Boolean) mm.invoke(md); }
            catch (NoSuchMethodException e) {}
            catch (Throwable t) { return false; }
        }
        return false;
    }

    /** Overload resolution: по arity, после exact тип match на аргументите (inferType),
     *  fallback първият с тази arity. */
    MethSig resolveSig(List<MethSig> sigs, Java.Rvalue[] args) {
        int na = args == null ? 0 : args.length;
        List<MethSig> byArity = new ArrayList<>();
        for (MethSig s : sigs) if (s.arity() == na) byArity.add(s);
        if (byArity.isEmpty()) return null;
        if (byArity.size() == 1) return byArity.get(0);
        for (MethSig s : byArity) {
            boolean ok = true;
            for (int i = 0; i < na; i++)
                if (!s.pirs[i].equals(inferType(args[i], this))) { ok = false; break; }
            if (ok) return s;
        }
        return byArity.get(0);
    }

    /** Сигнатури на метод, търсени в <cls> и super-веригата му (наследени методи). */
    List<MethSig> lookupMethod(String cls, String name) {
        if (cls == null) return null;
        List<MethSig> r = classMethods.get(cls + "::" + name);
        if (r != null && !r.isEmpty()) return r;
        return lookupMethod(classSuper.get(cls), name);
    }

    /** Дали MethodInvocation е apeл за метод на нашия клас (инстанса или static);
     *  връща [<MethSig масива>] докато "по типа на receiver-а". */
    List<MethSig> classSigs(Java.MethodInvocation mi, boolean[] staticCall) {
        Java.Rvalue tgt = (Java.Rvalue) get(mi, "target");
        if (tgt == null) { staticCall[0] = false; return null; }
        //
        if (tgt instanceof Java.AmbiguousName ta && ta.identifiers.length == 2
                && classIndex.containsKey(ta.identifiers[0])) {
            staticCall[0] = true;
            return lookupMethod(ta.identifiers[0], mi.methodName);
        }
        staticCall[0] = false;
        String rj = javaTypeOf(tgt);
        if (rj == null || !classIndex.containsKey(rj)) return null;
        return lookupMethod(rj, mi.methodName);
    }

    // ─── P3: vtable layout ─────────────────────────────────────────────────
    // Слот = "name(pjts…)" — един и същ key в цялата йерархия държи ОДНО И СЪЩО
    // място (override) и наследниците НЕ го местят: списъка на клас е super-first
    // (super slots като prefix) + собствените нови методи. Изключени са static и <init>.
    String sigKey(MethSig s) {
        StringBuilder k = new StringBuilder(s.name).append("(");
        for (int i = 0; i < (s.pjts == null ? 0 : s.pjts.length); i++) {
            if (i > 0) k.append(",");
            k.append(s.pjts[i] == null ? "_" : s.pjts[i]);
        }
        return k.append(")").toString();
    }

    void buildVtables() {
        for (Map.Entry<String, Integer> e : classIndex.entrySet()) {
            String cls = e.getKey();
            List<String> keys = new ArrayList<>();
            String sup = classSuper.get(cls);
            if (sup != null) {
                List<String> sp = classSlots.get(sup);
                if (sp != null) keys.addAll(sp);
            }
            for (Map.Entry<String, List<MethSig>> en : classMethods.entrySet()) {
                if (!en.getKey().startsWith(cls + "::") || en.getKey().startsWith(cls + "::<init>")) continue;
                for (MethSig s : en.getValue()) {
                    if (s.isStatic) continue;
                    String key = sigKey(s);
                    if (!keys.contains(key)) keys.add(key);
                }
            }
            classSlots.put(cls, keys);
        }
        for (Map.Entry<String, Integer> e : classIndex.entrySet()) {
            String cls = e.getKey();
            Ir.VTable vt = new Ir.VTable();
            vt.label = "vt_" + cls;
            for (String key : classSlots.get(cls)) vt.syms.add(vtableSymbol(cls, key));
            prog.vtables.add(vt);
        }
    }

    /** Символът, който да изпълнява slot <key> за обект от клас <cls>: own override или първият в super-веригата. */
    String vtableSymbol(String cls, String key) {
        int lp = key.indexOf('(');
        String name = key.substring(0, lp);
        String c = cls;
        while (c != null) {
            List<MethSig> ls = classMethods.get(c + "::" + name);
            if (ls != null) for (MethSig s : ls)
                if (!s.isStatic && sigKey(s).equals(key)) return s.symbol;
            c = classSuper.get(c);
        }
        throw new RuntimeException("vtable: no method " + key + " in " + cls + " chain");
    }

    /** Виртуално извикване: vtable на динамичния клас на recv + slot → ICALL.
     *  Позицията на slot-а идва от списъка на статичния клас на receiver-а
     *  (subclip-вередните vtable-и започват с него като prefix → една и съща
     *  позиция във всички динамични класове). Връща null ако slot липсва
     *  (fallback към директно извикване). */
    Ir.Value virtualCall(MethSig ms, Ir.Value recv, List<Ir.Value> avs, int dbg) {
        List<String> sp = classSlots.get(ms.cn);
        int sl = sp == null ? -1 : sp.indexOf(sigKey(ms));
        if (sl < 0) return null;
        String crt = ms.retIr == null ? "i32" : ms.retIr.equals("void") ? "i32" : ms.retIr;
        Ir.Value vt = emit("vt_ref", "ptr", recv); vt.dbg = dbg;
        Ir.Value off = konst(sl * 8, "i64", -1);
        Ir.Value adr = emit("lea_field", "ptr", vt, off); adr.dbg = dbg;
        Ir.Value fn = emit("ld_i64", "ptr", adr); fn.dbg = dbg;
        Ir.Value call = emit("icall", crt, fn);
        call.args.add(recv);
        call.args.addAll(avs);
        call.dbg = dbg;
        return call;
    }

    /** Всички classIndex-и на класове, чиято super-верига включва <tc> (вкл. самия tc). */
    List<Integer> subtypeIndexes(String tc) {
        Integer ti = classIndex.get(tc);
        List<Integer> res = new ArrayList<>();
        if (ti == null) return res;
        for (Map.Entry<String, Integer> e : classIndex.entrySet()) {
            String c = e.getKey();
            while (c != null) {
                Integer ci = classIndex.get(c);
                if (ci == null) break;
                if (ci.equals(ti)) { res.add(e.getValue()); break; }
                c = classSuper.get(c);
            }
        }
        return res;
    }

    /** Subtype тест на динамичния клас index `ci`: 1/0 дали е <tc> или негов наследник.
     *  Чист израз без control-flow: OR-верига от CMPEQ към всеки клас в subclass-затвора. */
    Ir.Value subtypeTest(Ir.Value ci, String tc, int dbg) {
        List<Integer> st = subtypeIndexes(tc);
        if (st.isEmpty()) { Ir.Value z = konst(0, "i32", dbg); return z; }
        Ir.Value r = null;
        for (Integer k : st) {
            Ir.Value eq = emit("cmpeq", "i32", ci, konst(k, "i32", dbg)); eq.dbg = dbg;
            if (r == null) r = eq;
            else { Ir.Value o = emit("or", "i32", r, eq); o.dbg = dbg; r = o; }
        }
        return r;
    }

    /** Адрес на поле <name> от класа на израза <base> — за ThisReference/AmbigName
     *  се load-ва alloca, за останали base-ове се използва стойността на израза. */
    FieldAddr fieldAddrFrom(Java.Rvalue base, String nm) {
        if (base == null || nm == null) return null;
        String bj = javaTypeOf(base);
        Map<String, Object[]> fs = bj == null || !classIndex.containsKey(bj) ? null : classFields.get(bj);
        Object[] f = fs == null ? null : fs.get(nm);
        if (f == null) {
            Object[] sf = bj == null ? null : staticField(bj, nm);
            if (sf != null) return staticAddrField(sf, -1);
            return null;
        }
        Ir.Value v;
        if (base instanceof Java.ThisReference) {
            Ir.Value al = allocaOf.get("this");
            if (al == null) return null;
            v = emit("load", al.type, al); v.dbg = -1;
        } else if (base instanceof Java.AmbiguousName an && an.identifiers.length == 1) {
            Ir.Value al = allocaOf.get(an.identifiers[0]);
            if (al == null) return null;
            v = emit("load", al.type, al); v.dbg = -1;
        } else {
            v = expr(base);
        }
        Ir.Value o = konst((Integer) f[1], "i64", -1);
        Ir.Value ad = emit("lea_field", "ptr", v, o); ad.dbg = -1;
        return new FieldAddr(ad, (String) f[0], (String) f[2]);
    }

    /** Адрес на поле <name> от текущия клас (bare име в instance метод = this.<name>). */
    FieldAddr fieldThis(String name) {
        if (curClass == null) return null;
        Map<String, Object[]> fs = classFields.get(curClass);
        Object[] f = fs == null ? null : fs.get(name);
        if (f == null) {
            Object[] sf = staticField(curClass, name);
            if (sf != null) return staticAddrField(sf, -1);
            return null;
        }
        Ir.Value al = allocaOf.get("this");
        if (al == null) return null;
        Ir.Value v = emit("load", al.type, al); v.dbg = -1;
        Ir.Value o = konst((Integer) f[1], "i64", -1);
        Ir.Value ad = emit("lea_field", "ptr", v, o); ad.dbg = -1;
        return new FieldAddr(ad, (String) f[0], (String) f[2]);
    }

    /** Резолвира верига `<var>.<fld>…` до АДРЕСА на последното поле: load-ва базовия
     *  указател на var-а и прекосява prefix-полетата с lea_field+ld_ptr. */
    FieldAddr fieldAddr(Java.AmbiguousName an) {
        if (an.identifiers.length < 2) return null;
        Object[] sf = ambigStatic(an);
        if (sf != null) return staticAddrField(sf, tag(an));
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
        if (e instanceof Java.ThisReference tr) {
            Ir.Value al = allocaOf.get("this");
            if (al == null) throw new RuntimeException("this used outside instance method");
            Ir.Value l = emit("load", al.type, al); l.dbg = tag(e);
            return l;
        }
        if (isSuperNode(e, "SuperclassMethodInvocation")) return superCall(e, tag(e));
        if (isSuperNode(e, "SuperclassFieldAccessExpression")) {
            String fn = getStr(e, "fieldName");
            FieldAddr f = superFieldAddr(fn);
            if (f == null) throw new RuntimeException("super field access on non-field: " + fn);
            Ir.Value l = emit("ld_" + f.ir, f.ir, f.addr); l.dbg = tag(e);
            return l;
        }
        if (e instanceof Java.IntegerLiteral lit) {
            String vs = lit.value;
            boolean isL = vs.endsWith("L") || vs.endsWith("l");
            if (isL) vs = vs.substring(0, vs.length()-1);
            return konst(Long.parseLong(vs), isL ? "i64" : "i32", tag(e));
        }
        if (e instanceof Java.FloatingPointLiteral lit)
            return konst(Double.doubleToRawLongBits(Double.parseDouble(String.valueOf(get(lit, "value")))), "f64", tag(e));
        if (e instanceof Java.BooleanLiteral lit) return konst(lit.value.equals("true")?1:0, "i32", tag(e));
        if (e instanceof Java.NullLiteral) return konst(0, "ptr", tag(e));
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
            FieldAddr ft = fieldThis(n);
            if (ft != null) { Ir.Value l = emit("ld_" + ft.ir, ft.ir, ft.addr); l.dbg = tag(e); return l; }
            throw new RuntimeException("undefined: " + n);
        }
        if (e instanceof Java.NewClassInstance nci) {
            Object t = get(nci, "type");
            String cn = t == null ? null : t.toString();
            if (cn != null && classIndex.containsKey(cn)) {
                Object argsO = get(nci, "arguments");
                int na = argsO instanceof Object[] o ? o.length : 0;
                int sz = classSizes.get(cn);
                Ir.Value c = konst(sz, "i32", tag(e));
                Ir.Value a = emit("alloc_obj", "ptr", c); a.dbg = tag(e);
                Ir.Value h = emit("st_hdr", "void", a, konst(classIndex.get(cn), "i32", tag(e))); h.dbg = tag(e);
                List<MethSig> cs = classMethods.get(cn + "::<init>");
                Java.Rvalue[] car = argsO instanceof Object[] oa ? (Java.Rvalue[]) oa : new Java.Rvalue[0];
                MethSig csig = cs == null ? null : resolveSig(cs, car);
                if (csig == null && na != 0)
                    throw new RuntimeException("no matching constructor: new " + cn + "(" + na + " args)");
                if (csig != null) {
                    List<Ir.Value> cargs = new ArrayList<>();
                    cargs.add(a);
                    for (int i = 0; i < car.length; i++)
                        if (car[i] != null)
                            cargs.add(conv(expr(car[i]), csig.pirs[i] == null ? "i32" : csig.pirs[i]));
                    Ir.Value call = emit("call", "i32");
                    call.name = csig.symbol;
                    call.args.addAll(cargs);
                    call.dbg = tag(e);
                }
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
            Object lh = get(fa, "lhs");
            if (lh instanceof Java.Rvalue lv) {
                FieldAddr fa2 = fieldAddrFrom(lv, nm);
                if (fa2 != null) { Ir.Value l = emit("ld_" + fa2.ir, fa2.ir, fa2.addr); l.dbg = tag(e); return l; }
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
        if (e instanceof Java.Instanceof io) {
            String tc = getStr(io, "rhs");
            if (!classIndex.containsKey(tc))
                throw new RuntimeException("instanceof on unsupported type: " + tc);
            int id = dbgSeq++;
            Ir.Value v = expr((Java.Rvalue) get(io, "lhs"));
            Ir.Block z = new Ir.Block("io_z_" + id), n = new Ir.Block("io_n_" + id), j = new Ir.Block("io_j_" + id);
            Ir.Value tmp = emit("alloca", "i32"); tmp.dbg = tag(io);
            Ir.Value nz = emit("cmpeq", "i32", v, konst(0, "ptr", tag(io))); nz.dbg = tag(io);
            emit("branch", "void", nz, blockRef(z), blockRef(n));
            curFunc.blocks.add(z); cur = z;
            emit("store", "void", tmp, konst(0, "i32", tag(io)));
            emit("jump", "void", blockRef(j));
            curFunc.blocks.add(n); cur = n;
            Ir.Value ci = emit("ld_i32", "i32", v); ci.dbg = tag(io);
            Ir.Value sub = subtypeTest(ci, tc, tag(io));
            emit("store", "void", tmp, sub);
            emit("jump", "void", blockRef(j));
            curFunc.blocks.add(j); cur = j;
            Ir.Value r = emit("load", "i32", tmp); r.dbg = tag(io);
            return r;
        }
        if (e instanceof Java.Cast c) {
            String tc = getStr(c, "targetType");
            if (classIndex.containsKey(tc)) {
                Ir.Value v = expr((Java.Rvalue) get(c, "value"));
                int id = dbgSeq++;
                Ir.Block z = new Ir.Block("cc_z_" + id), n = new Ir.Block("cc_n_" + id),
                        o = new Ir.Block("cc_o_" + id), f = new Ir.Block("cc_f_" + id), j = new Ir.Block("cc_j_" + id);
                Ir.Value tmp = emit("alloca", "ptr"); tmp.dbg = tag(c);
                Ir.Value nz = emit("cmpeq", "i32", v, konst(0, "ptr", tag(c))); nz.dbg = tag(c);
                emit("branch", "void", nz, blockRef(z), blockRef(n));
                curFunc.blocks.add(z); cur = z;
                emit("store", "void", tmp, konst(0, "ptr", tag(c)));
                emit("jump", "void", blockRef(j));
                curFunc.blocks.add(n); cur = n;
                Ir.Value ci = emit("ld_i32", "i32", v); ci.dbg = tag(c);
                Ir.Value sub = subtypeTest(ci, tc, tag(c));
                Ir.Value no = emit("cmpeq", "i32", sub, konst(0, "i32", tag(c))); no.dbg = tag(c);
                emit("branch", "void", no, blockRef(f), blockRef(o));
                curFunc.blocks.add(o); cur = o;
                emit("store", "void", tmp, v);
                emit("jump", "void", blockRef(j));
                curFunc.blocks.add(f); cur = f;
                emit("store", "void", tmp, konst(0, "ptr", tag(c)));
                emit("jump", "void", blockRef(j));
                curFunc.blocks.add(j); cur = j;
                Ir.Value r = emit("load", "ptr", tmp); r.dbg = tag(c);
                return r;
            }
            Ir.Value v = expr((Java.Rvalue) get(c, "value"));
            return conv(v, mapType(tc));
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
            // P3 class methods: instance (receiver) и static (ClassName.m)
            boolean[] sc = { false };
            List<MethSig> sigs = classSigs(mi, sc);
            if (sigs != null && !sigs.isEmpty()) {
                MethSig ms = resolveSig(sigs, mi.arguments);
                if (ms == null) throw new RuntimeException("no matching overload for " + mi.methodName + " (" + mi.arguments.length + " args)");
                List<Ir.Value> cargs = new ArrayList<>();
                if (!ms.isStatic) {
                    // virtual dispatch: vtable на динамичния клас на receiver-а
                    Ir.Value recv = recvValue(mi, tgt);
                    List<Ir.Value> avs = new ArrayList<>();
                    for (int i = 0; i < mi.arguments.length; i++)
                        if (mi.arguments[i] != null)
                            avs.add(conv(expr(mi.arguments[i]), ms.pirs[i] == null ? "i32" : ms.pirs[i]));
                    Ir.Value vc = virtualCall(ms, recv, avs, tag(mi));
                    if (vc != null) return vc;
                    cargs.add(recv);
                    cargs.addAll(avs);
                } else {
                    for (int i = 0; i < mi.arguments.length; i++)
                        if (mi.arguments[i] != null)
                            cargs.add(conv(expr(mi.arguments[i]), ms.pirs[i] == null ? "i32" : ms.pirs[i]));
                }
                String crt = ms.retIr == null ? "i32" : ms.retIr.equals("void") ? "i32" : ms.retIr;
                Ir.Value call = emit("call", crt);
                call.name = ms.symbol;
                call.args.addAll(cargs);
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
            List<MethSig> msiglist = null;
            if (curClass != null) {
                String c = curClass;
                while (c != null) {
                    List<MethSig> r = classMethods.get(c + "::" + mi.methodName);
                    if (r != null && !r.isEmpty()) { msiglist = r; break; }
                    c = classSuper.get(c);
                }
            }
            if (msiglist == null)
                for (Map.Entry<String, List<MethSig>> en : classMethods.entrySet())
                    if (en.getKey().endsWith("::" + mi.methodName)) { msiglist = en.getValue(); break; }
            if (msiglist != null && !msiglist.isEmpty()) {
                MethSig ms = resolveSig(msiglist, mi.arguments);
                if (ms == null) throw new RuntimeException("no matching overload for " + mi.methodName + " (" + mi.arguments.length + " args)");
                if (!ms.isStatic && curClass != null) {
                    // bare instance call в instance метод → virtual на this (динамичния клас)
                    Ir.Value al = allocaOf.get("this");
                    if (al != null) {
                        Ir.Value th = emit("load", al.type, al); th.dbg = -1;
                        List<Ir.Value> avs = new ArrayList<>();
                        for (int i = 0; i < mi.arguments.length; i++)
                            if (mi.arguments[i] != null)
                                avs.add(conv(expr(mi.arguments[i]), ms.pirs[i] == null ? "i32" : ms.pirs[i]));
                        Ir.Value vc = virtualCall(ms, th, avs, tag(mi));
                        if (vc != null) return vc;
                    }
                }
                List<Ir.Value> cargs = new ArrayList<>();
                for (int i = 0; i < mi.arguments.length; i++)
                    if (mi.arguments[i] != null)
                        cargs.add(conv(expr(mi.arguments[i]), ms.pirs[i] == null ? "i32" : ms.pirs[i]));
                String crt = ms.retIr == null ? "i32" : ms.retIr.equals("void") ? "i32" : ms.retIr;
                Ir.Value call = emit("call", crt);
                call.name = ms.symbol;
                call.args.addAll(cargs);
                call.dbg = tag(mi);
                return call;
            }
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