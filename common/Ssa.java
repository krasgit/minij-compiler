import java.util.*;
public class Ssa {
    public static void build(Ir.Program p) { for (Ir.Func f : p.funcs) buildFunc(f, p.debug); }
    static void buildFunc(Ir.Func f, Ir.DebugInfo dbg) {
        if (f.blocks.isEmpty()) return;
        Map<Ir.Value,String> prom = new LinkedHashMap<>();
        Map<String,String> nameTypes = new LinkedHashMap<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (v.op.equals("alloca")||v.op.equals("ALLOCA")) {
                String n = dbg.declNames.get(v.dbg);
                String nm = n != null ? n : ("__v" + System.identityHashCode(v));
                prom.put(v, nm);
                nameTypes.put(nm, v.type);
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
                for (Ir.Block d : dom.df.getOrDefault(b, Collections.emptySet())) if (has.add(d)) {
                    Ir.Value phi = new Ir.Value("phi", nameTypes.getOrDefault(name, "i32"));
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
        rename(f.blocks.get(0), phiDefs, stacks, children, prom, nameTypes, dbg, dom);
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
    static void rename(Ir.Block b, Map<Ir.Block,Map<String,Ir.Value>> phiDefs, Map<String,Deque<Ir.Value>> stacks, Map<Ir.Block,List<Ir.Block>> children, Map<Ir.Value,String> prom, Map<String,String> nameTypes, Ir.DebugInfo dbg, Dominance dom) {
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
                if (val == null) { val = new Ir.Value("undef", nameTypes.getOrDefault(e.getKey(), "i32")); val.dbg = phi.dbg; }
                Ir.Value mk = new Ir.Value("block","ptr"); mk.imm = b.hashCode(); mk.name = b.name;
                phi.args.add(mk); phi.args.add(val);
            }
        }
        for (Ir.Block c : children.getOrDefault(b, Collections.emptyList()))
            rename(c, phiDefs, stacks, children, prom, nameTypes, dbg, dom);
        for (String n : pushed) { Deque<Ir.Value> s = stacks.get(n); if (s != null && !s.isEmpty()) s.pop(); }
    }
}
