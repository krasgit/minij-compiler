package bg.minij.tools.regalloc;

import java.nio.file.*;
import bg.minij.common.*;
public class RegallocMain {
    public static void main(String[] args) throws Exception { run(args); }

    public static void run(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: regalloc <in.mir> <out.mir> [--target=arm64|x86-64] [rules/arm.rule]"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String target = "x86-64";
        String rf = null;
        for (String a : args) {
            if (a.startsWith("--target=")) target = a.substring(9);
            else if (!a.startsWith("-") && a.endsWith(".rule")) rf = a;
        }
        if (rf == null) rf = "rules/" + (target.equals("arm64") ? "arm.rule" : "x86.rule");
        if (!Files.exists(Path.of(rf))) rf = "../../rules/" + (target.equals("arm64") ? "arm.rule" : "x86.rule");
        if (!Files.exists(Path.of(rf))) rf = "../../../rules/" + (target.equals("arm64") ? "arm.rule" : "x86.rule");
        if (!Files.exists(Path.of(rf))) throw new RuntimeException("cannot find " + rf);
        RuleParser.Rules rules = RuleParser.parse(Files.readString(Path.of(rf)));
        Ir.Program p = Ir.Reader.parse(src);
        Regalloc.run(p, rules.regs, rules.fregs);
        String out = Ir.Writer.print(p);
        if (args[1].equals("-")) System.out.print(out); else Files.writeString(Path.of(args[1]), out);
    }
}