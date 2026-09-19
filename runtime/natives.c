/*
 * MiniJ corelib natives (P6).
 *
 * ABI: `k_native_<Class>_<name>_<arity>` (docs/corelib.md). Compiled
 * separately from runtime.c and linked with -lc -lm by `mc`; the ABI is the
 * platform C calling convention (i32 → w-regs, i64/ptr → x-regs, fp → d-regs,
 * aarch64), same as examples/native.mj.
 *
 * java.util.Random is fully implemented in MiniJ (corelib/java/util/Random.mj)
 * — no natives needed (P6 shift/bitwise/long ops).
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