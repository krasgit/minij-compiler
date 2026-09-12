import java.util.*;
public class Emitter {
    Ir.Program prog; String arch; StringBuilder out;
    Map<Ir.Value,String> loc; Ir.DebugInfo dbg;
    public static String emit(Ir.Program p, String target, String ruleFile) throws Exception {
        Emitter e = new Emitter();
        e.prog = p; e.arch = target; e.out = new StringBuilder();
        e.loc = new LinkedHashMap<>(p.debug.locations);
        e.dbg = p.debug;
        e.go();
        return e.out.toString();
    }
    void go() {
        out.append("    .file 1 \"").append(prog.module).append(".mj\"\n    .text\n\n");
        for (Ir.Func f : prog.funcs) func(f);
    }
    String reg(Ir.Value v) {
        String l = loc.get(v);
        if (l == null) return arch.equals("arm64") ? "w9" : "%eax";
        if (l.startsWith("reg ")) return l.substring(4);
        return arch.equals("arm64") ? "w9" : "%eax";
    }
    void line(Ir.Value v) {
        if (v.dbg < 0) return;
        int[] p = dbg.positions.get(v.dbg);
        if (p == null) return;
        out.append("    .loc 1 ").append(p[0]).append(" ").append(p[1]).append("\n");
    }
    void func(Ir.Func f) {
        out.append("    .globl ").append(f.name).append("\n").append(f.name).append(":\n");
        boolean arm = arch.equals("arm64");
        if (arm) {
            out.append("    stp x29, x30, [sp, #-16]!\n    mov x29, sp\n");
            out.append("    stp x19, x20, [sp, #-16]!\n");
            out.append("    stp x21, x22, [sp, #-16]!\n");
            out.append("    stp x23, x24, [sp, #-16]!\n");
            out.append("    stp x25, x26, [sp, #-16]!\n");
            out.append("    stp x27, x28, [sp, #-16]!\n");
            String[] a = {"w0","w1","w2","w3","w4","w5","w6","w7"};
            String[] r = {"w19","w20","w21","w22","w23","w24","w25","w26","w27","w28"};
            for (int i = 0; i < f.params.size() && i < 8; i++) out.append("    mov ").append(r[i]).append(", ").append(a[i]).append("\n");
        } else {
            out.append("    pushq %rbp\n    movq  %rsp, %rbp\n");
            out.append("    pushq %rbx\n    pushq %r12\n    pushq %r13\n    pushq %r14\n    pushq %r15\n    subq  $128, %rsp\n");
            String[] a = {"%edi","%esi","%edx","%ecx","%r8d","%r9d"};
            String[] r = {"%ebx","%r12d","%r13d","%r14d","%r15d"};
            for (int i = 0; i < f.params.size() && i < 5; i++) out.append("    movl  ").append(a[i]).append(", ").append(r[i]).append("\n");
        }
        for (Ir.Block b : f.blocks) {
            out.append(".L").append(f.name.replace("$","_")).append("_").append(Math.abs(b.hashCode()%100000)).append(":\n");
            for (Ir.Value v : b.ins) ins(v, f);
        }
        if (arm) out.append(".L").append(f.name.replace("$","_")).append("_exit:\n    ldp x27, x28, [sp], #16\n    ldp x25, x26, [sp], #16\n    ldp x23, x24, [sp], #16\n    ldp x21, x22, [sp], #16\n    ldp x19, x20, [sp], #16\n    ldp x29, x30, [sp], #16\n    ret\n\n");
        else out.append(".L").append(f.name.replace("$","_")).append("_exit:\n    addq  $128, %rsp\n    popq  %r15\n    popq  %r14\n    popq  %r13\n    popq  %r12\n    popq  %rbx\n    popq  %rbp\n    retq\n\n");
    }
    Ir.Block find(Ir.Func f, Ir.Value t) {
        if (!t.op.equals("block")) return f.blocks.get(0);
        if (t.name != null) for (Ir.Block b : f.blocks) if (b.name.equals(t.name)) return b;
        for (Ir.Block b : f.blocks) if (b.hashCode() == t.imm) return b;
        return f.blocks.get(0);
    }
    String lbl(Ir.Func f, Ir.Block b) { return ".L"+f.name.replace("$","_")+"_"+Math.abs(b.hashCode()%100000); }
    void ins(Ir.Value v, Ir.Func f) {
        line(v);
        boolean arm = arch.equals("arm64");
        String o = v.op;
        if (o.equals("BRANCH")||o.equals("branch")) {
            Ir.Value c = v.args.get(0);
            Ir.Block tb = find(f, v.args.get(1)), eb = find(f, v.args.get(2));
            if (arm) { out.append("    cmp ").append(reg(c)).append(", #0\n    b.ne ").append(lbl(f,tb)).append("\n    b ").append(lbl(f,eb)).append("\n"); }
            else { out.append("    cmpl $0, ").append(reg(c)).append("\n    jne ").append(lbl(f,tb)).append("\n    jmp ").append(lbl(f,eb)).append("\n"); }
        } else if (o.equals("JMP")||o.equals("jump")) {
            Ir.Block t = find(f, v.args.get(0));
            out.append(arm ? "    b " : "    jmp ").append(lbl(f,t)).append("\n");
        } else if (o.equals("RETURN")||o.equals("return")) {
            String ex = ".L"+f.name.replace("$","_")+"_exit";
            if (!v.args.isEmpty()) {
                if (arm) out.append("    mov w0, ").append(reg(v.args.get(0))).append("\n");
                else out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n");
            }
            if (arm) out.append("    b ").append(ex).append("\n");
            else out.append("    jmp ").append(ex).append("\n");
        } else if (o.startsWith("CONST_")||o.equals("const")) {
            if (arm) out.append("    mov ").append(reg(v)).append(", #").append(v.imm).append("\n");
            else out.append("    movl $").append(v.imm).append(", ").append(reg(v)).append("\n");
        } else if (o.startsWith("ADD_")||o.equals("add")) {
            if (arm) out.append("    add ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    addl ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("SUB_")||o.equals("sub")) {
            if (arm) out.append("    sub ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    subl ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("MUL_")||o.equals("mul")) {
            if (arm) out.append("    mul ").append(reg(v)).append(", ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", ").append(reg(v)).append("\n    imull ").append(reg(v.args.get(1))).append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("DIV_")||o.startsWith("MOD_")||o.equals("div")||o.equals("mod")) {
            boolean mod = o.startsWith("MOD_")||o.equals("mod");
            if (arm) { out.append("    sdiv w9, ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n");
                if (mod) out.append("    msub ").append(reg(v)).append(", w9, ").append(reg(v.args.get(1))).append(", ").append(reg(v.args.get(0))).append("\n");
                else out.append("    mov ").append(reg(v)).append(", w9\n"); }
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n    cltd\n    idivl ").append(reg(v.args.get(1))).append("\n    movl ").append(mod?"%edx":"%eax").append(", ").append(reg(v)).append("\n"); }
        } else if (o.startsWith("CMPLT_")||o.startsWith("CMPGT_")||o.startsWith("CMPLE_")||o.startsWith("CMPGE_")||o.startsWith("CMPEQ_")||o.startsWith("CMPNE_")) {
            String cc = o.contains("LT")?"lt":o.contains("GT")?"gt":o.contains("LE")?"le":o.contains("GE")?"ge":o.contains("EQ")?"eq":"ne";
            String xc = o.contains("LT")?"l":o.contains("GT")?"g":o.contains("LE")?"le":o.contains("GE")?"ge":o.contains("EQ")?"e":"ne";
            if (arm) { out.append("    cmp ").append(reg(v.args.get(0))).append(", ").append(reg(v.args.get(1))).append("\n    cset ").append(reg(v)).append(", ").append(cc).append("\n"); }
            else { out.append("    movl ").append(reg(v.args.get(0))).append(", %eax\n    cmpl ").append(reg(v.args.get(1))).append(", %eax\n    set").append(xc).append(" %al\n    movzbl %al, ").append(reg(v)).append("\n"); }
        } else if (o.equals("MOV_i32")||o.equals("copy")) {
            if (v.args.size() >= 2) {
                String d = reg(v.args.get(1)), s = reg(v.args.get(0));
                if (!d.equals(s)) { if (arm) out.append("    mov ").append(d).append(", ").append(s).append("\n"); else out.append("    movl ").append(s).append(", ").append(d).append("\n"); }
            } else if (v.args.size() >= 1) {
                String d = reg(v), s = reg(v.args.get(0));
                if (!d.equals(s)) { if (arm) out.append("    mov ").append(d).append(", ").append(s).append("\n"); else out.append("    movl ").append(s).append(", ").append(d).append("\n"); }
            }
        } else if (o.startsWith("CALL_")||o.equals("call")) {
            List<Ir.Value> actual = new ArrayList<>();
            for (Ir.Value a : v.args) if (a != null && a.op != null && !a.op.equals("symbol")) actual.add(a);
            String[] a = arm ? new String[]{"w0","w1","w2","w3","w4","w5","w6","w7"} : new String[]{"%edi","%esi","%edx","%ecx","%r8d","%r9d"};
            for (int i = 0; i < actual.size() && i < a.length; i++) out.append("    mov ").append(a[i]).append(", ").append(reg(actual.get(i))).append("\n");
            out.append("    ").append(arm ? "bl " : "callq ").append(v.name != null ? v.name : "?").append("\n");
            out.append("    ").append(arm ? "mov " + reg(v) + ", w0" : "movl %eax, " + reg(v)).append("\n");
        }
    }
}
