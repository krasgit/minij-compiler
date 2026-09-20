package bg.minij.common;

import java.util.*;

public class RuleParser {
    // ─── AST ─────────────────────────────────────────────────────────────────
    public static class Seg {
        public String text;                 // literal text (when !ph)
        public boolean ph;
        public String phName;               // placeholder name: dst, imm, name, ret, exit,
                                            // params, args, argregs, or a pattern name
        public boolean phIdx;               // "${...}[i]" form
        public static Seg lit(String s) { Seg g = new Seg(); g.text = s; return g; }
        public static Seg ph(String n, boolean idx) { Seg g = new Seg(); g.ph = true; g.phName = n; g.phIdx = idx; return g; }
    }

    public static class Stmt {
        public static final int LINE = 0, FOR = 1, IF = 2;
        public int kind;                    // LINE | FOR | IF
        public List<Seg> segs = new ArrayList<>();  // LINE: template segments
        public String list;                 // FOR: "args" | "argregs"
        public String cond;                 // IF: "p" (present) | "n" (absent) | "neq"
        public String c1, c2;               // IF: operand placeholder names
        public List<Stmt> body = new ArrayList<>();
    }

    public static class Rule {
        public String op;
        public List<String> pat = new ArrayList<>();
        public boolean any;                 // "..." matches any trailing args
        public List<Stmt> body = new ArrayList<>();
        public String key() { return op + "(" + String.join(",", pat) + (any ? "..." : "") + ")"; }
        public boolean hasCond() { return hasCond(body); }
        static boolean hasCond(List<Stmt> ss) {
            for (Stmt s : ss) {
                if (s.kind == Stmt.IF) return true;
                if (hasCond(s.body)) return true;
            }
            return false;
        }
        public String bodyText() { StringBuilder b = new StringBuilder(); txt(body, b); return b.toString(); }
        static void txt(List<Stmt> ss, StringBuilder b) {
            for (Stmt s : ss) {
                if (s.kind == Stmt.FOR) { b.append("for ").append(s.list).append('{'); txt(s.body, b); b.append('}'); }
                else if (s.kind == Stmt.IF) { b.append("if ").append(s.cond).append('(').append(s.c1).append(',').append(s.c2).append(')'); txt(s.body, b); }
                else for (Seg g : s.segs) b.append(g.ph ? "$[" + g.phName + (g.phIdx ? "i" : "") + "]" : g.text);
            }
        }
    }

    public static class Rules {
        public List<String> regs = new ArrayList<>();
        public List<String> args = new ArrayList<>();
        public String ret, fallback, scratch;
        // ── rule v2 ─────────────────────────────────────────────────────────
        public Map<String, String> subs = new LinkedHashMap<>(); // base → 32-bit view
        public List<String> fregs = new ArrayList<>();           // FP pool (d8.. / xmm..)
        public List<String> fargs = new ArrayList<>();           // FP ABI arg regs (d0..)
        public String fret;                                      // FP return reg (d0)
        public List<Stmt> prologue = new ArrayList<>();
        public List<Stmt> epilogue = new ArrayList<>();
        public LinkedHashMap<String, List<Rule>> ops = new LinkedHashMap<>();

        /** 32-bit view of `name` for type i32 (idempotent for other types / v1 rules). */
        public String width(String name, String type) {
            if (name == null) return null;
            if ("i32".equals(type)) {
                String s = subs.get(name);
                if (s != null) return s;
            }
            return name;
        }
        public boolean isIntType(String type) {
            if (type == null) return true;
            return type.equals("i32") || type.equals("i64") || type.equals("ptr") || type.equals("address");
        }
        public boolean isFpType(String type) {
            return type != null && (type.equals("f32") || type.equals("f64") || type.equals("float") || type.equals("double"));
        }
    }

