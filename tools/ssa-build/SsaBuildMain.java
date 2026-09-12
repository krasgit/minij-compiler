import java.nio.file.*;
public class SsaBuildMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ssa-build <in.lir> <out.ssa>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        Ir.Program p = Ir.Reader.parse(src);
        Ssa.build(p);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
