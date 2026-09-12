# ast-lower

Janino AST → Linear IR (.lir).

## Употреба

    ast-lower <in.mj> <out.lir>

## Какво прави

1. Парсва .mj с Janino.
2. Обхожда CompilationUnit през reflection.
3. Всеки statement → IR:
   - if → branch + 3 блока
   - while/for → header/body/exit
   - return → return
4. Локални → alloca + load/store.
5. Пише .lir.

## Debug info

- `dbg` на всяка IR инструкция.
- `declNames` — име на всяка локална.
- `positions` — line:col.

## Свързани

- преди: janino-parse
- следващ: ssa-build
