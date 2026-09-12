# IR текстов формат (.lir / .ssa / .mir)

`.lir`, `.ssa` и `.mir` ползват **една и съща текстова граматика** — пишат се от
`Ir.Writer.print()` и се четат от `Ir.Reader.parse()` (common/Ir.java). Етапите се
различават само по **набора от ops** (виж таблицата по-долу).

Файл = суфикс от секции с `key [...]` или `key {...}` блокове, после по един `.func` блок
на функция и накрая (след regalloc) секция `.locations`.

## Горни секции

```
.module "hello"                    ; име на модула (източник без .mj)
.target "x86-64"                   ; target: "x86-64" | "arm64"

.debug_positions [                 ; dbg id → line:col (позиции за .loc / debug)
  5 : 2:43
]

.debug_declarations [              ; dbg id → име/тип на декларация (за имената)
  1 : { name="a" type=i32 }
]

.debug_vars [                      ; SSA стойност-версиите на променливи
  "a" : versions=[%3, %7]
]

.locations [                       ; само след regalloc: стойност → локация
  %3 : reg w21                     ;   reg <r>      — регистър
  %2 : stack -8                    ;   stack -N     — стек слой
]
```

`.module` и `.target` се записват от всеки етап и се запазват до emit-а.

## Функции

```
.func add(a: i32, b: i32) -> i32 {
  .param %1 0                      ; параметър №0 → %1, №1 → %2, ...
  entry {
    %3 = ADD_i32 i32 %1 %2  ; dbg 5
    return %3
  }
}
```

- Параметрите се декларират с `.param %id N`; `%id` е стойността, `N` — позиция на параметъра.
- Блоковете се отварят с `name {`, затварят с `}`. Всяка функция започва с блок `entry`.
- Името на блока се реферира **по име** от `jump`/`branch`/phi.

## Стойности

Всяка стойност се дефинира от инструкция и се реферира с `%id` (положително число,
**програмен** scope — общо за всички функции в файла).

```
%id = op type %a %b ...  ; dbg N     ; слой тип, след тип ако число: imm
```

- `; dbg N` — необязателен debug id; `N >= 0` линква към `.debug_positions`/`.debug_declarations`.
- `const`/`CONST_*` и `param`/`PARAM_*` без args завършват с литерално число:
  `%7 = const i32 42`, `%1 = param i32 0`.

## Специални инструкции

| Ред | Формат | Бележка |
|---|---|---|
| jump | `jump <block>` | безусловен преход; block = име на блок |
| branch | `branch %cond, <then>, <else>` | 3 адреса: условие + два блока |
| return | `return` / `return %v` | без или с върната стойност |
| store | `store %a, %v` | адрес + стойност |
| alloca | `%i = alloca i32` | само в .lir (SSA го премахва) |
| phi | `%i = phi i32 [blk: %v] [blk2: %v2] ...` | по двойка (блок, входна стойност) за всеки predecessor |
| call | `%i = call f(%a, %b)` | v.name = име на функцията |
| MOV / copy | `%i = MOV_i32 i32 %a %b` | 1 или 2 аргумента — вж. emit/arm.rule |

## Ops по етап

| Етап | Ops |
|---|---|
| `.lir` (ast-lower) | generic: `const`, `param`, `alloca`, `store`, `load`, `add`, `sub`, `mul`, `div`, `mod`, `cmplt`, `cmpgt`, `cmple`, `cmpge`, `cmpeq`, `cmpne`, `and`, `or`, `jump`, `branch`, `return`, `call`, `block` |
| `.ssa` (ssa-build/ssa-opt) | същите generic, **плюс** `phi`, `copy`; без `alloca`/`store`/`load` (промоутирани) |
| `.mir` (ssa-lower) | machine: `CONST_i32`, `PARAM_i32`, `ADD_i32`, `SUB_i32`, `MUL_i32`, `DIV_i32`, `MOD_i32`, `CMPLT_i32`, `CMPGT_i32`, `CMPLE_i32`, `CMPGE_i32`, `CMPEQ_i32`, `CMPNE_i32`, `MOV_i32`, `PHI_i32`, `BRANCH`, `JMP`, `RETURN`, `CALL_i32` |
| `.mir` + `.locations` (regalloc, phi-elim) | machine + `regalloc` добавя `.locations`; `phi-elim` сваля `PHI_i32` → `MOV_i32` в predecessor-блокове |

Ssa-lower мапингът generic→machine: `add→ADD_i32`, `cmplt→CMPLT_i32`, `jump→JMP`,
`branch→BRANCH`, `return→RETURN`, `phi→PHI_i32`, `copy→MOV_i32`, `const→CONST_i32`,
`param→PARAM_i32`, `call→CALL_i32`, `undef→CONST_i32 0` (останалите аритметични — по аналогия).

## Пример (реален изход за examples/hello.mj, след phi-elim)

```
.module "hello"
.target "x86-64"

.debug_positions [
  5 : 2:43
]

.debug_declarations [
  1 : { name="a" type=i32 }
]

.func add(a: i32, b: i32) -> i32 {
  .param %1 0
  .param %2 1
  entry {
    %3 = ADD_i32 i32 %1 %2  ; dbg 5
    return %3
  }
}

.func main() -> i32 {
  entry {
    %7 = CONST_i32 i32 42  ; dbg 11
    %8 = CONST_i32 i32 10  ; dbg 12
    %9 = call add(%7, %8)  ; dbg 13
    return %9
  }
}

.locations [
  %1 : reg w19
  %7 : reg w21
]
```