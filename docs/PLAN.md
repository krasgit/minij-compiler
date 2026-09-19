# MiniJ Milestone Plan

> Коригиран план за AI-агент. Source-кодът е истина; docs се сверяват редовно (виж
> `Current Reality Check`). Всички твърдения без изричен code-proof се третират като
> бележка/риск, не като факт.

## Императив: ОБНОВЯВАЙ ТОЗИ ФАЙЛ при прогрес

Този файл е жив източник на състояние. При ВСЯКА значима промяна:
- Започнеш milestone → премести `STATUS: TODO` → `IN_PROGRESS`.
- Завършен milestone/задача → **`STATUS: DONE`**, обнови regression брой
  (N/N) и HEAD, премести завършеното в секцията «Progress Log» долу **в същия commit**.
- Нова информация (AST shapes, layout, правила, бъгове) → 1-2 реда в
  «Pipelines-факти»/«Решения», за да не се налага повторно проучване.
- Сблъсък source↔docs → обнови бележката в «Current Reality Check».
- `NEXT MOVE` остава първа четима секция при resume ("continue").

**Commit конвенция (T13):** `P{phase}: описание; N/N regression` (както в CONTEXT.md).

**Definition of Done (T14, от конвенцията в CONTEXT.md):**
- Експерименти/probes (Janino AST, edge-тестове) се правят в `/tmp/opencode` — НЕ в repo.
- Финалните продукти отиват в `examples/*.mj` + harness + docs + commit+push.
- Regression-броят се актуализира (N/N → M/N) при всяка промяна на примерите.

---

## Status Legend

- ✅ **DONE** — завършено, потвърдено от code+regression.
- 🚧 **IN PROGRESS** — активно.
- ⬜ **TODO** — не е започнато.
- 🔒 **BLOCKED** — чака решение/зависимост.
- ⚠️ **PARTIAL** — частично (например API-draft без impl).

---

## Milestone M0 — Docs-синхронизация и housekeeping

**Goal:** Нула остарели пътища/граматики в docs; harness-състоянието е изрично
документирано; repo-конвенциите са приведени в ред.

**Status:** ⚠️ PARTIAL (T1/T4/T9 зaвършени; T2/T5/T6/T7 отворени; T8 спорен — `.iml`
вече са committed, не untracked)

| ID | Задача | Description | Files | Depends | Done when | Verification |
|---|---|---|---|---|---|---|
| M0-T1 ✅ | CONTEXT.md пътища | Замени `/shared/compiler`/Termux-течения с текущия repo + Debian/Maven фактите. | `docs/CONTEXT.md` | — | Няма остарял път/инструмент | `grep -r "/shared/compiler" docs/` празен |
| M0-T2 ⬜ | grammar.md ъпдейт | `.mj` grammar покрива само `int\|void` (P0). Добави масиви, String, класове/обекти, long/double, control. | `docs/grammar.md` | — | Grammar съответства на `examples/{obj*,.cflow,.md}` | Ръчна сверка с examples |
| M0-T3 ⬜ | MIGRATE §4 x86-бележка | gcd(48)/forloop(hang) са от **преди** spillStore fix-а (`41b4381`); фактическото x86 състояние подлежи на проверка (в момента само textual x86, без асемблер). | `docs/MIGRATE.md` | — | Бъдещата x86-статия е помечена като пре-фикс | Ръчен преглед |
| M0-T4 ✅ | Harness-бележка | 29-теста `run_tests.sh` са били в `/tmp/opencode` и НЕ са в repo (репото има само `test.sh` с 4 теста). Baseline за feature-milestones; възстановяване като `scripts/run_tests.sh` при първия feature. | `docs/PLAN.md`, `docs/CONTEXT.md` | — | Изрично документирано + pointer | grep |
| M0-T5 ⬜ | Progress Log раздел | Добави дневник формат (дата / milestone / HEAD / regression / commit) + запълни историята P0→P6 от CONTEXT.md. | `docs/PLAN.md` | — | Логът съдържа миналите milestones | Ръчен преглед |
| M0-T6 ⬜ | ir-format.md: нови IR блокове | Документирай `.vtables` (P3 obj6), `.statics` (P3 obj7, сега и с const-инициализатори — P6) и ops `vt_ref`/`icall`/`lea_static`/`alloc_obj`/`lea_field` — липсват. | `docs/ir-format.md` | — | Всички ops, които Writer/Reader поддържат, са документирани | `grep vt_ref docs/ir-format.md` |
| M0-T7 ⬜ | rule-format.md: args.../return | Добави `args...` pattern (`${cargs}` loop-list, base=1) и void `return()` rules (arm `mov x0,#0; b exit` / x86 `movl $0,%eax; jmp exit`). | `docs/rule-format.md` | — | Документирано v2 + нови плейсхолдъри | `grep args. docs/rule-format.md` |
| M0-T8 ⚠️ | .gitignore: .iml | `minij-compiler.iml`/`tools.iml` вече са **committed** в `31db14d` — решени: да останат tracking или `git rm --cached` + `.gitignore *.iml`. | `.gitignore` | — | Изборът записан | `git status` |
| M0-T9 ✅ | CONTEXT авто-HEAD | Императивът вече казва да се обновява HEAD/Status; да се изпълни при следващия commit. | `docs/CONTEXT.md` | — | HEAD/Status актуални | `git log -1 --oneline` |
| M0-T10 ⬜ | CI (optional) | GitHub Actions: x86-64 нативен + arm64 през qemu-aarch64, N/N — ако е необходимо. | `.github/workflows/ci.yml` | M0-T4 | CI зелено на push | Actions статус |

