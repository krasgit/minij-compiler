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

/* ── String printing (P2): MiniJ String = char[] (header i32 length + 4-byte cells).
 * System.out.println/print in .mj lower to k_println/k_print (char[]),
 * k_println_i32/k_print_i32 (scalar) or k_newline (println()). ────────────── */

static void print_chars(const void *a) {
    if (a == NULL) return;
    int n = *(const int *) a;
    if (n <= 0) return;
    const int *p = (const int *) ((const char *) a + 8);
    for (int i = 0; i < n; i++) out_char(p[i]);
}

void k_print(void *a) { print_chars(a); }
void k_println(void *a) { print_chars(a); out_char('\n'); }
void k_print_i32(int v) { if (v < 0) { out_char('-'); out_digits((unsigned int)(-(long)v)); } else out_digits((unsigned int)v); }
void k_println_i32(int v) { k_print_i32(v); out_char('\n'); }
void k_newline(void) { out_char('\n'); }

/* ── String methods (P2 tail): s.equals(t) / s.concat(t) → k_string_*
 * equals: compare two char[] (header len + i32 cells). ──────────────────── */
void *mm_alloc(unsigned long size, int kind);

int k_string_equals(const void *a, const void *b) {
    if (a == b) return 1;
    if (a == NULL || b == NULL) return 0;
    int na = *(const int *) a;
    int nb = *(const int *) b;
    if (na != nb) return 0;
    const int *pa = (const int *) ((const char *) a + 8);
    const int *pb = (const int *) ((const char *) b + 8);
    for (int i = 0; i < na; i++) if (pa[i] != pb[i]) return 0;
    return 1;
}

/* concat: new char[] with len1+len2 cells, copy both (alloc_i32 shape (n+2)
 * words; data at +8). */
void *k_string_concat(const void *a, const void *b) {
    int na = a == NULL ? 0 : *(const int *) a;
    int nb = b == NULL ? 0 : *(const int *) b;
    int n = na + nb;
    char *dst = (char *) mm_alloc((unsigned long) (n + 2) * 4u, 0);
    *(int *) dst = n;
    int *pd = (int *) (dst + 8);
    const int *pa = (const int *) ((const char *) a + 8);
    const int *pb = (const int *) ((const char *) b + 8);
    for (int i = 0; i < na; i++) pd[i] = pa[i];
    for (int i = 0; i < nb; i++) pd[na + i] = pb[i];
    return dst;
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
/* ── bounds-error trap (P2) ───────────────────────────────────────────────── */

#ifdef __aarch64__
#define SYS_EXIT 93
static void sys_exit(int status) {
    register long x0 __asm__("x0") = status;
    register long x8 __asm__("x8") = SYS_EXIT;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    __builtin_unreachable();
}
#else
static void sys_exit(int status) {
    register long rax __asm__("rax") = 60;   /* SYS_EXIT */
    register long rdi __asm__("rdi") = status;
    __asm__ __volatile__("syscall" : "+r"(rax) : "r"(rdi) : "rcx", "r11", "memory");
    __builtin_unreachable();
}
#endif

/* k_bounds_error: called on out-of-bounds array access (never returns). */
void k_bounds_error(void) {
    sys_exit(134);   /* 128 + SIGABRT */
}

/* ── native contract (P0/P1): k_native_<Class>_<name>_<arity> ────────────── */

/* test/example natives used by examples/native.mj */
int k_native_N_foo_1(int x) { return x * 2; }
double k_native_N_fp_1(double x) { return x * 2.0; }
long k_native_N_lng_1(long x) { return x + 1L; }

/* ── exception support (P5) ─────────────────────────────────────────────── */

typedef struct exc_rec {
    struct exc_rec *prev;
    void *fp;
    int (*handler)(void*, void*);
} ExcRec;

#define EXC_MAX_DEPTH 128
static ExcRec exc_pool[EXC_MAX_DEPTH];
static int exc_depth = 0;

void *exc_head = 0;

/* k_exc_push: try-entry pushes a record with dispatcher label + caller fp;
 * returns the record so the normal path can k_exc_pop it. */
void *k_exc_push(void *dsp, long fp) {
    if (exc_depth >= EXC_MAX_DEPTH) sys_exit(77);
    ExcRec *r = &exc_pool[exc_depth++];
    r->handler = (int (*)(void*, void*)) dsp;
    r->fp = (void*) fp;
    r->prev = (ExcRec*) exc_head;
    exc_head = r;
    return r;
}

void k_exc_pop(void *rec) {
    ExcRec *r = (ExcRec*) rec;
    if (exc_head == r) {
        exc_head = r->prev;
        if (exc_depth > 0) exc_depth--;
    }
}

/* k_throw: never returns. Walk chain; dispatch to handlers. */
void k_throw(void *e) {
    for (;;) {
        ExcRec *r = (ExcRec*) exc_head;
        if (r == 0) {
            /* Uncaught exception: print class index and exit. */
            if (e != 0) {
                int ci = *(int*) e;
                out_char('#'); out_digits((unsigned int) ci); out_char('\n');
            }
            sys_exit(3);
        }
        int matched = r->handler(e, r);
        if (matched) {
            /* Handler matched; dispatcher already popped exc_head and
             * transferred control to the handler block. Return. */
            return;
        }
        /* Non-match: pop and continue to the next record. */
        exc_head = r->prev;
        if (exc_depth > 0) exc_depth--;
    }
}
