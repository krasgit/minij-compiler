# regalloc

Linear scan register allocation с spilling. Регистровият пул се чете от `.rule` файла (`regs:`), а не е твърдо кодиран.

## Употреба

    regalloc <in.mir> <out.mir> --target=arm64 [<rules>/*.rule]
    regalloc <in.mir> <out.mir>            [<rules>/*.rule]   # target=x86

Ако `.rule` не е подаден, инструментът използва `rules/arm.rule` или `rules/x86.rule` според `--target`.

## Формат

Вход: `.mir` с machine ops (виж [docs/ir-format.md](../../docs/ir-format.md)).
Изход: същият `.mir` **плюс** секция `.locations` в края:

    .locations [
      %1 : reg %rbx      ; стойност %1 е в регистър
      %2 : stack -8      ; стойност %2 е стек слой от -8
    ]

`emit` резолвира placeholder-ите по тези локации ($dst-стойностите и аргументите на
инструкциите); стойност без локация пада на `fallback:` от `.rule`-a.
