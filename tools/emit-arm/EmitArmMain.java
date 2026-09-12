import java.nio.file.*;
public class EmitArmMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: emit-arm <in.mir> <out.s> [rules/arm.rule]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String rf = args.length > 2 ? args[2] : "rules/arm.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../rules/arm.rule";
        if (!Files.exists(Path.of(rf))) rf = "../../../rules/arm.rule";
        Ir.Program p = Ir.Reader.parse(src);
        String out = Emitter.emit(p, "arm64", rf);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
