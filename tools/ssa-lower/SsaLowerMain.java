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
