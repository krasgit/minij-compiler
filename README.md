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

Регресията се гони от **`scripts/run_tests.sh`** (ползва `./mc`; arm64 — нативен хост или
qemu-aarch64 с `-L /usr/aarch64-linux-gnu`): **31/31 теста на arm64**
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
obj7 `2/2/3/4/100/100/107/2/2`, exit 42 (P3 static полета),
obj8 `3/8/10/6/96/996/11/102/15/15`, exit 42 (P3 super.method/field),
obj9 `28/11/1/15/25/5/5/4`, exit 42 (P3 масив от обекти),
obj10 `1/2/3/1/2/42`, exit 0 (P3 void методи + `static void main`);
cflow `30/2/23/23/22/40/24/33/8/103/3`, exit 0 (P4 control flow — short-circuit, compound, `++/--`,
labeled loops, enhanced-for, assignment-as-expression);
exc `10/110/111/7/114/118/518/50`, exit 3 (P5 exceptions),
imports `12/9/4/10/40/12`, exit 0 (P2.5 packages + import); всеки — exit code + stdout чек).

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

### Обекти / класове (P3, явен `super.method()` + `super.field`)

`super.m(args…)` се пази в отделни Janino AST възли — `Java.SuperclassMethodInvocation`
(`methodName` + `arguments`) и `Java.SuperclassFieldAccessExpression` (`fieldName`) — и фронтендът
ги снижава директно (без vtable): `superCall()` resolve-ва метода по super-веригата на `curClass`
(клас-символ на най-близкия супер, който го дефинира, non-static) и прави обикновен `call <sym>`
с `[this, args…]`. `super.<field>` и в двете посоки (`expr()` read, `handleAssign` write) минава
през `superFieldAddr()` — същият layout като `this` (наследените полета са на същите offsets) +
static fallback в super-веригата. Това позволява override-ващ метод да достъпи базовия:
`return super.g() + 1` във `B.g()` вика `A_g`, докато `a.f()`/`b.f()`/`c.f()` диспечват виртуално
по динамичния клас. Пример `examples/obj8.mj` (A/B/C йерархия с override на `g`, `super.g()` на
1 и 2 нива, `super.v = super.v + 5` write+read) → `3/8/10/6/96/996/11/102/15/15`, exit 42.

### Обекти / класове (P3, масиви от обекти `Foo[]`)

`Foo[]` работи напълно през вече изградената инфраструктура (P2 multi-D + P3 vtable) — без нови
ops. `elemOf("Foo[]")` дава `"ptr"`, затова `new Foo[n]` слиза до `alloc_ptr`/`st_hdr` (клетките
са 8-byte указатели). Индексиран достъп по клетка (`a[i]` read/write) е обичайният
`chk + lea_ptr + ld_ptr`/`st_ptr` (запис на `new Foo()` в клетка също минава `handleAssign`
`ArrayAccessExpression` → `st_ptr`). Janino пази `a[i].f` като **`FieldAccessExpression` с
`lhs = ArrayAccessExpression`** и `a[i].m()` като **`MethodInvocation` с `target =
ArrayAccessExpression`** — `javaTypeOf(ArrayAccessExpression)` връща елементния Java-тип ("Foo"),
затова `fieldAddrFrom(arr[i], name)` адресира полето, а `classSigs` → virtual `vt_ref`+`icall`
диспечва метода по динамичния клас на елемента (override през `legend()`→`mark()` на себе-то).
`arr.length` е обичайният `AmbiguousName [arr, length]`. Работят елементите и в цикъл с променлива
индекс (инициализация `arr[i] = new Shape()`, `arr[i].tag = i*7` write+read, виртуални повиквания).
Пример `examples/obj9.mj` (масив `Shape[]`, `arr[2] = new Box(5)` — последващите
`arr[2].legend()`/`.area()`/`.mark()` диспечват в Box) →
`28/11/1/15/25/5/5/4`, exit 42.

### Обекти / класове (P3, `void` методи + `static void main`)

`void` методи работят с пусто завръщане. Преди това `mapType("void")` падаше до `"i32"` в
fallback-а и методът се сигнираше `-> i32`, но `return;` (без стойност) все пак се емитираше гол —
Reader-ът (ир. `retType.equals("void")`) го четеше като `RETURN_i32` с 0 аргументи → грешка. Сега
`mapType` връща `"void"`, `buildSig` дава `retIr="void"`, `f.retType="void"` и голият `return` минава
чисто до пада в края (Reader/SsaLower-ът запазват голия `return` за void-функции). Call-site-овете
(static/virtual/`vt_ref`+`icall`) вече падаха на `crt="i32"` за void. `static void main()` изисква
и екзит-статус: `crt0` пуска `bl main` и `svc #93` (exit) с x0, затова arm64 `return()` rule-а
(и x86-версията за parity) върна води до `mov x0, #0`. Пример `examples/obj10.mj`
(инстантни `void reset()/add1()`, статичен `void printAll()`, изрични `return;`, инт-методи наред,
`static void main`) → `1/2/3/1/2/42`, exit 0.

