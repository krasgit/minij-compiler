# regalloc

Linear scan register allocation с spilling.

## Употреба

    regalloc <in.mir> <out.mir> [--target=arm64]

## Изход

Добавя `.locations`:
    .locations [
      %1 : reg %rbx
      %2 : stack -8
    ]
