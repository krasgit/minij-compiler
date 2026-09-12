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
             ─ as + ld      → executable

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

## Структура

    compiler/
    ├── build.sh, mc, test.sh
    ├── README.md, docs/grammar.md
    ├── lib/janino.jar
    ├── bin/               — 9 wrapper скриптове (emit-arm/emit-x86 → shared emit)
    ├── common/            — shared: Ir, Ssa, Opt, Regalloc, Emitter
    ├── tools/             — main-ове на tools + shared emit main
    ├── rules/             — x86.rule, arm.rule
    └── examples/          — hello.mj, gcd.mj, fib.mj, forloop.mj
