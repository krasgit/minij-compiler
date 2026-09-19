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
    // тип на аритметичния изход: тип на стойността; за сравнения — тип на първия операнд
    static String tyOf(Ir.Value v, Ir.Func f) {
        String o = v.op;
        if (o.equals("cmp") || o.startsWith("cmp") || o.equals("cmplt") || o.equals("cmpgt")
            || o.equals("cmple") || o.equals("cmpge") || o.equals("cmpeq") || o.equals("cmpne"))
            return v.args.isEmpty() ? "i32" : v.args.get(0).type;
        if (o.equals("return")) return f.retType;
        return v.type;
    }
    static void lower(Ir.Program p) {
        for (Ir.Func f : p.funcs) for (Ir.Block b : f.blocks) for (Ir.Value v : b.ins) {
            String o = v.op, t = tyOf(v, f);
            String s = (t.equals("i64")||t.equals("ptr")||t.equals("address")) ? "i64" : (t.equals("f64") ? "f64" : "i32");
            if (o.equals("add")) v.op = "ADD_" + s;
            else if (o.equals("sub")) v.op = "SUB_" + s;
            else if (o.equals("mul")) v.op = "MUL_" + s;
            else if (o.equals("div")) v.op = "DIV_" + s;
            else if (o.equals("mod")) v.op = "MOD_" + s;
            else if (o.equals("cmplt")) v.op = "CMPLT_" + s;
            else if (o.equals("cmpgt")) v.op = "CMPGT_" + s;
            else if (o.equals("cmple")) v.op = "CMPLE_" + s;
            else if (o.equals("cmpge")) v.op = "CMPGE_" + s;
            else if (o.equals("cmpeq")) v.op = "CMPEQ_" + s;
            else if (o.equals("cmpne")) v.op = "CMPNE_" + s;
            else if (o.equals("and")) v.op = "AND_" + s;
            else if (o.equals("or"))  v.op = "OR_" + s;
            else if (o.equals("xor")) v.op = "XOR_" + s;
            else if (o.equals("shl")) v.op = "SHL_" + s;
            else if (o.equals("shr")) v.op = "SHR_" + s;
            else if (o.equals("ushr")) v.op = "USHR_" + s;
            else if (o.equals("not")) v.op = "NOT_" + s;
            else if (o.equals("jump")) v.op = "JMP";
            else if (o.equals("branch")) v.op = "BRANCH";
            else if (o.equals("return")) { if (f.retType.equals("void")) v.op = "return"; else v.op = "RETURN_" + s; }
            else if (o.equals("phi")) v.op = "PHI_" + s;
            else if (o.equals("const")) v.op = "CONST_" + s;
            else if (o.equals("param")) v.op = "PARAM_" + s;
            else if (o.equals("call")) v.op = "CALL_" + s;
            else if (o.equals("icall")) v.op = "ICALL_" + s;
            else if (o.equals("copy")) v.op = "MOV_" + s;
            else if (o.equals("undef")) v.op = "CONST_" + s;
            if (o.equals("undef")) v.imm = 0;
        }
    }
}