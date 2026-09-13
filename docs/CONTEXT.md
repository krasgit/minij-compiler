# SESSION CONTEXT (handoff notes)

Resume instructions: kогато потребителят напише "continue" (или "продължи"), започни от
`Section: NEXT MOVE`. Ако започва чиста сесия: първо `git log --oneline -5` в `/shared/compiler`
за да видиш HEAD, чети този файл, после върви по NEXT MOVE. Потребителят кара напред с "go";
ако милстонът е цял — завърши го с регресия, docs, commit+push.

**Императив: ОБНОВЯВАЙ ТОЗИ ФАЙЛ при всяка промяна на състоянието.** След всяко значимо
напредване (поне при всеки commit/push и винаги в края на сесията):
- `## Status`: добави завършения милстон, обнови regression броя (17/17 → N/N) и HEAD;
- `## NEXT MOVE`: свали направеното, вдигни следващия кандидат; ако е останало
  незавършено — премести го тук като ACTIVE/pending с точните следващи стъпки;
- `Pipelines-факти`/`Relevant Files`: добавяй новите науки (AST шифти, правила, layout)
  с една-две реда, за да не се налага повторно проучване;
- держи `NEXT MOVE` първата секция, която се чете при resume.
Файлът е единственият продължителен контекст между сесиите — ако не го анплицираш,
„continue" няма да знае откъде да продължи.

## Objective (текуща посока)

Проект: Mini-Java compiler (Janino AST → SSA/IR → regalloc → arm64/x86 asm), roadmap
P0..P8 (P3 = обекти е следващата голяма фаза). Всеки милстон = functionality + пример +
regression + docs + commit + push.

## Environment / workflow constants (НЕ променяй!)

- Repo: `/shared/compiler` — **noexec mount**: `./bin/*`, `build.sh`, `test.sh` не вървят там.
- Build: `bash /shared/compiler/build.sh` (създава `/shared/compiler/out`).
- Tests/run: от `/tmp/opencode` (работеща директория), скрипт `/tmp/opencode/run_tests.sh`.
- CP: `$DIR/out:$DIR/lib/janino.jar:$DIR/lib/commons-compiler.jar`; RULE=`$DIR/rules/arm.rule`.
- Pipeline (по ред): `JaninoParseMain` → `AstLowerMain`(.lir) → `SsaBuildMain`(.ssa) →
  `SsaOptMain`(.opt.ssa) → `SsaLowerMain`(.mir) → `RegallocMain .mir .alloc.mir --target=arm64 $RULE`
  → `PhiElimMain`(.final.mir) → `EmitMain .final.mir .s --target=arm64 $RULE` → `as`+`ld`+run.
  (За x86: `--target=x86 $DIR/rules/x86.rule`, textual emit, без as/run.)
- Runtime: `runtime/crt0.S` + `runtime/runtime.c` (gcc `-ffreestanding -O2`); `mm_alloc`, `puti`,
  и P2 `k_print/k_println/k_print_i32/k_println_i32/k_newline`. OOB → `sys_exit(134)`.
- Доступни само **aarch64** binutils (as/ld/objdump); x86 = само textual.
- **`gdb-multiarch` е инсталиран** (GDB 16.3) — ползвай го за arm64 бинарни (batch-режим:
  `gdb-multiarch -q -batch -ex "file t_md" -ex "run" -ex "bt" t_md`), когато runtime се чупи
  (segfault/SIGBUS). За core-dump-ове добавяй `-ex "bt full"`.
- Git identity: `-c user.name=krasgit -c user.email=krasgit@users.noreply.github.com`;
  push: `git push origin master` (remote `https://github.com/krasgit/minij-compiler.git`).
