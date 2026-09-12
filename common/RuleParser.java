import java.util.*;
public class RuleParser {
    public static class Rule {
        public String op;
        public List<String> patArgs = new ArrayList<>();
        public List<String> template = new ArrayList<>();
    }
    public static class Rules {
        public List<String> regs = new ArrayList<>();
        public List<String> scratch = new ArrayList<>();
        public List<String> prologue = new ArrayList<>();
        public List<String> epilogue = new ArrayList<>();
        public List<Rule> emits = new ArrayList<>();
    }
    public static Rules parse(String src) {
        Rules r = new Rules();
        String[] lines = src.split("\n");
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i].trim();
            if (ln.isEmpty() || ln.startsWith("#")) { i++; continue; }
            if (ln.startsWith("regs:")) { r.regs = tokens(ln.substring(5)); i++; continue; }
            if (ln.startsWith("scratch:")) { r.scratch = tokens(ln.substring(8)); i++; continue; }
            if (ln.startsWith("prologue")) { i = readBlock(lines, i+1, r.prologue); continue; }
            if (ln.startsWith("epilogue")) { i = readBlock(lines, i+1, r.epilogue); continue; }
            if (ln.startsWith("emit")) { i = readEmit(lines, i, r.emits); continue; }
            i++;
        }
        return r;
    }
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.replace(";","").trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
    static int readBlock(String[] lines, int i, List<String> out) {
        while (i < lines.length && !lines[i].contains("{")) i++;
        i++;
        while (i < lines.length && !lines[i].trim().equals("}")) { out.add(lines[i].trim()); i++; }
        return i+1;
    }
    static int readEmit(String[] lines, int i, List<Rule> rules) {
        String ln = lines[i].trim();
        int arrow = ln.indexOf("->");
        String patStr = ln.substring(4, arrow).trim();
        Rule rl = new Rule();
        int lp = patStr.indexOf('(');
        if (lp < 0) rl.op = patStr.trim();
        else {
            int rp = patStr.lastIndexOf(')');
            rl.op = patStr.substring(0, lp).trim();
            String inner = patStr.substring(lp+1, rp).trim();
            if (!inner.isEmpty()) for (String a : inner.split(",")) rl.patArgs.add(a.trim());
        }
        i++;
        while (i < lines.length && !lines[i].trim().equals("}")) { rl.template.add(lines[i].trim()); i++; }
        rules.add(rl);
        return i+1;
    }
}
