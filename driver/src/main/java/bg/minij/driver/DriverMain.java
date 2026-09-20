package bg.minij.driver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import bg.minij.tools.ast_lower.AstLowerMain;
import bg.minij.tools.emit.EmitMain;
import bg.minij.tools.janino_parse.JaninoParseMain;
import bg.minij.tools.phi_elim.PhiElimMain;
import bg.minij.tools.regalloc.RegallocMain;
import bg.minij.tools.ssa_build.SsaBuildMain;
import bg.minij.tools.ssa_lower.SsaLowerMain;
import bg.minij.tools.ssa_opt.SsaOptMain;

public class DriverMain {
    public static void main(String[] args) throws Exception { run(args); }

    public static void run(String[] args) throws Exception {
        String target = "x86-64", stage = "exe", out = "a.out", link = "static", corelibMode = "src";
        boolean keep = false;
        String src = null, emitClassmap = null;
        List<String> imp = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--target=")) target = a.substring(9);
            else if (a.startsWith("--stage=")) stage = a.substring(8);
            else if (a.startsWith("--link=")) link = a.substring(7);
            else if (a.startsWith("--corelib=")) corelibMode = a.substring(10);
            else if (a.startsWith("--emit-classmap=")) emitClassmap = a.substring(16);
            else if (a.equals("--keep")) keep = true;
            else if (a.equals("-o") && i + 1 < args.length) out = args[++i];
            else if (a.equals("-I") && i + 1 < args.length) imp.add(args[++i]);
            else if (src == null) src = a;
            else usage();
        }
        if (src == null) usage();
        if (!corelibMode.equals("src") && !corelibMode.equals("lib")) usage();

        String base = new File(src).getName();
        if (base.endsWith(".mj")) base = base.substring(0, base.length() - 3);

        // minij.home: системна пропърти (mc/scripts я подават) или CWD, ако
        // corelib/ е налично тук (java -jar от корена на repo-то).
        String home = System.getProperty("minij.home", "");
        if (home.isEmpty() && new File("corelib").isDirectory()) home = ".";
        File runtimeDir = home.isEmpty() ? new File("runtime") : new File(home, "runtime");
        File corelibDir = home.isEmpty() ? new File("corelib") : new File(home, "corelib");

        // ─── стъпките на пайплайна (същият ред като mc) ────────────────────
        JaninoParseMain.run(new String[]{src, base + ".ast"});
        if (stage.equals("ast")) { printFile(base + ".ast"); return; }

