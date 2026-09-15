# MiniJ Compiler

Компилатор за MiniJ (Java subset) → x86-64 / ARM64 native executable.
Всеки етап е отделна програма, която чете in.file, пише out.file.

## Философия

- Всеки tool е самостоятелна програма.
- Всички формати са текстови.
- Debug info пътува с IR-а.
- Backend-ите са `.rule` файлове (данни).
- Janino е reference parser.

## Контракт между tools

- Всеки tool е **самостоятелна програма** — 1 входен файл → 1 изходен файл (`argv[1]` и `argv[2]`); не знае нищо за останалите.
- Изходът на всеки tool се **подава като вход на следващия** — тестовете пускат всеки tool сам и проверяват изхода му.
- При грешка tool-ът изписва съобщение на stderr и излиза с **различен от 0** exit code; успехът е exit 0.
- Backend-ът (**emit-arm / emit-x86 / regalloc**) освен IR чете `.rule` файл (3-ти аргумент) — шаблон за инструкциите + регистров пул; самите инструменти съдържат само рамка (labels, канонични имена на ops).
- Хостът може да е с друга архитектура от таргета: emit/асемблирането/линкването са разделени, така че `.s` може да се генерира независимо.

## Инсталация

    pkg install openjdk-17 binutils wget
    ./build.sh

## Употреба

    ./mc examples/hello.mj -o hello
    ./hello; echo $?

## Pipeline

    source.mj ─ janino-parse → source.ast
             ─ ast-lower    → source.lir
             ─ ssa-build    → source.ssa
             ─ ssa-opt      → source.opt.ssa
             ─ ssa-lower    → source.mir
             ─ regalloc     → source.alloc.mir
             ─ phi-elim     → source.final.mir
             ─ emit-x86     → source.s
             ─ as + ld      → executable (crt0.o + runtime.o за puti/putc/mm_alloc)

## Формати

Всички IR файлове (`.lir`, `.ssa`, `.mir`) ползват **една** текстова граматика —
[docs/ir-format.md](docs/ir-format.md); етапите се различават само по набора ops.
Backend-ът е изцяло `.rule` шаблони — [docs/rule-format.md](docs/rule-format.md).
Форматът на всеки tool е документиран в неговия README (секция "Формат").

## Инспекция

    ./mc --stage=ast examples/gcd.mj
    ./mc --stage=lir examples/gcd.mj
    ./mc --stage=ssa examples/gcd.mj
    ./mc --stage=asm examples/gcd.mj
    ./mc --keep examples/gcd.mj

## Ръчен pipeline

    ./bin/janino-parse  examples/gcd.mj  gcd.ast
    ./bin/ast-lower     examples/gcd.mj  gcd.lir
    ./bin/ssa-build     gcd.lir          gcd.ssa
    ./bin/ssa-opt       gcd.ssa          gcd.opt.ssa
    ./bin/ssa-lower     gcd.opt.ssa      gcd.mir
    ./bin/regalloc      gcd.mir          gcd.alloc.mir
    ./bin/phi-elim      gcd.alloc.mir    gcd.final.mir
    ./bin/emit-x86      gcd.final.mir    gcd.s
    as gcd.s -o gcd.o && ld gcd.o -o gcd
    ./gcd; echo $?

## Tools

| Tool | Вход | Изход | README |
|---|---|---|---|
| janino-parse | .mj | .ast | tools/janino-parse/README.md |
| ast-lower | .mj | .lir | tools/ast-lower/README.md |
| ssa-build | .lir | .ssa | tools/ssa-build/README.md |
| ssa-opt | .ssa | .ssa | tools/ssa-opt/README.md |
| ssa-lower | .ssa | .mir | tools/ssa-lower/README.md |
| regalloc | .mir | .mir | tools/regalloc/README.md |
| phi-elim | .mir | .mir | tools/phi-elim/README.md |
| emit-x86 | .mir + .rule | .s | tools/emit/README.md |
| emit-arm | .mir + .rule | .s | tools/emit/README.md |

## Cross-compilation

    ./mc --target=arm64 examples/hello.mj -o hello.arm

## Тестове

    ./test.sh