### Контрол (P4) — short-circuit, compound, `++/--`, labeled цикли, enhanced-for

Java-семантиката на изразите и циклите вече е правилна:

- **Short-circuit `&&` / `||`**: преди слизаха eager до `and`/`or` (RHS на `&&`/`||` се оценяваше
  винаги). Сега `&&` → блок „lhs==0 → store 0", иначе eval RHS → `cmpne(rv,0)`; `||` аналогично с
  1. В `expr()`: `BinaryOperation(&&/||)` → `scAndOr(b, isAnd)` (alloca tmp + `sc_<id>_e/_o/_j`
  блокове). `and`/`or` правилата остават — не са dead, subtype OR-веригата ги ползва.
- **Compound присвоявания** `+= -= *= /= %=`: преди `handleAssign` игнорираше operator-а и тихо
  правеше `=`. Сега `assignVal`/`tgtFor`/`readTgt`/`writeTgt`: `Tgt{ptr, t, isAlloca}` — array elem
  (`chk+lea_<el>`, st/ld_`<el>`), AmbigName полета/`this`, локали (alloca → `load`/`store`),
  `FieldAccessExpression`/super. Compound = `wide()` → `mapOp(чист binop)` → `conv` обратно → write,
  като израз връща финалната стойност.
- **`++` / `--`** (pre/postfix, `Java.Crement` — беше `# unsupported expr` → konst 0): `crementVal` —
  `readTgt` + `add`/`sub` 1 + `writeTgt`; pre връща новата, post старата стойност; работи и на
  локали, и на масивни елементи (`a[i]++`, `a[i++]++`).
- **Assignment-as-expression**: `Java.Assignment` в `expr()` → `assignVal` (преди konst 0 и без
  write). Работи `(x = expr)`, в ternary branch-ове и като функция-арг `f(a = f(b))`.
- **Labeled break/continue**: `Java.LabeledStatement` → `labelBreak`/`labelCont` карти (label → блок);
  `break lbl`/`continue lbl` резолват от тях; non-loop labeled тяло (`label: { … }`) получава
  `lblb_<id>` exit блок. Unlabeled и labeled се комбинират (break inner без label ходи в най-близкия).
- **Enhanced-for `for (T x : arr)`**: `forEachStmt` — alloca counter `_ec<id>`, блокове
  `feh_/feb_/fest_/fex_`, `len`+`cmplt`, `chk`+`lea_<el>`+`ld_<el>` → store в елементния слот;
  continue → step, break → exit. Елементният слот се инициализира с `const 0` преди цикъла — иначе
  SSA phi-ът на entry-edge стойността няма localocation и fallback-ва в w-reg → `mov x28, w9`
  (operand mismatch). Работи над int[]/други, `String` (char[] елементи), `Foo[]` и `int[][]` (2D).
  `for (;;)` без update/condition също е ок (null update guard).
- **Bugfix (латентен)**: bare-name MethodInvocation оценяваше аргументите **два пъти** (веднъж в
  общ `args` списък преди resolve-а и пак в call-args) — `f(bump())` викаше два пъти и `f(a=f(b))`
  даваше погрешно. Сега аргументите се оценяват веднъж, **преди** `emit("call")` (иначе call-ът стои
  в IR преди arg-инструкциите → scratch-регистри garbage). obj10/print/str (k_println_* със
  side-effect аргументи) го доказват.

Пример `examples/cflow.mj` (short-circuit със side-effect `bump()`, `+=`/`*=`/`-=`, pre/post `++`
и `--`, labeled `break outer`/`continue`, enhanced-for над `int[]` и `Card[]`, assignment-as-arg,
compound на масив елемент) → `30/2/23/23/22/40/24/33/8/103/3`, exit 0.

### Изключения (P5) — `try/catch/finally` + `throw`

`throw`, `try`/`catch`/`finally` и runtime unwinding работят end-to-end. IR-ниво:

- Нови ops: **`EH_LAB`** (`%N = EH_LAB <id>` — носи именато като 0-арг call: arm64
  `adrp x9, .L…_dsp_<id>`/`add $dst, x9, :lo12:…`, x86 `leaq …(%rip)`), **`FP`** (`mov $dst, x29` /
  `movq %rbp`), **`EH_EXC`** (`mov $dst, x0` / `movq %rdi`).
- Нов func metadata, сериализиран между `.param` и тялото: `.ehvar <name>…` (alloca имена, които
  SSA не трябва да промотира — регистрират се директно в слота), `.ehcatch <id> <handlerBlock>
  <kindsCsv>`, `.ehfin <id> <finBlock>`, `.ehsrc <predBlock> <handlerBlock>` (синтетични
  dominance/reachability ребра от всяка потенциална точка на хвърляне до handler-а).
