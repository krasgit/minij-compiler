package bg.minij.common;

import java.util.*;
public class Opt {
    public static void run(Ir.Program p) { for (Ir.Func f : p.funcs) { boolean ch = true; while (ch) ch = one(f); } }
    static boolean one(Ir.Func f) {
        boolean ch = false;
        for (Ir.Block b : f.blocks) { if (fold(b)) ch = true; if (copy(b)) ch = true; }
        if (dce(f)) ch = true;
        return ch;
    }
    static boolean isFpT(String t) { return t != null && (t.equals("f32") || t.equals("f64") || t.equals("float") || t.equals("double")); }
    static boolean fold(Ir.Block b) {
        boolean ch = false;
        for (Ir.Value v : b.ins) {
            if (v.args.size() != 2) continue;
            Ir.Value a = v.args.get(0), c = v.args.get(1);
            if (isFpT(a.type) || isFpT(c.type)) continue;   // FP consts са битови шаблони, не числа
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
                Ir.Value src = v.args.get(0);
                if (src.op.equals("phi") || src.op.startsWith("PHI_") || src.op.equals("undef")) continue;
                while (m.containsKey(src)) src = m.get(src);
                m.put(v, src); if (src != v.args.get(0)) ch = true;
            }
            for (int i=0;i<v.args.size();i++) if (m.containsKey(v.args.get(i))) { v.args.set(i, m.get(v.args.get(i))); ch = true; }
        }
        return ch;
    }
    static boolean dce(Ir.Func f) {
        Set<Ir.Value> used = new HashSet<>();
        for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins)
            if (Ir.isTerm(v.op) || v.op.startsWith("PHI_") || v.op.equals("phi")
                || v.op.equals("store") || v.op.equals("STORE_i32")
                || v.op.startsWith("st_") || v.op.equals("chk")
                || v.op.equals("call") || v.op.startsWith("CALL_")
                || v.op.equals("icall") || v.op.startsWith("ICALL_")) used.add(v);
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
