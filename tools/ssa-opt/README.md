# ssa-opt

Оптимизации върху SSA.

## Употреба

    ssa-opt <in.ssa> <out.ssa>

## Оптимизации

- Constant folding (`add 2, 3 → 5`)
- Copy propagation
- DCE (мъртви стойности)

## Формат

Същият IR текстов формат като `.ssa` (виж [docs/ir-format.md](../../docs/ir-format.md)):
generic ops, `phi` и `copy`. Промените са само стойности (константи-folded, копията
премахнати, мъртви инструкции няма) — блоковата структура и debug info се запазват.

## Свързани

- преди: ssa-build
- следващ: ssa-lower
