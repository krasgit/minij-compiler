import java.util.*;

public class Emitter {
    Ir.Program prog; String arch; StringBuilder out;
    Map<Ir.Value,String> loc; Ir.DebugInfo dbg;
    RuleParser.Rules R;
    Ir.Func fn;
    int loopIdx = -1;

    static final Set<String> SKIP = new HashSet<>(Arrays.asList(
        "store","STORE_i32","load","LOAD_i32","alloca","ALLOCA",
        "block","symbol","undef","phi","PHI_i32","PHI_i64","PHI_f64"));

    public static String emit(Ir.Program p, String target, String ruleFile) throws Exception {
        Emitter e = new Emitter();
        e.prog = p; e.arch = target; e.out = new StringBuilder();
        e.loc = new LinkedHashMap<>(p.debug.locations);
        e.dbg = p.debug;
        e.R = RuleParser.parse(java.nio.file.Files.readString(java.nio.file.Path.of(ruleFile)));
        e.go();
        return e.out.toString();
    }

    // ─── FP / 64-bit literal pool ───────────────────────────────────────────
    Map<Ir.Value, String> poolLabels = new LinkedHashMap<>();
    Map<Ir.Value, Long> poolBits = new LinkedHashMap<>();
    int poolSeq = 0;

    String poolTag(Ir.Value v) {
        String l = poolLabels.get(v);
        if (l == null) { l = ".LC" + (poolSeq++); poolLabels.put(v, l); poolBits.put(v, v.imm); }
        return l;
    }

    void rodata() {
        if (poolLabels.isEmpty()) return;
        out.append("    .section .rodata\n");
        out.append("    .p2align 3\n");
        for (var e : poolLabels.entrySet()) {
            Ir.Value v = e.getKey();
            out.append(".LC" ).append(Integer.parseInt(e.getValue().substring(3)))
               .append(": .").append(v.op.startsWith("CONST_f") && v.type.equals("f32") ? "long" : "quad")
               .append(" ").append(poolBits.get(v)).append("\n");
        }
    }

    void go() {
        out.append("    .file 1 \"").append(prog.module).append(".mj\"\n    .text\n\n");
        for (Ir.Func f : prog.funcs) func(f);
        rodata();
    }

    String reg(Ir.Value v) {
        String l = loc.get(v);
        if (l != null && l.startsWith("freg ")) return l.substring(5);
        if (l != null && l.startsWith("reg ")) return R.width(l.substring(4), v.type);
        String t = spillTemp.get(v);
        if (t != null) return t;
        return R.width(R.fallback != null ? R.fallback : (arch.equals("arm64") ? "w9" : "%eax"), v.type);
    }

    void line(Ir.Value v) {
        if (v.dbg < 0) return;
        int[] p = dbg.positions.get(v.dbg);
        if (p == null) return;
        out.append("    .loc 1 ").append(p[0]).append(" ").append(p[1]).append("\n");
    }

    void func(Ir.Func f) {
        fn = f;
        out.append("    .globl ").append(f.name).append("\n").append(f.name).append(":\n");
        spillBytes = align16(maxSpillBytes(f));
        StringBuilder save = out;
        StringBuilder prow = new StringBuilder();
        out = prow;
        expand(R.prologue);
        out = save;
        if (spillBytes > 0 && arch.equals("arm64")) prow.append("    sub sp, sp, #").append(spillBytes).append("\n");
        out.append(prow);
        for (Ir.Block b : f.blocks) {
            out.append(lbl(f, b)).append(":\n");
            for (Ir.Value v : b.ins) ins(v);
        }
        out.append(exit()).append(":\n");
        if (spillBytes > 0 && arch.equals("arm64")) out.append("    add sp, sp, #").append(spillBytes).append("\n");
        expand(R.epilogue);
        out.append("\n");
    }

    // largest spill magnitude (bytes) needed by any value of this function
    int maxSpillBytes(Ir.Func f) {
        int m = 0;
        for (Ir.Block b : f.blocks)
            for (Ir.Value v : b.ins) {
                String l = loc.get(v);
                if (l != null && l.startsWith("stack ")) {
                    int off = Integer.parseInt(l.substring(6).trim());
                    m = Math.max(m, -off);
                }
            }
        return m;
    }

    int align16(int n) { return (n + 15) & ~15; }

