import java.util.*;

public class Emitter {
    Ir.Program prog; String arch; StringBuilder out;
    Map<Ir.Value,String> loc; Ir.DebugInfo dbg;
    RuleParser.Rules R;
    Ir.Func fn;
    int loopIdx = -1;

    static final Set<String> SKIP = new HashSet<>(Arrays.asList(
        "store","STORE_i32","load","LOAD_i32","alloca","ALLOCA",
        "block","symbol","undef","phi","PHI_i32"));

    public static String emit(Ir.Program p, String target, String ruleFile) throws Exception {
        Emitter e = new Emitter();
        e.prog = p; e.arch = target; e.out = new StringBuilder();
        e.loc = new LinkedHashMap<>(p.debug.locations);
        e.dbg = p.debug;
        e.R = RuleParser.parse(java.nio.file.Files.readString(java.nio.file.Path.of(ruleFile)));
        e.go();
        return e.out.toString();
    }

    void go() {
        out.append("    .file 1 \"").append(prog.module).append(".mj\"\n    .text\n\n");
        for (Ir.Func f : prog.funcs) func(f);
    }

    String reg(Ir.Value v) {
        String l = loc.get(v);
        if (l != null && l.startsWith("reg ")) return l.substring(4);
        return R.fallback != null ? R.fallback : (arch.equals("arm64") ? "w9" : "%eax");
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
        expand(R.prologue);
        for (Ir.Block b : f.blocks) {
            out.append(lbl(f, b)).append(":\n");
            for (Ir.Value v : b.ins) ins(v);
        }
        out.append(exit()).append(":\n");
        expand(R.epilogue);
        out.append("\n");
    }

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
        expand(sel.body, cx);
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
                if (o.startsWith("CALL_")) return "call";
                if (o.equals("branch") || o.equals("jump") || o.equals("return")) return o;
                return o;
        }
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
        switch (name) {
            case "dst":
                if (cx == null) throw new RuntimeException("emit: $dst outside rule");
                return reg(cx.v);
            case "imm":
                if (cx == null) throw new RuntimeException("emit: ${imm} outside rule");
                return Long.toString(cx.v.imm);
            case "name":
                if (cx == null || cx.v.name == null) throw new RuntimeException("emit: op has no symbol name");
                return cx.v.name;
            case "ret":
                if (R.ret == null) throw new RuntimeException("emit: 'ret:' missing in rule file");
                return R.ret;
            case "exit":  return exit();
            case "params": return params();
            case "args":
                if (!idx) throw new RuntimeException("emit: ${args} must be used as ${args}[i]");
                if (cx == null || loopIdx < 0 || loopIdx >= cx.v.args.size()) throw new RuntimeException("emit: ${args}[i] index out of range");
                return reg(cx.v.args.get(loopIdx));
            case "argregs":
                if (!idx) throw new RuntimeException("emit: ${argregs} must be used as ${argregs}[i]");
                if (loopIdx < 0 || loopIdx >= R.args.size()) throw new RuntimeException("emit: ${argregs}[i] index out of range");
                return R.args.get(loopIdx);
            default:
                if (cx != null && cx.bind.containsKey(name)) return valReg(cx.bind.get(name));
                throw new RuntimeException("emit: unresolved placeholder '${" + name + "}'");
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
        int n = Math.min(fn.params.size(), Math.min(R.regs.size(), R.args.size()));
        boolean arm = arch.equals("arm64");
        for (int i = 0; i < n; i++) {
            if (b.length() > 0) b.append("\n");
            b.append("    ");
            if (arm) b.append("mov ").append(R.regs.get(i)).append(", ").append(R.args.get(i));
            else b.append("movl  ").append(R.args.get(i)).append(", ").append(R.regs.get(i));
        }
        return b.toString();
    }

    }