# MiniJ Compiler

Компилатор за MiniJ (Java subset) → x86-64 / ARM64 native executable.
Всеки етап е отделна програма, която чете in.file, пише out.file.

## Философия

- Всеки tool е самостоятелна програма.
- Всички формати са текстови.
- Debug info пътува с IR-а.
- Backend-ите са `.rule` файлове (данни).
- Janino е reference parser.

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
| emit-x86 | .mir | .s | tools/emit-x86/README.md |
| emit-arm | .mir | .s | tools/emit-arm/README.md |

## Cross-compilation

    ./mc --target=arm64 examples/hello.mj -o hello.arm

## Тестове

    ./test.sh

## Структура

    compiler/
    ├── build.sh, mc, test.sh
    ├── README.md, docs/grammar.md
    ├── lib/janino.jar
    ├── bin/               — 9 wrapper скриптове
    ├── common/            — shared: Ir, Ssa, Opt, Regalloc, ...
    ├── tools/             — 9 tools, всеки със свой Main + README
    ├── rules/             — x86.rule, arm.rule
    └── examples/          — hello.mj, gcd.mj, fib.mj, forloop.mj
