import java.nio.file.*;
public class RegallocMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: regalloc <in.mir> <out.mir> [--target=arm64]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        boolean arm = false;
        for (String a : args) if (a.equals("--target=arm64") || a.equals("--arch=arm")) arm = true;
        Ir.Program p = Ir.Reader.parse(src);
        Regalloc.run(p, arm);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}
