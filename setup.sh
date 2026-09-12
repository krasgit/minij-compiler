#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "═══ MiniJ Compiler Setup ═══"
mkdir -p common tools rules examples lib docs bin

# ─── Janino ───
if [ ! -f lib/janino.jar ]; then
    pkg install -y wget >/dev/null 2>&1 || true
    echo "→ Изтеглям Janino..."
    wget -q -O lib/janino.jar https://repo1.maven.org/maven2/org/codehaus/janino/janino/3.1.12/janino-3.1.12.jar
    wget -q -O lib/commons-compiler.jar https://repo1.maven.org/maven2/org/codehaus/janino/commons-compiler/3.1.12/commons-compiler-3.1.12.jar
fi

# ═══════════════════════════════════════════════════════════════════
# common/Ir.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Ir.java"
cat > common/Ir.java <<'JEOF'
import java.util.*;
public class Ir {
    public static class Value {
        public String op, type = "i32", name;
        public List<Value> args = new ArrayList<>();
        public long imm; public int dbg = -1;
        public Value() {}
        public Value(String op, String type, Value... a) { this.op=op; this.type=type; this.args=new ArrayList<>(Arrays.asList(a)); }
        public String toString() { return op+"@"+System.identityHashCode(this); }
    }
    public static class Block {
        public String name; public List<Value> ins = new ArrayList<>();
        public Block(String n) { name = n; }
        public Value term() { return ins.isEmpty() ? null : ins.get(ins.size()-1); }
    }
    public static class Func {
        public String name, retType = "i32";
        public List<String[]> params = new ArrayList<>();
        public List<Block> blocks = new ArrayList<>();
    }
    public static class Program {
        public String module = "stdin", target = "x86-64";
        public List<Func> funcs = new ArrayList<>();
        public DebugInfo debug = new DebugInfo();
    }
    public static class DebugInfo {
        public Map<Integer,int[]> positions = new LinkedHashMap<>();
        public Map<Integer,String> declNames = new LinkedHashMap<>();
        public Map<Integer,String> declTypes = new LinkedHashMap<>();
        public Map<String,List<Value>> varVersions = new LinkedHashMap<>();
        public Map<Value,String> locations = new LinkedHashMap<>();
    }
    public static boolean isTerm(String op) {
        return op.equals("jump")||op.equals("branch")||op.equals("return")
            || op.equals("JMP")||op.equals("BRANCH")||op.equals("RETURN");
    }
    public static class Writer {
        Map<Value,Integer> vid = new HashMap<>(); int next = 0;
        StringBuilder sb = new StringBuilder(); Program p;
        public static String print(Program p) { Writer w = new Writer(); w.p = p; w.go(); return w.sb.toString(); }
        int id(Value v) { Integer n = vid.get(v); if (n == null) { n = ++next; vid.put(v, n); } return n; }
        void go() {
            sb.append(".module \"").append(p.module).append("\"\n.target \"").append(p.target).append("\"\n\n");
            if (!p.debug.positions.isEmpty()) {
                sb.append(".debug_positions [\n");
                for (var e : p.debug.positions.entrySet())
                    sb.append("  ").append(e.getKey()).append(" : ").append(e.getValue()[0]).append(":").append(e.getValue()[1]).append("\n");
                sb.append("]\n\n");
            }
            if (!p.debug.declNames.isEmpty()) {
                sb.append(".debug_declarations [\n");
                for (var e : p.debug.declNames.entrySet())
                    sb.append("  ").append(e.getKey()).append(" : { name=\"").append(e.getValue()).append("\" type=").append(p.debug.declTypes.getOrDefault(e.getKey(),"i32")).append(" }\n");
                sb.append("]\n\n");
            }
            if (!p.debug.varVersions.isEmpty()) {
                sb.append(".debug_vars [\n");
                for (var e : p.debug.varVersions.entrySet()) {
                    sb.append("  \"").append(e.getKey()).append("\" : versions=[");
                    for (int i=0;i<e.getValue().size();i++) { if (i>0) sb.append(", "); sb.append("%").append(id(e.getValue().get(i))); }
                    sb.append("]\n");
                }
                sb.append("]\n\n");
            }
            for (Func f : p.funcs) func(f);
            if (!p.debug.locations.isEmpty()) {
                sb.append(".locations [\n");
                for (var e : p.debug.locations.entrySet())
                    sb.append("  %").append(id(e.getKey())).append(" : ").append(e.getValue()).append("\n");
                sb.append("]\n");
            }
        }
        void func(Func f) {
            sb.append(".func ").append(f.name).append("(");
            for (int i=0;i<f.params.size();i++) { if (i>0) sb.append(", "); sb.append(f.params.get(i)[0]).append(": ").append(f.params.get(i)[1]); }
            sb.append(") -> ").append(f.retType).append(" {\n");
            for (Block b : f.blocks) {
                sb.append("  ").append(b.name).append(" {\n");
                for (Value v : b.ins) ins(v);
                sb.append("  }\n");
            }
            sb.append("}\n\n");
        }
        void ins(Value v) {
            String d = v.dbg >= 0 ? "  ; dbg "+v.dbg : "";
            String o = v.op;
            if (o.equals("jump")||o.equals("JMP")) { sb.append("    jump ").append(bn(v.args.get(0))).append(d).append("\n"); return; }
            if (o.equals("branch")||o.equals("BRANCH")) { sb.append("    branch %").append(id(v.args.get(0))).append(", ").append(bn(v.args.get(1))).append(", ").append(bn(v.args.get(2))).append(d).append("\n"); return; }
            if (o.equals("return")||o.equals("RETURN")) { sb.append("    return"); if (!v.args.isEmpty()) sb.append(" %").append(id(v.args.get(0))); sb.append(d).append("\n"); return; }
            if (o.equals("store")||o.equals("STORE_i32")) { sb.append("    store %").append(id(v.args.get(0))).append(", %").append(id(v.args.get(1))).append(d).append("\n"); return; }
            if (o.equals("alloca")||o.equals("ALLOCA")) { sb.append("    %").append(id(v)).append(" = alloca ").append(v.type).append(d).append("\n"); return; }
            if (o.equals("phi")||o.startsWith("PHI_")) {
                sb.append("    %").append(id(v)).append(" = phi ").append(v.type);
                for (int i=0;i+1<v.args.size();i+=2)
                    sb.append(" [").append(bn(v.args.get(i))).append(": %").append(id(v.args.get(i+1))).append("]");
                sb.append(d).append("\n"); return;
            }
            if (o.equals("call")||o.startsWith("CALL_")) {
                sb.append("    %").append(id(v)).append(" = call ").append(v.name!=null?v.name:"?").append("(");
                for (int i=0;i<v.args.size();i++) { if (i>0) sb.append(", "); sb.append("%").append(id(v.args.get(i))); }
                sb.append(")").append(d).append("\n"); return;
            }
            if (o.equals("block")||o.equals("symbol")||o.equals("undef")) return;
            sb.append("    %").append(id(v)).append(" = ").append(o).append(" ").append(v.type);
            for (Value a : v.args) sb.append(" %").append(id(a));
            if ((o.equals("const")||o.startsWith("CONST_")) && v.args.isEmpty()) sb.append(" ").append(v.imm);
            if ((o.equals("param")||o.startsWith("PARAM_")) && v.args.isEmpty()) sb.append(" ").append(v.imm);
            sb.append(d).append("\n");
        }
        String bn(Value v) { return v.op.equals("block") ? (v.name!=null?v.name:"B"+Math.abs(v.imm%1000)) : "B"+id(v); }
    }
    public static class Reader {
        List<String> lines = new ArrayList<>(); int pos = 0;
        Program p = new Program();
        Map<Integer,Value> values = new LinkedHashMap<>();
        Map<String,Block> blocks = new LinkedHashMap<>();
        Func cur; Block curB;
        public static Program parse(String src) {
            Reader r = new Reader();
            for (String ln : src.split("\n")) r.lines.add(ln);
            r.run(); return r.p;
        }
        String peek() { return pos < lines.size() ? lines.get(pos) : ""; }
        String next() { return pos < lines.size() ? lines.get(pos++) : ""; }
        void run() {
            while (pos < lines.size()) {
                String ln = peek().trim();
                if (ln.isEmpty()) { next(); continue; }
                if (ln.startsWith(".module")) { p.module = str(ln); next(); continue; }
                if (ln.startsWith(".target")) { p.target = str(ln); next(); continue; }
                if (ln.startsWith(".debug_positions")) { readPos(); continue; }
                if (ln.startsWith(".debug_declarations")) { readDecl(); continue; }
                if (ln.startsWith(".debug_vars")) { readVars(); continue; }
                if (ln.startsWith(".locations")) { readLoc(); continue; }
                if (ln.startsWith(".func")) { readFunc(); continue; }
                next();
            }
        }
        String str(String s) { int a=s.indexOf('"'), b=s.lastIndexOf('"'); return (a>=0&&b>a) ? s.substring(a+1,b) : ""; }
        void readPos() { next();
            while (!peek().trim().startsWith("]")) {
                String ln = next().trim(); if (ln.isEmpty()) continue;
                String[] p2 = ln.split(":");
                p.debug.positions.put(Integer.parseInt(p2[0].trim()), new int[]{Integer.parseInt(p2[1].trim()), Integer.parseInt(p2[2].trim())});
            } next(); }
        void readDecl() { next();
            while (!peek().trim().startsWith("]")) {
                String ln = next().trim(); if (ln.isEmpty()) continue;
                int c = ln.indexOf(':');
                int id = Integer.parseInt(ln.substring(0,c).trim());
                String rest = ln.substring(c+1);
                int n1 = rest.indexOf("name=\""), n2 = rest.indexOf("\"", n1+6);
                String name = rest.substring(n1+6, n2);
                int t1 = rest.indexOf("type="), t2 = rest.indexOf(" ", t1); if (t2 < 0) t2 = rest.indexOf("}", t1);
                String type = rest.substring(t1+5, t2).trim();
                p.debug.declNames.put(id, name); p.debug.declTypes.put(id, type);
            } next(); }
        void readVars() { next();
            while (!peek().trim().startsWith("]")) {
                String ln = next().trim(); if (ln.isEmpty()) continue;
                int q1=ln.indexOf('"'), q2=ln.indexOf('"',q1+1);
                String name = ln.substring(q1+1, q2);
                int a = ln.indexOf("versions=["), b = ln.indexOf("]", a);
                List<Value> vals = new ArrayList<>();
                for (String s : ln.substring(a+10, b).split(",")) {
                    s = s.trim();
                    if (s.startsWith("%")) { Value v = values.get(Integer.parseInt(s.substring(1))); if (v!=null) vals.add(v); }
                }
                p.debug.varVersions.put(name, vals);
            } next(); }
        void readLoc() { next();
            while (!peek().trim().startsWith("]")) {
                String ln = next().trim(); if (ln.isEmpty()) continue;
                int c = ln.indexOf(':');
                int id = Integer.parseInt(ln.substring(1, c).trim());
                String loc = ln.substring(c+1).trim();
                Value v = values.get(id);
                if (v != null) p.debug.locations.put(v, loc);
            } next(); }
        void readFunc() {
            String ln = next().trim();
            int lp = ln.indexOf('('), rp = ln.indexOf(')');
            String name = ln.substring(5, lp).trim();
            String params = ln.substring(lp+1, rp).trim();
            String ret = ln.substring(ln.indexOf("->")+2, ln.indexOf('{')).trim();
            cur = new Func(); cur.name = name; cur.retType = ret;
            if (!params.isEmpty()) for (String ps : params.split(",")) {
                String[] kv = ps.trim().split(":"); cur.params.add(new String[]{kv[0].trim(), kv[1].trim()});
            }
            while (true) {
                String l = peek().trim();
                if (l.equals("}")) { next(); break; }
                if (l.isEmpty()) { next(); continue; }
                if (l.endsWith("{") && !l.startsWith(".")) {
                    String bn = l.substring(0, l.length()-1).trim();
                    Block b = new Block(bn); cur.blocks.add(b); blocks.put(bn, b); curB = b;
                    next(); continue;
                }
                if (l.startsWith("%") || l.startsWith("jump") || l.startsWith("branch") || l.startsWith("return") || l.startsWith("store")) {
                    readIns(l); next(); continue;
                }
                next();
            }
            p.funcs.add(cur); cur = null; curB = null;
        }
        Value lookup(int id) { Value v = values.get(id); if (v == null) { v = new Value(); v.op = "undef"; values.put(id, v); } return v; }
        void readIns(String ln) {
            int dbg = -1;
            int sc = ln.indexOf(";");
            String body = ln;
            if (sc >= 0) { body = ln.substring(0, sc).trim(); String d = ln.substring(sc+1).trim(); if (d.startsWith("dbg")) try { dbg = Integer.parseInt(d.substring(3).trim()); } catch (Exception e) {} }
            if (body.isEmpty()) return;
            if (body.startsWith("jump ")) { Value t = new Value("block","ptr"); t.name = body.substring(5).trim(); Value v = new Value("jump","void",t); v.dbg = dbg; curB.ins.add(v); return; }
            if (body.startsWith("branch ")) {
                String[] parts = body.substring(7).split(",");
                int cid = Integer.parseInt(parts[0].trim().substring(1));
                Value tb = new Value("block","ptr"); tb.name = parts[1].trim();
                Value eb = new Value("block","ptr"); eb.name = parts[2].trim();
                Value v = new Value("branch","void",lookup(cid),tb,eb); v.dbg=dbg; curB.ins.add(v); return;
            }
            if (body.startsWith("return")) {
                Value v = new Value("return","void"); String r = body.substring(6).trim();
                if (!r.isEmpty() && r.startsWith("%")) v.args.add(lookup(Integer.parseInt(r.substring(1))));
                v.dbg=dbg; curB.ins.add(v); return;
            }
            if (body.startsWith("store ")) {
                String[] parts = body.substring(6).split(",");
                int a = Integer.parseInt(parts[0].trim().substring(1));
                int b = Integer.parseInt(parts[1].trim().substring(1));
                Value v = new Value("store","void",lookup(a),lookup(b)); v.dbg=dbg; curB.ins.add(v); return;
            }
            if (body.startsWith("%")) {
                int eq = body.indexOf('=');
                int id = Integer.parseInt(body.substring(1, eq).trim());
                Value v = parseOp(body.substring(eq+1).trim()); v.dbg = dbg; values.put(id, v); curB.ins.add(v); return;
            }
        }
        Value parseOp(String s) {
            String[] parts = s.split("\\s+");
            Value v = new Value(); v.op = parts[0];
            if (v.op.equals("alloca")) { v.type = parts.length>1?parts[1]:"i32"; return v; }
            if (v.op.startsWith("CONST_") || v.op.equals("const")) { v.type=parts[1]; v.imm=Long.parseLong(parts[2]); return v; }
            if (v.op.startsWith("PARAM_") || v.op.equals("param")) { v.type=parts[1]; v.imm=Long.parseLong(parts[2]); return v; }
            if (v.op.startsWith("CALL_") || v.op.equals("call")) {
                int lp = s.indexOf('('), rp = s.lastIndexOf(')');
                if (lp > 0 && rp > lp) {
                    String pre = s.substring(0, lp).trim();
                    String[] pr = pre.split("\\s+");
                    v.name = pr.length>1 ? pr[1] : "?";
                    for (String a : s.substring(lp+1, rp).split(",")) {
                        a = a.trim(); if (a.startsWith("%")) v.args.add(lookup(Integer.parseInt(a.substring(1))));
                    }
                }
                v.type = "i32"; return v;
            }
            if (v.op.startsWith("PHI_") || v.op.equals("phi")) {
                v.type = parts[1];
                for (int j=2;j<parts.length;j++) {
                    String p2 = parts[j];
                    if (p2.startsWith("[") && p2.endsWith(":")) {
                        Value bv = new Value("block","ptr"); bv.name = p2.substring(1, p2.length()-1); v.args.add(bv);
                    } else if (p2.startsWith("%")) v.args.add(lookup(Integer.parseInt(p2.substring(1))));
                    else if (p2.endsWith("]") && p2.startsWith("%")) v.args.add(lookup(Integer.parseInt(p2.substring(1, p2.length()-1))));
                }
                return v;
            }
            if (parts.length >= 2) v.type = parts[1];
            for (int j=2;j<parts.length;j++) if (parts[j].startsWith("%")) v.args.add(lookup(Integer.parseInt(parts[j].substring(1))));
            return v;
        }
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/Dominance.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Dominance.java"
cat > common/Dominance.java <<'JEOF'
import java.util.*;
public class Dominance {
    public Ir.Func f;
    public List<Ir.Block> order = new ArrayList<>();
    public Map<Ir.Block,Integer> orderIdx = new HashMap<>();
    public Map<Ir.Block,Set<Ir.Block>> dom = new HashMap<>();
    public Map<Ir.Block,Ir.Block> idom = new HashMap<>();
    public Map<Ir.Block,List<Ir.Block>> preds = new LinkedHashMap<>();
    public Map<Ir.Block,List<Ir.Block>> succs = new LinkedHashMap<>();
    public Map<Ir.Block,Set<Ir.Block>> df = new LinkedHashMap<>();
    public static Dominance compute(Ir.Func f) { Dominance d = new Dominance(); d.f = f; d.run(); return d; }
    Ir.Block resolve(Ir.Value v) {
        if (!v.op.equals("block")) return null;
        for (Ir.Block b : f.blocks) if (b.hashCode() == v.imm) return b;
        return null;
    }
    void run() {
        if (f.blocks.isEmpty()) return;
        Ir.Block entry = f.blocks.get(0);
        for (Ir.Block b : f.blocks) { preds.put(b,new ArrayList<>()); succs.put(b,new ArrayList<>()); df.put(b,new LinkedHashSet<>()); }
        for (Ir.Block b : f.blocks) {
            Ir.Value t = b.term(); if (t == null) continue;
            if (t.op.equals("jump")||t.op.equals("JMP")) {
                Ir.Block tb = resolve(t.args.get(0));
                if (tb != null) { succs.get(b).add(tb); preds.get(tb).add(b); }
            } else if (t.op.equals("branch")||t.op.equals("BRANCH")) {
                Ir.Block tb = resolve(t.args.get(1)), eb = resolve(t.args.get(2));
                if (tb != null) { succs.get(b).add(tb); preds.get(tb).add(b); }
                if (eb != null) { succs.get(b).add(eb); preds.get(eb).add(b); }
            }
        }
        Deque<Ir.Block> q = new ArrayDeque<>();
        q.add(entry); order.add(entry); orderIdx.put(entry, 0);
        while (!q.isEmpty()) { Ir.Block b = q.poll();
            for (Ir.Block s : succs.get(b)) if (!orderIdx.containsKey(s)) { orderIdx.put(s, order.size()); order.add(s); q.add(s); } }
        for (Ir.Block b : f.blocks) {
            if (b == entry) { Set<Ir.Block> s = new HashSet<>(); s.add(b); dom.put(b, s); }
            else dom.put(b, new HashSet<>(f.blocks));
        }
        boolean ch = true;
        while (ch) { ch = false;
            for (int i = 1; i < order.size(); i++) {
                Ir.Block b = order.get(i); Set<Ir.Block> nd = null;
                for (Ir.Block p : preds.get(b)) { if (nd == null) nd = new HashSet<>(dom.get(p)); else nd.retainAll(dom.get(p)); }
                if (nd == null) continue; nd.add(b);
                if (!nd.equals(dom.get(b))) { dom.put(b, nd); ch = true; }
            }
        }
        for (int i = 1; i < order.size(); i++) {
            Ir.Block b = order.get(i); Ir.Block best = null;
            for (Ir.Block d : dom.get(b)) { if (d == b) continue; if (best == null || dom.get(d).size() > dom.get(best).size()) best = d; }
            idom.put(b, best);
        }
        for (Ir.Block b : order) {
            if (preds.get(b).size() < 2) continue;
            for (Ir.Block p : preds.get(b)) { Ir.Block r = p; while (r != null && r != idom.get(b)) { df.get(r).add(b); r = idom.get(r); } }
        }
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/Ssa.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Ssa.java"
cat > common/Ssa.java <<'JEOF'
import java.util.*;
public class Ssa {
    public static void build(Ir.Program p) { for (Ir.Func f : p.funcs) buildFunc(f, p.debug); }
    static void buildFunc(Ir.Func f, Ir.DebugInfo dbg) {
        if (f.blocks.isEmpty()) return;
        Map<Ir.Value,String> prom = new LinkedHashMap<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (v.op.equals("alloca")||v.op.equals("ALLOCA")) {
                String n = dbg.declNames.get(v.dbg);
                prom.put(v, n != null ? n : ("__v" + System.identityHashCode(v)));
            }
        if (prom.isEmpty()) return;
        Dominance dom = Dominance.compute(f);
        Map<String,Set<Ir.Block>> defSites = new LinkedHashMap<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (v.op.equals("store")||v.op.equals("STORE_i32")) {
                String n = prom.get(v.args.get(0));
                if (n != null) defSites.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(b);
            }
        Map<Ir.Block,Map<String,Ir.Value>> phiDefs = new LinkedHashMap<>();
        for (var e : defSites.entrySet()) {
            String name = e.getKey();
            Deque<Ir.Block> work = new ArrayDeque<>(e.getValue());
            Set<Ir.Block> has = new HashSet<>();
            while (!work.isEmpty()) {
                Ir.Block b = work.poll();
                for (Ir.Block d : dom.df.get(b)) if (has.add(d)) {
                    Ir.Value phi = new Ir.Value("phi","i32");
                    phi.dbg = dbg.declNames.entrySet().stream().filter(x -> x.getValue().equals(name)).findFirst().map(Map.Entry::getKey).orElse(-1);
                    phiDefs.computeIfAbsent(d, k -> new LinkedHashMap<>()).put(name, phi);
                    d.ins.add(0, phi);
                    work.add(d);
                }
            }
        }
        Map<Ir.Block,List<Ir.Block>> children = new LinkedHashMap<>();
        for (Ir.Block b : f.blocks) children.put(b, new ArrayList<>());
        for (var e : dom.idom.entrySet()) if (e.getValue() != null) children.get(e.getValue()).add(e.getKey());
        Map<String,Deque<Ir.Value>> stacks = new LinkedHashMap<>();
        for (String n : defSites.keySet()) stacks.put(n, new ArrayDeque<>());
        rename(f.blocks.get(0), phiDefs, stacks, children, prom, dbg, dom);
        for (Ir.Block b : f.blocks) {
            List<Ir.Value> keep = new ArrayList<>();
            for (Ir.Value v : b.ins) {
                if (v.op.equals("alloca")||v.op.equals("ALLOCA")) continue;
                if ((v.op.equals("store")||v.op.equals("STORE_i32")) && prom.containsKey(v.args.get(0))) continue;
                keep.add(v);
            }
            b.ins = keep;
        }
        dbg.varVersions.clear();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            String n = dbg.declNames.get(v.dbg);
            if (n != null && (v.op.equals("phi")||v.op.equals("copy")))
                dbg.varVersions.computeIfAbsent(n, k -> new ArrayList<>()).add(v);
        }
    }
    static void rename(Ir.Block b, Map<Ir.Block,Map<String,Ir.Value>> phiDefs, Map<String,Deque<Ir.Value>> stacks, Map<Ir.Block,List<Ir.Block>> children, Map<Ir.Value,String> prom, Ir.DebugInfo dbg, Dominance dom) {
        List<String> pushed = new ArrayList<>();
        Map<String,Ir.Value> phis = phiDefs.getOrDefault(b, Collections.emptyMap());
        for (var e : phis.entrySet()) { stacks.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>()).push(e.getValue()); pushed.add(e.getKey()); }
        List<Ir.Value> newIns = new ArrayList<>();
        for (Ir.Value v : b.ins) {
            if (v.op.equals("load")||v.op.equals("LOAD_i32")) {
                String n = prom.get(v.args.get(0));
                if (n != null) {
                    Deque<Ir.Value> s = stacks.get(n);
                    if (s != null && !s.isEmpty()) { v.op = "copy"; v.args = new ArrayList<>(); v.args.add(s.peek()); newIns.add(v); continue; }
                }
            } else if (v.op.equals("store")||v.op.equals("STORE_i32")) {
                String n = prom.get(v.args.get(0));
                if (n != null) { stacks.computeIfAbsent(n, k -> new ArrayDeque<>()).push(v.args.get(1)); pushed.add(n); continue; }
            }
            newIns.add(v);
        }
        b.ins = newIns;
        for (Ir.Block succ : dom.succs.get(b)) {
            Map<String,Ir.Value> sp = phiDefs.get(succ);
            if (sp == null) continue;
            for (var e : sp.entrySet()) {
                String n = e.getKey(); Ir.Value phi = e.getValue();
                Deque<Ir.Value> s = stacks.get(n);
                Ir.Value val = (s == null || s.isEmpty()) ? null : s.peek();
                if (val == null) { val = new Ir.Value("undef","i32"); val.dbg = phi.dbg; }
                Ir.Value mk = new Ir.Value("block","ptr"); mk.imm = b.hashCode(); mk.name = b.name;
                phi.args.add(mk); phi.args.add(val);
            }
        }
        for (Ir.Block c : children.getOrDefault(b, Collections.emptyList()))
            rename(c, phiDefs, stacks, children, prom, dbg, dom);
        for (String n : pushed) { Deque<Ir.Value> s = stacks.get(n); if (s != null && !s.isEmpty()) s.pop(); }
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/Opt.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Opt.java"
cat > common/Opt.java <<'JEOF'
import java.util.*;
public class Opt {
    public static void run(Ir.Program p) { for (Ir.Func f : p.funcs) { boolean ch = true; while (ch) ch = one(f); } }
    static boolean one(Ir.Func f) {
        boolean ch = false;
        for (Ir.Block b : f.blocks) { if (fold(b)) ch = true; if (copy(b)) ch = true; }
        if (dce(f)) ch = true;
        return ch;
    }
    static boolean fold(Ir.Block b) {
        boolean ch = false;
        for (Ir.Value v : b.ins) {
            if (v.args.size() != 2) continue;
            Ir.Value a = v.args.get(0), c = v.args.get(1);
            boolean ac = a.op.equals("const")||a.op.startsWith("CONST_");
            boolean cc = c.op.equals("const")||c.op.startsWith("CONST_");
            if (ac && cc) {
                long x = a.imm, y = c.imm, r = 0; boolean ok = true;
                switch (v.op) {
                    case "add": case "ADD_i32": r=x+y; break;
                    case "sub": case "SUB_i32": r=x-y; break;
                    case "mul": case "MUL_i32": r=x*y; break;
                    case "div": case "DIV_i32": if (y==0) ok=false; else r=x/y; break;
                    case "mod": case "MOD_i32": if (y==0) ok=false; else r=x%y; break;
                    case "cmplt": case "CMPLT_i32": r=x<y?1:0; break;
                    case "cmpgt": case "CMPGT_i32": r=x>y?1:0; break;
                    case "cmple": case "CMPLE_i32": r=x<=y?1:0; break;
                    case "cmpge": case "CMPGE_i32": r=x>=y?1:0; break;
                    case "cmpeq": case "CMPEQ_i32": r=x==y?1:0; break;
                    case "cmpne": case "CMPNE_i32": r=x!=y?1:0; break;
                    default: ok = false;
                }
                if (ok) { v.op = "const"; v.imm = r; v.args.clear(); ch = true; }
            }
        }
        return ch;
    }
    static boolean copy(Ir.Block b) {
        Map<Ir.Value,Ir.Value> m = new HashMap<>(); boolean ch = false;
        for (Ir.Value v : b.ins) {
            if (v.op.equals("copy") && !v.args.isEmpty()) {
                Ir.Value src = v.args.get(0); while (m.containsKey(src)) src = m.get(src);
                m.put(v, src); ch = true;
            }
            for (int i=0;i<v.args.size();i++) if (m.containsKey(v.args.get(i))) { v.args.set(i, m.get(v.args.get(i))); ch = true; }
        }
        return ch;
    }
    static boolean dce(Ir.Func f) {
        Set<Ir.Value> used = new HashSet<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (Ir.isTerm(v.op) || v.op.equals("phi") || v.op.equals("PHI_i32")
                || v.op.equals("store") || v.op.equals("STORE_i32")
                || v.op.equals("call") || v.op.startsWith("CALL_")) used.add(v);
        boolean ch = true;
        while (ch) { ch = false;
            for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) if (used.contains(v))
                for (Ir.Value a : v.args) if (!used.contains(a)) { used.add(a); ch = true; } }
        boolean rm = false;
        for (Ir.Block b : f.blocks) {
            List<Ir.Value> keep = new ArrayList<>();
            for (Ir.Value v : b.ins) {
                if (used.contains(v) || v.op.equals("block") || v.op.equals("symbol")) keep.add(v);
                else rm = true;
            }
            b.ins = keep;
        }
        return rm;
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/Regalloc.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Regalloc.java"
cat > common/Regalloc.java <<'JEOF'
import java.util.*;
public class Regalloc {
    static final String[] X = {"%rbx","%r12","%r13","%r14","%r15"};
    static final String[] A = {"x19","x20","x21","x22","x23","x24","x25","x26"};
    public static void run(Ir.Program p, boolean arm) {
        String[] regs = arm ? A : X;
        for (Ir.Func f : p.funcs) alloc(f, regs, p.debug);
    }
    static void alloc(Ir.Func f, String[] regs, Ir.DebugInfo dbg) {
        List<Ir.Value> order = new ArrayList<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            if (Ir.isTerm(v.op) || v.op.equals("block") || v.op.equals("symbol")) continue;
            if (v.op.startsWith("STORE_") || v.op.equals("store")) continue;
            order.add(v);
        }
        Map<Ir.Value,Integer> last = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Ir.Value v = order.get(i);
            for (Ir.Value a : v.args) last.put(a, i);
            last.putIfAbsent(v, i);
        }
        Map<Ir.Value,String> loc = new LinkedHashMap<>();
        PriorityQueue<Ir.Value> active = new PriorityQueue<>((a,b) -> Integer.compare(last.getOrDefault(b,0), last.getOrDefault(a,0)));
        Deque<String> free = new ArrayDeque<>(Arrays.asList(regs));
        int slot = 0;
        for (int i = 0; i < order.size(); i++) {
            Ir.Value v = order.get(i);
            List<Ir.Value> exp = new ArrayList<>();
            for (Ir.Value a : active) if (last.getOrDefault(a,0) < i) exp.add(a);
            for (Ir.Value a : exp) { active.remove(a); String r = loc.get(a); if (r != null && r.startsWith("reg ")) free.addLast(r.substring(4)); }
            if (!free.isEmpty()) { loc.put(v, "reg " + free.removeFirst()); active.add(v); }
            else {
                Ir.Value victim = active.peek();
                if (victim != null && last.getOrDefault(victim,0) > last.getOrDefault(v,0)) {
                    active.poll(); String r = loc.get(victim).substring(4);
                    loc.put(victim, "stack " + (-8*(++slot)));
                    loc.put(v, "reg " + r); active.add(v);
                } else loc.put(v, "stack " + (-8*(++slot)));
            }
        }
        for (var e : loc.entrySet()) dbg.locations.put(e.getKey(), e.getValue());
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/PhiElim.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/PhiElim.java"
cat > common/PhiElim.java <<'JEOF'
import java.util.*;
public class PhiElim {
    public static void run(Ir.Program p) {
        for (Ir.Func f : p.funcs) {
            Map<Integer,Ir.Block> byHash = new HashMap<>();
            for (Ir.Block b : f.blocks) byHash.put(b.hashCode(), b);
            Map<Ir.Block,List<Ir.Value>> inserts = new LinkedHashMap<>();
            for (Ir.Block b : f.blocks) {
                List<Ir.Value> keep = new ArrayList<>();
                for (Ir.Value v : b.ins) {
                    if (!v.op.startsWith("PHI_") && !v.op.equals("phi")) { keep.add(v); continue; }
                    for (int i = 0; i+1 < v.args.size(); i += 2) {
                        Ir.Value mk = v.args.get(i), val = v.args.get(i+1);
                        Ir.Block pred = byHash.get((int)mk.imm);
                        if (pred == null) continue;
                        Ir.Value mov = new Ir.Value("MOV_i32","i32", val, v);
                        mov.dbg = v.dbg;
                        inserts.computeIfAbsent(pred, k -> new ArrayList<>()).add(mov);
                    }
                }
                b.ins = keep;
            }
            for (var e : inserts.entrySet()) {
                Ir.Block b = e.getKey();
                int termIdx = b.ins.size();
                if (termIdx > 0 && Ir.isTerm(b.ins.get(termIdx-1).op)) termIdx--;
                b.ins.addAll(termIdx, e.getValue());
            }
        }
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/Emitter.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/Emitter.java"
cat > common/Emitter.java <<'JEOF'
import java.util.*;
public class Emitter {
    Ir.Program prog; String arch; StringBuilder out;
    Map<Ir.Value,String> loc; Ir.DebugInfo dbg;
    public static String emit(Ir.Program p, String target, String ruleFile) throws Exception {
        Emitter e = new Emitter();
        e.prog = p; e.arch = target; e.out = new StringBuilder();
        e.loc = new LinkedHashMap<>(p.debug.locations);
        e.dbg = p.debug;
        e.go();
        return e.out.toString();
    }
    void go() {
        out.append("    .file 1 \"").append(prog.module).append(".mj\"\n    .text\n\n");
        for (Ir.Func f : prog.funcs) func(f);
    }
    String reg(Ir.Value v) {
        String l = loc.get(v);
        if (l == null) return arch.equals("arm64") ? "x9" : "%eax";
        if (l.startsWith("reg ")) return l.substring(4);
        return arch.equals("arm64") ? "x9" : "%eax";
    }
    void line(Ir.Value v) {
        if (v.dbg < 0) return;
        int[] p = dbg.positions.get(v.dbg);
        if (p == null) return;
        out.append("    .loc 1 ").append(p[0]).append(" ").append(p[1]).append("\n");
    }
    void func(Ir.Func f) {
        out.append("    .globl ").append(f.name).append("\n").append(f.name).append(":\n");
        boolean arm = arch.equals("arm64");
        if (arm) {
            out.append("    stp x29, x30, [sp, #-16]!\n    mov x29, sp\n");
            out.append("    stp x19, x20, [sp, #-16]!\n    stp x21, x22, [sp, #-16]!\n    stp x23, x24, [sp, #-16]!\n");
            String[] a = {"w0","w1","w2","w3","w4","w5","w6","w7"};
            String[] r = {"x19","x20","x21","x22","x23","x24","x25","x26"};
            for (int i = 0; i < f.params.size() && i < 8; i++) out.append("    mov ").append(r[i]).append(", ").append(a[i]).append("\n");
        } else {
            out.append("    pushq %rbp\n    movq  %rsp, %rbp\n");
            out.append("    pushq %rbx\n    pushq %r12\n    pushq %r13\n    pushq %r14\n    pushq %r15\n    subq  $128, %rsp\n");
            String[] a = {"%edi","%esi","%edx","%ecx","%r8d","%r9d"};
            String[] r = {"%ebx","%r12d","%r13d","%r14d","%r15d"};
            for (int i = 0; i < f.params.size() && i < 5; i++) out.append("    movl  ").append(a[i]).append(", ").append(r[i]).append("\n");
        }
        for (Ir.Block b : f.blocks) {
            out.append(".L").append(f.name.replace("$","_")).append("_").append(Math.abs(b.hashCode()%100000)).append(":\n");
            for (Ir.Value v : b.ins) ins(v, f);
        }
        if (arm) out.append("    ldp x23, x24, [sp], #16\n    ldp x21, x22, [sp], #16\n    ldp x19, x20, [sp], #16\n    ldp x29, x30, [sp], #16\n    ret\n\n");
        else out.append("    addq  $128, %rsp\n    popq  %r15\n    popq  %r14\n    popq  %r13\n    popq  %r12\n    popq  %rbx\n    popq  %rbp\n    retq\n\n");
    }
    Ir.Block find(Ir.Func f, Ir.Value t) {
        if (!t.op.equals("block")) return f.blocks.get(0);
        for (Ir.Block b : f.blocks) if (b.hashCode() == t.imm) return b;
        return f.blocks.get(0);
    }
    String lbl(Ir.Func f, Ir.Block b) { return ".L"+f.name.replace("$","_")+"_"+Math.abs(b.hashCode()%100000); }
    void ins(Ir.Value v, Ir.Func f) {
        line(v);
        boolean arm = arch.equals("arm64");
        String o = v.op;
        if (o.equals("BRANCH")||o.equals("branch")) {
            Ir.Value c = v.args.get(0);
            Ir.Block tb = find(f, v.args.get(1)), eb = find(f, v.args.get(2));
            if (arm) { out.append("    cmp ").append(reg(c)).append(", #0\n    b.ne ").append(lbl(f,tb)).append("\n    b ").append(lbl(f,eb)).append("\n"); }
            else { out.append("    cmpl $0, ").append(reg(c)).append("\n    jne ").append(lbl(f,tb)).append("\n    jmp ").append(lbl(f,eb)).append("\n"); }
        } else if (o.equals("JMP")||o.equals("jump")) {
            Ir.Block t = find(f, v.args.get(0));
            out.append("    jmp ").append(lbl(f,t)).append("\n");
        } else if (o.equals("RETURN")||o.equals("return")) {
            if (!v.args.isEmpty()) { if (arm) out.append("    mov w0, ").append(reg(v.args.get(0))).append("\n"); else out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n"); }
        } else if (o.startsWith("CONST_")||o.equals("const")) {
            if (arm) out.append("    mov ").append(reg(v)).append(", #").append(v.imm).append("\n");
            else out.append("    movl $").append(v.imm).append(", ").append(reg(v)).append("\n");
        } else if (o.startsWith("ADD_")||o.equals("add")) {
            if (arm) out.append("    add ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    addl ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("SUB_")||o.equals("sub")) {
            if (arm) out.append("    sub ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    subl ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("MUL_")||o.equals("mul")) {
            if (arm) out.append("    mul ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    imull ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("DIV_")||o.startsWith("MOD_")||o.equals("div")||o.equals("mod")) {
            boolean mod = o.startsWith("MOD_")||o.equals("mod");
            if (arm) { out.append("    sdiv x9, ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
                if (mod) out.append("    msub ").append(reg(v)).append(", x9, ").append(reg(v.args.get(1))).append(", ").append(reg(v.args.get(0))).append("\n");
                else out.append("    mov ").append(reg(v)).append(", x9\n"); }
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n    cltd\n    idivl ").append(reg(v.args.get(1))).append("\n    movl ").append(mod?"%edx":"%eax").append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("CMPLT_")||o.startsWith("CMPGT_")||o.startsWith("CMPLE_")||o.startsWith("CMPGE_")||o.startsWith("CMPEQ_")||o.startsWith("CMPNE_")) {
            String cc = o.contains("LT")?"lt":o.contains("GT")?"gt":o.contains("LE")?"le":o.contains("GE")?"ge":o.contains("EQ")?"eq":"ne";
            String xc = o.contains("LT")?"l":o.contains("GT")?"g":o.contains("LE")?"le":o.contains("GE")?"ge":o.contains("EQ")?"e":"ne";
            if (arm) { out.append("    cmp ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n    cset ").append(reg(v)).append(", ").append(cc).append("\n"); }
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n    cmpl ").append(reg(v.args.get(1))).append(", %eax\n    set").append(xc).append(" %al\n    movzbl %al, ").append(reg(v)).append("\n"); }
        } else if (o.equals("MOV_i32")||o.equals("copy")) {
            if (v.args.size() >= 2 && (v.args.get(1).op.startsWith("PHI_")||v.args.get(1).op.equals("phi"))) {
                String d = reg(v.args.get(1)), s = reg(v.args.get(0));
                if (!d.equals(s)) { if (arm) out.append("    mov ").append(d).append(", ").append(s).append("\n"); else out.append("    movl ").append(s).append(", ").append(d).append("\n"); }
            } else if (v.args.size() >= 1) {
                String d = reg(v), s = reg(v.args.get(0));
                if (!d.equals(s)) { if (arm) out.append("    mov ").append(d).append(", ").append(s).append("\n"); else out.append("    movl ").append(s).append(", ").append(d).append("\n"); }
            }
        } else if (o.startsWith("CALL_")||o.equals("call")) {
            List<Ir.Value> actual = new ArrayList<>();
            for (Ir.Value a : v.args) if (a != null && a.op != null && !a.op.equals("symbol")) actual.add(a);
            String[] a = arm ? new String[]{"w0","w1","w2","w3","w4","w5","w6","w7"} : new String[]{"%edi","%esi","%edx","%ecx","%r8d","%r9d"};
            for (int i = 0; i < actual.size() && i < a.length; i++) out.append("    mov ").append(a[i]).append(", ").append(reg(actual.get(i))).append("\n");
            out.append("    ").append(arm ? "bl " : "callq ").append(v.name != null ? v.name : "?").append("\n");
            out.append("    ").append(arm ? "mov " + reg(v) + ", w0" : "movl %eax, " + reg(v)).append("\n");
        }
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# common/RuleParser.java
# ═══════════════════════════════════════════════════════════════════
echo "→ common/RuleParser.java"
cat > common/RuleParser.java <<'JEOF'
import java.util.*;
public class RuleParser {
    public static class Rule {
        public String op;
        public List<String> patArgs = new ArrayList<>();
        public List<String> template = new ArrayList<>();
    }
    public static class Rules {
        public List<String> regs = new ArrayList<>();
        public List<String> scratch = new ArrayList<>();
        public List<String> prologue = new ArrayList<>();
        public List<String> epilogue = new ArrayList<>();
        public List<Rule> emits = new ArrayList<>();
    }
    public static Rules parse(String src) {
        Rules r = new Rules();
        String[] lines = src.split("\n");
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i].trim();
            if (ln.isEmpty() || ln.startsWith("#")) { i++; continue; }
            if (ln.startsWith("regs:")) { r.regs = tokens(ln.substring(5)); i++; continue; }
            if (ln.startsWith("scratch:")) { r.scratch = tokens(ln.substring(8)); i++; continue; }
            if (ln.startsWith("prologue")) { i = readBlock(lines, i+1, r.prologue); continue; }
            if (ln.startsWith("epilogue")) { i = readBlock(lines, i+1, r.epilogue); continue; }
            if (ln.startsWith("emit")) { i = readEmit(lines, i, r.emits); continue; }
            i++;
        }
        return r;
    }
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.replace(";","").trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
    static int readBlock(String[] lines, int i, List<String> out) {
        while (i < lines.length && !lines[i].contains("{")) i++;
        i++;
        while (i < lines.length && !lines[i].trim().equals("}")) { out.add(lines[i].trim()); i++; }
        return i+1;
    }
    static int readEmit(String[] lines, int i, List<Rule> rules) {
        String ln = lines[i].trim();
        int arrow = ln.indexOf("->");
        String patStr = ln.substring(4, arrow).trim();
        Rule rl = new Rule();
        int lp = patStr.indexOf('(');
        if (lp < 0) rl.op = patStr.trim();
        else {
            int rp = patStr.lastIndexOf(')');
            rl.op = patStr.substring(0, lp).trim();
            String inner = patStr.substring(lp+1, rp).trim();
            if (!inner.isEmpty()) for (String a : inner.split(",")) rl.patArgs.add(a.trim());
        }
        i++;
        while (i < lines.length && !lines[i].trim().equals("}")) { rl.template.add(lines[i].trim()); i++; }
        rules.add(rl);
        return i+1;
    }
}
JEOF

# ═══════════════════════════════════════════════════════════════════
# tools/janino-parse
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/janino-parse"
mkdir -p tools/janino-parse
cat > tools/janino-parse/JaninoParseMain.java <<'JEOF'
import java.io.*;
import java.nio.file.*;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Java;
public class JaninoParseMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: janino-parse <in.mj> <out.ast>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String base = args[0].replaceAll(".*/","").replaceAll("\\.mj$","");
        Scanner sc = new Scanner(base + ".mj", new StringReader(src));
        Java.CompilationUnit cu = new Parser(sc).parseCompilationUnit();
        String dump = cu.toString();
        if (args[1].equals("-")) System.out.print(dump);
        else Files.writeString(Path.of(args[1]), dump);
    }
}
JEOF
cat > tools/janino-parse/README.md <<'EOF'
# janino-parse

Парсва MiniJ source с Janino и дъмпва AST.

## Употреба

    janino-parse <in.mj> <out.ast>

## Какво прави

1. Чете source.
2. Парсва с org.codehaus.janino.Parser.
3. Дъмпва `Java.CompilationUnit.toString()`.

## Инварианти

- Janino е reference.
- Ако Janino не парсне нещо, MiniJ не го поддържа.
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/ast-lower — с рефлексия за Janino API
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/ast-lower"
mkdir -p tools/ast-lower
cat > tools/ast-lower/AstLowerMain.java <<'JEOF'
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
        Java.CompilationUnit cu = new Parser(sc).parseCompilationUnit();
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
            Java.Location loc = n.getLocation();
            if (loc != null) prog.debug.positions.put(id, new int[]{loc.getLineNumber(), loc.getColumnNumber()});
        } catch (Throwable t) {}
        return id;
    }
    Ir.Value emit(String op, String type, Ir.Value... args) { Ir.Value v = new Ir.Value(op, type, args); cur.ins.add(v); return v; }
    Ir.Value konst(long v, int dbg) { Ir.Value x = new Ir.Value("const","i32"); x.imm=v; x.dbg=dbg; cur.ins.add(x); return x; }
    Ir.Value blockRef(Ir.Block b) { Ir.Value v = new Ir.Value("block","ptr"); v.imm=b.hashCode(); v.name=b.name; return v; }

    void cls(Java.ClassDeclaration cd) {
        Object body = null;
        Optional<?> opt = getOpt(cd, "optionalTypeBody");
        if (opt != null && opt.isPresent()) body = opt.get();
        if (body == null) body = get(cd, "typeBody");
        if (body == null) return;
        List<?> methods = getList(body, "methodDeclarators");
        if (methods == null) return;
        for (Object mo : methods) {
            if (!(mo instanceof Java.MethodDeclarator m)) continue;
            Object mb = null;
            Optional<?> ob = getOpt(m, "optionalBody");
            if (ob != null && ob.isPresent()) mb = ob.get();
            if (mb instanceof Java.Block blk) method(m, blk);
        }
    }

    void method(Java.MethodDeclarator m, Java.Block mb) {
        Ir.Func f = new Ir.Func(); f.name = m.name;
        Object params = get(m, "parameters");
        if (params instanceof List<?> ps) for (Object p : ps) {
            String name = getStr(p, "name");
            f.params.add(new String[]{name != null ? name : "?", "i32"});
        }
        curFunc = f; allocaOf.clear(); breaks.clear(); conts.clear();
        Ir.Block e = new Ir.Block("entry"); cur = e; f.blocks.add(e);
        if (mb.statements != null) for (Java.BlockStatement s : mb.statements) stmt(s);
        if (cur.term()==null || !Ir.isTerm(cur.term().op)) emit("return","void",konst(0,-1));
        prog.funcs.add(f);
    }

    void stmt(Java.BlockStatement s) {
        if (s instanceof Java.LocalVariableDeclarationStatement d) {
            Object vds = get(d, "variableDeclarators");
            if (vds instanceof List<?> list) for (Object vo : list) {
                Java.VariableDeclarator vd = (Java.VariableDeclarator) vo;
                Ir.Value a = emit("alloca","i32"); a.dbg = tag(s);
                allocaOf.put(vd.name, a);
                prog.debug.declNames.put(a.dbg, vd.name);
                prog.debug.declTypes.put(a.dbg, "i32");
                Object init = get(vd, "init");
                if (init instanceof Java.Rvalue rv) {
                    Ir.Value v = expr(rv);
                    Ir.Value st = emit("store","void",a,v); st.dbg = a.dbg;
                }
            }
        } else if (s instanceof Java.ExpressionStatement es) {
            Object e = get(es, "expression");
            if (e instanceof Java.Assignment a) handleAssign(a);
            else if (e instanceof Java.Rvalue rv) expr(rv);
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
        } else if (s instanceof Java.ForStatement f) {
            List<?> init = getList(f, "init");
            if (init != null) for (Object i : init) if (i instanceof Java.BlockStatement bs) stmt(bs);
            int id = dbgSeq++;
            Ir.Block h = new Ir.Block("head_"+id), b = new Ir.Block("body_"+id), st = new Ir.Block("step_"+id), x = new Ir.Block("exit_"+id);
            emit("jump","void",blockRef(h));
            curFunc.blocks.add(h); cur = h;
            Object cond = get(f, "condition");
            if (cond instanceof Java.Rvalue rv) {
                Ir.Value c = expr(rv);
                emit("branch","void",c,blockRef(b),blockRef(x));
            } else emit("jump","void",blockRef(b));
            breaks.push(x); conts.push(st);
            curFunc.blocks.add(b); cur = b;
            Object body = get(f, "body");
            if (body instanceof Java.BlockStatement bs) stmt(bs);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(st));
            curFunc.blocks.add(st); cur = st;
            List<?> update = getList(f, "update");
            if (update != null) for (Object u : update) if (u instanceof Java.BlockStatement bs) stmt(bs);
            if (cur.term()==null||!Ir.isTerm(cur.term().op)) emit("jump","void",blockRef(h));
            breaks.pop(); conts.pop();
            curFunc.blocks.add(x); cur = x;
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
        if (a.lhs instanceof Java.AmbiguousName an) name = an.identifiers.get(0);
        if (name == null) { System.err.println("# assign target unsupported"); return; }
        Ir.Value al = allocaOf.get(name);
        if (al == null) { System.err.println("# undefined: " + name); return; }
        Ir.Value v = expr(a.rhs);
        Ir.Value st = emit("store","void",al,v); st.dbg = tag(a);
    }

    Ir.Value expr(Java.Rvalue e) {
        if (e == null) return konst(0, -1);
        if (e instanceof Java.Literal lit) {
            Object v = lit.value;
            if (v instanceof Number n) return konst(n.longValue(), tag(e));
            if (v instanceof Boolean b) return konst(b?1:0, tag(e));
        }
        if (e instanceof Java.AmbiguousName an) {
            String n = an.identifiers.get(0);
            Ir.Value al = allocaOf.get(n);
            if (al != null) { Ir.Value l = emit("load",al.type,al); l.dbg = tag(e); return l; }
            for (int i=0;i<curFunc.params.size();i++)
                if (curFunc.params.get(i)[0].equals(n)) {
                    Ir.Value p = new Ir.Value("param",curFunc.params.get(i)[1]);
                    p.imm = i; p.dbg = tag(e);
                    return p;
                }
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
        if (e instanceof Java.MethodInvocation mi) {
            List<Ir.Value> args = new ArrayList<>();
            Object argList = get(mi, "arguments");
            if (argList instanceof List<?> al) for (Object a : al) if (a instanceof Java.Rvalue rv) args.add(expr(rv));
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
JEOF
cat > tools/ast-lower/README.md <<'EOF'
# ast-lower

Janino AST → Linear IR (.lir).

## Употреба

    ast-lower <in.mj> <out.lir>

## Какво прави

1. Парсва .mj с Janino.
2. Обхожда CompilationUnit през reflection.
3. Всеки statement → IR:
   - if → branch + 3 блока
   - while/for → header/body/exit
   - return → return
4. Локални → alloca + load/store.
5. Пише .lir.

## Debug info

- `dbg` на всяка IR инструкция.
- `declNames` — име на всяка локална.
- `positions` — line:col.

## Свързани

- преди: janino-parse
- следващ: ssa-build
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/ssa-build
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/ssa-build"
mkdir -p tools/ssa-build
cat > tools/ssa-build/SsaBuildMain.java <<'JEOF'
import java.nio.file.*;
public class SsaBuildMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ssa-build <in.lir> <out.ssa>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        Ir.Program p = Ir.Reader.parse(src);
        Ssa.build(p);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/ssa-build/README.md <<'EOF'
# ssa-build

Linear IR → SSA.

## Употреба

    ssa-build <in.lir> <out.ssa>

## Какво прави

1. Promotable alloca.
2. Dominance frontiers.
3. Phi insertion чрез DF (Cytron).
4. Renaming — DFS по dominance tree.
5. Премахва alloca/store.
6. Обновява .debug_vars.

## Инварианти

- Всяка SSA стойност дефинирана точно веднъж.
- Всеки phi има вход за всеки predecessor.
- Няма alloca/load/store.

## Свързани

- преди: ast-lower
- следващ: ssa-opt
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/ssa-opt
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/ssa-opt"
mkdir -p tools/ssa-opt
cat > tools/ssa-opt/SsaOptMain.java <<'JEOF'
import java.nio.file.*;
public class SsaOptMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ssa-opt <in.ssa> <out.ssa>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        Ir.Program p = Ir.Reader.parse(src);
        Opt.run(p);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/ssa-opt/README.md <<'EOF'
# ssa-opt

Оптимизации върху SSA.

## Употреба

    ssa-opt <in.ssa> <out.ssa>

## Оптимизации

- Constant folding (`add 2, 3 → 5`)
- Copy propagation
- DCE (мъртви стойности)

## Свързани

- преди: ssa-build
- следващ: ssa-lower
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/ssa-lower
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/ssa-lower"
mkdir -p tools/ssa-lower
cat > tools/ssa-lower/SsaLowerMain.java <<'JEOF'
import java.nio.file.*;
public class SsaLowerMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ssa-lower <in.ssa> <out.mir>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        Ir.Program p = Ir.Reader.parse(src);
        lower(p);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
    static void lower(Ir.Program p) {
        for (Ir.Func f : p.funcs) for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            String o = v.op;
            if (o.equals("add")) v.op = "ADD_i32";
            else if (o.equals("sub")) v.op = "SUB_i32";
            else if (o.equals("mul")) v.op = "MUL_i32";
            else if (o.equals("div")) v.op = "DIV_i32";
            else if (o.equals("mod")) v.op = "MOD_i32";
            else if (o.equals("cmplt")) v.op = "CMPLT_i32";
            else if (o.equals("cmpgt")) v.op = "CMPGT_i32";
            else if (o.equals("cmple")) v.op = "CMPLE_i32";
            else if (o.equals("cmpge")) v.op = "CMPGE_i32";
            else if (o.equals("cmpeq")) v.op = "CMPEQ_i32";
            else if (o.equals("cmpne")) v.op = "CMPNE_i32";
            else if (o.equals("jump")) v.op = "JMP";
            else if (o.equals("branch")) v.op = "BRANCH";
            else if (o.equals("return")) v.op = "RETURN";
            else if (o.equals("phi")) v.op = "PHI_i32";
            else if (o.equals("const")) v.op = "CONST_i32";
            else if (o.equals("param")) v.op = "PARAM_i32";
            else if (o.equals("call")) v.op = "CALL_i32";
            else if (o.equals("copy")) v.op = "MOV_i32";
            else if (o.equals("undef")) { v.op = "CONST_i32"; v.imm = 0; }
        }
    }
}
JEOF
cat > tools/ssa-lower/README.md <<'EOF'
# ssa-lower

Generic SSA → Machine IR.

## Употреба

    ssa-lower <in.ssa> <out.mir>

## Mapping

| Generic | Machine |
|---|---|
| add | ADD_i32 |
| cmplt | CMPLT_i32 |
| jump | JMP |
| branch | BRANCH |
| return | RETURN |
| phi | PHI_i32 |
| copy | MOV_i32 |
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/regalloc
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/regalloc"
mkdir -p tools/regalloc
cat > tools/regalloc/RegallocMain.java <<'JEOF'
import java.nio.file.*;
public class RegallocMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: regalloc <in.mir> <out.mir> [--target=arm64]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        boolean arm = false;
        for (String a : args) if (a.equals("--target=arm64") || a.equals("--arch=arm")) arm = true;
        Ir.Program p = Ir.Reader.parse(src);
        Regalloc.run(p, arm);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/regalloc/README.md <<'EOF'
# regalloc

Linear scan register allocation с spilling.

## Употреба

    regalloc <in.mir> <out.mir> [--target=arm64]

## Изход

Добавя `.locations`:
    .locations [
      %1 : reg %rbx
      %2 : stack -8
    ]
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/phi-elim
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/phi-elim"
mkdir -p tools/phi-elim
cat > tools/phi-elim/PhiElimMain.java <<'JEOF'
import java.nio.file.*;
public class PhiElimMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: phi-elim <in.mir> <out.mir>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        Ir.Program p = Ir.Reader.parse(src);
        PhiElim.run(p);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/phi-elim/README.md <<'EOF'
# phi-elim

Премахва phi. Вмъква MOV в predecessor блоковете.

## Употреба

    phi-elim <in.mir> <out.mir>
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/emit-x86
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/emit-x86"
mkdir -p tools/emit-x86
cat > tools/emit-x86/EmitX86Main.java <<'JEOF'
import java.nio.file.*;
public class EmitX86Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: emit-x86 <in.mir> <out.s> [rules/x86.rule]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String rf = args.length > 2 ? args[2] : "rules/x86.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../rules/x86.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../../rules/x86.rule";
        Ir.Program p = Ir.Reader.parse(src);
        String out = Emitter.emit(p, "x86-64", rf);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/emit-x86/README.md <<'EOF'
# emit-x86

Machine IR → x86-64 asm чрез rules/x86.rule.

## Употреба

    emit-x86 <in.mir> <out.s> [rules/x86.rule]
EOF

# ═══════════════════════════════════════════════════════════════════
# tools/emit-arm
# ═══════════════════════════════════════════════════════════════════
echo "→ tools/emit-arm"
mkdir -p tools/emit-arm
cat > tools/emit-arm/EmitArmMain.java <<'JEOF'
import java.nio.file.*;
public class EmitArmMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: emit-arm <in.mir> <out.s> [rules/arm.rule]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String rf = args.length > 2 ? args[2] : "rules/arm.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../rules/arm.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../../rules/arm.rule";
        Ir.Program p = Ir.Reader.parse(src);
        String out = Emitter.emit(p, "arm64", rf);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
JEOF
cat > tools/emit-arm/README.md <<'EOF'
# emit-arm

Machine IR → ARM64 asm чрез rules/arm.rule.

## Употреба

    emit-arm <in.mir> <out.s> [rules/arm.rule]
EOF

# ═══════════════════════════════════════════════════════════════════
# rules/
# ═══════════════════════════════════════════════════════════════════
echo "→ rules/"
cat > rules/x86.rule <<'EOF'
regs: %rbx %r12 %r13 %r14 %r15 ;
scratch: %r8 %r9 %r10 %r11 ;

prologue {
    pushq %rbp
    movq %rsp, %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    subq $128, %rsp
}

epilogue {
    addq $128, %rsp
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    retq
}

emit CONST_i32(v) { movl $${v}, $dst }
emit ADD_i32(x, y) { movl ${x}, $dst
                     addl ${y}, $dst }
emit SUB_i32(x, y) { movl ${x}, $dst
                     subl ${y}, $dst }
emit MUL_i32(x, y) { movl ${x}, $dst
                     imull ${y}, $dst }
emit DIV_i32(x, y) { movl ${x}, %eax
                     cltd
                     idivl ${y}
                     movl %eax, $dst }
emit MOD_i32(x, y) { movl ${x}, %eax
                     cltd
                     idivl ${y}
                     movl %edx, $dst }
emit CMPLT_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       setl %al
                       movzbl %al, $dst }
emit CMPGT_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       setg %al
                       movzbl %al, $dst }
emit CMPLE_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       setle %al
                       movzbl %al, $dst }
emit CMPGE_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       setge %al
                       movzbl %al, $dst }
emit CMPEQ_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       sete %al
                       movzbl %al, $dst }
emit CMPNE_i32(x, y) { movl ${x}, %eax
                       cmpl ${y}, %eax
                       setne %al
                       movzbl %al, $dst }
EOF

cat > rules/arm.rule <<'EOF'
regs: x19 x20 x21 x22 x23 x24 x25 x26 ;
scratch: x8 x9 x10 x11 ;

prologue {
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    stp x19, x20, [sp, #-16]!
    stp x21, x22, [sp, #-16]!
    stp x23, x24, [sp, #-16]!
}

epilogue {
    ldp x23, x24, [sp], #16
    ldp x21, x22, [sp], #16
    ldp x19, x20, [sp], #16
    ldp x29, x30, [sp], #16
    ret
}

emit CONST_i32(v) { mov $dst, #${v} }
emit ADD_i32(x, y) { add $dst, ${x}, ${y} }
emit SUB_i32(x, y) { sub $dst, ${x}, ${y} }
emit MUL_i32(x, y) { mul $dst, ${x}, ${y} }
emit DIV_i32(x, y) { sdiv $dst, ${x}, ${y} }
emit MOD_i32(x, y) { sdiv x9, ${x}, ${y}
                     msub $dst, x9, ${y}, ${x} }
emit CMPLT_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, lt }
emit CMPGT_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, gt }
emit CMPLE_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, le }
emit CMPGE_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, ge }
emit CMPEQ_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, eq }
emit CMPNE_i32(x, y) { cmp ${x}, ${y}
                       cset $dst, ne }
EOF

# ═══════════════════════════════════════════════════════════════════
# examples/
# ═══════════════════════════════════════════════════════════════════
echo "→ examples/"
cat > examples/hello.mj <<'EOF'
class Hello {
    static int add(int a, int b) { return a + b; }
    static int sub(int a, int b) { return a - b; }
    static int main() {
        return add(42, 10) - sub(20, 15);
    }
}
EOF
cat > examples/gcd.mj <<'EOF'
class Gcd {
    static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
    static int main() { return gcd(48, 36); }
}
EOF
cat > examples/fib.mj <<'EOF'
class Fib {
    static int fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }
    static int main() { return fib(10); }
}
EOF
cat > examples/forloop.mj <<'EOF'
class ForLoop {
    static int main() {
        int sum = 0;
        for (int i = 1; i <= 10; i = i + 1) {
            sum = sum + i;
        }
        return sum;
    }
}
EOF

# ═══════════════════════════════════════════════════════════════════
# build.sh
# ═══════════════════════════════════════════════════════════════════
echo "→ build.sh"
cat > build.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
CP="lib/janino.jar:lib/commons-compiler.jar"
SRC="common/*.java"
for d in tools/*/; do
    if ls "$d"*.java >/dev/null 2>&1; then SRC="$SRC $d*.java"; fi
done
javac -cp "$CP" -d out $SRC
echo "✓ build ok"
EOF
chmod +x build.sh

# ═══════════════════════════════════════════════════════════════════
# bin/
# ═══════════════════════════════════════════════════════════════════
echo "→ bin/"
for tool in janino-parse ast-lower ssa-build ssa-opt ssa-lower regalloc phi-elim emit-x86 emit-arm; do
    case "$tool" in
        janino-parse) CLASS="JaninoParseMain" ;;
        ast-lower)    CLASS="AstLowerMain" ;;
        ssa-build)    CLASS="SsaBuildMain" ;;
        ssa-opt)      CLASS="SsaOptMain" ;;
        ssa-lower)    CLASS="SsaLowerMain" ;;
        regalloc)     CLASS="RegallocMain" ;;
        phi-elim)     CLASS="PhiElimMain" ;;
        emit-x86)     CLASS="EmitX86Main" ;;
        emit-arm)     CLASS="EmitArmMain" ;;
    esac
    cat > "bin/$tool" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
DIR="\$(cd "\$(dirname "\$0")/.." && pwd)"
CP="\$DIR/out:\$DIR/lib/janino.jar:\$DIR/lib/commons-compiler.jar"
exec java -cp "\$CP" $CLASS "\$@"
EOF
    chmod +x "bin/$tool"
done

# ═══════════════════════════════════════════════════════════════════
# mc
# ═══════════════════════════════════════════════════════════════════
echo "→ mc"
cat > mc <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
export PATH="$DIR/bin:$PATH"
TARGET="x86-64"; SRC=""; OUT="a.out"; STAGE="exe"; KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --target=*) TARGET="${1#*=}" ;;
        --stage=*)  STAGE="${1#*=}" ;;
        --keep)     KEEP=1 ;;
        -o)         shift; OUT="$1" ;;
        *)          if [ -z "$SRC" ]; then SRC="$1"; fi ;;
    esac
    shift
done
[ -z "$SRC" ] && { echo "usage: mc [--target=x86-64|arm64] [--stage=ast|lir|ssa|opt|mir|asm] [--keep] <src.mj> [-o out]"; exit 1; }
BASE=$(basename "$SRC" .mj)

janino-parse "$SRC" "$BASE.ast"
[ "$STAGE" = "ast" ] && { cat "$BASE.ast"; exit 0; }

ast-lower "$SRC" "$BASE.lir"
[ "$STAGE" = "lir" ] && { cat "$BASE.lir"; exit 0; }

ssa-build "$BASE.lir" "$BASE.ssa"
[ "$STAGE" = "ssa" ] && { cat "$BASE.ssa"; exit 0; }

ssa-opt "$BASE.ssa" "$BASE.opt.ssa"
[ "$STAGE" = "opt" ] && { cat "$BASE.opt.ssa"; exit 0; }

ssa-lower "$BASE.opt.ssa" "$BASE.mir"
[ "$STAGE" = "mir" ] && { cat "$BASE.mir"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    regalloc "$BASE.mir" "$BASE.alloc.mir" --target=arm64
else
    regalloc "$BASE.mir" "$BASE.alloc.mir"
fi
phi-elim "$BASE.alloc.mir" "$BASE.final.mir"

if [ "$TARGET" = "arm64" ]; then
    emit-arm "$BASE.final.mir" "$BASE.s"
else
    emit-x86 "$BASE.final.mir" "$BASE.s"
fi
[ "$STAGE" = "asm" ] && { cat "$BASE.s"; exit 0; }

if [ "$TARGET" = "arm64" ]; then
    aarch64-linux-gnu-as "$BASE.s" -o "$BASE.o" 2>/dev/null || as "$BASE.s" -o "$BASE.o"
    aarch64-linux-gnu-ld "$BASE.o" -o "$OUT" 2>/dev/null || ld "$BASE.o" -o "$OUT"
else
    as "$BASE.s" -o "$BASE.o"
    ld "$BASE.o" -o "$OUT"
fi

if [ "$KEEP" = "0" ]; then
    rm -f "$BASE.ast" "$BASE.lir" "$BASE.ssa" "$BASE.opt.ssa" "$BASE.mir" "$BASE.alloc.mir" "$BASE.final.mir" "$BASE.s" "$BASE.o"
fi
echo "✓ compiled → $OUT"
EOF
chmod +x mc

# ═══════════════════════════════════════════════════════════════════
# test.sh
# ═══════════════════════════════════════════════════════════════════
echo "→ test.sh"
cat > test.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
./build.sh
pass=0; fail=0
run() {
    name="$1"; src="$2"; expected="$3"
    if ! ./mc "$src" -o "t_$name" >/dev/null 2>&1; then
        echo "  FAIL $name (compile)"; fail=$((fail+1)); return
    fi
    ./t_$name
    got=$?
    if [ "$got" = "$expected" ]; then
        echo "  PASS $name (exit $got)"; pass=$((pass+1))
    else
        echo "  FAIL $name (expected $expected got $got)"; fail=$((fail+1))
    fi
    rm -f "t_$name"
}
echo "Running regression tests..."
run hello   examples/hello.mj   47
run gcd     examples/gcd.mj     12
run fib     examples/fib.mj     55
run forloop examples/forloop.mj 55
echo ""
echo "$pass passed, $fail failed"
[ "$fail" = "0" ] || exit 1
EOF
chmod +x test.sh

# ═══════════════════════════════════════════════════════════════════
# README.md
# ═══════════════════════════════════════════════════════════════════
echo "→ README.md"
cat > README.md <<'EOF'
# MiniJ Compiler

Компилатор за MiniJ (Java subset) → x86-64 / ARM64 native executable.
Всеки етап е отделна програма, която чете in.file, пише out.file.

## Философия

- Всеки tool е самостоятелна програма.
- Всички формати са текстови.
- Debug info пътува с IR-а.
- Backend-ите са `.rule` файлове (данни).
- Janino е reference parser.

## Инсталация

    pkg install openjdk-17 binutils wget
    ./build.sh

## Употреба

    ./mc examples/hello.mj -o hello
    ./hello; echo $?

## Pipeline

    source.mj ─ janino-parse → source.ast
             ─ ast-lower    → source.lir
             ─ ssa-build    → source.ssa
             ─ ssa-opt      → source.opt.ssa
             ─ ssa-lower    → source.mir
             ─ regalloc     → source.alloc.mir
             ─ phi-elim     → source.final.mir
             ─ emit-x86     → source.s
             ─ as + ld      → executable

## Инспекция

    ./mc --stage=ast examples/gcd.mj
    ./mc --stage=lir examples/gcd.mj
    ./mc --stage=ssa examples/gcd.mj
    ./mc --stage=asm examples/gcd.mj
    ./mc --keep examples/gcd.mj

## Ръчен pipeline

    ./bin/janino-parse  examples/gcd.mj  gcd.ast
    ./bin/ast-lower     examples/gcd.mj  gcd.lir
    ./bin/ssa-build     gcd.lir          gcd.ssa
    ./bin/ssa-opt       gcd.ssa          gcd.opt.ssa
    ./bin/ssa-lower     gcd.opt.ssa      gcd.mir
    ./bin/regalloc      gcd.mir          gcd.alloc.mir
    ./bin/phi-elim      gcd.alloc.mir    gcd.final.mir
    ./bin/emit-x86      gcd.final.mir    gcd.s
    as gcd.s -o gcd.o && ld gcd.o -o gcd
    ./gcd; echo $?

## Tools

| Tool | Вход | Изход | README |
|---|---|---|---|
| janino-parse | .mj | .ast | tools/janino-parse/README.md |
| ast-lower | .mj | .lir | tools/ast-lower/README.md |
| ssa-build | .lir | .ssa | tools/ssa-build/README.md |
| ssa-opt | .ssa | .ssa | tools/ssa-opt/README.md |
| ssa-lower | .ssa | .mir | tools/ssa-lower/README.md |
| regalloc | .mir | .mir | tools/regalloc/README.md |
| phi-elim | .mir | .mir | tools/phi-elim/README.md |
| emit-x86 | .mir | .s | tools/emit-x86/README.md |
| emit-arm | .mir | .s | tools/emit-arm/README.md |

## Cross-compilation

    ./mc --target=arm64 examples/hello.mj -o hello.arm

## Тестове

    ./test.sh

## Структура

    compiler/
    ├── build.sh, mc, test.sh
    ├── README.md, docs/grammar.md
    ├── lib/janino.jar
    ├── bin/               — 9 wrapper скриптове
    ├── common/            — shared: Ir, Ssa, Opt, Regalloc, ...
    ├── tools/             — 9 tools, всеки със свой Main + README
    ├── rules/             — x86.rule, arm.rule
    └── examples/          — hello.mj, gcd.mj, fib.mj, forloop.mj
EOF

# ═══════════════════════════════════════════════════════════════════
# docs/grammar.md
# ═══════════════════════════════════════════════════════════════════
echo "→ docs/grammar.md"
cat > docs/grammar.md <<'EOF'
# Граматики на форматите

## .mj — MiniJ source (Java subset)

    program    = { class_decl } ;
    class_decl = "class" IDENT "{" { method } "}" ;
    method     = "static" type IDENT "(" [params] ")" block ;
    type       = "int" | "void" ;
    block      = "{" { stmt } "}" ;
    stmt       = var_decl | assign | if_stmt | while_stmt | for_stmt
               | return_stmt | expr_stmt | block
               | "break" ";" | "continue" ";" ;
    expr       = or_expr ;
    or_expr    = and_expr { "||" and_expr } ;
    and_expr   = cmp_expr { "&&" cmp_expr } ;
    cmp_expr   = add_expr { ("<"|">"|"<="|">="|"=="|"!=") add_expr } ;
    add_expr   = mul_expr { ("+"|"-") mul_expr } ;
    mul_expr   = unary { ("*"|"/"|"%") unary } ;
    atom       = INT | IDENT | call | "(" expr ")" ;

## .ast — Janino AST дъмп

`CompilationUnit.toString()` — човекочетим, не се парсва обратно.

## .lir — Linear IR

    lir_file = header { debug_section } { func } { locations_section } ;
    header   = '.module' STRING '.target' STRING ;
    func     = '.func' IDENT '(' params ')' '->' type block ;
    block    = IDENT '{' { instruction } '}' ;
    instruction = value_inst | store_inst | terminator ;
    value_inst  = REF '=' IDENT type { operand } [ ';' 'dbg' INT ] ;
    store_inst  = 'store' REF ',' REF [ ';' 'dbg' INT ] ;
    terminator  = 'jump' IDENT
                | 'branch' REF ',' IDENT ',' IDENT
                | 'return' [ REF ] ;

## .ssa — като .lir, но

- Без alloca/load/store
- С phi: `REF '=' 'phi' type { '[' IDENT ':' REF ']' }`

## .mir — като .ssa, но

- Ops са машинни: ADD_i32, CMPGT_i32, BRANCH, JMP, RETURN, MOV_i32
- След regalloc — `.locations` секция:
    `REF ':' 'reg' REG | REF ':' 'stack' INT`

## .s — GNU as

Стандартен GNU as синтаксис с `.loc` директиви.

## .rule — emit правила

    rule_file = { section } ;
    section   = regs_section | scratch_section
              | prologue_section | epilogue_section
              | emit_rule ;
    regs_section    = 'regs' ':' { IDENT } ';' ;
    scratch_section = 'scratch' ':' { IDENT } ';' ;
    prologue_section= 'prologue' '{' { line } '}' ;
    epilogue_section= 'epilogue' '{' { line } '}' ;
    emit_rule       = 'emit' pattern '{' { line } '}' ;
    pattern         = IDENT [ '(' { pat_arg } ')' ] ;
    line            = TEXT | '${' IDENT '}' | '$dst' | '$$' ;
EOF

# ═══════════════════════════════════════════════════════════════════
# BUILD
# ═══════════════════════════════════════════════════════════════════
echo ""
echo "═══ Compiling ═══"
./build.sh

echo ""
echo "✓ Setup завършен."
echo ""
echo "Следващи стъпки:"
echo "  ./test.sh"
echo "  ./mc examples/hello.mj -o h && ./h; echo \$?"
echo "  ./mc --stage=ssa examples/gcd.mj"
echo "  ./mc --target=arm64 examples/hello.mj -o h.arm"
echo ""
echo "Файлове:"
find . -maxdepth 2 -type f | grep -v "^./out/" | grep -v "^./lib/" | sort | sed 's|^\./|  |'
