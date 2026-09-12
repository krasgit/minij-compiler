# ssa-lower

Generic SSA → Machine IR.

## Употреба

    ssa-lower <in.ssa> <out.mir>

## Mapping

| Generic | Machine |
|---|---|
| add | ADD_i32 |
| cmplt | CMPLT_i32 |
| jump | JMP |
| branch | BRANCH |
| return | RETURN |
| phi | PHI_i32 |
| copy | MOV_i32 |

## Формат на изхода (.mir)

Същият IR текстов формат (виж [docs/ir-format.md](../../docs/ir-format.md)), но с **machine ops**
(`ADD_i32`, `SUB_i32`, ..., `BRANCH`/`JMP`/`RETURN`, `CONST_i32`, `PARAM_i32`, `PHI_i32`,
`MOV_i32`, `CALL_i32`). Пример:

    .func add(a: i32, b: i32) -> i32 {
      .param %1 0
      .param %2 1
      entry {
        %3 = ADD_i32 i32 %1 %2  ; dbg 5
        return %3
      }
    }

Това е форматът, който `regalloc` и `phi-elim` четат/пишат и който `emit` приема.
