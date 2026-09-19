# MiniJ Core Library — Договор (Draft, ~P6)

> **Положение**: API-огледало на `java.base` — javadoc-съвместими сигнатури/семантика,
> но **собствена имплементация** (без копиране на OpenJDK source). „Огледало, не пълен JDK“.

## Статус
- **DRAFT код** — файловете под `corelib/` не се компилират още от MiniJ (изчакват фазите, посочени в header-а им).
- Целта на draft-а: фиксира API повърхността, `@native` ABI-то и редът на включване, за да диктува дизайна на P0–P5.
- **АКТУАЛНО (P6/M8, DONE):** `examples/corelib.mj` компилира и върви в harness
  (`scripts/run_tests.sh`, 33/33). Long/Integer/Math/Arrays/System **bit-точни vs JDK-17**;
  **Random — бит-идентичен с JDK-17** (чист MiniJ, без natives).

## Файлове
| Файл | Клас | Изисква фаза |
|---|---|---|
| `corelib/java/lang/Object.mj` | Object | P0 (native), P3 |
| `corelib/java/lang/PrintStream.mj` | PrintStream | P1 (long), P2 (I/O), P3 |
| `corelib/java/lang/System.mj` | System | P2, P3 |
| `corelib/java/lang/String.mj` | String | P2 (char[]+bounds), P3, P5 |
| `corelib/java/lang/Integer.mj` | Integer | P1, P3, P5 |
| `corelib/java/lang/Long.mj` | Long | P1, P3, P5 |
| `corelib/java/lang/Math.mj` | Math | P1 (FP+long), P3, -lm |
| `corelib/java/util/Random.mj` | Random | P1, P3, P5 |
| `corelib/java/util/Arrays.mj` | Arrays | P1, P2, P3, P6 (конкат) |

## `native` ABI (да се фиксира в P0)
- `native` keyword (Janino вече го парси) → AstLower генерира детерминиран символ: `k_native_<Class>_<name>_<arity>`.
- Аргументи/return по нашия ABI (int→wregs, long→x-pairs, float→d/xmm, String/масив→домашен header layout от P2/P3).
- Реализациите живеят в `corelib/natives.c` (gcc, `-lc`, `-lm`), registry: `{name, signature, fn}`.

## Контракт (резюме на повърхността)
- **System**: `out/err` (PrintStream), `exit(int)`, `arraycopy(int[]|char[]|long[]|boolean[]|double[], …)`,
  `currentTimeMillis()`, `nanoTime()`.
- **PrintStream**: `print|println(int|long|boolean|char|String)`, `println()`.
- **String** (върху `char[]`, copy-семантика): `length/charAt/equals/compareTo/indexOf(int|String)/substring/concat/
  contains/startsWith/endsWith/toCharArray/replace/trim/toLowerCase/toUpperCase/isEmpty/valueOf(...)/toString`.
- **Integer**: `parseInt/toString/valueOf/intValue/equals/hashCode/MIN_VALUE/MAX_VALUE`.
- **Long**: същото за long (`parseLong/toString/valueOf/longValue`).
- **Math**: `abs/min/max` (4 типа), `sqrt/pow/floor/ceil/exp/log/sin/cos/tan` (native→libm),
  `round(float|double)`, `PI`, `E`, `random()` (през Random).
- **Arrays**: `fill(5 типа)`, `sort(int[]|long[])` (quicksort, ascending), `binarySearch` (контракт `-(insertionPoint)-1`),
  `copyOf(4 типа)`, `equals(int[]|long[])`, `toString(int[]|long[])`.
- **Random**: JDK-идентичен 48-bit LCG (`0x5DEECE66D`/`0xB`) — **бит-идентичен с JDK-17**
  от `Random(1L)` (проверено с oracle). Чист MiniJ (маската `0xFFFFFFFFFFFF` = литерал
  `281474976710655L`), **без natives**; `nextInt(bound)` = JDK rejection sampling;
  `nextInt()/nextLong()/nextFloat()/nextDouble()/nextBoolean()/setSeed(long)`.

## Отворени места (TODO)
- **P5**: `parseInt/parseLong` throw-ват `NumberFormatException` при невалиден char
  (напр. празен низ/'_' след '0x'); невалиден `nextInt(bound)`/`setSeed` — `IllegalArgumentException`
  контрактът се покрива само частично.
- **P3**: `Object.toString()` разчита на class table (`Object.typeName`) + identity hash.
- **FP (M8-T1 ост.)**: `Math.exp/log/sin/cos/tan` (native→libm) — не-блокер за P6;
  някой от clip-адъра на libm не е в harness отделно.

## Тестове (когато тръгнат)
- `examples/corelib/*.mj` + `run_tests.sh` — очакван печат.
- Random: фиксиран seed срещу известна JDK-референция (бит-съвместимост).
  **DONE** — `examples/corelib.mj` (8 draw-а + `nextInt(bound)`) и `examples/bitop.mj`
  (shift/bitwise) са бит-идентични с JDK-17 oracle (в harness, 33/33).
- Edge-case: `parseInt` невалиден вход (P5), `binarySearch` точка на вмъкване, `trim`/`replace`.

## P7 — Thread/Concurrency core lib (по-нататък)
След P6 core lib влиза и concurrency слой в `java.lang` / `java.util.concurrent`:
- `java.lang.Thread`, `java.lang.Runnable`, `java.lang.ThreadLocal`.
- `Object.wait()/notify()/notifyAll()` (inflated monitor от header).
- `java.util.concurrent.locks.ReentrantLock`, `Condition`.
- `java.util.concurrent.Semaphore`, `CountDownLatch`.
- `java.util.concurrent.atomic.AtomicInteger/AtomicLong/AtomicReference`.
- `volatile` + бариери: `dmb ish` (ARM64), `lock`/`mfence` (x86).
- Memory model: happens-before за lock/unlock + volatile (JMM-подобен, не пълен).

## Лиценз/произход
Имплементация систематична по javadoc контракт — без копиране на OpenJDK код.