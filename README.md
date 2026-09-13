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
(директни `java -cp` повиквания + `as`/`ld`/`gcc` в /tmp): **19/19 теста на arm64**
(47, 12, 55, 55, 55, 5, 92, print `123/-7/A`, dbl `1/2/2`, lng `68/3/1`, mix `6/4`,
native `14/7/5` с `-lc`, arrays `30/5/6/1000000009/4`, oob exit 134, str `hello/world/A->101/5/hXllo/abcde`,
str2 с `\t`/`\n` escapes и char[] return/params, md `3/4/138/12/7/3/13/5` (multi-D),
str3 `1/0/0/7`, `abcdef`, `6/6`, `xabc`, `abcABcd`, `1` (String equals/concat),
obj `5/7/6/12`, `1/2/3/1`, exit 2 (P3 classes); всеки — exit code + stdout чек).

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
