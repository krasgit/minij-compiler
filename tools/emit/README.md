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

## Формат

Вход: `.mir`(с `.locations` от regalloc, след phi-elim) + `.rule` шаблон
(граматика: [docs/rule-format.md](../../docs/rule-format.md); IR формат:
[docs/ir-format.md](../../docs/ir-format.md)).

Изход: `.s` — асемблерен файл:

- Java-страната (`Emitter`) добавя: header `.file 1 "<module>.mj"` / `.text`, по функция
  `.globl <fn>` + `fn:`, блокови labels `.L<fn>_<hash>` и exit label `.L<fn>_exit`,
  `.loc 1 line col` ред преди всяка инструкция с dbg информация.
- Инструкциите идват изцяло от шаблоните в `.rule` (prologue, epilogue, emit правила);
  всеки `.rule` ред → 1 асемблерен ред (нормализиран на 4 space indent).
- Ако даден op няма rule за своята аритити, emit хвърля `no rule for op '...'`.