package bg.minij.common;

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
        if (v.name != null) for (Ir.Block b : f.blocks) if (b.name.equals(v.name)) return b;
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
        for (String[] es : f.ehSrc) {
            Ir.Block p = null, h = null;
            for (Ir.Block b : f.blocks) if (b.name.equals(es[0])) { p = b; break; }
            for (Ir.Block b : f.blocks) if (b.name.equals(es[1])) { h = b; break; }
            if (p != null && h != null && !succs.get(p).contains(h)) { succs.get(p).add(h); preds.get(h).add(p); }
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