**Dependencies:** M0-T1..T4, T8 независими; T5/T6/T7/T9 след смисловите docs.

**Milestone Completion Criteria:** nяма остарели пътища в docs/; harness-статусът е
изричен в PLAN/CONTEXT; `.gitignore` чист.

---

## Milestone M1 (P0) — Инфраструктура

**Goal:** `.rule v2`, типизирани ops, native контракт, контрол (do/ternary/switch),
runtime skeleton (crt0, mm_alloc, puti/puts/exit), `-lc` link, Maven wrapper.

**Status:** ✅ DONE

**Бележки за синхрон:**
- Baseline P0: 8/8–12/12 (arm64) — тези числа са исторически; актуалната регресия e 29/29 (виж M6).
- `.rule v2` (`subs/fregs/fargs/fret`), RuleParser `args...`, Emitter `${cargs}` — изпълнени.

---

## Milestone M2 (P1) — Типове

**Goal:** long/double, native, типизиран SSA; документирани решения за малките типове.

**Status:** ✅ DONE (с корекции на документ-твърденията)

**Корекции (code = истина):**
- 🚩 `byte/short/char/boolean → i8` **НЕ е вярно**: `AstLowerMain.java:94` мапира всичките
  на **`i32`** (widening, без i8-storage). Записва се като дизайн-решение, не оставаща задача.
- 🚩 `i64 sign-extend при i32↔i64 retype` — **DONE**, не „останало“: `MOVSXT_i64` в
  `arm.rule:219` (`sxtw`) и `x86.rule:286` (`movslq`), използван в `AstLowerMain:310-311`.
- 🚩 `f32` — **не е планиран отделен тип**: `float→f64` coercion (`AstLowerMain:97`).
  Бележка за corelib Math (Math.round(float) ще мапира към f64, ако изобщо се прави).

**Milestone Completion Criteria:** гореизброените решения записани в PLAN/CONTEXT;
без TODO за i8/f32.

---

## Milestone M3 (P2) — Памет/масиви/String

**Goal:** Heap през mm_alloc, масиви (вкл. multi-D), stack spill, char[] String,
System.out, String methods.

**Status:** ✅ DONE

**Корекции:**
- 🚩 `String.equals/concat` беше в „Останало“ — **DONE** (commit `b9e3499`, 18/18 — str3.mj).
  Останали от P2: **само GC seam + TLS allocator → преместени в M5/M9** (не са impl-нати;
  `runtime.c` `mm_alloc` е bump 16KB с игнориран `kind`, без TLS — `runtime.c:125-140`).

