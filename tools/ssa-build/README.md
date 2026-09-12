# ssa-build

Linear IR → SSA.

## Употреба

    ssa-build <in.lir> <out.ssa>

## Какво прави

1. Promotable alloca.
2. Dominance frontiers.
3. Phi insertion чрез DF (Cytron).
4. Renaming — DFS по dominance tree.
5. Премахва alloca/store.
6. Обновява .debug_vars.

## Инварианти

- Всяка SSA стойност дефинирана точно веднъж.
- Всеки phi има вход за всеки predecessor.
- Няма alloca/load/store.

## Формат на изхода (.ssa)

Същият IR текстов формат като `.lir` (виж [docs/ir-format.md](../../docs/ir-format.md)),
но без `alloca`/`store`/`load` (промоутирани) и **плюс**:

- `phi`: `%i = phi i32 [head: %v] [exit: %v2] ...` — по двойка (блок, входна стойност)
- `copy`: `%i = copy i32 %v` — за стойности, влизащи в блок (по-късно `MOV_i32`)
- `.debug_vars [ "name" : versions=[%3, %7] ]` — SSA версиите на всяка локална

## Свързани

- преди: ast-lower
- следващ: ssa-opt