        List<String> astArgs = new ArrayList<>();
        for (String im : imp) { astArgs.add("-I"); astArgs.add(im); }
        if (corelibDir.isDirectory()) { astArgs.add("-I"); astArgs.add(corelibDir.getPath()); }
        if (emitClassmap != null) astArgs.add("--emit-classmap=" + emitClassmap);
        if (corelibMode.equals("lib")) {
            File classMap = new File(new File(corelibDir, "target"), "classmap.txt");
            if (!classMap.isFile())
                throw new IOException("--corelib=lib изисква предварително построена библиотека: "
                    + "build corelib (libminijcore.a + classmap.txt) липсват в " + classMap.getParent());
            for (String line : Files.readAllLines(classMap.toPath())) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                if (p.length == 2) astArgs.add("--classmap=" + p[1] + ":" + p[0]);
            }
            astArgs.add("--external-dir=" + corelibDir.getPath());
        }
        astArgs.add(src);
        astArgs.add(base + ".lir");
        AstLowerMain.run(astArgs.toArray(new String[0]));
        if (stage.equals("lir")) { printFile(base + ".lir"); return; }

        SsaBuildMain.run(new String[]{base + ".lir", base + ".ssa"});
        if (stage.equals("ssa")) { printFile(base + ".ssa"); return; }

        SsaOptMain.run(new String[]{base + ".ssa", base + ".opt.ssa"});
        if (stage.equals("opt")) { printFile(base + ".opt.ssa"); return; }

        SsaLowerMain.run(new String[]{base + ".opt.ssa", base + ".mir"});
        if (stage.equals("mir")) { printFile(base + ".mir"); return; }

        if (target.equals("arm64"))
            RegallocMain.run(new String[]{base + ".mir", base + ".alloc.mir", "--target=arm64"});
        else
            RegallocMain.run(new String[]{base + ".mir", base + ".alloc.mir"});

        PhiElimMain.run(new String[]{base + ".alloc.mir", base + ".final.mir"});

        if (target.equals("arm64"))
            EmitMain.run(new String[]{base + ".final.mir", base + ".s", "--target=arm64"});
        else
            EmitMain.run(new String[]{base + ".final.mir", base + ".s"});
        if (stage.equals("asm")) { printFile(base + ".s"); return; }

        // ─── assemble + link (същите команди като mc:49-71) ────────────────
        link(base, target, out, keep, runtimeDir, corelibDir, link, corelibMode);
        if (!keep) for (String ext : new String[]{"ast","lir","ssa","opt.ssa","mir","alloc.mir","final.mir","s","o","runtime.o","natives.o","crt0.o"})
            new File(base + "." + ext).delete();
        System.out.println("✓ compiled → " + out);
    }

    static void usage() {
        System.err.println("usage: mc [--target=x86-64|arm64] [--stage=ast|lir|ssa|opt|mir|asm] [--link=static|dynamic]"
            + " [--corelib=src|lib] [--keep] <src.mj> [-o out]");
        System.exit(1);
    }

    static void printFile(String p) throws IOException { System.out.print(Files.readString(Path.of(p))); }

    static String which(String cmd) {
        String pathVar = System.getenv("PATH");
        if (pathVar == null) return null;
        for (String d : pathVar.split(File.pathSeparator)) {
            File f = new File(d, cmd);
            if (f.isFile() && f.canExecute()) return f.getPath();
        }
        return null;
    }

    // ─── assemble + link ────────────────────────────────────────────────────
    static void link(String base, String target, String out, boolean keep, File runtimeDir, File corelibDir, String link, String corelibMode) throws Exception {
        // assemble .s → .o
        if (target.equals("arm64")) {
            String as = which("aarch64-linux-gnu-as");
            if (as == null) as = which("as");
            exec(new String[]{as, base + ".s", "-o", base + ".o"});
        } else {
            String as = which("as");
            exec(new String[]{as, base + ".s", "-o", base + ".o"});
        }

        // CC избор (mc:56-61)
        String cc;
        if (target.equals("arm64")) {
            cc = System.getenv("AARCH64_CC");
            if (cc == null || which(cc) == null) cc = which("aarch64-linux-gnu-gcc");
            if (cc == null) cc = which("gcc");
        } else {
            cc = System.getenv("CC");
            if (cc == null || which(cc) == null) cc = which("gcc");
        }
        if (cc == null) throw new IOException("gcc не е намерен на PATH");

        String crt0 = target.equals("arm64") ? "crt0.S" : "crt0-x64.S";
        List<String> crt = new ArrayList<>(List.of(cc, "-c", new File(runtimeDir, crt0).getPath(), "-o", base + ".crt0.o"));
        try {
            exec(crt.toArray(new String[0]));
        } catch (Exception e) {
            String as = which("as");
            exec(new String[]{as, new File(runtimeDir, crt0).getPath(), "-o", base + ".crt0.o"});
        }

        // Колкото възможно повече се свързва от prebuilt libruntime
        // (--link=static → libruntime.a, --link=dynamic → libruntime.so);
        // ако липсва — fallback: per-app компилация на runtime.c/natives.c.
        File libA  = new File(new File(runtimeDir, "target"), "libruntime.a");
        File libSo = new File(new File(runtimeDir, "target"), "libruntime.so");
        String runDir = new File(runtimeDir, "target").getAbsolutePath();

        // corelib като библиотека (--corelib=lib): libminijcore.a (или .so при
        // наличен "-shared" build) се свързва ПРЕД libruntime — app → corelib → runtime.
        String coreObj = null, coreLibDir = null;
        if (corelibMode.equals("lib")) {
            File cA  = new File(new File(corelibDir, "target"), "libminijcore.a");
            File cSo = new File(new File(corelibDir, "target"), "libminijcore.so");
            coreLibDir = new File(corelibDir, "target").getAbsolutePath();
            if (link.equals("dynamic") && cSo.isFile()) coreObj = "-lminijcore";
            else if (cA.isFile()) coreObj = cA.getAbsolutePath();
            if (coreObj == null)
                throw new IOException("--corelib=lib: libminijcore липсва в " + coreLibDir + " (build corelib първо)");
        }

        if (link.equals("dynamic") && libSo.isFile()) {
            List<String> dyn = new ArrayList<>(List.of(cc, "-no-pie", "-nostartfiles",
                base + ".o", base + ".crt0.o"));
            if (coreObj != null) dyn.add(coreObj);
            dyn.add("-L" + runDir); dyn.add("-lruntime");
            if (coreObj != null && coreliObjIsSo(coreObj)) dyn.add("-Wl,-rpath," + coreLibDir);
            dyn.add("-Wl,-rpath," + runDir); dyn.add("-lc"); dyn.add("-lm"); dyn.add("-o"); dyn.add(out);
            exec(dyn.toArray(new String[0]));
            return;
        }
        if (libA.isFile()) {
            List<String> st = new ArrayList<>(List.of(cc, "-no-pie", "-nostartfiles",
                base + ".o", base + ".crt0.o"));
            if (coreObj != null) st.add(coreObj);
            st.add(libA.getAbsolutePath()); st.add("-lc"); st.add("-lm"); st.add("-o"); st.add(out);
            exec(st.toArray(new String[0]));
            return;
        }

        exec(new String[]{cc, "-fno-stack-protector", "-ffreestanding", "-O2", "-c",
            new File(runtimeDir, "runtime.c").getPath(), "-o", base + ".runtime.o"});
        exec(new String[]{cc, "-fno-stack-protector", "-ffreestanding", "-O2", "-c",
            new File(runtimeDir, "natives.c").getPath(), "-o", base + ".natives.o"});
        List<String> fb = new ArrayList<>(List.of(cc, "-no-pie", "-nostartfiles",
            base + ".o", base + ".crt0.o"));
        if (coreObj != null) fb.add(coreObj);
        fb.add(base + ".runtime.o"); fb.add(base + ".natives.o");
        fb.add("-lc"); fb.add("-lm"); fb.add("-o"); fb.add(out);
        exec(fb.toArray(new String[0]));
    }

    static boolean coreliObjIsSo(String coreObj) {
        return coreObj.equals("-lminijcore");
    }

    static void exec(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.print(out);
            throw new IOException("команда неуспешна (" + rc + "): " + String.join(" ", cmd));
        }
        if (!out.isEmpty()) System.err.print(out);
    }
}