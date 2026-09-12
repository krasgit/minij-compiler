# regalloc

Linear scan register allocation с spilling. Регистровият пул се чете от `.rule` файла (`regs:`), а не е твърдо кодиран.

## Употреба

    regalloc <in.mir> <out.mir> --target=arm64 [<rules>/*.rule]
    regalloc <in.mir> <out.mir>            [<rules>/*.rule]   # target=x86

Ако `.rule` не е подаден, инструментът използва `rules/arm.rule` или `rules/x86.rule` според `--target`.

## Изход

Добавя `.locations`:
    .locations [
      %1 : reg %rbx
      %2 : stack -8
    ]
