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
>
> **Статус (P2 — String methods equals/concat).** `s.equals(t)` → `k_string_equals`
> (сравнява header len + клетки, връща 1/0), `s.concat(t)` → `k_string_concat` (нов
> char[] с len1+len2, копира двете). Frontend детектира `MethodInvocation` с target
> `AmbigName[recv, м]` (receiver-стойността = `load` от alloca-та на `recv`, метод е
> ids[1]) или `StringLiteral` (иначе `expr(target)`); аргументите се lower-ват ПРЕДИ
> call-emit (иначе инструкциите падат след call-а → garbage в x0/x1). `javaTypeOf`/
> `inferType` разпознават `concat`→`"String"` (така `println(s.concat(..))` отива в
> `k_println`), а `AmbigName[s,length]` вали **int** — само `identifiers[1]=="length"`
> дава int, иначе връща типа на receiver-а (за да не счупи String dispatch-а).
> Пример `str3.mj`; регресия **18/18 (arm64)**; x86 textual emit OK.

## P3 — Обекти
- Symbol/type table, класове/полета/методи/overloads.
- Object layout: **header дума** (class index + **monitor/bias bits** за P7 + GC bits space).
- Конструктори/`this`, `new`, наследяване, vtable, `instanceof`/casts.
- Trace-tables per клас (reference bitmap), **safepoint GC maps** (per-thread ready).
- Виртуални повиквания, `super`.