    Ir.Block find(Ir.Func f, Ir.Value t) {
        if (!t.op.equals("block")) return f.blocks.get(0);
        if (t.name != null) for (Ir.Block b : f.blocks) if (b.name.equals(t.name)) return b;
        for (Ir.Block b : f.blocks) if (b.hashCode() == t.imm) return b;
        return f.blocks.get(0);
    }
    String lbl(Ir.Func f, Ir.Block b) {
        return ".L" + f.name.replace("$", "_") + "_" + Math.abs(b.hashCode() % 100000);
    }
    String exit() { return ".L" + fn.name.replace("$", "_") + "_exit"; }

    // ─── instruction selection ──────────────────────────────────────────────
    void ins(Ir.Value v) {
        line(v);
        String can = canon(v.op);
        if (SKIP.contains(v.op) || SKIP.contains(can)) return;
        List<RuleParser.Rule> rs = R.ops.get(can);
        if (rs == null)
            throw new RuntimeException("no rule for op '" + v.op + "' (canonical '" + can + "'); have: " + R.ops.keySet());
        RuleParser.Rule sel = null;
        for (RuleParser.Rule r : rs) if (r.any || r.pat.size() == v.args.size()) { sel = r; break; }
        if (sel == null)
            throw new RuntimeException("no rule matches op '" + v.op + "' with " + v.args.size() + " args");
        Ctx cx = new Ctx();
        cx.v = v;
        if (!sel.any) for (int i = 0; i < sel.pat.size(); i++) cx.bind.put(sel.pat.get(i), v.args.get(i));
        loopIdx = -1;
        prepareSpills(v);
        expand(sel.body, cx);
        saveSpills(v);
    }

    static String canon(String o) {
        switch (o) {
            case "add": case "ADD": return "ADD_i32";
            case "sub": case "SUB": return "SUB_i32";
            case "mul": case "MUL": return "MUL_i32";
            case "div": case "DIV": return "DIV_i32";
            case "mod": case "MOD": return "MOD_i32";
            case "copy": return "MOV_i32";
            case "BRANCH": return "branch";
            case "JMP": return "jump";
            case "RETURN": return "return";
            default:
                if (o.equals("branch") || o.equals("jump") || o.equals("return")) return o;
                return o;
        }
    }

    // ─── stack spill support ───────────────────────────────────────────────
    // Regalloc may assign a value location "stack -N". Spilled operands are
    // reloaded into dedicated scratch registers that no rule template uses
    // (arm64: w/x/d 11/12/13; x86: r11 — single temp, textual backend only).
    Map<Ir.Value, String> spillTemp = new HashMap<>();
    int spillBytes;

    boolean spilled(Ir.Value v) {
        String l = loc.get(v);
        return l != null && l.startsWith("stack ");
    }

    int spillSlot(Ir.Value v) {
        return Integer.parseInt(loc.get(v).substring(6).trim());
    }

    String stemp(Ir.Value v, int idx) {
        if (arch.equals("arm64")) {
            String base = idx == 0 ? "11" : (idx == 1 ? "12" : "13");
            if (R.isFpType(v.type)) return "d" + base;
            if (v.type == null || v.type.equals("i32")) return "w" + base;
            return "x" + base;
        }
        if (R.isFpType(v.type)) return "%r11";
        return (v.type == null || v.type.equals("i32")) ? "%r11d" : "%r11";
    }

    String slotMem(Ir.Value v) {
        if (arch.equals("arm64")) return "[sp, #" + (spillBytes + spillSlot(v)) + "]";
        return "-" + (240 + (-spillSlot(v))) + "(%rbp)";
    }

    void spillLoad(Ir.Value v) {
        String t = spillTemp.get(v);
        String mem = slotMem(v);
        if (arch.equals("arm64")) out.append("    ldr ").append(t).append(", ").append(mem).append("\n");
        else out.append(t.equals("%r11") ? "    movq " : "    movl ").append(mem).append(", ").append(t).append("\n");
    }

    void spillStore(Ir.Value v) {
        String t = spillTemp.get(v);
        String mem = slotMem(v);
        if (arch.equals("arm64")) out.append("    str ").append(t).append(", ").append(mem).append("\n");
        else out.append(t.equals("%r11") ? "    movq " : "    movl ").append(mem).append(", ").append(t).append("\n");
    }

