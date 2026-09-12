# MiniJ Roadmap — Full Java (P0–P8)

> Актуализирано след взети решения: GC-agnostic от P2, `java core lib` база (API-огледало на `java.base`), Thread/Concurrency фаза.

---

## P0 — Инфраструктура
- `.rule v2`: `pairs/subs` (32/64-битови алиаси), `fregs/fargs/fret` FP пулове, 64-bit адресен пул.
- Типизирани ops (`ADD_i64`, `FADD_f64`, `LOAD/STORE`, `LEA`, sign/zero extend, `I2FP/FP2I`…), regalloc по тип.
- `native` keyword → `k_native_<Class>_<name>_<arity>` AST/IR контракт (за corelib `@native`).
- Emitter SKIP set → `block/symbol/undef/phi` (STORE/LOAD/LEA реални).
- Контрол: `do-while`, ternary, basic `switch` (JCC верига).
- Runtime skeleton: `runtime/` (crt0, `mm_alloc` bump-arena seam, `putc/puti/puts/exit`, libc).
- Линк: програма + runtime.o + `-lc`, gcc host.

> **Статус (P0 core изпълнено):** `.rule v2` (`subs/fregs/fargs/fret`) + width-резолюция в
> Emitter; Regalloc с FP пул и споделен stack slot; `rules/{arm,x86}.rule` → 64-bit пул + subs;
> AstLower: `do`/ternary/`switch` (JCC); `runtime/` (crt0.S, runtime.c: `putc/puti/puts`,
> `mm_alloc` bump seam); **`native` контракт** `k_native_<Class>_<name>_<arity>`; **`-lc` линк**
> в `mc` (gcc `-nostartfiles`); 8/8–12/12 регресия на arm64, baseline байт-идентичен с оракула.

## P1 — Типове
- long (i64), byte/short/char/boolean → i8 + sign/zero extension.
- float/double: FP пул, FP ops, FP ABI (d0–d7 / xmm), FP constants.
- SSA constant folding за нови типове, `params()` по тип.

> **Статус (P1 — long/double изпълнено):** типизиран фронтенд (AstLower: `mapType`,
> `conv()` ITOF/FTOI/ITOF_64/FTOI_64/retype, `methodRet` таблица, typed alloca/store/load/
> param/call/return, литерали за `L`-long и FP — битове в `imm`); typed SSA
> (phi/undef през `nameTypes`); type-aware SSA-lower (ADD_i64/ADD_f64/CMP_*/CALL_*/RETURN_*/...);
> `Param`/call аргументи по тип; FP пул (`fregs/fargs/fret`, `d19–d28`/`%xmm8-15` saves в
> prologue за call-безопасност), literal pool (`adrp+ldr` arm / `movabsq`+`movsd(%rip)` x86),
> `${scratch}` и `${ws}[i]` плейсхолдери. Примерка: `native` контракт
> `k_native_<Class>_<name>_<arity>` (+ `MOV_i32` retype през `${x:sub32}`), `-lc` линк през
> `gcc -nostartfiles … -lc` в `mc`, `.p2align 3` в literal pool. Примери
> `dbl.mj`/`lng.mj`/`mix.mj`/`native.mj`; регресия **12/12 (arm64)**, твърд x86 oracle refresh.
> Останало: byte/short/char/boolean → i8 + sign/zero extension; i64 sign-extend при i32↔i64
> retype; f32.

## P2 — Памет / масиви / String
- Heap през **`mm_alloc`** (GC-agnostic seam, без GC).
- `new T[n]`, `a.length`, bounds-check, `ALOAD/ASTORE`.
- `char[]` String (lean, copy-семантика), `System.out.println/print`.
- TLS-ready `mm_alloc` дизайн (per-thread allocator pointer — за P7 threads).
- `java.lang` mini-core (System.out basics, lean String).