`/shared/compiler` е на noexec mount — `./bin/*` и `test.sh` (който вика `./build.sh`)
не работят на място. Регресията се гони от `/tmp/opencode/run_tests.sh`
(директни `java -cp` повиквания + `as`/`ld`/`gcc` в /tmp): **25/25 теста на arm64**
(47, 12, 55, 55, 55, 5, 92, print `123/-7/A`, dbl `1/2/2`, lng `68/3/1`, mix `6/4`,
native `14/7/5` с `-lc`, arrays `30/5/6/1000000009/4`, oob exit 134, str `hello/world/A->101/5/hXllo/abcde`,
str2 с `\t`/`\n` escapes и char[] return/params, md `3/4/138/12/7/3/13/5` (multi-D),
str3 `1/0/0/7`, `abcdef`, `6/6`, `xabc`, `abcABcd`, `1` (String equals/concat),
obj `5/7/6/12`, `1/2/3/1`, exit 2 (P3 classes),
obj2 `6/7/7/17/117`, `7/14/8`, exit 10 (P3 methods/overloads),
obj3 `3/30/10/20`, `5/6/100/15`, exit 26 (P3 ctors/this),
obj4 `1/0/0/1`, `7/9/0`, exit 16 (P3 instanceof/cast),
obj5 `15/7/1/1/0/1/7/7/2/1/4/0`, exit 77 (P3 extends/super/subtype),
obj6 `18/64/8/11/-1/64`, exit 34 (P3 vtable dispatch/override),
obj7 `2/2/3/4/100/100/107/2/2`, exit 42 (P3 static полета); всеки — exit code + stdout чек).

### String / char (P2)

`String` е lean `char[]` (header i32 length + 4-byte cells, copy-by-reference), `char` е i32.
`System.out.println/print` минават през `k_println`/`k_print` (char[]),
`k_println_i32`/`k_print_i32` (скалари) и `k_newline` (`println()` с 0 аргумента).
Работят `System.out.println(s)`, `s.length`, `s[i]`, литерали с escapes (`\n \t \0 \" \\ \uXXXX`),
char[] като параметри/return, `call`-overload разпознаване по типа на първия аргумент.

(P2 tail) **String методи**: `s.equals(t)` → `k_string_equals` (сравнява header len + клетки, 1/0),
`s.concat(t)` → `k_string_concat` (нов char[] с len1+len2, копира и двете) — за dispatch детектираме
`Java.MethodInvocation` с target `AmbiguousName[recv, метод]` или `StringLiteral` (receiver-стойността
се сваля като `load` от alloca при AmbigName, иначе — `expr(target)`); резултатът на `concat` е `ptr`
и `javaTypeOf`/`inferType` знаят `concat` → `"String"`, затова `println(s.concat(x))` отива в
`k_println`, а `s.concat(x).length`/`c.length` вали тип `int`. Покрито в `examples/str3.mj`.

### Много-мерни масиви (P2)

Multi-D = масив от масиви: клетките на външния масив са `ptr` (8-byte) към редове.
`new int[m][n]` слиза до `alloc_ptr` + цикъл с `alloc_i32(n)` редове/`lea_ptr`/`st_ptr`;
`new int[m][]` дава `alloc_ptr` с нулеви клетки (редовете се пълнят с `a[i] = new int[n]`).
Има `a.length`, `a[i].length`, `a[i][j]` read/write с bounds-check на всяко ниво, `int[] r = a[1]`.
Regalloc вече поддържа **stack spill** (стойности с `stack -N` location се reload-ват в
скретч `w/x/d11..13` на arm; слага се `sub sp` пропорционално на функцията), което е и истинска
поправка на латентен бъг — преди spill-нати стойности тихо падаха в скретч `w9` и се трошеха.

### Обекти / класове (P3, първа част)

Обект = 8-byte header (class index + pad/GC bits, записва се с `st_hdr`) + instance-полета от +8,
aligned по тип (i32→4, i64/f64/ptr→8), total size в `classSizes`. `new Foo()` (Janino
`NewClassInstance`, 0-арг ctor) → `alloc_obj size` + `st_hdr classIndex`. Достъп `f.x` / `f.x = v`
(Janino `AmbiguousName[f, x]`) → `load` на base, `lea_field offset` (преходи през prefix-полета с
`ld_ptr` за дълги вериги като `h.next.next.v`) и `ld_<ft>`/`st_<ft>`. `Foo[]` — `elemOf` дава
`ptr` клетки. Layout-ът се смята в `collectClass` (pre-pass преди lowering), така че класовете се
позовават свободно и напред. Методи/виртуални dispatch-и — следващата P3 част. Пример `examples/obj.mj`.

### Обекти / класове (P3, методи + overloads)

