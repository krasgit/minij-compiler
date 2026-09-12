# ssa-opt

Оптимизации върху SSA.

## Употреба

    ssa-opt <in.ssa> <out.ssa>

## Оптимизации

- Constant folding (`add 2, 3 → 5`)
- Copy propagation
- DCE (мъртви стойности)

## Свързани

- преди: ssa-build
- следващ: ssa-lower