> **Статус (P2 — масиви + char[] String + System.out).** Масиви: layout 8-байтов header
> (32-bit дължина, горните 32 бита 0 от bump arena) + data от +8. Frontend (AstLower):
> `mapType("T[]")→ptr`, `elemOf` (int[]→i32, long[]→i64, double[]/float[]→f64,
> `T[][]…`/`String[]`→ptr) + `javaTypeOf` + `elemOfAccess`,
> `new T[n]`→`alloc_<el>`+`st_hdr`, `a.length` (AmbiguousName с n=2)→`load`+`len`,
> `a[i]` read→`chk`+`lea_<el>`+`ld_<el>`, write→`chk`+`lea_<el>`+`st_<el>`. Нови ops
> `alloc_i32/i64/f64`, `st_hdr`, `len`, `chk`, `lea_*`, `ld_*`, `st_*` минават безпроблемно
> през SSA/canon/ssa-lower (ptr→i64-width). Bounds-check `chk` → `sys_exit(134)`.
> Regalloc фиксове: void ops (chk/st_*) участват в last-use на аргументите си; PHI аргумент
> се брои за ползван в края на predecessor-а; стойности дефинирани извън цикъл, но четени
> в него, се удължават до края на цикъла. `MOVSXT_i64` sign-extend при int→long retype;
> ARM i32 consts през `movz/movk` (`${imov}`). String/char: `String`=lean `char[]`
> (header len + 4-byte cells, copy-by-reference), `char`=i32; StringLiteral/CharacterLiteral
> от Janino са **суров текст с кавички и не-декодирани escapes** — `decodeString` сваля
> кавичките и декодира (`\n \t \0 \" \\ \uXXXX`); литералът алокира свеж char[] (alloc+st_hdr+
> unrolled lea/st). `System.out.print/println` → детекция по target `System/out` +
> overload по първи аргумент (ptr→`k_print/k_println`, scalar→`k_print_i32/k_println_i32`,
> `println()`→`k_newline`). Примери `arrays.mj`/`oob.mj`/`str.mj`/`str2.mj`;
> регресия **16/16 (arm64)**, x86 textual emit OK.
>
> **Статус (P2 — multi-D масиви + stack spill).** Много-мерните — масив от масиви:
> клетки на външния са 8-byte `ptr` (ops `alloc_ptr`/`lea_ptr`/`ld_ptr`/`st_ptr`).
> `new T[m][n]` → `alloc_ptr` + нова helper `newArray2D` (цикъл: `alloc_<el>(n)` ред +
> `st_ptr(lea_ptr(a,i))`); `new T[m][]` → `alloc_ptr` с празни клетки; `a[i]=new T[n]`
> довършва редовете. Frontend `varElem`→`varJType`+`javaTypeOf`+`elemOfAccess`;
> `a[i][j]` → двойно `chk`+level. `FieldAccessExpression.length` (`a[0].length`) се хваща
> по `fieldName`. Пример `md.mj`. **Stack spill**: regalloc може да даде `stack -N` на
> стойност; Emitter вече reload-ва spill-натите оператори в скретч `w/x/d11..13` (arm),
> резервира `sub sp` по функция, x86 — `-N(%rbp)` (textual-only, единичен temp `%r11`).
> Това поправя латентен бъг: преди spill-нати стойности тихо ползваха скретч `w9` и се
> трошеха. Регресия **17/17 (arm64)**. Останало в P2: GC seam layout, TLS-ready allocator,
> `String.equals`/concat.

## P3 — Обекти
- Symbol/type table, класове/полета/методи/overloads.
- Object layout: **header дума** (class index + **monitor/bias bits** за P7 + GC bits space).
- Конструктори/`this`, `new`, наследяване, vtable, `instanceof`/casts.
- Trace-tables per клас (reference bitmap), **safepoint GC maps** (per-thread ready).
- Виртуални повиквания, `super`.

## P3.5 — GC решение (слот)
- **A)** Ръчен conservative mark&sweep (stack scan).
- **B)** MMTk binding (Rust, `VMBinding` FFI).
- Seam-ът от P0/P2/P3 (header, trace-tables, GC maps, `mm_alloc`) е готов.

