import java.util.*;
public class Regalloc {
    public static void run(Ir.Program p, List<String> regs) { run(p, regs, null); }
    public static void run(Ir.Program p, List<String> regs, List<String> fregs) {
        if (regs == null || regs.isEmpty()) throw new IllegalArgumentException("register pool is empty (from .rule file)");
        String[] ir = regs.toArray(new String[0]);
        String[] fr = (fregs == null || fregs.isEmpty()) ? null : fregs.toArray(new String[0]);
        for (Ir.Func f : p.funcs) alloc(f, ir, fr, p.debug);
    }

    static boolean isFp(String t) {
        return t != null && (t.equals("f32") || t.equals("f64") || t.equals("float") || t.equals("double"));
    }

    static List<Ir.Value> orderOf(Ir.Func f) {
        List<Ir.Value> order = new ArrayList<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            if (Ir.isTerm(v.op) || v.op.equals("block") || v.op.equals("symbol")) continue;
            if (v.op.startsWith("STORE_") || v.op.equals("store")) continue;
            order.add(v);
        }
        return order;
    }

    static List<Ir.Value> pool(List<Ir.Value> order, boolean fpPass) {
        List<Ir.Value> out = new ArrayList<>();
        for (Ir.Value v : order) if (isFp(v.type) == fpPass) out.add(v);
        return out;
    }

    static void alloc(Ir.Func f, String[] intRegs, String[] fpRegs, Ir.DebugInfo dbg) {
        List<Ir.Value> order = orderOf(f);
        boolean haveFp = fpRegs != null && fpRegs.length > 0;
        if (!haveFp) for (Ir.Value v : order) if (isFp(v.type))
            throw new IllegalArgumentException("FP value '" + v.op + "' but rule has no 'fregs:' pool");

        // ── parameter homes (per-type ABI index) — consistent with Emitter.params()
        String[] intHome = new String[f.params.size()];
        String[] fpHome = new String[f.params.size()];
        for (int k = 0, ii = 0, fi = 0; k < f.params.size(); k++) {
            if (isFp(f.params.get(k)[1])) { fpHome[k] = fi < fpRegs.length ? fpRegs[fi] : null; fi++; }
            else                            { intHome[k] = ii < intRegs.length ? intRegs[ii] : null; ii++; }
        }
        LinkedHashSet<String> takenInt = new LinkedHashSet<>(), takenFp = new LinkedHashSet<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) for (Ir.Value a : v.args) {
            if (a == null || (!a.op.equals("param") && !a.op.startsWith("PARAM_"))) continue;
            int k = (int) a.imm;
            boolean fp = k >= 0 && k < f.params.size() && isFp(f.params.get(k)[1]);
            String home = fp ? (k >= 0 && k < fpHome.length ? fpHome[k] : null) : (k >= 0 && k < intHome.length ? intHome[k] : null);
            if (home == null) continue;
            if (fp) { if (!dbg.locations.containsKey(a)) dbg.locations.put(a, "freg " + home); takenFp.add(home); }
            else    { if (!dbg.locations.containsKey(a)) dbg.locations.put(a, "reg " + home);  takenInt.add(home); }
        }

        // ── PHI pre-assignment per pool (from the end of each pool) ─────────
        List<Ir.Value> intPhis = new ArrayList<>(), fpPhis = new ArrayList<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (v.op.startsWith("PHI_") || v.op.equals("phi")) (isFp(v.type) ? fpPhis : intPhis).add(v);
        preassign(intPhis, intRegs, takenInt, dbg, "reg ");
        if (haveFp) preassign(fpPhis, fpRegs, takenFp, dbg, "freg ");

        // ── linear scan, one pass per pool, shared stack-slot counter ───────
        int[] slotRef = new int[1];
        linscan(pool(order, false), intRegs, takenInt, dbg, slotRef, false);
        if (haveFp) linscan(pool(order, true), fpRegs, takenFp, dbg, slotRef, true);
    }

    static void preassign(List<Ir.Value> phis, String[] pool, LinkedHashSet<String> taken, Ir.DebugInfo dbg, String pref) {
        for (Ir.Value ph : phis) {
            String r = null;
            for (int k = pool.length - 1; k >= 0 && r == null; k--) if (!taken.contains(pool[k])) r = pool[k];
            if (r == null) continue;
            taken.add(r);
            dbg.locations.put(ph, pref + r);
        }
    }

    // linear scan for a single pool; writes assignments into dbg.locations
    static void linscan(List<Ir.Value> items, String[] pool, LinkedHashSet<String> taken,
                        Ir.DebugInfo dbg, int[] slotRef, boolean fpPass) {
        String pref = fpPass ? "freg " : "reg ";
        Map<Ir.Value,Integer> last = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Ir.Value v = items.get(i);
            for (Ir.Value a : v.args) last.put(a, i);
            last.putIfAbsent(v, i);
        }
        Map<Ir.Value,String> loc = new LinkedHashMap<>();
        LinkedList<String> fr = new LinkedList<>(Arrays.asList(pool));
        for (String r : taken) fr.remove(r);
        Deque<String> free = new ArrayDeque<>(fr);
        PriorityQueue<Ir.Value> active = new PriorityQueue<>((a,b) -> Integer.compare(last.getOrDefault(b,0), last.getOrDefault(a,0)));
        for (int i = 0; i < items.size(); i++) {
            Ir.Value v = items.get(i);
            if (dbg.locations.containsKey(v)) continue;
            List<Ir.Value> exp = new ArrayList<>();
            for (Ir.Value a : active) if (last.getOrDefault(a,0) < i) exp.add(a);
            for (Ir.Value a : exp) { active.remove(a); String r = loc.get(a); if (r != null && r.startsWith(pref)) free.addLast(r.substring(pref.length())); }
            if (!free.isEmpty()) { loc.put(v, pref + free.removeFirst()); active.add(v); }
            else {
                Ir.Value victim = null;
                for (Ir.Value cand : active) {
                    if (cand.op.startsWith("PHI_") || cand.op.equals("phi")) continue;
                    if (victim == null || last.getOrDefault(cand,0) > last.getOrDefault(victim,0)) victim = cand;
                }
                if (victim != null && last.getOrDefault(victim,0) > last.getOrDefault(v,0)) {
                    active.poll(); active.remove(victim);
                    String r = loc.get(victim).substring(pref.length());
                    loc.put(victim, "stack " + (-8*(++slotRef[0])));
                    loc.put(v, pref + r); active.add(v);
                } else loc.put(v, "stack " + (-8*(++slotRef[0])));
            }
        }
        for (var e : loc.entrySet()) dbg.locations.put(e.getKey(), e.getValue());
    }
}