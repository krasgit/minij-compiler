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
                        Ir.Block pred = null;
                        if (mk.name != null) for (Ir.Block bb : f.blocks) if (bb.name.equals(mk.name)) { pred = bb; break; }
                        if (pred == null) pred = byHash.get((int)mk.imm);
                        if (pred == null) continue;
                        String s = Ir.suffix(v.type);
                        Ir.Value mov = new Ir.Value("MOV_" + s, v.type, val, v);
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