    // ─── parser ─────────────────────────────────────────────────────────────
    public static Rules parse(String src) {
        Rules r = new Rules();
        List<String> lines = new ArrayList<>(Arrays.asList(src.split("\n")));
        int[] pos = new int[1];
        while (pos[0] < lines.size()) {
            String t = lines.get(pos[0]).trim();
            if (t.isEmpty() || t.startsWith("#")) { pos[0]++; continue; }
            if (t.startsWith("regs:")) { r.regs = toks(t.substring(5)); pos[0]++; continue; }
            if (t.startsWith("args:")) { r.args = toks(t.substring(5)); pos[0]++; continue; }
            if (t.startsWith("subs:") || t.startsWith("pairs:")) {
                int off = t.startsWith("pairs:") ? 6 : 5;
                for (String p : t.substring(off).replace(";", "").split(",")) {
                    p = p.trim();
                    if (p.isEmpty()) continue;
                    int eq = p.indexOf('=');
                    String base = eq > 0 ? p.substring(0, eq).trim() : p.trim();
                    String sub = eq > 0 ? p.substring(eq + 1).trim() : null;
                    // bare token (no '='): base=name, sub=32-bit known from context? — expect pair form
                    if (sub == null) sub = r.subs.getOrDefault(base, base);
                    r.subs.put(base, sub);
                    if (eq < 0) r.subs.put(sub, base);
                }
                pos[0]++; continue;
            }
            if (t.startsWith("fregs:")) { r.fregs = toks(t.substring(6)); pos[0]++; continue; }
            if (t.startsWith("fargs:")) { r.fargs = toks(t.substring(6)); pos[0]++; continue; }
            if (t.startsWith("fret:")) { r.fret = first(t.substring(5)); pos[0]++; continue; }
            if (t.startsWith("scratch:")) { r.scratch = first(t.substring(8)); pos[0]++; continue; }
            if (t.startsWith("ret:")) { r.ret = first(t.substring(4)); pos[0]++; continue; }
            if (t.startsWith("fallback:")) { r.fallback = first(t.substring(9)); pos[0]++; continue; }
            if (t.startsWith("prologue")) { pos[0]++; r.prologue = readBlock(lines, pos); continue; }
            if (t.startsWith("epilogue")) { pos[0]++; r.epilogue = readBlock(lines, pos); continue; }
            if (t.startsWith("emit")) { readEmit(lines, pos, r); continue; }
            pos[0]++;
        }
        validate(r);
        return r;
    }

