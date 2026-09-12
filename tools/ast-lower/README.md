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

## Формат на изхода (.lir)

IR текстов формат (виж [docs/ir-format.md](../../docs/ir-format.md)) с generic ops.
Пример:

    .func main() -> i32 {
      entry {
        %1 = alloca i32  ; dbg 1
        %2 = const i32 42  ; dbg 2
        store %1, %2
        %3 = load i32 %1
        return %3
      }
    }

На този етап променливите са `alloca` + `store`/`load`; блоковете на цикли/if имат имена
`head_N`, `body_N`, `step_N`, `exit_N`, `then_N`, `else_N`, `join_N`.

## Свързани

- преди: janino-parse
- следващ: ssa-build