    void prepareSpills(Ir.Value v) {
        spillTemp.clear();
        int ti = 0;
        for (Ir.Value a : v.args) {
            if (spilled(a)) {
                spillTemp.put(a, stemp(a, ti++));
                spillLoad(a);
            }
        }
        if (v.type != null && !v.type.equals("void") && spilled(v)) spillTemp.put(v, stemp(v, ti));
    }

    void saveSpills(Ir.Value v) {
        if (v.type != null && !v.type.equals("void") && spilled(v)) spillStore(v);
    }

    // ─── template interpreter ───────────────────────────────────────────────
    static class Ctx { Ir.Value v; Map<String, Ir.Value> bind = new HashMap<>(); }

    void expand(List<RuleParser.Stmt> body) { expand(body, null); }

    void expand(List<RuleParser.Stmt> body, Ctx cx) {
        for (RuleParser.Stmt s : body) {
            switch (s.kind) {
                case RuleParser.Stmt.LINE: {
                    StringBuilder b = new StringBuilder();
                    for (RuleParser.Seg g : s.segs) {
                        if (g.ph) b.append(resolve(g.phName, g.phIdx, cx));
                        else b.append(g.text);
                    }
                    String lines = b.toString();
                    if (lines.trim().isEmpty()) break;
                    for (String ln : lines.split("\n", -1)) {
                        String t2 = ln.trim();
                        if (t2.isEmpty()) continue;
                        out.append("    ").append(t2).append("\n");
                    }
                    break;
                }
                case RuleParser.Stmt.FOR: {
                    int n = s.list.equals("args")
                        ? (cx != null ? Math.min(cx.v.args.size(), R.args.size()) : 0)
                        : R.args.size();
                    int save = loopIdx;
                    for (int i = 0; i < n; i++) { loopIdx = i; expand(s.body, cx); }
                    loopIdx = save;
                    break;
                }
                case RuleParser.Stmt.IF: {
                    boolean hit;
                    if (s.cond.equals("p")) hit = present(s.c1, cx);
                    else if (s.cond.equals("n")) hit = !present(s.c1, cx);
                    else hit = !resolve(s.c1, false, cx).equals(resolve(s.c2, false, cx));
                    if (hit) expand(s.body, cx);
                    break;
                }
            }
        }
    }

    boolean present(String name, Ctx cx) {
        name = strip(name);
        if (name.equals("dst")) return cx != null;
        if (name.equals("ret") || name.equals("exit") || name.equals("imm") || name.equals("name")) return true;
        return cx != null && cx.bind.containsKey(name);
    }

