/*
 * MiniJ corelib natives (P6).
 *
 * ABI: `k_native_<Class>_<name>_<arity>` (docs/corelib.md). Compiled
 * separately from runtime.c and linked with -lc -lm by `mc`; the ABI is the
 * platform C calling convention (i32 → w-regs, i64/ptr → x-regs, fp → d-regs,
 * aarch64), same as examples/native.mj.
 *
 * java.util.Random: every function here is phase-neutral — it does NOT
 * advance the LCG seed. The MiniJ Random class owns the `seed` field and
 * advances it with step() exactly like JDK's next(bits) calls, so the whole
 * sequence is bit-identical to `java.util.Random`.
 */

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>
#include <unistd.h>

/* ── java.lang.System ─────────────────────────────────────────────────── */

void k_native_System_exit_1(int code) { _exit(code); }

long k_native_System_currentTimeMillis_0(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long) ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

long k_native_System_nanoTime_0(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000000000L + ts.tv_nsec;
}

/* arraycopy(int[] src, int srcPos, int[] dst, int dstPos, int len)
 * int[] = i32 header len + data at +8. */
void k_native_System_arraycopy_5(void *src, int sp, void *dst, int dp, int len) {
    const char *s = (const char *) src + 8 + (unsigned int) sp * 4u;
    char *d = (char *) dst + 8 + (unsigned int) dp * 4u;
    size_t n = (size_t) len * 4u;
    if (src == dst) memmove(d, s, n); else memcpy(d, s, n);
}

/* ── java.lang.Math (transcendentals → libm) ──────────────────────────── */

double k_native_Math_sqrt_1(double x) { return sqrt(x); }
double k_native_Math_pow_2(double a, double b) { return pow(a, b); }
double k_native_Math_floor_1(double x) { return floor(x); }
double k_native_Math_ceil_1(double x) { return ceil(x); }

/* ── java.util.Random: JDK-identical 48-bit LCG ───────────────────────── */

#define RMULT 0x5DEECE66DL
#define RADD  0xBL
#define RMASK 0x0000FFFFFFFFFFFFL

/* setSeed(s): seed = (s ^ MULTIPLIER) & MASK */
long k_native_Random_normSeed_1(long s) { return (s ^ RMULT) & RMASK; }
/* one next(bits)-style advance: seed = (seed * A + C) & MASK */
long k_native_Random_step_1(long s) { return (s * RMULT + RADD) & RMASK; }

/* JDK next(bits) read = (int)(seed >>> (48 - bits)), no advance. */
int k_native_Random_bits32_1(long s) { return (int)((unsigned long) s >> 16); }
int k_native_Random_bits31_1(long s) { return (int)((unsigned long) s >> 17); }
int k_native_Random_bits26_1(long s) { return (int)((unsigned long) s >> 22); }
int k_native_Random_bits27_1(long s) { return (int)((unsigned long) s >> 21); }
int k_native_Random_bits1_1(long s)  { return (int)((unsigned long) s >> 47); }

/* nextLong: (((long)next(32)) << 32) + next(32) — hi/lo are the two draws. */
long k_native_Random_mix64_2(long hi, long lo) {
    return ((long) (unsigned int) hi << 32) + (lo & 0xFFFFFFFFL);
}

/* nextDouble: (((long)next(26)) << 27) + next(27)) / 2^53 */
double k_native_Random_scale53_2(long hi, long lo) {
    long v = (hi << 27) + (long) (int) lo;
    return (double) v / 9007199254740992.0;  /* (double)(1L << 53) */
}