- Commit стил: `git add -A` + един ред message („P2 …: …; N/N regression").
- Commit history: `8873729` P0 → `318c431` P1 types → `60f704e` P1 native →
  `824824d` P2 arrays → `b5f2736` P2 String/char/System.out →
  `e89d6f4` P2 multi-D arrays + stack spill → `a094239` docs/CONTEXT.md →
  **`b9e3499` P2 String.equals/concat (HEAD, pushed)**.

## Status (актуално към HEAD = b9e3499)

- DONE: P0 infra; P1 long/double + native; P2 arrays; P2 String/char/System.out;
  **P2 multi-D arrays (17/17 regression, pushed)**; **P2 String.equals/concat
  (18/18 regression — str3.mj)**.
- ACTIVE: P2 напълно приключен (освен GC seam layout / TLS-ready allocator, всичко е done).
  Следваща стъпка = по-долу в NEXT MOVE.
- Regression: **18/18 PASS на arm64** (run_tests.sh): hello 47, gcd 12, fib 55, forloop 55,
  dowhile 55, ternary 5, switch 92, print, dbl, lng, mix, arrays, oob 134, str, str2, md, native(-lc),
  **str3** (`1/0/0/7`, `abcdef`, `6/6`, `xabc`, `abcABcd`, `1`).

## NEXT MOVE (при "continue")

P2 String.equals/concat е **завършен** → следващият кандидат по roadmap:
1. **P3 Objects** — голямата следваща фаза: symbol/type table, класове/полета/методи/overloads;
   object header дума (class index + monitor/bias bits + GC bits); `new`, поле достъп,
   virtual calls/`call` overload с обекти, `instanceof`/cast. Свалено от ROADMAP: N+1 hit —
   методите на обекти ще изискват `dispatch` (class index → vtable), което ще усложни
   Regalloc/Emitter.
2. (останало в P2) **GC seam layout / TLS-ready allocator** — `mm_alloc` kind аргументът и
   header-думата за обектите се решават заедно с P3.

Първа стъпка преди кода: ~10-минутно проучване на Janino AST за избрания feature (SPIA или
продължи модела с probe файлове в `/tmp/opencode`), после frontend,
rules ако има нови ops, пример (`examples/*.mj`), добавяне в `run_tests.sh`, bumper на
елементите, README/ROADMAP/CONTEXT, комит+push.

## Pipelines-факти (проверени, няма нужда да се преоткриват)

### Janino AST (верни, supersede по-стари предположения)
- `StringLiteral.value` = суров source ВКЛ. `"…"` кавичките, escapes необработени.
- `CharacterLiteral.value` е **String** (не Character), суров с `'…'` и необработени escapes.
  Frontend `decodeString(raw, q)` сваля кавичките и декодира `\n \t \r \b \f \0 \\ \" \' \uXXXX`.
- multi-D shapes: `int[][] a = new int[3][2]` → `NewArray(dimExprs=[3,2], dims=0)`; `new int[m][n]`
  → dimExprs, dims=0; `new int[3][]` → dimExprs=[3], dims=1; `a[0][1]=5` →
  `Assignment(lhs=ArrayAccessExpression(lhs=ArrayAccessExpression, index))`;
  **`a[0].length` → `Java.FieldAccessExpression`** (fields `lhs`, `fieldName="length"`,
  `value=null`); гол `a.length` → `AmbiguousName` n=2.
- `System.out.println/print` dispatch: target `AmbiguousName [System,out,…]`; overload по тип на
  първия аргумент: ptr → `k_print/k_println`, i32 → `k_print_i32/k_println_i32`,
  `println()` (0 args) → `k_newline`.
- **String методи (P2 str3)**: `s.equals("a")` → `MethodInvocation(methodName, arguments,
  target=AmbigName [s, equals])` — методът е **последният identifier** (ids[1]), receiver
  `s`=ids[0]; `"x".concat(s)` → target=`StringLiteral`. Frontend детекция: `javaTypeOf(tgt)`
  ==`"String"` && methodName в {equals,concat} && 1 arg → `call k_string_equals`/`k_string_concat`
  с args=[recv, arg0]; receiver стойността за AmbigName[recv,метод] = `load` от alloca-та на ids[0]
  (иначе `expr(tgt)`); **аргументите трябва да се lower-ват ПРЕДИ emit("call")** — иначе
  arg-инструкциите падат след call-а и x0/x1 получават garbage от scratch-регистрите.
- `javaTypeOf`/`inferType` за MethodInvocation: `concat`→`"String"`/`"ptr"` (иначе println
  ще вземе `k_println_i32`); `equals`→`"int"`/`"i32"`. `AmbigName` с >1 ids: само
  `identifiers[1]=="length"` дава int; всяко друго връща типа на receiver-а (`s.equals` се
  типизира като "String") — пълният `length>1 → int` чупи String dispatch-а.

### Типове / layout
- `String` = lean `char[]` (i32 header + 4-byte cells, copy-by-reference); `mapType("String")→"ptr"`;
  елементен достъп до String → `"i32"` (`elemOfAccess("String")`).
- Масив: 8-byte header за i64/ptr/f64, i32-за i32?; header=[len:u32][pad], data при +8;
  header len = за елементите (alloc_<el> прави `(n+pad)*size`).
- Multi-D = масив от масиви: клетките на външния са 8-byte **ptr**:
  `elemOf("T[][]…")→"ptr"`, `elemOf("String[]")→"ptr"`, 1-D prim. остават (int[]→i32,…).
- `new int[m][n]` (nd=2, trailing=0) → `newArray2D` helper: `alloc_ptr`+`st_hdr` за външния,
  `en` (вътрешна дължина) веднъж, alloca counter, head блок `load`+`cmplt`+`branch`,
  body: `alloc_<el>(en)`+`st_hdr`, `st_ptr(lea_ptr(outer,i),row)`, `add i,1`, loop jump;
  `cur` остава на exit-блока. `new int[m][]` (dims=1) → `alloc_ptr` с празни клетки; редове
  довършва `a[i]=new int[n]` (regular elemOfAccess write → st_ptr).
- `a[i].length`: `expr(fa.lhs)`+`len`; `a[i][j]` read→повтори `chk`+`lea`+`ld` на всяко ниво.

### IR / pasove
- Няма op-table: element type е в суфикса на op-name-а (`alloc_i32`, `ld_i64`, …);
  `Ir.suffix()` мапира `ptr`→i64-width; Writer/Reader generic — нови ops стават автоматично;
  backend работа = само rule темплейти.
- Regalloc (linear scan, callee-saved pools `x19..x28` + `d19..d28`):
  - void ops (chk/st_*) участват в last-use на аргументите си;
  - PHI аргумент се брои „ползван" в края на predecessor-а (PhiElim слага mov там);
  - стойност дефинирана извън цикъл, чете в него → жива до края на цикъла;
  - простира втори pass за FP (fregs) с общ `slotRef`.
- **Stack spill (ново в e89d6f4, ВАЖНО)**: regalloc може да даде location `"stack -N"`.
  Преди Eмиттер-а тихо го падаше в скретч `w9` → грешен код (търсеше се като segfault).
  Сега Emitter: `spilled(v)` = loc започва `"stack "`; `stemp(v,idx)` → arm64 `w/x/d11`, `12`,
  `13` (idx 0/1/2; НЕ се ползват от rules!), x86 → `%r11[ d]` (единичен temp; текстово);
  `slotMem(v)` = arm64 `[sp, #(spillBytes + spillSlot(v))]` (positive offset, `spillSlot` e
  отрицателно!), x86 `-(240+|-slot|)(%rbp)`; `prepareSpills(v)` reload-ва всяко spill-нато
  оператор преди инструкцията; `saveSpills(v)` store след, ако result е spill-нат;
  `func()` изчислява `maxSpillBytes(f)` (max |slot|), `align16`, `sub sp, sp, #N` след
  prologue-pushes-те (reserved area Е над callee-saved, call-safe), `add sp, sp, #N` преди
  epilogue; prologue се expand-ва в отделен buffer.
  - симптоми ако се чупи: `immediate offset out of range` = negative offset извън sub-area;
    SIGBUS exit=135 = разсинхронизиран sp (offset съвпада с reserved но poor sign).
- Emitter поддържа само: `reg()`, `valReg()`, `${ws}[i]` (mov/movq/movl по type;
  **`"ptr"`/`"address"` третирани като `i64`** за movq — x86 fix), `${args}[i]`,
  `${argregs}[i]` (по pool-и), `${params}` (param movs), `${imm}/${imov}`,
  `${scratch}`=x9. `CALL_*` rule ползва `${ws}[i] ${argregs}[i], ${args}[i]` per arg + `bl ${name}`
  + `mov $dst, ${ret}` — spill-нати call args се reload-ват автоматично.
- Rule format v2: `regs/args/ret/fallback/scratch/subs/fregs/fargs/fret/prologue{…}/epilogue{…}`
  + `emit <op>(...){ … }` с плейсхолдъри; `subs:` (32-bit views), `pairs:`. x86 pool
  `%rbx %r12..%r15`, args `%rdi..%r9`, scratch `%r10`, `%r11` ползва се в `chk`.
- Consts: arm64 i32 през `movz/movk` (`${imov}`); i64/f64 през pool (`adrp/ldr` + rodata `.p2align 3`).

## Testing

- `cd /tmp/opencode && bash run_tests.sh` → build + 18 теста; текущ резултат **18/18**.
- Тест функции: `run name src exitcode`, `run_out name src exitcode $'expected\nout\n'`;
  добавяне на нов пример = `run_out str3 "$DIR/examples/str3.mj" 0 $'1\n0\n0\n7\nabcdef\n6\n6\nxabc\nabcABcd\n1\n'`
  + обновяват се броя и README/ROADMAP/CONTEXT.
- Примерни файлове за multi-D: `examples/md.mj`. OOB multi-D (m7/m8 .mj в /tmp/opencode) → exit 134.
- Отделни минимални програми за бисouter (m1..m8.mj) стоят в /tmp/opencode; не се комитват.

## Relevant Files (карта)

- `tools/ast-lower/AstLowerMain.java` — frontend (mapType/elemOf/elemOfAccess/javaTypeOf/varJType/
  methodRetJt/newArray2D/NewArray rewrite/FieldAccessExpression.length/inferType/decodeString/
  System.out dispatch; varElem е премахнат).
- `common/Emitter.java` — spill support (spillTemp/spillBytes/stemp/slotMem/spillLoad/spillStore/
  prepareSpills/saveSpills/maxSpillBytes), x86 ptr→movq, template interpreter + Ctx.
- `common/Regalloc.java` — linear scan + preassign, loop/phi/void liveness, "stack -N" locs.
- `common/RuleParser.java` — rule v2 parser (Rules.width/isFpType).
- `rules/arm.rule`, `rules/x86.rule` — include `alloc_i32/i64/f64/ptr`, `st_hdr`, `len`, `chk`,
  `lea_i32/i64/f64/ptr`, `ld_i32/i64/f64/ptr`, `st_i32/i64/f64/ptr`, CONST/MOV/ADD/…, prologue/epilogue.
- `runtime/runtime.c`, `runtime/crt0.S` — k_* helpers (`k_print/k_println`, `k_print_i32/
  k_println_i32`, `k_newline`, **`k_string_equals`/`k_string_concat`**), mm_alloc, syscalls.
- `examples/*.mj` — hello, gcd, fib, forloop, dowhile, ternary, switch, print, dbl, lng, mix,
  arrays, oob, str, str2, md, **str3**, native.
- `/tmp/opencode/run_tests.sh` — regression harness.
- `/tmp/opencode/*Probe.java` (SV/EV/CH/MDP/NAD) — Janino AST probes, преизползваеми.
- `README.md`, `docs/ROADMAP.md`, `docs/ir-format.md`, `docs/rule-format.md`, `docs/corelib.md`.

## При съмнение

Първо попитай потребителя („за кое 'go' — X или Y?") при големи/свободни избори (напр. дали
next milestone е P3 objects или P2 tail); за малки неща действай по конвенцията на repo-то.