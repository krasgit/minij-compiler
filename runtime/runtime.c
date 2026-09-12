/*
 * MiniJ runtime (P0 skeleton).
 *
 * Linked together with crt0.S and the compiled program:
 *     as   runtime/crt0.S   -o crt0.o
 *     cc   runtime/runtime.c -c -o runtime.o
 *     ld   program.o crt0.o runtime.o -o program
 *
 * Syscall-based output (no libc dependency) so the resulting binary runs
 * bare against the kernel. `puti`/`putc` are callable directly from .mj as
 * free functions (Janino parses undefined calls; AstLower lowers them to
 * `call name=puti`).
 *
 * mm_alloc is the GC-agnostic allocation seam (P2): a bump arena with a
 * per-class `kind` tag. Later phases swap in mark&sweep / MMTk while keeping
 * the same entry point.
 */

#include <stddef.h>

#ifdef __aarch64__
#define SYS_WRITE 64
static void sys_write(long fd, const char *s, long len) {
    register long x0 __asm__("x0") = fd;
    register const char *x1 __asm__("x1") = s;
    register long x2 __asm__("x2") = len;
    register long x8 __asm__("x8") = SYS_WRITE;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
}
#elif defined(__x86_64__)
#define SYS_WRITE 1
static void sys_write(long fd, const char *s, long len) {
    register long rax __asm__("rax") = SYS_WRITE;
    register long rdi __asm__("rdi") = fd;
    register const char *rsi __asm__("rsi") = s;
    register long rdx __asm__("rdx") = len;
    __asm__ __volatile__("syscall" : "+r"(rax) : "r"(rdi), "r"(rsi), "r"(rdx) : "rcx", "r11", "memory");
}
#else
#error "runtime.c supports aarch64 and x86-64 only"
#endif

/* ── output ─────────────────────────────────────────────────────────────── */

static void out_char(int ch) {
    char b = (char) ch;
    sys_write(1, &b, 1);
}

/* putc(int ch): MiniJ-visible */
void putc(int ch) { out_char(ch); }

static void out_digits(unsigned int v) {
    char tmp[10];
    int i = 0;
    do { tmp[i++] = (char)('0' + v % 10); v /= 10; } while (v != 0);
    while (i > 0) out_char(tmp[--i]);
}

/* puti(int v): MiniJ-visible, prints decimal + newline */
void puti(int v) {
    if (v < 0) { out_char('-'); out_digits((unsigned int)(-(long)v)); }
    else out_digits((unsigned int)v);
    out_char('\n');
}

/* puts(int p): print a NUL-terminated byte string at address p (P1+) */
void puts(int p) {
    const char *s = (const char *) (long) p;
    while (s != NULL && *s != 0) out_char(*s++);
}

/* ── allocation seam (GC-agnostic, P2+) ─────────────────────────────────── */

#define MM_ALIGN 16u
#define MM_HEAP_SIZE (16u * 1024u)

static char heap[MM_HEAP_SIZE];
static char *next = heap;

/* mm_alloc(size, kind): zeroed bump allocation; `kind` is reserved for the
 * GC/MMTk object layout. Returns address in the 64-bit result register. */
void *mm_alloc(unsigned long size, int kind) {
    (void) kind;
    unsigned long n = (size + MM_ALIGN - 1u) & ~(MM_ALIGN - 1u);
    if ((unsigned long)(next - heap) + n > MM_HEAP_SIZE) return NULL;
    char *p = next;
    next = p + n;
    return p;
}

/* mm_alloc_zeroed: bump arena is already zero-filled on every boot. */
/* ── native contract (P0/P1): k_native_<Class>_<name>_<arity> ────────────── */

/* test/example natives used by examples/native.mj */
int k_native_N_foo_1(int x) { return x * 2; }
double k_native_N_fp_1(double x) { return x * 2.0; }
long k_native_N_lng_1(long x) { return x + 1L; }