- Emitter строи за всеки func с `.ehcatch`/`.ehfin` **dispatcher** `.L<fn>_dsp_<id>` в края:
  чете `rec->handler`-кода от exception-а (`ldr w9, [x0]` — exception обектът е арг 0), сравнява с
  поредните `kindsCsv` стойности (`b.eq` към match-блок), при несъвпадение минава на следващия
  catch или на finally/`ret 0`. **Match-блокът прави restore преди да скочи в handler-а**
  (`pop exc_head`, `mov x29, rec->fp`, `sub sp, x29, #frame`) — без това един stale `rec` на
  chain-а пре-хващаше предхвърлено в тялото изключение в безкраен цикъл.
- Try-entry-то е `k_exc_push(dsp_label, fp)` (връща `rec`, който нормалният път `k_exc_pop`-ва),
  всяко `throw` call-ва `k_throw(e)` и слага недостижим `return`.

Frontend (AstLower): `Deque<ExcGuard>` пази активните try-та; `unwindGuards(terminated)` се вика от
`return` (първо се оцени стойността), `break`/`continue` — пуска не-pop-натите rec-и и изпълнява
finally блоковете в обратен ред преди изхода. `finally` се изпълнява по 3 пътя: нормален falloff,
при catch, и при пренасочване от dispatcher (`ehf` блок re-throw-ва). Статусът на една `finally`
се пази в `Exception` temp (EH_EXC), който се `k_throw`-ва ако дойде от unwinding.

Runtime (`runtime.c`): `ExcRec{prev, fp, handler}` в статичен `exc_pool[128]` (най-дълбок вложен
rec → exit 77), глобален `exc_head`. `k_throw(e)` минава по chain-а търсейки handler, който върне
не-0 (catch-нал) — иначе печата `#<classIndex>` и `sys_exit(3)` (необработено изключение).
Exception обектите са обичайните MiniJ обекти от MM arena (vtable + fields).

Пример `examples/exc.mj` (две нива try/catch/finally, re-throw, `finally` и при трите пътя,
SubException/`getMessage`, необработено в крайна сметка) → `10/110/111/7/114/118/518/50`, exit 3
(uncaught). x86 backend също emit-ва (текстуално) — регресията върви само на arm64.

### Пакети и import (P2.5)

Един MiniJ проект може да се разбие на няколко `.mj` файла с `package` и `import` декларации.
`ast-lower` зарежда транзитивно **closure-а** на главния файл: всички `import`-и (single-type
`import pkg.Cls`, on-demand `import pkg.*`, статични `import static pkg.Cls.m` /
`import static pkg.Cls.*`) и всички реферирани имена, събрани с reflection walk по AST-то (всеки
dotted `Java.ReferenceType`, с identity `seen`-guard), се роутират като `pkg/Cls.mj` спрямо source
root-овете: директорията на входния файл + `-I dir` / `--src d1:d2` за `ast-lower` (а `mc` приема
`-I`). Цикълът `units → imports → refs → resolve` е фиксирана точка и продължава докато всички
класове се заредят. Имената са в **flat simple-name namespace**: дублиран клас или нееднозначен
`pkg.*` дават грешка; on-demand търси в `java.lang`, текущия пакет и `import pkg.*`-ите.

Типовите имена от Java (`geom.Geom`, `shapes.Circle`) се свеждат до простото име **навсякъде**, където
frontend-ът консумира тип-стринг — `norm()` (запазва `[]`-суфикси) в `mapType`/`irType`/`elemOf`,
сигнатури, `new`/`cast`/`instanceof`/`new T[]`, локални, for-each, try-catch. Статични членове от
`import static`: `Geom.setUnit(...)`/`Geom.UNIT` минават през съществуващия клас-квалифициран път
(`classSigs`/`ambigStatic`), а **голите** имена (`setUnit(4)`, `UNIT`, `two(5)`) — през
`importedStaticMethod`/`importedStaticField` (read + write + type-inference).

Попътно (environment fix в `mc`): линква се с **`-no-pie`** — под qemu-aarch64 PIE +
`R_AARCH64_RELATIVE` vtable relocations segfault-ваха програмите (11 от obj*/cflow примера);
`-no-pie` ги върна в регресията. Пример `examples/imports/` (`geom/Geom.mj` със static поле/методи +
`shapes/Circle.mj`/`shapes/Square.mj` с наследяване и override, `import shapes.*` +
`import java.lang.String`) → `12/9/4/10/40/12`, exit 0.

## Пътна карта

Пълен план P0–P8: [docs/ROADMAP.md](docs/ROADMAP.md).
Core lib договор (API-огледало на java.base): [docs/corelib.md](docs/corelib.md).

Кратко: P0 (инфраструктура/`.rule v2`) → P1 (типове) → P2 (памет/масиви/String) → ~~P2.5 (packages/import — done)~~ → P3 (обекти/header) → P3.5 (GC) → ~~P4 (контрол — done)~~ → ~~P5 (exceptions — done)~~ → P6 (core lib) → P7 (threads/concurrency) → P8 (модерен Java).

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
