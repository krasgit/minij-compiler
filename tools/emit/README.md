# emit

Споделен emitter за x86-64 и ARM64. Шаблонният интерпретатор е в `common/Emitter.java`,
инструкциите и регистровият пул — в `rules/`. Това е имплементацията зад CLI tools
`emit-x86` и `emit-arm`.

## Употреба

    emit <in.mir> <out.s> [--target=arm64|x86-64] [rules/*.rule]

Target по подразбиране е x86-64. Ако `.rule` не е подаден, се търси
`rules/<target>.rule` (с `../../rules/` и `../../../rules/` fallback) според `--target`.

## CLI обвивки

- `bin/emit-x86` → `EmitMain "$@"` — x86-64
- `bin/emit-arm` → `EmitMain "$@" --target=arm64` — ARM64