> **Статус (P3 — classes: fields + new + access).** Pre-pass `collectClass` строи layout:
> обект = 8-byte header (class index via `st_hdr`) + instance-полета от +8, aligned по тип
> (i32→4, i64/f64/ptr→8); `classIndex/classSizes/classFields` се пълнят от
> `getVariableDeclaratorsAndInitializers()` за всички типове ПРЕДИ lowering (свободни
> препратки напред). `new Foo()` = `Java.NewClassInstance` (0-арг, `type.toString()` е името)
> → нов op `alloc_obj size` (arm+s86 rule: mm_alloc) + `st_hdr classIndex`. Достъп
> `f.x`/`f.x=v` = `AmbiguousName[f,x]` → `fieldAddr`: `load` base от alloca, преходи с
> `lea_field` (нов op: `add dst, base, offset`; offset-i64 const) и `ld_ptr` за вериги
> (`h.next.next.v` — 4 ids), после `ld_<ft>`/`st_<ft>`. Типове: `irType`/`javaTypeOf`/
> `inferType`/`elemOf` разпознават клас-имена → `ptr`. Regalloc/Emitter непроменени освен
> новите ops. Пример `examples/obj.mj`; регресия **19/19 (arm64)**; x86 textual emit OK.
> Регресия сега: **28/28 (arm64)** — obj, obj2, obj3, obj4, obj5, obj6, obj7, obj8, obj9, obj10 добавени.
>
> **P3 `void` методи + `static void main` (DONE, пример `examples/obj10.mj`, регресия 28/28):**
> `mapType("void")` вече връща `"void"` (преди падаше до fallback `"i32"`). Промяната оправя
> пълен клас бъгове: void метод се сигнираше `-> i32` (buildSig), но `return;` (без стойност) все
> пак се емитираше гол → Reader-ът (ир. `retType.equals("void")`) четеше `RETURN_i32` с 0 аргументи
> → "no rule matches op 'RETURN_i32' with 0 args" в Emitter. Сега `retIr="void"`, `f.retType="void"`,
> голият `return` се носи чисто (Writer/Reader/SsaLower вече пазят гол `return` за void-функции).
> Call-site-овете (static/virtual `vt_ref`+`icall`) вече падаха на `crt="i32"` за void — без промени.
> `static void main()` + екзит статус: `crt0.S` прави `bl main; mov x8,#93; svc #0` → exit взема x0,
> затова arm64 `return()` rule-ът (и x86 за parity, `movl $0,%eax`) слага x0=0 при void return.
> Тук всички instance/static void методи, изрични `return;`, инт-методи и `static void main`.
>
> **P3 конструктори + `this` chaining (DONE, пример `examples/obj3.mj`, регресия 21/21):**
> `new Foo(args)` → alloc_obj+st_hdr + call `Foo_init[<_irparams>]` ([obj, args…], overload
> резолюция по `classMethods["Foo::<init>"]`); без ctor при 0-арг → само alloc (zeroed arena).
> `this(...)` = отделен `constructorInvocation` (`AlternateConstructorInvocation`) → call преди
> тялото с `[this, args…]`. Конструкторите са в `cd.constructors` (не `declaredMethods`).
> Bugfix: void `return` без аргумент (Reader/SsaLower оставят `return` гол, нови rule-и `return()`
> в arm.rule/x86.rule) — фантомния null arg даваше `mov x0, w9`.
>
> **P3 instanceof/cast (DONE, exact-class; пример `examples/obj4.mj`, регресия 22/22):**
> header-ът е клас-индекс (i32) на offset 0 → `instanceof` = null-guard + `ld_i32` + `cmpeq(idx)`
> (1/0); `(Foo) x` = null-guard + typecheck → x при съвпадение, null при несъвпадение. Subtype
> семантика няма (чака vtable/наследяване). Попътни фиксове: **PhiElim пази phi-дефинициите**
> (Emitter skip-ва PHI) — иначе ptr join-стойност губи типа → `mov w24, x20`; **ptr/address
> CONST-i минават през литерал басейна** в `${imm}` — иначе `adrp x9, 0` → gas internal error.
>
> **P3 наследяване `extends` + `super(...)` + subtype (DONE, пример `examples/obj5.mj`,
> регресия 23/23):** три pre-pass-а (collectClass рекурсивно над super-а + cyclic guard,
> collectSigs, emitMethods); subclass layout продължава от `classSizes[super]`, super-полетата се
> копират на същите офсети; instance-методите се наследяват през `lookupMethod` (walk по
> `classSuper`); ctor-ите викат `super(...)` (`SuperConstructorInvocation`) с `[this, args…]`
> resolve по `super::<init>` (имплицитен `super()` ако липсва явен и суперкласът има 0-арг ctor).
> `instanceof`/cast използват `subtypeTest` — рефлексивно-транзитивно затваряне по super,
> едноблокова OR-верига (`cmpeq` + `or`); несъвместим cast → null. Попътни фиксове: **AND_i32/
> OR_i32 rules** в arm/x86 (subtype веригата + `&&`/`||`); **Emitter.saveSpills** съхранява
> 2-арг phi-move `MOV(val, phi)` със spill-нат phi в слота на phi-то.
>
> **P3 vtable dispatch + override (DONE, пример `examples/obj6.mj`, регресия 24/24):**
> `buildVtables` строи per-клас slot-списък (super-списъкът като prefix + собствените не-static
> методи; slot key = `name(pjts…)`, override-и с един key → една позиция в цялата йерархия);
> позицията на слота се търси в `classSlots[ms.cn]` (стабилна за всички потомци; независими
> йерархии с еднакви sigKey не си пречат). Нов `icall` op = `vt_ref` (header class index →
> `adrp/add` в масива `vtables`, `ldr` vtable ptr) + `lea_field`/`ld_i64` до слота + `blr fn`
> (`${cargs}` — args започват от 1, receiver-ът е arg 0). Нов `.vtables` IR блок (Writer/Reader).
> Попътни фиксове: **(1) vtables в `.data`, не `.rodata`** — PIE ldso прилага `R_AARCH64_RELATIVE`
> при load, store в RO сегмент = segfault в `ldso/dynlink.c do_relocs`; **(2)** Emitter `${cargs}`
> loop-лист + RuleParser `args...` (pattern име + any); **(3)** bind-цикълът клипва при
> `i < args.size()` (без out-of-bounds за `pat` по-дълъг от args).
>
> **P3 static полета (DONE, пример `examples/obj7.mj`, регресия 25/25):**
> `collectClass` отделя `static` декларациите (елементът е `Java.FieldDeclaration`, `.isStatic()`)
> от instance layout-a в `staticFields: cls → field → {irType, symbol, javaType}`; инициализатори
> → грешка (не се поддържат). Съхранението е извън обектите: нов `.statics` IR блок
> (`symbol : size`), `Emitter` го пуска в `.bss` (`p2align 3` + `.globl <cls>_<name>` + `.zero N`).
> Достъп = нов op `lea_static` (0-арг, носи името като call: `adrp x9, sym`/`add $dst, x9,
> :lo12:sym` arm64; `leaq sym(%rip)` x86) + обичайния `ld_<t>`/`st_<t>`. Пътища: `Foo.count`/
> `f.count` (2-ид AmbigName → `ambigStatic` в `fieldAddr`), bare `count` (fallback в `fieldThis` —
> работи и в instance, и в static метод, защото `curClass` сенася при всички `MethodDeclarator`-и),
> `this.count`/`expr.count` (static fallback в `fieldAddrFrom`); наследяване през super-верига
> (`staticField()` walk-ва `classSuper`). `inferType` за 2-ид AmbigName resolve-ва статик преди
> generic `i32`. Попътно: **bare `this` като стойност** → `load allocaOf["this"]` (беше konst 0).
>
> **P3 явен `super.method()` + `super.field` (DONE, пример `examples/obj8.mj`, регресия 26/26):**
> Janino пази `super.m()` в **отделен AST клас** `Java.SuperclassMethodInvocation` (`methodName` +
> `arguments`, имплементира `Rvalue`) и `super.f` в `Java.SuperclassFieldAccessExpression`
> (`fieldName`, `qualification=null`) — НЕ са `MethodInvocation`/`FieldAccessExpression`. Frontend:
> `superCall()` walk-ва super-веригата от `classSuper[curClass]` нагоре, resolve-ва overload по
> `classMethods["<super>::<name>"]` (non-static) и emit-ва **директен `call <sym>`** с `[this,
> args…]` (без `icall`/vtable — това е смисълът на super). `superFieldAddr()` третира `super.f`
> като `this.f` (наследените полета са на същите offsets в subclass layout-а) + static fallback в
> super-веригата; read-ът e в `expr()`, write-ът в `handleAssign`. Проверка: `B_useSuper` →
> `call A_g`, `C_twoLevel` → `call B_g` (директно), докато `a.f()/b.f()/c.f()` продължават да
> диспечват виртуално по динамичния клас.
>
> **P3 масиви от обекти `Foo[]` (DONE, пример `examples/obj9.mj`, регресия 27/27):**
> НУЛЕВИ frontend промени — комбинацията от P2 multi-D (ptr клетки) и P3 vtable/fields покрива
> всичко. `elemOf("Foo[]")` вече дава `"ptr"` (branch `classIndex.containsKey` в `elemOf`), затова
> `new Foo[n]` = `alloc_ptr`+`st_hdr`; `arr[i] = new Foo()` и `arr[i].f = v` = `chk+lea_ptr+st_ptr`.
> AST: `arr[i].f` = **`FieldAccessExpression(lhs=ArrayAccessExpression, fieldName)`**,
> `arr[i].m()` = **`MethodInvocation(target=ArrayAccessExpression)`** (НЕ AmbigName [a,i,m]);
> `javaTypeOf(ArrayAccessExpression)` връща елементния Java-тип ("Foo"), затова
> `fieldAddrFrom(arr[i], f)` адресира полето, а `classSigs` → virtual `vt_ref`+`icall` диспечва
> метода по динамичния клас на елемента („през себе-то" `legend()`→`mark()`). AST-формите са
> потвърдени с ObjArrProbe2. Пример: `Shape[]` с `arr[2]=new Box(5)` → `arr[2].legend()/area()/
> mark()` диспечват в Box → `28/11/1/15/25/5/5/4`, exit 42.
>
> **P3 методи + overloads (DONE, пример `examples/obj2.mj`, регресия 20/20):** instance-методи
> `obj.m(args)` + static `Cls.m(args)` диспеч без vtable. `cls()` гради `MethSig` DB в
> `classMethods: "cls::name" → List<MethSig>` (overloads); символи `cls_name_<irparams>` (`main`
> = "main"); instance методите имат скрит `this` param 0 (ptr, argreg x0). Overload резолюция
> `resolveSig` (arity + inferType exact, fallback първия с arity); static call `Cls.make(7)` =
> `target AmbigName[Cls, make]`, instance = `javaTypeOf(tgt)==клас`; `this.m()/this.f/bare f` през
> `FieldAccessExpression`/`ThisReference`/bare-name fallbacks (`fieldAddrFrom`/`fieldThis`);
> bare-name static user-calls (`half(3)`, `dash()`, `repeat(s)`) през втори bare-name scan;
> native/String/System.out пътищата остават. Ключов bug: sig-match към конкретен declarator,
> не `list.get(0)` (dup symbols при overloads).

## P3.5 — GC решение (слот)
- **A)** Ръчен conservative mark&sweep (stack scan).
- **B)** MMTk binding (Rust, `VMBinding` FFI).
- Seam-ът от P0/P2/P3 (header, trace-tables, GC maps, `mm_alloc`) е готов.

## P4 — Контрол ✅ (done, cflow; 29/29)
- ✅ `tableswitch`/`lookupswitch` (switch.mj съществува от P0), enhanced-for (`forEachStmt` — чек,
  обекти/2D burn-tested), break/continue с label (`labelBreak`/`labelCont` карти).
- ✅ Switch-изрази, ternary в повече позиции (`?:` с eval-блогове + assignment-клонове).
- ✅ Short-circuit `&&`/`||` (RHS само когато LHS не решава), compound `+= -= *= /= %=`,
  `++`/`--` pre/post, assignment-as-expression (`(x = e)`, като аргумент).
- ✅ Latent bugfix: bare-name MethodInvocation оценяваше args два пъти.

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