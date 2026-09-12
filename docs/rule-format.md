# Rule формат (.rule)

`.rule` файлът описва изцяло backend-а: регистровия пул и инструкционните шаблони.
Чете се от `RuleParser` (common/RuleParser.java) и се интерпретира от `Emitter`.
Два таргета: `rules/arm.rule` (ARM64) и `rules/x86.rule` (x86-64).

## Header

```
regs: w19 w20 w21 w22 w23 w24 w25 w26 w27 w28     ; регистров пул (за regalloc и emit)
args: w0 w1 w2 w3 w4 w5 w6 w7                     ; аргументни регистри на архитектурата
ret: w0                                           ; регистър за върната стойност
fallback: w9                                      ; регистър при стойност без локация
```

- `regs` + `args` се ползват и от `regalloc` (пул = `regs`, параметри = `args[min]`).
- `ret` се ползва от `return(v)`, `fallback` — за стойности без `reg` локация.

## prologue / epilogue

```
prologue {
    stp x29, x30, [sp, #-16]!
    ${params}              ; mov <pool[i]>, <args[i]> за всеки формален параметър
}
epilogue {
    ldp x27, x28, [sp], #16
    ret
}
```

`${params}` се разгъва до по един `mov` на параметър (arm: `mov pool[i], args[i]`,
x86: `movl args[i], pool[i]`).

## emit правила

```
emit OP(pat1, pat2, ...) {
    <шаблонни редове>
}
```

- `OP` — каноничното име на op: `CONST_i32`, `ADD_i32`, ..., `BRANCH`, `JMP`,
  `RETURN`, `CALL_i32`, `MOV_i32`.
- Имената в скобите са **шаблон-променливи** — по подразбиране съответстват на
  аргументите на инструкцията по позиция и при резолва се заместват с техния регистър
  (по `.locations`, иначе `fallback`).
- Последните args може да са `...` — matchva произволен брой оставащи аргументи
  (ползва се от `call`).
- **Безусловен rule трябва да е последен за своя op**; след него не може да има друг
  rule за същия op (валидира `RuleParser`).

## Placeholders

| Форма | Резолвира до |
|---|---|
| `$dst` | регистъра на **дефинираната** стойност (лявата страна `%i = ...`) |
| `${name}` | регистъра на шаблон-променливата `name` |
| `${name}[i]` | `i`-тия element (в `for each` цикъл) |
| `${imm}` | целочислената константа (`CONST_i32`) |
| `${name}` (на инструкция с `v.name`) | името на инструкцията (за `call` → името на функцията) |
| `${ret}` | `ret:` от header-а |
| `${exit}` | exit label-а на функцията (`.L<fn>_exit`) |
| `${params}` | параметър-копията (виж prologue) |
| `${args}[i]` / `${argregs}[i]` | в `for each` — `i`-тия аргумент на инструкцията / арг. регистър |
| `$$` | буквално `$` (за x86 immediate: `movl $$${imm}, %eax`) |

Позициите се заместват директно в ниската на шаблона; `; dbg`, `.loc`, labels на блокове
и exit label се добавят от Java-страната на `Emitter`.

## Контролни конструкции

```
for each ${args} i {
    mov ${argregs}[i], ${args}[i]     ; повтори за всеки аргумент (i = 0,1,2,...)
}

if ${x} != $dst {                     ; "да" ако резолвнатата стойност не съвпада
    mov $dst, ${x}
}
if !${cond} { ... }                   ; "да" ако placeholder-ът е празен (без стойност)
if ${cond} { ... }                    ; "да" ако placeholder-ът има стойност
```

`for each` поддържа само `${args}` и `${argregs}`. В `if !=` и двете страни може да са
шаблон-имена, `$dst`, или `${name}` (нормализират се със strip — `$dst` → `dst`,
`${x}` → `x`).

## Валидация (RuleParser)

При parse/валидация се хвърля `IllegalArgumentException` за:

- неизвестен placeholder (`unknown placeholder '${bogus}' in rule`)
- безусловен rule след друг такъв за същия op (`rule without condition must be last`)
- лош `for each` list или malformed `if`/`{`
- незатворен блок (`unterminated block`)

По време на emit се хвърля грешка ако даден op няма подходящ rule
(`no rule for op '...'`).

## Пример (rules/arm.rule, съкратен)

```
regs: w19 w20 w21 w22 w23 w24 w25 w26 w27 w28
args: w0 w1 w2 w3 w4 w5 w6 w7
ret: w0
fallback: w9

emit CONST_i32() {
    mov $dst, #${imm}
}
emit ADD_i32(x, y) {
    add $dst, ${x}, ${y}
}
emit MOV_i32(x, d) {
    if ${x} != ${d} {
        mov ${d}, ${x}
    }
}
emit branch(c, t, e) {
    cmp ${c}, #0
    b.ne ${t}
    b ${e}
}
emit call(...) {
    for each ${args} i {
        mov ${argregs}[i], ${args}[i]
    }
    bl ${name}
    mov $dst, ${ret}
}
```