    static List<String> toks(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.replace(";", "").trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
    static String first(String s) {
        List<String> t = toks(s);
        return t.isEmpty() ? null : t.get(0);
    }

    // block: reads styled lines until a lone "}", returns the closing-index advance
    static List<Stmt> readBlock(List<String> lines, int[] pos) {
        List<Stmt> out = new ArrayList<>();
        while (pos[0] < lines.size()) {
            String t = lines.get(pos[0]).trim();
            if (t.isEmpty()) { pos[0]++; continue; }
            if (t.equals("}")) { pos[0]++; return out; }
            if (t.startsWith("for each ")) {
                String[] p = t.split("\\s+");
                if (p.length < 4 || !p[3].equals("i") || !t.endsWith("{"))
                    err("bad 'for each' line: " + t);
                Stmt s = new Stmt(); s.kind = Stmt.FOR;
                String list = p[2];
                if (!list.equals("${args}") && !list.equals("${argregs}") && !list.equals("${cargs}"))
                    err("for each list must be ${args}, ${argregs} or ${cargs}, got " + list);
                s.list = list.substring(2, list.length() - 1);
                pos[0]++;
                s.body = readBlock(lines, pos);
                out.add(s);
                continue;
            }
            if (t.startsWith("if ")) {
                String c = t.substring(3).trim();
                if (!c.endsWith("{")) err("if must end with '{': " + t);
                c = c.substring(0, c.length() - 1).trim();
                Stmt s = new Stmt(); s.kind = Stmt.IF;
                if (c.startsWith("!")) { s.cond = "n"; s.c1 = c.substring(1).trim(); }
                else if (c.contains("!=")) {
                    String[] pp = c.split("!=");
                    s.cond = "neq"; s.c1 = pp[0].trim(); s.c2 = pp[1].trim();
                } else { s.cond = "p"; s.c1 = c; }
                pos[0]++;
                s.body = readBlock(lines, pos);
                out.add(s);
                continue;
            }
            out.add(lineStmt(lines.get(pos[0])));
            pos[0]++;
        }
        err("unterminated block (missing '}')");
        return out;
    }

    static Stmt lineStmt(String raw) {
        Stmt s = new Stmt(); s.kind = Stmt.LINE;
        s.segs = tokenize(raw);
        return s;
    }

    static void readEmit(List<String> lines, int[] pos, Rules r) {
        String t = lines.get(pos[0]).trim().substring(4).trim();
        int lp = t.indexOf('(');
        if (lp < 0) err("emit needs (args): " + t);
        int rp = t.lastIndexOf(')');
        Rule rule = new Rule();
        rule.op = t.substring(0, lp).trim();
        String inner = t.substring(lp + 1, rp).trim();
        if (!inner.isEmpty()) {
            for (String a : inner.split(",")) {
                String name = a.trim();
                if (name.endsWith("...")) { rule.any = true; String b = name.substring(0, name.length() - 3).trim();
                    if (!b.isEmpty()) rule.pat.add(b); }
                else if (name.equals("...")) rule.any = true;
                else rule.pat.add(name);
            }
        }
        if (rp >= 0) {
            if (rp + 1 < t.length()) {
                String rest = t.substring(rp + 1).trim();
                if (!rest.endsWith("{")) err("emit body must open with '{': " + t);
            } else err("emit body must open with '{': " + t);
        }
        pos[0]++;
        rule.body = readBlock(lines, pos);
        r.ops.computeIfAbsent(rule.op, k -> new ArrayList<>()).add(rule);
    }

    // tokenize a template line: `$$`=literal $, `$dst`=dst, `${name}`=placeholder,
    // `${name}[i]`=indexed placeholder (loop var i)
    static List<Seg> tokenize(String raw) {
        List<Seg> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String line = raw;
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '$' && i + 1 < n && line.charAt(i + 1) == '$') {
                buf.append('$'); i += 2; continue;
            }
            if (c == '$' && i + 1 < n && line.charAt(i + 1) == '{') {
                int e = line.indexOf('}', i + 2);
                if (e < 0) err("unterminated placeholder in: " + raw);
                flush(out, buf);
                String name = line.substring(i + 2, e);
                boolean idx = false;
                int adv = e + 1;
                if (adv + 2 < n && line.startsWith("[i]", adv)) { idx = true; adv += 3; }
                out.add(Seg.ph(name, idx));
                i = adv;
                continue;
            }
            if (c == '$' && line.startsWith("$dst", i)
                    && (i + 4 >= n || !Character.isLetterOrDigit(line.charAt(i + 4)))) {
                flush(out, buf);
                out.add(Seg.ph("dst", false));
                i += 4;
                continue;
            }
            buf.append(c);
            i++;
        }
        flush(out, buf);
        return out;
    }
    static void flush(List<Seg> out, StringBuilder buf) {
        if (buf.length() > 0) { out.add(Seg.lit(buf.toString())); buf.setLength(0); }
    }

    // ─── checks ─────────────────────────────────────────────────────────────
    static void validate(Rules r) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<Rule>> e : r.ops.entrySet()) {
            List<Rule> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Rule ru = list.get(i);
                String sig = ru.key() + " || " + ru.bodyText();
                if (!seen.add(sig)) err("duplicate rule: " + ru.key());
                for (int j = i + 1; j < list.size(); j++) {
                    Rule later = list.get(j);
                    boolean overlap = ru.any || later.any || ru.pat.size() == later.pat.size();
                    if (!ru.hasCond() && overlap)
                        err("rule without condition must be last for op '" + ru.op + "': "
                            + ru.key() + " before " + later.key());
                }
                checkPh(ru);
            }
        }
        // placeholder-name sanity per rule
    }
    static void checkPh(Rule ru) {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList("dst", "imm", "imov", "name", "ret", "exit", "args", "argregs", "params", "scratch", "ws"));
        allowed.addAll(ru.pat);
        for (Stmt s : ru.body) checkStmt(s, allowed);
    }
    static void checkStmt(Stmt s, Set<String> allowed) {
        if (s.kind == Stmt.LINE) for (Seg g : s.segs)
            if (g.ph && !allowed.contains(g.phName)) err("unknown placeholder '${" + g.phName + "}' in rule");
        else if (s.kind == Stmt.IF) {
            String a = strip(s.c1), b = s.c2 == null ? null : strip(s.c2);
            if (a != null && !allowed.contains(a) && !a.equals("dst")) err("unknown placeholder '${" + a + "}' in if");
            if (b != null && !allowed.contains(b) && !b.equals("dst")) err("unknown placeholder '${" + b + "}' in if");
            checkStmtList(s.body, allowed);
        } else if (s.kind == Stmt.FOR) {
            checkStmtList(s.body, allowed);
        }
    }
    static void checkStmtList(List<Stmt> ss, Set<String> allowed) { for (Stmt s : ss) checkStmt(s, allowed); }

    static String strip(String n) {
        if (n != null && n.startsWith("${") && n.endsWith("}")) return n.substring(2, n.length() - 1);
        if (n != null && n.equals("$dst")) return "dst";
        return n;
    }

    static void err(String m) { throw new IllegalArgumentException("rule: " + m); }
}