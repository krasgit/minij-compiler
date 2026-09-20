package bg.minij.tools.janino_parse;

import java.io.*;
import java.nio.file.*;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Java;
import org.codehaus.janino.Unparser;
import bg.minij.common.*;
public class JaninoParseMain {
    public static void main(String[] args) throws Exception { run(args); }

    public static void run(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: janino-parse <in.mj> <out.ast>"); System.exit(1); }
        String src = args[0].equals("-") ? new String(System.in.readAllBytes()) : Files.readString(Path.of(args[0]));
        String base = args[0].replaceAll(".*/","").replaceAll("\\.mj$","");
        Scanner sc = new Scanner(base + ".mj", new StringReader(src));
        Java.AbstractCompilationUnit cu = new Parser(sc).parseAbstractCompilationUnit();
        StringWriter sw = new StringWriter();
        Unparser.unparse(cu, sw);
        String dump = sw.toString();
        if (args[1].equals("-")) System.out.print(dump);
        else Files.writeString(Path.of(args[1]), dump);
    }
}