---

## Milestone M3.5 — Runtime-памет без crash (NEW)

**Goal:** Дълги програми (вкл. corelib-низови цикли) да не изчерпват bump-arena-та.

**Status:** ⬜ TODO (рискът е потвърден жив: `String.toString`/`concat` алокират свеж
char[] на всеки call; `examples/corelib.mj` в момента минава — кратките му цикли
събират <16KB — но дългите низови run-ове пак могат да ударят 16KB bump-arena)
**Trigger verification (31db14d):** `examples/corelib.mj` → компилира, линква
(natives.o), exit 0 — `5/7/3/4/…`, виж Progress Log. OOM-тестът от преди
(`App.mj` i<=100000 + string literal) НЕ е в harness.

| ID | Задача | Description | Files | Depends | Done when | Verification |
|---|---|---|---|---|---|---|
| M3.5-T1 | 16KB bump-arena exhaustion (T1) | `MM_HEAP_SIZE` е 16KB (`runtime.c:126`); string literals се пре-разпределят per-loop-iteration. Решение: (a) **string literal interning/pool** в `.data` (AstLower) ИЛИ (б) heap-растеж/arena bump. | `runtime/runtime.c`, `tools/ast-lower/AstLowerMain.java`, `rules/{arm,x86}.rule` | M0-T4 (baseline) | `for(i..100000)` + `println("hello1 ")` не крашва, N/N | Loop-пример + много итерации; стар `App.mj` 100000 |
| M3.5-T2 | String layout единствен източник (T2) | `k_string_equals/concat` хардкодят header (8-byte, len на 0) + i32 cells при +8 (`runtime.c:79-81,95-121`) — дублиран layout между компилатор и runtime. Централизирай констати/comment, `static_assert` или документирай в ir-format. | `runtime/runtime.c`, `docs/ir-format.md` | M3.5-T1, M0-T6 | Layout описан еднократно, runtime чете по него | Ръчна сверка; str/str2/str3 примери |

**Dependencies:** M3 (масиви/String), M0-T4 (baseline за verify).

**Milestone Completion Criteria:** дългопрограмни тестове без OOM; String-layout
документиран. Това е **блокер за M8 (corelib)** — низовете в дълги run-ове.

---

## Milestone M3.6 (P2.5) — Пакети / import (NEW)

**Goal:** Проект от няколко `.mj` файла — `package`, `import` (single-type/on-demand/
static), транзитивен closure-load на модула. **Тоя milestone НЕ беше в първоначалния
PLAN — добавено по факт.**

**Status:** ✅ DONE (`examples/imports/imports.mj`; **31/31**; commit `60c6ddb`)

**Синопсис (подробности в CONTEXT.md):** `ast-lower` зарежда затварянето (closure) на главния
файл до фикс-точка (`addUnit/loadClosure/processImports/loadClassFile/loadFromPath/
findFile/resolveRefs/onDemandPkgs/walkRefs`); flat simple-name namespace; `norm()`
свежда dotted Java-тип до просто име навсякъде (с `[]`-суфикси); static import →
`importedStaticMethod/importedStaticField`. Source roots: dir на входния файл + `-I`
(`mc`) / `--src` (`ast-lower`). Попътно: `mc` линква с **`-no-pie`** (PIE +
`R_AARCH64_RELATIVE` vtable relocations segfault-ваха под qemu).

---

## Milestone M4 (P3) — Обекти

**Goal:** classes, fields, new, методы/overloads, достъп, ctors/this, instanceof/cast,
наследяване/super, vtable/override, static полета, super.method/field, Foo[], void.

**Status:** ✅ DONE (obj…obj10; 28/28 → 29/29 с M6)

**Корекции/бележки:**
- 🚩 Объект header = **само class-index + pad/GC bits слот** (`AstLowerMain` comment,
  `st_hdr`) — **НЕ съществуват monitor/bias bits, trace-tables, GC maps, safepoints**
  (grep в source е празен по темите). Те се отнасят към M5/M9.