Instance-методи `obj.method(args)` и static `Class.method(args)`: `cls()` гради сигнатурен DB
(`MethSig` в `classMethods: "cls::name" → List` за overloads), символите се мантлират
`<cls>_<name>_<irparams>` (единствено `main` остава `main`); instance методите получават скрит
`this` param 0 (`ptr`), който първи попада в argreg x0. `this.method()` / `this.field` и bare
`field` в instance метод минават през `allocaOf["this"]`. Overload резолюция по arity + inferType
exact match (`resolveSig`). Native `puti`/`println`/System.out и String equals/concat dispatch-ът
са незасегнати — bare-name повиквания на потребителски static методи (напр. `half(3)`) се
резолвират във втория dispatch pass. Пример `examples/obj2.mj` (Counter с `inc()`/`sum()` overloads/
static `make`, cross-class `Util.twice` → `6/7/7/17/117`, `7/14/8`, exit 10).

### Обекти / класове (P3, конструктори + `this` chaining)

Конструкторите са `Java.ConstructorDeclarator` в `cd.constructors` (извън `declaredMethods`,
`name="<init>"`), символи `Foo_init[<_irparams>]`; `new Foo(args)` → `alloc_obj`+`st_hdr` +
call към ctor-а с args=`[obj, args…]` (overload резолюция с `resolveSig` по
`classMethods["Foo::<init>"]`; без ctor при 0-арг → само alloc в zeroed-arena). `this(...)`
в ctor тяло (отделното поле `constructorInvocation` = `AlternateConstructorInvocation`) →
call `this`-тор-а с `[this, args…]` преди тялото. Попътно: **void returns** вече се емитират
`return` без стойност (rule `return()` в arm.rule/x86.rule; `Ir.Reader` спира да suffix-ва
`return` за void функции — досегашния фантомен null arg даваше `mov x0, w9`).
Пример `examples/obj3.mj` (Point `this(1,2)` chaining + `new Point(10,20)`, Counter 0-арг и с
арг ctors, `new Point(7,8).sum()` → `3/30/10/20`, `5/6/100/15`, exit 26).

### Обекти / класове (P3, `instanceof` + cast, exact-class)

Обектният header (offset 0) пази **клас-индекс (i32)** → `x instanceof Foo` се снижава до
null-guard + `ld_i32(x)` + `cmpeq(header, idx)` (резултат 1/0); `(Foo) x` → null-guard +
typecheck: при exact съвпадение връща x, при несъвпадение — null (без exceptions засега).
`Java.Instanceof` (`lhs`/`rhs`) и `Java.Cast` (`targetType`/`value`) се разпознават по клас-име
в `classIndex`; scalar/array cast-ове остават на стария `conv` път. Новите контролно-поточни
join-ове изпълват **ptr phi**-та и **ptr константи** — два фикса в общия път:
(1) `PhiElim` вече НЕ маха phi-дефинициите от текста (Emitter ги skip-ва) — иначе join-стойността
губеше типа си при re-read и preassigned `x24` се рендерираше като `w24` (`mov w24, x20`);
(2) `${imm}` в Emitter-а за `ptr`/`address` CONST-и минава през литерал басейна (иначе
`adrp x9, 0` → gas internal error). Пример `examples/obj4.mj` (exact класове A/B с еднакви
полета, `instanceof` в четирите посоки, успешен и неуспешен cast, cast-на-задача → null) →
`1/0/0/1`, `7/9/0`, exit 16.

### Обекти / класове (P3, наследяване `extends` + `super(...)`)

`class B extends A` се строи с три pre-pass-а в frontend-а: `collectClass` (рекурсивно първо
super-класът, cyclic guard; subclass layout **продължава от** super-а — офсетът започва от
`classSizes[super]` и super-полетата се копират в map-а на същите офсети), `collectSigs` (метод
DB за всички класове) и `emitMethods` (bodies). Инстанс-методи се наследяват през `lookupMethod`
(ход по `classSuper` веригата); bare-name скан преферира super-веригата на текущия клас. Ctor-ите
поддържат `this(...)` и `super(...)` (Janino `SuperConstructorInvocation`) — super-ctor се resolve-ва
по `super::<init>` сигнатурите и се вика с `[this, args…]`; при липса на явно обръщане се инжектира
имплицитен `super()` ако суперкласът има 0-арг ctor. `instanceof`/cast вече НЕ сравнява exact
клас-индекс: `subtypeTest` проверява дали динамичният индекс е в рефлексивно-транзитивното
затваряне на sub по super (едноблокова OR-верига от `cmpeq`); несъвместим cast връща null.
Попътно: нови ALU rules `AND_i32`/`OR_i32` (arm `and`/`orr`, x86 movl/andl/orl) и fix в
`Emitter.saveSpills` — 2-арг phi-move `MOV(val, phi)` с spill-нат phi записва стойността обратно
в слота на phi-то (иначе join чете stale стойност). Диспечът засега е **статичен**. Пример
`examples/obj5.mj` (A/B/C верига, `super(7)`/`super()`/`super(4)`,
наследени полета и методи, subtype `instanceof`/cast, `(C) b` → null) →
`15/7/1/1/0/1/7/7/2/1/4/0`, exit 77.

