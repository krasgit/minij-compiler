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

    // Linear scan за един пул; пише assign-циите в dbg.locations.
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
        for (Ir.Value v : order) {
            boolean isVoid = v.type == null || v.type.equals("void");
            if (isVoid) { out.add(v); continue; }   // void ops (chk/st_hdr/st_*) — само за last-use на аргументите, без регистър
            if (isFp(v.type) == fpPass) out.add(v);
        }
        return out;
    }

    static void alloc(Ir.Func f, String[] intRegs, String[] fpRegs, Ir.DebugInfo dbg) {
        List<Ir.Value> order = orderOf(f);
        Map<String,Integer> bIdx = new HashMap<>();
        for (int i = 0; i < f.blocks.size(); i++) bIdx.put(f.blocks.get(i).name, i);
        Map<String,int[]> loop = loops(f, bIdx);
        Map<Ir.Value,String> vblock = new HashMap<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) { vblock.put(v, b.name); for (Ir.Value a : v.args) if (a != null) vblock.putIfAbsent(a, b.name); }
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
        linscan(pool(order, false), intRegs, takenInt, dbg, slotRef, false, vblock, loop, bIdx);
        if (haveFp) linscan(pool(order, true), fpRegs, takenFp, dbg, slotRef, true, vblock, loop, bIdx);
    }

    static String targ(Ir.Value v) { return v.name != null ? v.name : "B" + v.imm; }

    static List<String> successors(Ir.Block b) {
        List<String> r = new ArrayList<>();
        if (b.ins.isEmpty()) return r;
        Ir.Value t = b.ins.get(b.ins.size()-1);
        if (t.op.equals("jump") || t.op.equals("JMP")) r.add(targ(t.args.get(0)));
        else if (t.op.equals("branch") || t.op.equals("BRANCH")) { r.add(targ(t.args.get(1))); r.add(targ(t.args.get(2))); }
        return r;
    }

    static void dfsLoops(String cur, Map<String,String[]> succ, Map<String,Integer> bIdx, Set<String> done, Set<String> onPath, List<int[]> back) {
        if (done.contains(cur)) return;
        onPath.add(cur);
        for (String to : succ.get(cur)) {
            if (done.contains(to)) continue;
            if (onPath.contains(to)) { back.add(new int[]{bIdx.get(cur), bIdx.get(to)}); continue; }
            dfsLoops(to, succ, bIdx, done, onPath, back);
        }
        done.add(cur); onPath.remove(cur);
    }

    // Връща за всеки блок innermost-loop'а му: {headerIdx, backEdgeSourceIdx}, или null.
    // Ползва се за удължаване на live-range-а на стойности дефинирани ИЗВЪН цикъла, но чети в него —
    // линейният scan иначе освобождава регистъра им при първия лексикален last-use, а той се чете отново всяка итерация.
    static Map<String,int[]> loops(Ir.Func f, Map<String,Integer> bIdx) {
        Map<String,String[]> succ = new HashMap<>();
        Set<String> done = new HashSet<>(), onPath = new HashSet<>();
        List<int[]> back = new ArrayList<>();
        for (Ir.Block b : f.blocks) succ.put(b.name, successors(b).toArray(new String[0]));
        for (Ir.Block b : f.blocks) dfsLoops(b.name, succ, bIdx, done, onPath, back);
        Map<String,int[]> out = new HashMap<>();
        for (int[] be : back) {
            int h = be[1], e = be[0];
            for (int i = Math.min(h, e); i <= Math.max(h, e); i++) {
                int[] cur = out.get(f.blocks.get(i).name);
                if (cur == null || h > cur[0]) out.put(f.blocks.get(i).name, new int[]{h, e});
            }
        }
        return out;
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
                        Ir.DebugInfo dbg, int[] slotRef, boolean fpPass,
                        Map<Ir.Value,String> vblock, Map<String,int[]> loop, Map<String,Integer> bIdx) {
        String pref = fpPass ? "freg " : "reg ";
        Map<Ir.Value,Integer> last = new HashMap<>();
        Map<Integer,Integer> endIdx = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Integer bi = bIdx.get(vblock.get(items.get(i)));
            if (bi != null) endIdx.put(bi, i);
        }
        for (int i = 0; i < items.size(); i++) {
            Ir.Value v = items.get(i);
            String vb = vblock.get(v);
            if (v.op.startsWith("PHI_") || v.op.equals("phi")) {
                // PHI аргументът се "ползва" в края на съответния predecessor блок (там PhiElim слага mov-а),
                // а не в началото на phi блока — иначе линейният scan освобождава регистъра на back-edge стойността преждевременно.
                for (int j = 0; j + 1 < v.args.size(); j += 2) {
                    Integer pIdx = bIdx.get(targ(v.args.get(j)));
                    int lastUse = pIdx == null ? i : endIdx.getOrDefault(pIdx, i);
                    Ir.Value a = v.args.get(j + 1);
                    if (lastUse > last.getOrDefault(a, Integer.MIN_VALUE)) last.put(a, lastUse);
                }
                last.putIfAbsent(v, i);
                continue;
            }
            for (Ir.Value a : v.args) {
                int[] lp = vb == null ? null : loop.get(vb);
                Integer db = bIdx.get(vblock.get(a));
                int lastUse = i;
                // стойност дефинирана извън цикъл, но четена в него → жива до края на цикъла
                if (lp != null && (db == null || db < lp[0])) {
                    Integer ext = endIdx.get(lp[1]);
                    if (ext != null && ext > lastUse) lastUse = ext;
                }
                if (lastUse > last.getOrDefault(a, Integer.MIN_VALUE)) last.put(a, lastUse);
            }
            last.putIfAbsent(v, i);
        }
        Map<Ir.Value,String> loc = new LinkedHashMap<>();
        LinkedList<String> fr = new LinkedList<>(Arrays.asList(pool));
        for (String r : taken) fr.remove(r);
        Deque<String> free = new ArrayDeque<>(fr);
        PriorityQueue<Ir.Value> active = new PriorityQueue<>((a,b) -> Integer.compare(last.getOrDefault(b,0), last.getOrDefault(a,0)));
        for (int i = 0; i < items.size(); i++) {
            Ir.Value v = items.get(i);
            if (v.type == null || v.type.equals("void")) continue;   // без резултат: само last-use за аргументите
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