import java.util.*;
public class Regalloc {
    static final String[] X = {"%rbx","%r12","%r13","%r14","%r15"};
    static final String[] A = {"w19","w20","w21","w22","w23","w24","w25","w26","w27","w28"};
    public static void run(Ir.Program p, boolean arm) {
        String[] regs = arm ? A : X;
        String[] argReg = arm
            ? new String[]{"w19","w20","w21","w22","w23","w24","w25","w26","w27","w28"}
            : new String[]{"%ebx","%r12d","%r13d","%r14d","%r15d"};
        for (Ir.Func f : p.funcs) alloc(f, regs, argReg, p.debug);
    }
    static void alloc(Ir.Func f, String[] regs, String[] argReg, Ir.DebugInfo dbg) {
        List<Ir.Value> order = new ArrayList<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            if (Ir.isTerm(v.op) || v.op.equals("block") || v.op.equals("symbol")) continue;
            if (v.op.startsWith("STORE_") || v.op.equals("store")) continue;
            order.add(v);
        }
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            for (Ir.Value a : v.args) {
                if (a == null || (!a.op.equals("param") && !a.op.startsWith("PARAM_"))) continue;
                int i = (int) a.imm;
                if (i >= 0 && i < argReg.length && !dbg.locations.containsKey(a))
                    dbg.locations.put(a, "reg " + argReg[i]);
            }
        Map<Ir.Value,Integer> last = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Ir.Value v = order.get(i);
            for (Ir.Value a : v.args) last.put(a, i);
            last.putIfAbsent(v, i);
        }
        Map<Ir.Value,String> loc = new LinkedHashMap<>();
        LinkedHashSet<String> taken = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(f.params.size(), argReg.length); i++) taken.add(argReg[i]);
        List<Ir.Value> phis = new ArrayList<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (v.op.startsWith("PHI_") || v.op.equals("phi")) phis.add(v);
        for (Ir.Value ph : phis) {
            String r = null;
            for (int k = regs.length-1; k >= 0 && r == null; k--) if (!taken.contains(regs[k])) r = regs[k];
            if (r == null) continue;
            taken.add(r);
            dbg.locations.put(ph, "reg " + r);
        }
        PriorityQueue<Ir.Value> active = new PriorityQueue<>((a,b) -> Integer.compare(last.getOrDefault(b,0), last.getOrDefault(a,0)));
        LinkedList<String> fr = new LinkedList<>(Arrays.asList(regs));
        for (String r : taken) fr.remove(r);
        Deque<String> free = new ArrayDeque<>(fr);
        int slot = 0;
        for (int i = 0; i < order.size(); i++) {
            Ir.Value v = order.get(i);
            if (dbg.locations.containsKey(v)) continue;
            List<Ir.Value> exp = new ArrayList<>();
            for (Ir.Value a : active) if (last.getOrDefault(a,0) < i) exp.add(a);
            for (Ir.Value a : exp) { active.remove(a); String r = loc.get(a); if (r != null && r.startsWith("reg ")) free.addLast(r.substring(4)); }
            if (!free.isEmpty()) { loc.put(v, "reg " + free.removeFirst()); active.add(v); }
            else {
                Ir.Value victim = null;
                for (Ir.Value cand : active) {
                    if (cand.op.startsWith("PHI_") || cand.op.equals("phi")) continue;
                    if (victim == null || last.getOrDefault(cand,0) > last.getOrDefault(victim,0)) victim = cand;
                }
                if (victim != null && last.getOrDefault(victim,0) > last.getOrDefault(v,0)) {
                    active.poll(); active.remove(victim);
                    String r = loc.get(victim).substring(4);
                    loc.put(victim, "stack " + (-8*(++slot)));
                    loc.put(v, "reg " + r); active.add(v);
                } else loc.put(v, "stack " + (-8*(++slot)));
            }
        }
        for (var e : loc.entrySet()) dbg.locations.put(e.getKey(), e.getValue());
    }
}
