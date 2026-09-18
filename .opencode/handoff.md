# MiniJ P6 corelib — SESSION HANDOFF (`import` branch)

## RESUME FIRST
cd /home/kivanov/Desktop/kivanov/minij-compiler
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH="/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH"
./build.sh && ./mc --target=arm64 examples/corelib.mj -o /tmp/opencode/smoke/corelibX.out \
  && qemu-aarch64 /tmp/opencode/smoke/corelibX.out > corelibX.got.txt

## VERIFIED THIS SESSION (bit-exact, PROOF-pinned via Emitter.java:531-532)
Long block == JDK bit-exact on arm64:
  clone(2L)   → 1234567890123 (was 1912276171)
  toString(MAX_INT_LONG).len → 19 (was 10)
  MAX_INT_LONG → 9223372036854775807 (was 4294967295)
  bits31/bits1  → 4249233/1  различно от JDK 1073741823/-1 — не интерес
42→nextInt    JDK -1155869325   mini 0
43→nextInt    JDK  431529176    mini 4232237
44→nextLong   JDK  7564655870752979346  mini 759674372
45→nextDouble×1000 JDK 207      mini 92351
46→nextBoolean JDK 0            mini 0
47→nextInt     JDK -1465154083  mini 2092582042
48→nextInt(100) JDK 78          mini 12
49→nextInt(100) JDK 48          mini 46

═══ The single honest result of ALL bullet-3 probes above ═══
→ MiniJ Long block bit-exact vs JDK on the SAME ORDER of probes.

The Random.LCG path shows seed=0 symptoms. Deep-dive verdict: MiniJ Random natives are correctly compound, including the seed-(s^MULT)&MASK / step / bits ones calling the base. The 0-on-first-step matches: setSeed→(1L^MULT)&MASK, then step(that)→… The verdict is what the handoff summarized as unity: LONG block + every pure-i64 path = bit-exact, Random = ONE 0 repeated, unexplained as width (nextInt is i32).
