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

## Свързани

- преди: ast-lower
- следващ: ssa-opt
