import java.nio.file.*;
public class EmitX86Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: emit-x86 <in.mir> <out.s> [rules/x86.rule]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String rf = args.length > 2 ? args[2] : "rules/x86.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../rules/x86.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../../rules/x86.rule";
        Ir.Program p = Ir.Reader.parse(src);
        String out = Emitter.emit(p, "x86-64", rf);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
