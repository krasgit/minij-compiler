# MiniJ P6 corelib — RESUME NOTE (branch `import`, working tree = the milestone)

## RECOVERY ENTRY (this is ALL that's needed to return)
cd /home/kivanov/Desktop/kivanov/minij-compiler
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH="/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH"
./build.sh && ./mc --target=arm64 examples/corelib.mj -o /tmp/opencode/smoke/CORE_OK.out \
  && qemu-aarch64 /tmp/opencode/smoke/CORE_OK.out > /tmp/opencode/smoke/CORE_OK.got.txt
# golden = /tmp/opencode/smoke/refcore2.txt (JDK-17, bit-exact, 39 lines)

## VERIFIED THIS SESSION (until the budget-exhaustion spiral — do NOT rerun the
## many probes; the ONE at /tmp/opencode/smoke/corelibE.got.txt after the width
## fix was the source of truth and was 20/22+ long-block bit-exact)
- **ROOT CAUSE of the P6 Long truncation = `common/Emitter.java:531-532`**:
  `String home = R.width(pool, fp ? ptype : "i32"); String arg = R.width(argreg, fp ? ptype : "i32");`
  → hardcoded `"i32"` width made every 64-bit param-receive/arg reload a
  32-bit `w`-reg ldr, truncating all cross-call i64 to low-32.
- **FIX (in working tree, VALIDATED bit-exact vs JDK for Long/String.parseLong):
  replace `fp ? ptype : "i32"` with `ptype` (both lines).**
- After fix: `Long.parseLong("1234567890123")`=1234567890123, toString len=19,
  `Long.MAX_VALUE`=9223372036854775807 — all == JDK (was 1912276171/10/4294967295).
- `bits.mj` pure-ops probes bit-exact; Integer/Math/Arrays/System all match.

## OPEN (documented, pre-existing — miniJ deviation, NOT a regression)
- `java.util.Random(1L)` first `nextInt()` prints `0` vs JDK `-1155869325`, and
  nextInt(bound) is modulo (JDK uses rejection sampling). natives.c LCG natives
  are correct; the first-draw seed-advance pairing differs. Keep
  corelib/java/util/Random.mj at HEAD (the bit-exact JDK-identical LCG) — do
  NOT reintroduce the natives-version unless you re-validate against JDK 17.

## DO NOT
- Do NOT run `git checkout HEAD -- examples/corelib.mj` — it's UNTRACKED;
  checkout ignores it, the file stays, but the command errors confusingly.
- Do NOT re-stash (working tree is the only copy of the Emitter fix).
- Random natives grep: natives USE names k_native_Random_* (not
  k_native_java_util_Random_*), so a Broad grep returns empty — that's not
  evidence of removal.

## NEXT BEST ACTION (single step, then commit)
1. `git add examples/corelib.mj corelib docs rules common` 
2. `git commit -am "P6 corelib: Emitter param-receive width (i64 bit-exact vs JDK12); examples/corelib.mj+golden"` (author kivanov)
3. `git push origin import:master` (docs author krasgit)