## P4 — Контрол
- `tableswitch`/`lookupswitch`, enhanced-for, break/continue с label.
- Switch-изрази, ternary в повече позиции.

## P5 — Exceptions
- `throw`, `try/catch/finally`, runtime unwinding (frame таблици от компилатора).
- `Exception` + subclasses (съобщение), `finally` реализация.
- `NumberFormatException`, `IllegalArgument`, `NullPointerException`, `ArrayIndexOutOfBoundsException` за core lib.

## P6 — MiniJ core library (API-огледало на `java.base`)
- **`java.lang`**: System, PrintStream, Object, String, Integer, Long, Math (native→libm).
- **`java.util`**: Arrays (fill/sort/binarySearch/copyOf/equals/toString), Random (48-bit LCG JDK-съвместим).
- `corelib/` .mj файлове + `natives.c` + `bin/mc-corelib` build.
- `docs/corelib.md` contract + `examples/corelib/*.mj` тестове.

## P7 — Concurrency (Threads)
> Широк обем: Thread/Runnable, synchronized, volatile, atomics, `java.util.concurrent` подмножество.

### Ядро
- **`java.lang.Thread`** (implement Runnable): `start()`, `run()`, `join()`, `sleep(ms)`, `interrupt()` + `InterruptedException`, `currentThread()`, `getName/setName`, `isAlive/getState`, `setDaemon`.
- **`java.lang.Runnable`** (functional interface).
- Runtime: **1:1 OS threads** (pthread/clone), per-thread stack (от `mm_alloc`), **TLS** (current-thread pointer + allocator mutator + safepoint flag).
- Object header: **thin lock** (CAS lock word) → **inflated monitor** (stack lock full за `wait/notify`).
- **`synchronized`** методи/блокове → monitorenter/exit; `Object.wait()/notify()/notifyAll()`.
- **`volatile`** полета: load-acquire / store-release семантика; **хардуерни бариери** в emitters (ARM64: `dmb ish`; x86: `lock` префикс / `mfence`).
- **`java.util.concurrent`** подмножество:
  - `java.util.concurrent.locks.ReentrantLock` (non-fair/fair), `Lock`/`Condition` интерфейси.
  - `java.util.concurrent.Semaphore` (permits).
  - Атомики: `AtomicInteger`, `AtomicLong`, `AtomicReference` (CAS операции → `cmpxchg` / `ldxr/stxr`).
  - `CountDownLatch` (elementary).
  - `ThreadLocal<T>`.
- GC взаимодействие: STW (P3.5) — safepoints за всички нишки, per-thread root set scanning.
- Memory model (документирано „JMM-подобно, не пълен JDK“):
  - happens-before за lock/unlock + volatile write/read + Thread.start/join.
  - **без** full JSR-133 компетиция (pre-orden有足够的).

### Зависимости
P3 (обекти/header), P5 (exceptions), P3.5 (multi-thread GC), P2 (памет/TLS), P0 (`native` за pthread hooks).

## P8 — Modern Java (Java feature set subset)
- Generics → type erasure (parse/analyze, bind към raw types, bridge methods).
- Records (synthesized ctor/accessors/equals/hashCode/toString).
- Enum (static instances, ordinal/values/valueOf, switch).
- Lambdas + функционални интерфейси (desugar → anonymous class/method + invocation).
- `var`, switch-изрази, text blocks, pattern matching за instanceof.
- Note: threads-safe по дизайн (stable от P7).

---

## Design Decisions (запазени в кода)
- **Object header**: резервиран слот за monitor/bias bits (P3), monitor pointer (inflate) при P7.
- **`mm_alloc`**: TLS-ready от P2 (per-thread bump pointer); multi-thread идва с P7.
- **Safepoint GC maps**: per-thread root sets от P3 — подготвени за multi-thread STW.
- **native ABI**: `k_native_<Class>_<name>_<arity>` фиксиран в P0 — corelib, pthread hooks и libm ползват същия контракт.