    String resolve(String name, boolean idx, Ctx cx) {
        name = strip(name);
        if (name.endsWith(":sub32")) {
            String base = name.substring(0, name.length() - 6);
            return R.width(resolve(base, idx, cx), "i32");
        }
        switch (name) {
            case "dst":
                if (cx == null) throw new RuntimeException("emit: $dst outside rule");
                return reg(cx.v);
            case "imm":
                if (cx == null) throw new RuntimeException("emit: ${imm} outside rule");
                if (R.isFpType(cx.v.type) || cx.v.type.equals("i64")) return poolTag(cx.v);
                return Long.toString(cx.v.imm);
            case "imov":
                if (cx == null) throw new RuntimeException("emit: ${imov} outside rule");
                {
                    long lo = cx.v.imm & 0xFFFF, hi = (cx.v.imm >> 16) & 0xFFFF;
                    String d = reg(cx.v);
                    StringBuilder m = new StringBuilder();
                    m.append("movz ").append(d).append(", #").append(lo);
                    if (hi != 0) m.append("\n").append("movk ").append(d).append(", #").append(hi).append(", lsl #16");
                    return m.toString();
                }
            case "name":
                if (cx == null || cx.v.name == null) throw new RuntimeException("emit: op has no symbol name");
                return cx.v.name;
            case "ret":
                if (cx == null) throw new RuntimeException("emit: ${ret} outside rule");
                {
                    String rt = cx.v.op.startsWith("RETURN") ? (fn != null ? fn.retType : "i32") : cx.v.type;
                    if (rt == null) rt = "i32";
                    return R.isFpType(rt) ? (R.fret != null ? R.width(R.fret, rt) : R.ret) : R.width(R.ret, rt);
                }
            case "exit":  return exit();
            case "scratch": return R.width(R.scratch != null ? R.scratch : (R.fallback != null ? R.fallback : "x9"), cx != null ? cx.v.type : "i32");
            case "ws":
                if (!idx) throw new RuntimeException("emit: ${ws} must be used as ${ws}[i]");
                if (cx == null || loopIdx < 0 || loopIdx >= cx.v.args.size()) throw new RuntimeException("emit: ${ws}[i] index out of range");
                {
                    Ir.Value av = cx.v.args.get(loopIdx);
                    String vt = av.type;
                    boolean fp = vt != null && (vt.equals("f32") || vt.equals("f64") || vt.equals("float") || vt.equals("double"));
                    boolean i64 = vt != null && (vt.equals("i64") || vt.equals("ptr") || vt.equals("address"));
                    if (arch.equals("arm64")) return fp ? "fmov" : "mov";
                    else return fp ? "movsd" : (i64 ? "movq" : "movl");
                }
            case "params": return params();
            case "args":
                if (!idx) throw new RuntimeException("emit: ${args} must be used as ${args}[i]");
                if (cx == null || loopIdx < 0 || loopIdx >= cx.v.args.size()) throw new RuntimeException("emit: ${args}[i] index out of range");
                return reg(cx.v.args.get(loopIdx));
            case "argregs":
                if (!idx) throw new RuntimeException("emit: ${argregs} must be used as ${argregs}[i]");
                if (cx == null || loopIdx < 0 || loopIdx >= cx.v.args.size()) throw new RuntimeException("emit: ${argregs}[i] index out of range");
                {
                    int fi = 0, ii = 0;
                    for (int k = 0; k < loopIdx; k++) {
                        String kt = cx.v.args.get(k).type;
                        if (kt != null && (kt.equals("f32") || kt.equals("f64") || kt.equals("float") || kt.equals("double"))) fi++; else ii++;
                    }
                    String t = cx.v.args.get(loopIdx).type;
                    boolean fp = t != null && (t.equals("f32") || t.equals("f64") || t.equals("float") || t.equals("double"));
                    String reg = fp ? (fi < R.fargs.size() ? R.fargs.get(fi) : null)
                                    : (ii < R.args.size() ? R.args.get(ii) : null);
                    if (reg == null) throw new RuntimeException("emit: arg register index out of range");
                    return R.width(reg, t);
                }
            default:
                if (cx != null && cx.bind.containsKey(name)) return valReg(cx.bind.get(name));
                throw new RuntimeException("emit: unresolved placeholder '${" + name + "}' in rule '"
                        + (cx != null && cx.v != null ? cx.v.op : "?") + "'");
        }
    }

    String valReg(Ir.Value v) {
        if (v.op.equals("block")) return lbl(fn, find(fn, v));
        return reg(v);
    }

    static String strip(String n) {
        if (n == null) return n;
        if (n.startsWith("${") && n.endsWith("}")) return n.substring(2, n.length() - 1);
        if (n.equals("$dst")) return "dst";
        return n;
    }

    String params() {
        StringBuilder b = new StringBuilder();
        boolean arm = arch.equals("arm64");
        int ii = 0, fi = 0;
        for (int n = 0; n < fn.params.size(); n++) {
            String ptype = fn.params.get(n)[1];
            boolean fp = R.isFpType(ptype);
            String pool = fp
                ? (fi < R.fregs.size() ? R.fregs.get(fi) : null)
                : (ii < R.regs.size() ? R.regs.get(ii) : null);
            String argreg = fp
                ? (fi < R.fargs.size() ? R.fargs.get(fi) : null)
                : (ii < R.args.size() ? R.args.get(ii) : null);
            ii = fp ? ii : ii + 1;
            fi = fp ? fi + 1 : fi;
            if (pool == null || argreg == null) continue;
            String home = R.width(pool, fp ? ptype : "i32");
            String arg = R.width(argreg, fp ? ptype : "i32");
            if (b.length() > 0) b.append("\n");
            b.append("    ");
            if (arm) b.append(fp ? "fmov " : "mov ").append(home).append(", ").append(arg);
            else {
                boolean i64 = ptype.equals("i64") || ptype.equals("ptr") || ptype.equals("address");
                b.append(fp ? "movsd " : (i64 ? "movq " : "movl ")).append(arg).append(", ").append(home);
            }
        }
        return b.toString();
    }

    }