- vtable-ите трябва да са в `.data` (не `.rodata` — `R_AARCH64_RELATIVE` segfault);
  статиката в `.bss`. Записано като PIPELINE-факт.

---

## Milestone M5 (P3.5) — GC решение + seam

**Goal:** GC по **MMTk-архитектура**, но **собствена C имплементация** в runtime
(без Rust FFI) + липсващия seam (trace-tables/kind); не се твърди че е готов.

**Status:** 🚧 IN PROGRESS (решение взето: MMTk-подобен, C, собствен)

| ID | Задача | Description | Files | Depends | Done when | Verification |
|---|---|---|---|---|---|---|
| M5-T1 | Избор записан ✅ | **(B') MMTk-архитектура, собствен C** — mark-space + generational/immix-идеи от MMTk, без Rust `VMBinding` FFI. Записва се в PLAN/CONTEXT. | `docs/PLAN.md`, `docs/CONTEXT.md` | — | Избор записан като **B'** | Ръчен преглед |
| M5-T2 | Seam: trace-tables + kind | Per-class reference битмапи от vtable/classFields; `mm_alloc` ползва `kind` (сега игнориран) → space/trace-класификация. | `common/Emitter.java`, `tools/ast-lower/AstLowerMain.java`, `runtime/runtime.c`, `rules/*.rule` | M5-T1 | Reference bitmap per клас; kind записан в header | OOM/цикл → GC flush |
| M5-T2.1 | Seam: safepoints + STW hook | Emission на safepoint-check в цикли/backedge; STW механизъм (спиране на нишките по време на GC). | `common/Emitter.java`, `rules/*.rule`, `runtime/runtime.c` | M5-T2 | safepoint poll в пролози/цикли | GDB pause-тест при GC |
| M5-T3 | GC имплементация (C) | Имплементирай MMTk-модел в чист C: bump spaces + mark-sweep/immix-ни to-space, tracing през M5-T2 битмапи, stack scan по frame pointers, TLS mutator (за M9). | `runtime/runtime.c` (+ `runtime/gc.c` new) | M5-T2, M5-T2.1, **T12/TLS mutator бележка** | Дълги цикли без leak/arena exhaustion; 29/29 | Heap-стрес тест + N/N |

**Dependencies:** M3 (header/mm_alloc), M4 (vtable за trace-tables), M0-T4 (baseline),
T12 (mm_alloc е single-thread bump — TLS mutator е M9 dependency).

**Milestone Completion Criteria:** циклични/тuning тестове без leak (вкл. пре-alloc stress);
и двата таргета N/N. (B' seam-ът е reuse-able за P7 threads×STW.)

---

## Milestone M6 (P4) — Контрол

**Goal:** tableswitch/lookupswitch, enhanced-for, break/continue с label, switch-изрази,
short-circuit &&/||, compound, ++/--, assignment-as-expression.

**Status:** ✅ DONE (`cflow.mj`; **29/29**; commit `be8233d`)

**Бележки:** хармонизира се с baseline на 29/29; x86-64 пре-верификация = бележка M0-T3/T4.

---

## Milestone M7 (P5) — Изключения

**Goal:** `throw`, `try/catch/finally`, runtime unwinding, Exception + subclasses.

**Status:** ✅ DONE (`examples/exc.mj`; **30/30**; commit `961995e`)

**Синопсис (подробности в CONTEXT.md):** ops `EH_LAB`/`FP`/`EH_EXC`, func-метаданни
`.ehvar/.ehcatch/.ehfin/.ehsrc`; per-try dispatcher в края на функцията с frame-restore
**преди** match (иначе stale `exc_head` → безкраен re-catch); runtime `ExcRec` pool +
`exc_head` + `k_throw` chain-walk (uncaught → `#<classIndex>`, exit 3); frontend
`ExcGuard`/`unwindGuards()` за return/break/continue + finally и по трите пътя + `ehf`
re-throw. Core Exception класове (`NumberFormat/IllegalArgument/NullPointer/
ArrayIndexOutOfBounds/RuntimeException`) вече съществуват в `corelib/java/lang/*.mj`
от P6 — виж M8.

---

## Milestone M8 (P6) — Core lib

**Goal:** Компилируема `corelib/` (API-огледало на java.base) + native/libm + tests.

**Status:** ✅ DONE — **ядрото компилира и върви в harness (33/33)**:
`examples/corelib.mj` → exit 0, детерминиран изход; Long/Integer/Math/Arrays/System
**bit-точни vs JDK-17**; **Random — бит-идентичен с JDK-17** (чист MiniJ LCG, root-cause:
DCE изтриваше void virtual call-ове в ctors → seed stuck на 0). Нов `examples/bitop.mj`
(shift/bitwise, `>>>`, &31/&63 masking, compound) — бит-идентичен с JDK oracle.

| ID | Задача | Description | Files | Depends | Done when | Verification |
|---|---|---|---|---|---|---|
| M8-T1 ✅ | Езикови празнини | `<< >> >>> & | ^ ~` end-to-end (frontend mapOp + shift masking &63/&31, compound `<<= >>= >>>= &= |= ^=`, `~`), `long >>>` (LSR_i64), `0x…`/`0b…`/underscore literali (parseLongSmart), FP ops (Math natives). Остатък: FP ops `exp/log/sin/cos/tan` + Минифолд не-фолдваните константи не е блокер. | frontend/rules | M2, M3 | corelib .mj компилират | `mc corelib/... --stage=exe` |
| M8-T2 ✅ | natives.c + mc build (T6) | `runtime/natives.c` (System exit/currentTimeMillis/nanoTime/arraycopy, Math sqrt/pow/floor/ceil → libm); Random natives ПРЕМАХНАТИ (LCG е чист MiniJ). `mc` компилира natives.o и линква `-lc -lm`. | `runtime/natives.c`, `mc`, `build.sh` | M8-T1, M2 (native) | Math (sqrt/pow/…) работи | Math пример (`examples/corelib.mj`) |
| M8-T3 ⚠️ | java.lang impl | System (out/err frontend-special + 4 natives), Object/PrintStream playholders, String methods (equals/concat от P2, toString де факто през char[]), Integer/Long/Math **bit-точни**. | `corelib/java/lang/*.mj` | M8-T1, M8-T2 | smoke тестове | corelib smoke |
| M8-T4 ✅ | java.util impl | Arrays (fill/sort/binarySearch/equals/toString), Random (48-bit LCG JDK-бит, **чист MiniJ**: rejecton sampling за `nextInt(bound)`, маската като литерал). | `corelib/java/util/*.mj` | M8-T3 | Random+Arrays вървят | seeded Random сравнение |
| M8-T5 ✅ | Тестове в harness | `check corelib 0 "<JDK-точни стойности>"` + `check bitop 0 "<oracle>"` в `scripts/run_tests.sh` → 33/33. | `scripts/run_tests.sh`, docs | M8-T4, M0-T4 | N/N и двата таргета | harness |

**Документирани остатъци (NOT bugs):**
1. ~~`Random(1L)` — първият `nextInt()` дава `0`~~ — **РЕШЕНО** (root-cause DCE:
   `icall`/`ICALL_*` не бяха rooted в `common/Opt.java` → `setSeed()` (void virtual call
   в ctor-а) се махаше като dead code → seed 0). След fix: `-1155869325/431529176/
   7564655870752979346/…` = JDK-17 **бит-идентичен** (8/8 draw-а + Arrays/Math).
2. ~~`nextInt(bound)` е modulo~~ — **РЕШЕНО** с JDK rejection sampling (детерминиран, bit-точен).
3. `System.arraycopy` е native `memcpy/memmove` без range-check (`ArrayIndexOutOfBounds`).
4. Shift-fold в static init (`(1L<<48)-1`) НЕ се fold-ва — Random ползва литерал-маска
   `281474976710655L` (без рантайм последица).
5. FP ops `exp/log/sin/cos/tan` (Math natives) — не-блокер за P6, опр. се появяват в M11/M12.

**Blocking бележка:** дългите низови цикли пак зависят от **M3.5-T1** (16KB bump
arena). Текущите corelib примери събират <16KB и минават.

**Milestone Completion Criteria:** програма (String+Integer+Math+Arrays+Random) се
компилира и изпълнява; добавена в harness; N/N и двата таргета.

---

## Milestone M9 (P7) — Threads

**Goal:** 1:1 OS threads, synchronized, volatile, atomics, j.u.c подмножество.

**Status:** ⬜ TODO. **Широк → под-милстоуни:**

### M9.1 — Threads kernel
`java.lang.Thread`/`Runnable`: start/run/join/sleep/interrupt(+InterruptedException)/
currentThread/getName/isAlive/setDaemon. Runtime 1:1 pthread, per-thread stack от
mm_alloc, **TLS** (currentThread + allocator mutator + safepoint flag).

### M9.2 — Sync/volatile
monitorenter/exit; thin lock → inflated monitor (+ wait/notify/notifyAll); synchronized
метод+блок; volatile load-acquire/store-release; бариери — ARM64 `dmb ish`, x86 `lock`/`mfence`.

### M9.3 — j.u.c + atomics
`AtomicInteger/Long/Reference` (CAS — `cmpxchg` / `ldxr`-`stxr`); ReentrantLock
(fair/non-fair), Condition, Semaphore, CountDownLatch, ThreadLocal<T>.

### M9.4 — GC × STW
Safepoints за всички нишки, per-thread root set scanning. **Изисква M5 (GC).**

**Dependencies:** M5 (GC×STW), M7 (InterruptedException), M4 (header monitor bits),
**T12 (TLS allocator mutator — сега mm_alloc е single-thread bump)**, M0.

**Memory model (документирано):** JMM-подобен, не пълен JDK.

---

## Milestone M10 (P8) — Modern Java

**Goal:** Generics, Records, Enum, Lambdas, var/switch/text blocks/pattern matching.

**Status:** ⬜ TODO — long-term, зависи от стабилност на M0–M9.

- **M10.1** Generics → type erasure (parse/analyze, raw types, bridge methods).
- **M10.2** Records (synthesized ctor/accessors/equals/hashCode/toString).
- **M10.3** Enum (static instances, ordinal/values/valueOf, switch).
- **M10.4** Lambdas + функционални интерфейси (desugar → анонимен клас).
- **M10.5** `var`, switch-изрази, text blocks, pattern matching за instanceof.

**Dependencies:** M4 (classes), M6 (control), стабилност от M0–M9.

---

## Design Decisions (актуализирани по source)

- **Object header** = **class-index + pad/GC слот (T11)** — НЕ monitor/bias (идва M5/M9).
  P3-текстове обещаваха повече → коригирани.
- **GC (M5, решение B')**: **MMTk-архитектура, но собствена C имплементация** — bump spaces,
  mark-sweep/immix-идеи, tracing през per-class битмапи, safepoints+STW, TLS mutator за M9.
  Без Rust `VMBinding` FFI.
- **`mm_alloc`** = bump arena 16KB, `kind` игнориран, **без TLS (T12)**; TLS mutator е
  dependency за M9 (threads) и M5 (GC).
- **Малки типове** → `i32` widening (`AstLowerMain:94`); **float → f64** (`AstLowerMain:97`);
  **i32↔i64** sign-extend — `MOVSXT_i64` DONE.
- **native ABI** `k_native_<Class>_<name>_<arity>` фиксиран (P0) — corelib/libm/pthread ползват него.
- **vtables в `.data`** (R_AARCH64_RELATIVE → не .rodata); статика в `.bss`.
- **P6 param-receive width (Emitter.java:531-532)**: при CALL param-receive/arg-reload
  ширината е **`ptype`**, не хардкод `"i32"` — старият `fp ? ptype : "i32"` трошеше
  всички cross-call i64/ptr до low-32 (Long/Integer parsing даваха грешни стойности).
- **P6 static const init**: `.statics` блокът поддържа 3-ти елемент `: value` —
  `AstLower.constNumericInit()` позволява константни числови инициализатори
  (`static long MAX_VALUE = 9223372036854775807L;`) → `.data` с `.long/.quad`;
  non-const init → `RuntimeException`.
- **P6 Random (финален)**: чист MiniJ, **без natives** (`runtime/natives.c` Random
  helpers-ите са премахнати). `next(48-bit)` = `(seed*5DEECE66D+0xB)&281474976710655L`
  (маската е литерал — static shift-fold не се прави); `nextInt(bound)` = JDK rejection
  sampling. **Бит-идентичен с JDK-17** за цялата последователност от `Random(1L)`
  (проверено: `-1155869325/431529176/7564655870752979346/207/0/-1465154083/78/48`).
  Root-cause на старото отклонение: **DCE bug** — `common/Opt.java` root-ваше
  `call`/`CALL_*`, но НЕ `icall`/`ICALL_*` → void-виртуални call-ове (напр. `setSeed()`
  в ctor-а) се изтриваха като dead code. Виж M8.
- **P6 natives.mc**: natives-ите се компилират като отделен `natives.o` и се линкват с
  `-lc -lm` директно в `mc` (без отделен `mc-corelib` build).

## Pipelines-факти (сверени, за да не се преоткриват)

- `mc` default target = **x86-64** (mc:5); `--target=arm64` за arm; `--outdir`, `--stage`, `--keep`.
- Pipeline: janino-parse → ast-lower → ssa-build → ssa-opt → ssa-lower → regalloc →
  phi-elim → emit (arm/x86) → as → link(program+crt0+runtime+`-lc`, gcc `-nostartfiles`).
- Всички нови ops се появяват автоматично през `Ir.Writer/Reader` (op-name suffix = тип); backend работа = само rules.
- Runtime: `putc/puti/puts`; P2 `k_print/k_println/k_print_i32/k_println_i32/k_newline`;
  `k_string_equals/k_string_concat`; OOB → `sys_exit(134)`; `mm_alloc` bump 16KB.
- Emitter template плейсхолдъри: `reg/valReg/${ws}/${args}/${argregs}/${params}/${imm}/${imov}/
  ${scratch}/${cargs}`; RuleParser `args...`; spill temps arm `w/x/d11..13`, x86 `%r11`.
- **P6 build/link**: `mc` компилира и `runtime/natives.c` (natives.o) и линква
  `-lc -lm`; `bin/emit-*` = общ `EmitMain --target=` (legacy `tools/emit-arm|emit-x86`
  изтрити); `.statics` има `init/value`.

---

## Execution Order

```
M0 (docs sync, PARTIAL) → M3.6 (P2.5 import ✅) → M3.5 (runtime-памет) → [ M5 (GC) ‖ M7 (P5 ✅) ]
→ M8 (core lib ✅ 33/33) → M9 (threads) → M10 (modern)
```

- **M5 и M7 са независими** — M7 е **DONE**; M5 (GC) остава свободен кандидат.
- **M8 (P6) е DONE** — corelib в harness (33/33); следващ кандидат: **M5 GC** или
  **M3.5 runtime-памет** (надвишаване на 16KB bump-arena).
- **M8 изисква M3.5-T1** (дълги низови програми) + M2–M7.
- **M9 изисква M5 (GC×STW) и M7 (InterruptedException).**
- Всички feature-milestones изискват M0-T4 harness baseline (✅ — scripts/run_tests.sh).

---

## Current Reality Check (source = истина)

1. **Harness е в repo (T4 DONE):** `scripts/run_tests.sh` — **33/33 PASS на arm64**
   (native aarch64 хост): corelib и bitop са включени (JDK-точни очаквани изходи).
   x86 remains textual-only (без x86 as/ld на тази кутия).
2. **P1-останало е фикция:** i8→i32 (`AstLowerMain:94`), MOVSXT_i64 DONE
   (`arm.rule:219`/`x86.rule:286`), float→f64 (`AstLowerMain:97`).
3. **P3.5 seam НЕ е готов:** няма trace-tables/GC maps/safepoints/монитори в source;
   header е само class-index(+pad) (`st_hdr`).
4. **P6 (M8) = DONE:** `corelib/` компилира и върви в harness; `runtime/natives.c` +
   `-lm` линк в `mc`; bit-точни Long/Integer/Math/Arrays/System; **Random бит-идентичен
   с JDK-17** (чист MiniJ + rejection sampling); shift/bitwise ops end-to-end (`bitop.mj`,
   JDK-идентичен). Progress Log актуален.
5. **M3.5 бъг (жив):** 16KB bump-arena → OOM при string-literal loops; corelib.mj
   минава защото събира <16KB; дългите низови run-ове пак рискуват.
6. **Git-статус:** преди следващия commit — HEAD `31db14d`; `tools/emit-arm/`,
   `tools/emit-x86/` изтрити (legacy, bin/emit-* ползват общ `EmitMain`); `.iml`
   committed (спор за .gitignore). Env: **native aarch64 хост**, /shared е
   **no-chmod/диск без exec** → build/test трябва да вървят от exec-копие
   (напр. `cp -r /shared/compiler → /tmp/opencode/minij`).
7. **Env:** Debian/arm64, JDK17; CONTEXT.md Termux-факти — остарели (M0-T1 done).

---

## Progress Log

_Формат:_ `дата | milestone | HEAD | regression | commit`

- `2026-09-19 | P6 corelib (M8, DONE — shift/bitwise ops, Random bit-exact JDK, bitop) | 31db14d → 2955350 | 33/33 | 2955350`
- `2026-09-18 | P2.5 packages/import (M3.6, DONE) | 60c6ddb | 31/31 | 60c6ddb`
- `2026-09-18 | P5 exceptions (M7, DONE) | 961995e | 30/30 | 961995e`
- `2026-09-18 | P4 control flow (M6, DONE) | be8233d | 29/29 | be8233d`
- `2026-09-18 | P3 void+static void main (obj10) | ee95a94 | 28/28 | ee95a94`
- `2026-09-18 | P3 Foo[] (obj9) | 1ac9d83 | 27/27 | 1ac9d83`
- `2026-09-18 | P3 super.method/field (obj8) | (HEAD тогава) | 26/26 | 1ac9d83`
- `2026-09-18 | P3 static полета (obj7) | 5a17f16 | 25/25 | 5a17f16`
- `2026-09-18 | P3 vtable dispatch (obj6) | 580c5fd | 24/24 | 580c5fd`
- `2026-09-18 | P3 extends/super (obj5) | 6f056f7 | 23/23 | 6f056f7`
- `2026-09-18 | P3 instanceof/cast (obj4) | ed21fa3 | 22/22 | ed21fa3`
- `2026-09-18 | P3 ctors+this (obj3) | 27d0ac9 | 21/21 | 27d0ac9`
- `2026-09-18 | P3 methods+overloads (obj2) | 8f11393 | 20/20 | 8f11393`
- `2026-09-18 | P3 classes (obj) | 081e7a2 | 19/19 | 081e7a2`
- `2026-09-18 | P2 String.equals/concat (str3) | b9e3499 | 18/18 | b9e3499`
- `2026-09-18 | P2 multi-D + spill (md) | e89d6f4 | 17/17 | e89d6f4`
- `2026-09-18 | P2 String/char/System.out (str) | b5f2736 | 16/16 | b5f2736`
- `2026-09-18 | P2 arrays | 824824d | 14/14→16/16 | 824824d`
- `2026-09-18 | P1 native | 60f704e | 12/12 | 60f704e`
- `2026-09-18 | P1 types | 318c431 | 9/9→12/12 | 318c431`
- `2026-09-18 | P0 infra | 8873729 | 8/8 | 8873729`

---

## Reference (изходен ROADMAP факт — не delete)

- Пълните низови детайли на P0–P4 (AST shapes, layout, order, правила) са в
  `docs/CONTEXT.md` и `docs/ROADMAP.md` — PLAN.md ги сочи; при конфликт PLAN/source
  кодът печели, конфликтът се отбелязва тук/в M0.