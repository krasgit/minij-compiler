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
        @Override public int hashCode() { return name == null ? 0 : name.hashCode(); }
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
            || op.equals("JMP")||op.equals("BRANCH")||op.equals("RETURN")
            || op.startsWith("RETURN_");
    }
    public static String suffix(String type) {
        if (type != null && (type.equals("i64") || type.equals("ptr") || type.equals("address"))) return "i64";
        return type != null && type.equals("f64") ? "f64" : "i32";
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
            Set<Value> seen = new LinkedHashSet<>();
            for (Block b : f.blocks) for (Value v : b.ins) for (Value a : v.args)
                if (a != null && (a.op.equals("param")||a.op.startsWith("PARAM_")) && seen.add(a))
                    sb.append("  .param %").append(id(a)).append(" ").append(a.imm).append("\n");
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
            if (o.equals("return")||o.equals("RETURN")||o.startsWith("RETURN_")) { sb.append("    return"); if (!v.args.isEmpty()) sb.append(" %").append(id(v.args.get(0))); sb.append(d).append("\n"); return; }
            if (o.equals("store")||o.equals("STORE_i32")) { sb.append("    store %").append(id(v.args.get(0))).append(", %").append(id(v.args.get(1))).append(d).append("\n"); return; }
            if (o.equals("alloca")||o.equals("ALLOCA")) { sb.append("    %").append(id(v)).append(" = alloca ").append(v.type).append(d).append("\n"); return; }
            if (o.equals("phi")||o.startsWith("PHI_")) {
                sb.append("    %").append(id(v)).append(" = phi ").append(v.type);
                for (int i=0;i+1<v.args.size();i+=2)
                    sb.append(" [").append(bn(v.args.get(i))).append(": %").append(id(v.args.get(i+1))).append("]");
                sb.append(d).append("\n"); return;
            }
            if (o.equals("call")||o.startsWith("CALL_")) {
                String kw = o.startsWith("CALL_") ? o : "call";
                String nm = v.name != null ? v.name : "?";
                sb.append("    %").append(id(v)).append(" = ").append(kw).append(" ").append(nm).append(":").append(v.type).append("(");
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
        Map<Value,Integer> pending = new LinkedHashMap<>();
        Map<String,Block> blocks = new LinkedHashMap<>();
        Func cur; Block curB;
        public static Program parse(String src) {
            Reader r = new Reader();
            for (String ln : src.split("\n")) r.lines.add(ln);
            r.run(); r.resolve(); return r.p;
        }
        void resolve() {
            for (Map.Entry<Value,Integer> e : pending.entrySet()) {
                Value ph = e.getKey(); Value real = values.get(e.getValue());
                if (real == null || real == ph) continue;
                for (Func f : p.funcs) for (Block b : f.blocks) for (Value v : b.ins)
                    for (int i=0;i<v.args.size();i++) if (v.args.get(i) == ph) v.args.set(i, real);
            }
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
                if (l.equals("}")) { next();
                    if (curB != null) { curB = null; continue; }
                    break;
                }
                if (l.isEmpty()) { next(); continue; }
                if (l.startsWith(".param")) {
                    String[] pp = l.split("\\s+");
                    Value pv = values.get(Integer.parseInt(pp[1].substring(1)));
                    if (pv == null) { pv = new Value(); pv.op = "param"; values.put(Integer.parseInt(pp[1].substring(1)), pv); }
                    pv.op = "param"; pv.type = "i32"; pv.imm = Long.parseLong(pp[2]);
                    if (cur != null && pv.imm < cur.params.size()) pv.type = cur.params.get((int)pv.imm)[1];
                    next(); continue;
                }
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
        Value lookup(int id) { Value v = values.get(id); if (v == null) { v = new Value(); v.op = "undef"; values.put(id, v); pending.put(v, id); } return v; }
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
                Value v = new Value(cur.retType.equals("void") ? "return" : "RETURN_" + suffix(cur.retType), "void"); String r = body.substring(6).trim();
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
                    int colon = v.name.lastIndexOf(':');
                    if (colon > 0) { v.type = v.name.substring(colon+1); v.name = v.name.substring(0, colon); }
                    else v.type = "i32";
                    for (String a : s.substring(lp+1, rp).split(",")) {
                        a = a.trim(); if (a.startsWith("%")) v.args.add(lookup(Integer.parseInt(a.substring(1))));
                    }
                } else v.type = "i32";
                return v;
            }
            if (v.op.startsWith("PHI_") || v.op.equals("phi")) {
                v.type = parts[1];
                for (int j=2;j<parts.length;j++) {
                    String p2 = parts[j];
                    if (p2.startsWith("[") && p2.endsWith(":")) {
                        Value bv = new Value("block","ptr"); bv.name = p2.substring(1, p2.length()-1); v.args.add(bv);
                    } else if (p2.startsWith("%")) {
                        String id = p2.endsWith("]") ? p2.substring(1, p2.length()-1) : p2.substring(1);
                        v.args.add(lookup(Integer.parseInt(id)));
                    }
                }
                return v;
            }
            if (parts.length >= 2) v.type = parts[1];
            for (int j=2;j<parts.length;j++) if (parts[j].startsWith("%")) v.args.add(lookup(Integer.parseInt(parts[j].substring(1))));
            return v;
        }
    }
}