### Обекти / класове (P3, vtable dispatch + override)

Виртуалните повиквания вече диспечват по **динамичния** клас: фронтендът строи per-клас vtable
(`buildVtables` в `AstLowerMain`) — списък от slot-ове = super-списъкът (като prefix) + собствените
не-static методи; slot = `name(pjts…)` (override-ите с един и същ key заемат едно и също място в
цялата йерархия). Vtable-ите се потяват в нов `.vtables` IR блок и се емитират в `.data` като масив
`vtables` от `.quad vt_<cls>` + самите структури (`vt_<cls>` = `.quad <method>` за всеки slot).
`obj.m(args)` се снижава до: нов op `vt_ref` (чете header class index, `adrp`+индекс в `vtables`),
`lea_field`/`ld_i64` до слота и нов op `icall` (`blr` през fn-pointer; `${cargs}` — receiver param 0
се подминава като "call-args grounded at 1"). Bare-методи в instance метод (`legend()` внаtre
`Shape`) също минават през виртуален dispatch на `this`. Попътно: **vtables в `.data`, не
`.rodata`** — PIE ldso прилага `R_AARCH64_RELATIVE` при load, а store в read-only сегмент
segfault-ва динамичния линкер (даде се с gdb bt в `ldso/dynlink.c do_relocs`). Пример
`examples/obj6.mj` (Shape/Box, `legend()` вика виртуалния `mark()`, override на `mark`/`area`,
`(Box) s1` cast след vtable) → `18/64/8/11/-1/64`, exit 34.

### Обекти / класове (P3, static полета)

`static` полетата вече са извън object layout-а: `collectClass` ги отделя в `staticFields`
(`className → field → {irType, symbol, javaType}`) и не консумират offset; съхранението е в нов
`.statics` IR блок (`symbol : size`), който `Emitter` пуска в `.bss` с `.globl <cls>_<name>`.
Достъпът слиза до нов op `lea_static` (arm64 `adrp/add :lo12:<sym>`, x86 `leaq sym(%rip)`), следван
от `ld_<t>`/`st_<t>`. Работят всички форми: `Foo.count` (клас-квалифициран), bare `count`
(и в instance, и в static метод — през super-веригата на текущия клас), `f.count`/`this.count`
(обект-квалифициран fallback в `fieldAddrFrom`); суперкласов статик се наследява
(`staticField()` walk-ва `classSuper`). Инициализатори на static полета → грешка (не са
поддържани още). Попътно: **bare `this` като стойност** (`last = this`) вече се снижава
(load от `allocaOf["this"]`), а не тихо дава 0. Пример `examples/obj7.mj` (споделен
`static int counter`/`long total`/`T last` между инстанции + `T.counter`, `b.counter`, bare `counter`
в static `next()`, `(int) (T.total / 20000000000L)`) → `2/2/3/4/100/100/107/2/2`, exit 42.

## Пътна карта

Пълен план P0–P8: [docs/ROADMAP.md](docs/ROADMAP.md).
Core lib договор (API-огледало на java.base): [docs/corelib.md](docs/corelib.md).

Кратко: P0 (инфраструктура/`.rule v2`) → P1 (типове) → P2 (памет/масиви/String) → P3 (обекти/header) → P3.5 (GC) → P4 (контрол) → P5 (exceptions) → P6 (core lib) → P7 (threads/concurrency) → P8 (модерен Java).

## Структура

    compiler/
    ├── build.sh, mc, test.sh
    ├── README.md, docs/grammar.md, docs/ROADMAP.md, docs/corelib.md, docs/rule-format.md
    ├── lib/janino.jar
    ├── bin/               — wrapper скриптове (emit-arm/emit-x86 → shared emit)
    ├── common/            — shared: Ir, Ssa, Opt, Regalloc, Emitter
    ├── tools/             — main-ове на tools + shared emit main
    ├── rules/             — x86.rule, arm.rule (.rule v2: regs+subs+fregs/fargs/fret)
    ├── runtime/           — crt0.S + runtime.c (putc/puti/puts/exit, mm_alloc seam)
    ├── corelib/           — draft core library (java.lang/java.util .mj + README)
    └── examples/          — hello, gcd, fib, forloop, dowhile, ternary, switch, print (.mj)
