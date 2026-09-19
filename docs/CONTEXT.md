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
P0..P10 (P6 core lib = DONE; P3.5 GC е следващият голям риск/кандидат). Всеки милстон =
функциональност + пример + regression + docs + commit + push.

## Environment / workflow constants (НЕ променяй!)

- Repo: `/shared/compiler` — **noexec mount**: `./bin/*`, `build.sh`, `test.sh` не вървят там.
- Build: `bash /shared/compiler/build.sh` (създава `/shared/compiler/out`).
- Tests/run: **`scripts/run_tests.sh`** от repo-root (arm64 нативен или qemu-aarch64
  `-L /usr/aarch64-linux-gnu`); /tmp/opencode е само за еднократни probes/edge-тестове.
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
  `b9e3499` P2 String.equals/concat → `7840f2c` docs CONTEXT HEAD →
  `081e7a2` P3 classes: fields+new+access → `da04822` docs →
  `8f11393` P3 methods + overloads → `16b46dc` docs →
  `27d0ac9` P3 конструктори + `this` chaining → `e31e319` docs →
  `ed21fa3` P3 instanceof/cast exact-class (obj4) → `6f056f7` P3 extends/super/subtype (obj5) →
  **`580c5fd` P3 vtable dispatch + override (обj6; README/ROADMAP вкл в същия комит)**
  → **`5a17f16` P3 static полета (обj7)`.** → **P3 явен super.method/field (обj8, HEAD)**
  → **`1ac9d83` P3 `Foo[]` масиви от обекти (обj9; 27/27)** → **`ee95a94` P3 `void` методи +
  `static void main` (обj10; 28/28)** → **`be8233d` P4 control flow (cflow; 29/29)**
  → **`961995e` P5 exceptions (exc; 30/30)**
  → **`60c6ddb` import/packages (imports; 31/31; harness `scripts/run_tests.sh`; `-no-pie` link)**
  → **`31db14d` P6 corelib ядро (natives.c + `-lm`, param-width fix, `.statics` init)**
  → **`2955350` P6 corelib DONE: shift/bitwise ops + Random бит-идентичен с JDK (33/33)**

## Status (актуално към HEAD = 2955350, регресия 33/33)

- DONE: P0 infra; P1 long/double + native; P2 arrays; P2 String/char/System.out;
  **P2 multi-D arrays (17/17 regression, pushed)**; **P2 String.equals/concat
  (18/18 regression — str3.mj)**; **P3 classes: fields+new+access (19/19 — obj.mj)**;
  **P3 methods + overloads (20/20 — obj2.mj)**;
  **P3 конструктори с аргументи + `this` chaining (21/21 — obj3.mj)**;
  **P3 instanceof/cast exact-class (22/22 — obj4.mj)**;
  **P3 наследяване `extends` + `super(...)` + subtype instanceof/cast (23/23 — obj5.mj)**;
  **P3 vtable dispatch + override (24/24 — obj6.mj)**;
  **P3 static полета (25/25 — obj7.mj)**;
  **P3 явен `super.method()`/`super.field` (26/26 — obj8.mj)**;
  **P3 `Foo[]` масиви от обекти (27/27 — obj9.mj, без frontend промени — vtable+fields
  от obj6-obj8 покриват всичко; проверки с ObjArrProbe2)**;
  **P3 `void` методи + `static void main` (28/28 — obj10.mj, `mapType("void")→"void"`,
  arm rule `return()` с `mov x0,#0`)**;
  **P4-Control (29/29 — cflow.mj): short-circuit `&&`/`||`, compound `+= -= *= /= %=`, `++`/`--`
  pre/post, assignment-as-expression, labeled break/continue, enhanced-for, `for(;;)`, латентен
  bugfix bare-name MethodInvocation double-arg-eval**;
  **P5-Exceptions (30/30 — exc.mj): `throw`/`try`/`catch`/`finally` + runtime unwinding —
  ops `EH_LAB`/`FP`/`EH_EXC`, func `.ehvar/.ehcatch/.ehfin/.ehsrc` metadata, per-try dispatcher
  с restore **преди** match-та (иначе stale `exc_head` re-catch в безкраен цикъл), `ExcRec` pool
  + `exc_head`, `k_throw` chain-walk → uncaught `#<classIndex>` exit 3, frontend
  `ExcGuard`/`unwindGuards()` за return/break/continue + finally по 3-те пътя + `ehf` re-throw**;
  **Packages + import (31/31 — examples/imports/imports.mj): single-type `import pkg.Cls`,
  on-demand `import pkg.*`, static `import static pkg.Cls.m`/`import static pkg.Cls.*`, транзитивно
  зареждане на модула чрез reflection walk (референциите се събират като dotted `ReferenceType`,
  identity-seen цикъл-guard), пакетен префикс се нормира експлицитно (`norm()`), source root-ове
  през `-I dir`/`--src d1:d2` на `ast-lower` (и `-I` в `mc`); `mc` линква с `-no-pie`.
  Test harness: `scripts/run_tests.sh` (31 теста, arm64 нативен/qemu)**.
- ACTIVE: Packages + import **завършени** (31/31); **P6 corelib DONE (33/33)**.
  Следва P3.5 GC или избор на GC решение (виж NEXT MOVE).
- Regression: **33/33 PASS на arm64** (scripts/run_tests.sh): hello 47, gcd 12, fib 55, forloop 55,
  dowhile 55, ternary 5, switch 92, print, dbl, lng, mix, arrays, oob 134, str, str2, md, native(-lc),
  str3 (`1/0/0/7`, `abcdef`, `6/6`, `xabc`, `abcABcd`, `1`),
  **obj** (`5/7/6/12`, `1/2/3/1`, exit 2),
  **obj2** (`6/7/7/17/117`, `7/14/8`, exit 10),
  **obj3** (`3/30/10/20`, `5/6/100/15`, exit 26),
  **obj4** (`1/0/0/1`, `7/9/0`, exit 16),
  **obj5** (`15/7/1/1/0/1/7/7/2/1/4/0`, exit 77),
  **obj6** (`18/64/8/11/-1/64`, exit 34),
  **obj7** (`2/2/3/4/100/100/107/2/2`, exit 42),
  **obj8** (`3/8/10/6/96/996/11/102/15/15`, exit 42),
  **obj9** (`28/11/1/15/25/5/5/4`, exit 42),
  **obj10** (`1/2/3/1/2/42`, exit 0),
  **cflow** (`30/2/23/23/22/40/24/33/8/103/3`, exit 0),
  **exc** (`10/110/111/7/114/118/518/50`, exit 3 — uncaught),
  **imports** (`12/9/4/10/40/12`, exit 0 — packages/static import),
  **corelib** (JDK-точни числа от `Random(1L)`: `-1155869325/431529176/…`; exit 0),
  **bitop** (36 стойности, JDK oracle-идентични; exit 0).

## NEXT MOVE (при "continue")

**P6 corelib (M8) е завършен** (examples/corelib.mj + examples/bitop.mj; **33/33**) →
следва избор между:
1. **P3.5 GC решение** — паметта е arena/без free-ване от P0; GC (mark-sweep по vtables/header
   seam от P3) е големият риск за P6+ (core lib обекти/контейнери, P7 threads).
   Решение: (A) ръчен conservative mark&sweep по stack scan или (B) MMTk binding.
   Seam-ът (header class index + pad, `mm_alloc`) е готов от P3. **Също така реши 16KB
   bump-arena OOM (M3.5-T1)** — дългите низови run-ове пак рискуват (3.5 бъг, жив).
2. **M8 остатък (не-блокер)**: Math `exp/log/sin/cos/tan` natives; `parseInt/parseLong`
   пълни `NumberFormatException`-и; MiniF оставен настрана.

Цикъл: frontend → rules (ако нови ops) → пример (`examples/*.mj`) → `scripts/run_tests.sh`
(33/33→N/N) → README/ROADMAP/CONTEXT → комит+push.

## Pipelines-факти (проверени, няма нужда да се преоткриват)

### P6 — shift/bitwise ops, literali, Random, DCE (проверено в този milestone)
- **Shift/bitwise оператори (M8-T1, всички работи end-to-end)**: frontend
  `mapOp` добавя `<<`→`shl`, `>>`→`shr`, `>>>`→`ushr`, `&`→`and`, `|`→`or`, `^`→`xor`;
  unary `~`→`not`; **shift-дистанцията се маскира** с `&63` (i64) / `&31` (i32) и в
  `expr(BinaryOperation)`, и в `assignVal` (compound); compound `<<= >>= >>>= &= |= ^=`.
  SsaLower: XOR_/SHL_/SHR_/USHR_/NOT_ suffix. Rules: `arm.rule` — lsl/asr/lsr, eor, mvn,
  and/or; `x86.rule` — shl/sar/shr `%cl` (i32 `movl %eax,%ebx` dance + i64), andq/orq/xorq/notq.
- **IntegerLiteral hex/bin/underscore (bugfix)**: `expr(IntegerLiteral)` ползваше
  `Long.parseLong` → `0x1000` фърляше `NumberFormatException`. Сега ползва съществуващия
  `parseLongSmart` (hex `0x`, oct `0`, bin `0b`, underscores) — пътят е общ (`int` и `long`
  литерали), както и `constNumericInit` в `.statics`.
- **Детерминирана DCE root (bugfix, ВАЖНО)**: `common/Opt.java` root-ваше `call`/`CALL_*`,
  но НЕ `icall`/`ICALL_*` → **void-виртуални call-ове като dead code се изтриваха**.
  Реалният случай: `Random()` ctor-а вика виртуалния `setSeed(seed)` (void) → DCE го
  махаше → seed оставаше 0 и `nextInt()` даваше `0` вместо JDK-ското `-1155869325`.
  Fix: `icall` + `ICALL_*` са rooted. Симптом ако се счупи пак: ctor с side-effect
  виртуален call се смалява до празно тяло (8 icalls → 7 в opt.ssa).
- **Random = чист MiniJ (финален)**: `corelib/java/util/Random.mj` — JDK LCG
  `nextSeed = (seed*0x5DEECE66D+0xB) & 281474976710655L` (маската е **литерал** — static
  const-fold на `(1L<<48)-1` не се прави), `>>>`-read-ове за bit extraction, `nextInt(bound)`
  = **JDK rejection sampling** (не modulo). **Бит-идентичен с JDK-17** от `Random(1L)`:
  `-1155869325/431529176/7564655870752979346/207/0/-1465154083/78/48`. Random natives-ите
  (RMULT/RADD/RMASK, k_native_Random_*) са ПРЕМАХНАТИ от `runtime/natives.c`.
- **Emitter i32 const с бит 31 (потвърдено)**: `movz/movk` 16-bit половинки (цикличния
  `(x & 0xFFFF)` / shift) handle-т отрицателни i32 константи коректно — bitop 32-bit
  изходи (напр. `-2147483648`) мачават JDK.

### Janino AST (верни, supersede по-стари предположения)
- **P3 classes shapes**: `class Foo { int x; long w; String s; }` — класовите полета се взимат
  през `cd.getVariableDeclaratorsAndInitializers()` (рефлексия `invoke0`), елемент = FieldDeclaration
  cъс `type` (Java type toString: "int"/"long"/"String"/"Foo") и `variableDeclarators` (Object[]).
  `new Foo()` → `Java.NewClassInstance(type.toString()="Foo", arguments=[], qualification=null)`;
  0-арг ctor. Достъп `f.x` = **`AmbiguousName[f, x]`**, `f.x = v` → `Assignment(lhs=AmbigName…,
  rhs)`; верига `h.next.next.v` = AmbigName с 4 ids. `.length` само за масиви/String.
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
- **П3 ctors (obj3, проверено)**: конструкторите са **`Java.ConstructorDeclarator`** в отделен
  список `cd.constructors` (НЕ в `declaredMethods`!), `name="<init>"`, `formalParameters`/
  `statements` от `FunctionDeclarator`, чейнинг `this(...)` е **отделен поле**
  `constructorInvocation` = `AlternateConstructorInvocation(arguments)` (НЕ стои в statements);
  `super(...)` = `SuperConstructorInvocation` — поддържа се от P3 extends (resolve по super `::<init>`
  sig-ове, args=[this, args...]; имплицитно `super()` ако няма явен). Символи:
  `Foo_init`/`Foo_init_i32` (0-арг: `Foo_init`). `new Foo(args)` → alloc_obj+st_hdr, после
  `resolveSig(cs, args)` по `classMethods["Foo::<init>"]` → call с args=[obj, args...], методът
  връща obj; ако класът няма ctor и args=0 → само alloc (arena-та е zeroed). Ctor тялото ползва
  bare fields / `this.x` (вече работещо от methods-милстона). В cls() всички методи/ctor-и минават
  през общ `buildSig/matchSig` (match по arity+java типове, НЕ get(0)).
- **void return (bugfix, ВАЖНО)**: преди `emit("return","void", null)` за void функции създаваше
  **фантомен null arg** (Reader/Writer асign-ваше му id → `mov x0, w9`, operand mismatch). Сега:
  (1) frontend `return void` ЕМИТИРА без args; (2) `Ir.Reader` оставя `return` **гол**
  (`"return"`, без suffix) за функции с `retType==void` (иначе `RETURN_i32` с 0 args → "no rule
  matches"); (3) SsaLower guard същия: void → стар `"return"`; (4) **rule-и `emit return()`
  в arm.rule (`b ${exit}`) и x86.rule (`jmp ${exit}`)**.
  Мантика: `Reader` double-suffixing-а `return` при всеки re-read беше скрит източник на бъгове.
- **P3 `void` методи + `static void main` (obj10, проверено)**: `mapType("void")` сега връща
  **`"void"`** (преди падаше до fallback `"i32"`). Симптомът: void метод се сигнираше `-> i32`
  (buildSig/retIr от mapType), но `return;` емитираше гол `return` → Reader-ът (sp. `retType.equals
  ("void")`) го правеше `RETURN_i32` с 0 args → `no rule matches op 'RETURN_i32' with 0 args`.
  С fix-а: `retIr="void"`, `f.retType="void"`, гол `return` минава чисто до `return()` rule-а.
  Call-site-овете (static 841/virtual `vt_ref`+`icall` 992, 1399, bare-name 1461) вече падаха на
  `crt="i32"` за void — без промени. `static void main()` + exit: `crt0.S` = `bl main; mov x8,#93;
  svc #0` → exit взима x0 → arm64 `return()` rule-а върна и добавя `mov x0, #0` (x86 parity: `movl
  $0, %eax` пред `jmp ${exit}`; x86 es локально не-тестляем — aarch64-only binutils). Пример
  `examples/obj10.mj` (instance `reset()/add1()` + static `printAll()` + изрични `return;` +
  инт-методи + `static void main`) → `1/2/3/1/2/42`, exit 0.
- **P4-Control (cflow, проверено — всичките по-долу са прогонени в /tmp/opencode)**:
  `Java.LabeledStatement`/`Block`/`If`/`For`/`While`/`Do`/`ForEach`/`Break`/`Continue` всичките
  имплементират `Java.BlockStatement` → разпознават се в `stmt(Java.BlockStatement)`. Labeled:
  `label: Тяло` → карти `labelBreak`/`labelCont` (label → Ir.Block), `break lbl`/`continue lbl`
  резолват от тях (иначе `# break label not in scope:` + токо-блок); unlabeled break/continue
  ползват стека `breaks`/`conts`. **`continue lbl` за for → step-блока, за while/do → head-блока,
  за for-each → feat_ step-блока.** Non-loop labeled тяло → `lblb_<id>` exit блок.
- **Enhanced-for shape (проверено)**: `for (T x : arr)` = **`Java.ForEachStatement`**, полета
  `currentElement` (FormalParameter: `name`, `type`), `expression` (Rvalue), `body`;
  ForEachStatement наследява `Java.ContinuableStatement` → `.body` е на ancestor-а (родител на
  `expression`!). Lowering: alloca counter `_ec<id>` + блокове `feh_/feb_/fest_/fex_`;
  `len`+`cmplt`, body: `chk`+`lea_<elIr>`+`ld_<elIr>` → store в елементния alloca `_e<id>`;
  continue → `fest_` (step), break → `fex_`. **Елементният слот СЛУЖИ като SSA phi (SSA промотира
  всички alloca-та) → задължително инициализирай с `const 0 EL`-тип преди цикъла**: иначе
  entry-edge стойността на phi-я няма localocation, regalloc я слага в скретч `w9` → 2-arg
  `MOV_i64(ptr)` = `mov x28, w9` → operand mismatch при as. (i32 елементите минават случайно — и
  двете са w-reg.) `for (;;)` → `f.update` може да е **null** (не празен масив) → guard.
- **Short-circuit `&&`/`||`**: бяха `BinaryOperation(operator=&&/||)`, lowered eager като
  `and`/`or` (RHS винаги вали). Сега `expr()` → `scAndOr(b, isAnd)`: alloca tmp i32, блокове
  `sc_<id>_e(else)/_o(out)/_j(join)`, `cmpeq`/`cmpne` с `konst(0, c.type)`; `&&`: lhs==0 → store 0,
  иначе eval rhs → `cmpne(rv,0)` → store; `||`: lhs!=0 → store 1. Връща load i32 (1/0). Правилата
  `AND_i32`/`OR_i32` остават — subtypeTest OR-веригата (obj5) ги ползва.
- **Compound + assignment/c-crement (expr-стойности)**: `handleAssign` игнорираше `operator` →
  `a += 1` тихо ставаше `a = 1`. Сега: `Tgt(ptr, t, isAlloca)` record; `tgtFor` покрива
  ArrayAccess(`chk`+`lea_<el>`), AmbigName>1 (fieldAddr), локал-име (allocaOf → `load`/`store`),
  fieldThis, SuperclassFieldAccessExpression, FieldAccessExpression; `readTgt`/`writeTgt` =
  `load`/`store` или `ld_<t>`/`st_<t>` (по isAlloca). `assignVal`: `=` → conv(expr(rhs))+write;
  compound (`+= -= *= /= %=`) → `wide()`(lhs.ir,r.ir) → conv → `mapOp(чист binop)` → conv обратно →
  write; **compound/assign връщат стойността** (assignment-as-expression). `Java.Crement` полета:
  `pre` (boolean), `operator` ("++"/"--"), `operand` (Lvalue); `crementVal` = readTgt + add/sub 1 +
  writeTgt; pre → new, post → old; → работи `a[i]++`, `a[i++][j]--` и като функция-арг.
- **MethodInvocation double-arg-eval (латентен bugfix, ВАЖНО)**: bare-name path (expr ред ~1571)
  оценяваше ALL args в общ `args` списък ПРЕДИ resolve-а и пак в `cargs` за call-а → `f(bump())`
  викаше bump-а два пъти, `f(a=f(b))` даваше грешка (36 вместо 16). Фикс: args се оценяват ВЕДНЪЖ
  в конкретния клон (sysout/msiglist/native), И винаги **ПРЕДИ** `emit("call")` — call-ът трябва да
  стои СЛЕД arg-инструкциите в IR/asm потока, иначе x0/x1 четат scratch garbage (obj10/print/str с
  `k_println_i32(c.bump())` дефакто го доказаха).
- **П3 наследяване `extends` + `super(...)` (obj5, проверено)**: `NamedClassDeclaration.extendedType`
  е `Java.ReferenceType` c `identifiers=[Име]` (или null). Frontend dържи `classSuper (cls→super)` и
  `allDecls (cls→decl)`; Main прави 3 pre-pass-a: `collectClass` (рекурсия в super-а ПЪРВО, цикличен
  guard `visiting`) → `collectSigs` (метод DB за всички класове) → `emitMethods` (bodies). Layout:
  подкласовите полета continue-ват OТ super-а: offset започва от `classSizes[super]`, super-полетата
  се копират в subclass map-a на сЪщите offsets (съвместим header+field-layout). Символите остават
  статични (`B_sum_i32_i32` на дълбочината, където са дефинирани). Инстанс-метод dispatch:
  `lookupMethod(cls,name)` walk-ва `classSuper` веригата; bare-name скан предпочита super-веригата на
  curClass преди glob-алния. Ctor чейнинг: `this(...)` (`AlternateConstructorInvocation`) и `super(...)`
  (`SuperConstructorInvocation`, поле `constructorInvocation`); super-ctor-ът се resolve-ва по
  `classMethods[super+"::<init>"]`, извиква се с args=[this, args...]; ако няма явен ctor-invocation и
  super-класът има 0-арг ctor → имплицитно `super()`. `instanceof`/cast вече НЕ сравнява exact index:
  `subtypeIndexes(tc)` = рефлексивно-транзитивно затваряне на sub по `classSuper`; `subtypeTest(ci, tc)`
  = OR-верига от `cmpeq_i32`/`or_i32` за всеки индекс от затварянето (едноблокова, без control flow).
  Cast до несъвместим тип → null (Java cast-семантика; obj5 проверява `nc != null ? 1 : 0`).
- **P3 vtable dispatch (obj6, проверено)**: virtual instance-calls = `vt_ref` + `lea_field(slot*8)` +
  `ld_i64` + `icall`. Slot = `name(pjts…)` (`sigKey`), per-клас list в `classSlots`: super-списъкът
  като prefix + собствените не-static методи (по реда на `classMethods` за класа; static/<init>
  excluded). Override = един и същ key заема една позиция навсякъде в йерархията; позицията на
  слота за даден call се търси в `classSlots[ms.cn].indexOf(sigKey)` — НЕ глобален index-by-key
  (независими йерархии с еднакъв sigKey си пречат!). `vt_ref` (искано: arm64) load-ва header
  class index, `adrp/add x9, vtables`, `add x11, x9, w11 uxtw #3`, `ldr $dst`; `icall` блр-ва
  fn-pointer с args от argreg 1 нататък (`${cargs}` = "call args" за прескачане на receiver param 0).
  Bare instance call в instance метод (`legend()` → `mark()` в Shape) също минава през virtual на
  `this` (load от allocaOf["this"]). Нов `.vtables` IR блок (Writer/Reader/add-а `readVtables`);
  SsaLower: `icall` → `ICALL_<s>`. Emitter: `icall` rule + `vt_ref` rule + `${cargs}` loop-list
  (base=1; `${ws}/${args}/${argregs}`) + RuleParser `args...` (име+any) + bind-clip `i < args.size()`.
  **ВАЖНО: vtables трябва да са в `.data`, не `.rodata`** — vtables array и slot-ове са `.quad
  <символ>` → R_AARCH64_RELATIVE при load в PIE; ldso пише в тях, а .rodata е RO → segfault
  (gdb bt: `ldso/dynlink.c do_relocs`).
- **P3 static полета (obj7, проверено)**: `Foo.count`/`f.count` (read И assign) = **2-ид
  `AmbiguousName`**; `this.count = v` → `Assignment(lhs=FieldAccessExpression(lhs=ThisReference))`;
  bare `count` = `AmbiguousName` с 1 id. `FieldDeclarationOrInitializer` членовете се проверяват с
  `mb instanceof Java.FieldDeclaration fd && fd.isStatic()`; init-ите се отхвърлят (грешка).
  data symbols: `<ClassName>_<name>`; `.bss` storage (`.globl` + `.zero 4/8`), нов `.statics` IR
  блок `symbol : size` (Writer/Reader `readStatics`). Нов op `lea_static` (0-арг, името се носи
  като call-ите: Writer-ът го пише в call-branch-а с `:type()`, Reader `lea_static`→call-branch-а;
  arm64 `adrp x9,sym`/`add $dst,x9,:lo12:sym`, x86 `leaq sym(%rip),$dst`). Пътища: `ambigStatic`
  в `fieldAddr` (2-ид AmbigName: `classIndex.containsKey(ids[0])` клас-квалифициран, иначе
  `varJType` реceiver-а), static fallback в `fieldThis` (bare) и `fieldAddrFrom` (this/expr);
  `staticField(cls,name)` walk-ва `classSuper` → наследени static-и. `inferType` resolve-ва stats-а
  преди generic `i32` (иначе `Foo.bigDouble` като arg ще иде в `k_println_i32`). **`curClass` се
  сенася дори за static методи** (line 444: `sig.cn`), затова bare static достъп работи и в static
  методи (fieldThis `allocaOf["this"]==null` → instance lookup пада → static fallback).
  **ВАЖНО: bare `this` като стойност** (`last = this`) — преди падаше тихо до konst 0; сега:
  `expr(ThisReference)` → `load allocaOf["this"]` (throw извън instance метод).
- **P3 явен `super.method()` + `super.field` (obj8, проверено)**: Janino ползва **отделни AST
  класове**, НЕ MethodInvocation/FieldAccessExpression: `super.m()` =
  **`Java.SuperclassMethodInvocation`** (`Invocation.methodName`, `Invocation.arguments`, имплементира
  `Rvalue` — деца в BinaryOperation), `super.f` = **`Java.SuperclassFieldAccessExpression`**
  (`fieldName`, `qualification=null`, `value=null`). Detect: `getSimpleName()` равен на
  "SuperclassMethodInvocation"/"SuperclassFieldAccessExpression" (двата са nested в Java). Lowering:
  `superCall()` → resolve по super-веригата от `classSuper[curClass]` (`resolveSig` в
  `classMethods["<sup>::<name>"]`, non-static) → **директен `call <sym>`** с `[this, args…]`
  (без `icall` — това е разликата спрямо vtable). `superFieldAddr(name)` = `this.<name>` (наследен
  layout, същите offsets) + static fallback в super-веригата; read в `expr()`, write в `handleAssign`.
  Helper-и: `isSuperNode(o,"ClassSimpleName")`. Проверки в MIR: `B_useSuper` → `call A_g:i32`,
  `C_twoLevel` → `call B_g:i32`.
- **P3 `Foo[]` масиви от обекти (obj9, проверено, НУЛЕВИ frontend промени)**: `arr[i].f` =
  **`FieldAccessExpression(lhs=ArrayAccessExpression, fieldName)`**; `arr[i].m()` =
  **`MethodInvocation(target=ArrayAccessExpression)`** (НЕ AmbigName [a,i,m]!). Работи всичко през
  съществуващото: `elemOf("Foo[]")`→`"ptr"` (branch `classIndex.containsKey(c)`), `new Foo[n]` →
  `alloc_ptr`+`st_hdr`, `arr[i]=new Foo()` и `arr[i].f=v` → `chk+lea_ptr+st_ptr`,
  `javaTypeOf(ArrayAccessExpression)` дава елементния Java-тип ("Foo") → `fieldAddrFrom(arr[i], f)`
  работи, `classSigs` → virtual `vt_ref`+`icall` диспечва по динамичния клас (override „през
  себе-то": `arr[2].legend()` на Box елемент → `Shape_legend`→`this.mark()`→`Box_mark`). Проверено с
  ObjArrProbe2 (AST форми) + obj9 compile/run.
- **Emitter spill bugfix (с този комит, ВАЖНО)**: `saveSpills` записваше обратно само РЕЗУЛТАТА на
  инструкцията. Phi-move-ите от PhiElim са 2-арг `MOV_<s>(val, phiDst)` — dst (phi) стои в `args[1]`.
  Ако phi-то е spill-нато, стойността оставаше в скретч-temp и се губи при jump-а (join-блокчето чете
  stale slot → грешен резултат/непредвидимост). Фикс: в `saveSpills` за 2-арг MOV и spill-нат `args[1]`
  → `spillStore(args[1])`. Не докосва 1-арг MOV/copy; 22/22 предишни тестове пак минават.
- **Нови ALU rules (с този комит)**: `AND_i32`/`OR_i32` (SsaLower → `and`/`or`); arm64:
  `and`/`orr $dst, ${x}, ${y}`; x86: `movl x→%eax; andl/orl y; movl %eax→dst`. Използват се от
  subtype OR-веригата; освен това оформят `&&`/`||` операторите. Преди тях липсваха нулеви правила
  → "no rule for op 'OR_i32'".
- `javaTypeOf`/`inferType` за MethodInvocation: `concat`→`"String"`/`"ptr"` (иначе println
  ще вземе `k_println_i32`); `equals`→`"int"`/`"i32"`; P3: клас-методи → resolved `retJt`.
  `AmbigName` с >1 ids: само `identifiers[1]=="length"` дава int; всяко друго връща типа на
  receiver-а (`s.equals` се типизира като "String") — пълният `length>1 → int` чупи String
  dispatch-а; `FieldAccessExpression` → javaTipo на полето (classFields lookup);
  `ThisReference` → varJType["this"].
**П3 methods shapes (obj2, проверено)**: `p.add(1,2)` → `MethodInvocation(methodName, argument,
  target=AmbigName [p, add])`; `this.n` → **`Java.FieldAccessExpression`** (не AmbigName — `this`
  е primary), поле = `fieldName`, lhs=`ThisReference`; bare `n` (в instance метод) = AmbigName с 1 id.
  Static call `Counter.make(7)` → target AmbigName [Counter, make]. Приеман `this.add()` също
  AmbigName [this, add]. `inc()` cto `static int main()` в клас → target=null → bare-name scan.
- **П3 method sig DB (frontend)**: `MethSig{cn,name,symbol,retIr,retJt,pjts[],pirs[],isStatic}` в
  `classMethods Map "cls::name" → List<MethSig>` (overloads). Symbol mangling:
  `cn_name_<irparams>` (напр. `Counter_sum_i32_i32`, `main` остава "main"). Instance методите
  имат **скрит `this` param 0** (`f.params[0]={"this","ptr"}`, `varJType["this"]=cn`) — CALL_ rule
  потегля от argreg 0, работи без Emitter промени. Overload resolution = `resolveSig` по arity +
  inferType-на арг. exact match, fallback първия с arity.
- Dispatch ред в `expr()` MethodInvocation: (1) String equals/concat, (2) **classSigs** — за
  `AmbigName[ClassName,m]` (static) и `javaTypeOf(tgt)==клас` (instance, receiver=`recvValue(mi,tgt)`
  = load от alloca на ids[0] / expr(tgt)), (3) System.out, (4) **bare-name scan** по всички
  `classMethods` keys (`::name` суфикс; half/dash/repeat са User static методи) — иначе native mangle.
  Манткироването на символа изисква sig-ът във втората cls() итерация да се match-не към конретния
  MethodDeclarator (по arity+jt), НЕ `list.get(0)` (3 overload-а sum → троен dup bug).
- **`this`/FieldAccess баre-fallbacks**: експr бележи field read `this.n`/`d.n` (FieldAccess →
  `fieldAddrFrom(base,nm)` — load alloca за this/locals, expr(base) за др., + lea_field+ld); bare
  име в instance метод fallback `fieldThis(nm)`; `handleAssign` — същите два пътя + FieldAccess lhs.

### Типове / layout
- **P3 object layout**: обект = 8-byte header (class index + pad/GC), полета от +8, aligned по тип
  (i32→4, i64/f64/ptr→8); `collectClass` pre-pass строи `classIndex (className→int)`, `classSizes`
  (мин. 16, align 8), `classFields (cls → field → {irType, byteOff, javaType})` от всички типове
  ПРЕДИ lowering (свободни препратки). С P3 extends: subclass `classFields` включва super-полетата
  на сЪщите offsets, начален offset = `classSizes[super]` (първо super-ът се collect-ва рекурсивно).
  Header index се записва с `st_hdr` (i32 const).
- **Нови ops в P3**: `alloc_obj(size-i32)` (arm: sxtw x9→x0; mm_alloc; x86: movslq→%rdi) и
  `lea_field(base-ptr, off-i64 const)` (arm: `add dst, a, o` — и двата x-reg; x86: movq+addq).
  ЗАБЕЛЕЖКА: arm `mov x0, ${w-reg}` от i32 const НЕ валиден — винаги `sxtw x9, ${c}`.
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

- `bash scripts/run_tests.sh` (от repo-root; self-contained) → build + **33/33** теста
  (arm64 нативен хост или qemu-aarch64 с `-L /usr/aarch64-linux-gnu`; `--only=<name>` филтър).
  (Може да отнеме >2 мин — таймаут-ът на bash tool трябва да е ~400s; на тази машина
  ~1 min на native aarch64.)
- Тест за нов пример = добавяне на `check <name> <exitcode> $'expected\ndata\n' <src>`
  в `scripts/run_tests.sh` + обновяване на броя и README/ROADMAP/CONTEXT.
- Примерни файлове за multi-D: `examples/md.mj`. OOB multi-D (m7/m8 .mj в /tmp/opencode) → exit 134.
- Отделни минимални програми за бисouter (m1..m8.mj) стоят в /tmp/opencode; не се комитват.
- P4 edge-тестове в /tmp/opencode: t4a–t4g (short-circuit, ternary+assign, labeled, for-each,
  compound), e1–e7 (labeled continue/break do/for, compound на масив елемент+`a[i++]++`, &&-вериги
  със side-effect, for-each обекти/String/2D, assignment-as-arg, `for(;;)`). Не се комитват.
- Примерни файлове за multi-D: `examples/md.mj`. OOB multi-D (m7/m8 .mj в /tmp/opencode) → exit 134.
- Отделни минимални програми за бисouter (m1..m8.mj) стоят в /tmp/opencode; не се комитват.
- P4 edge-тестове в /tmp/opencode: t4a–t4g (short-circuit, ternary+assign, labeled, for-each,
  compound), e1–e7 (labeled continue/break do/for, compound на масив елемент+`a[i++]++`, &&-вериги
  със side-effect, for-each обекти/String/2D, assignment-as-arg, `for(;;)`). Не се комитват.

## Relevant Files (карта)

- `tools/ast-lower/AstLowerMain.java` — frontend (mapType/elemOf/elemOfAccess/javaTypeOf/varJType/
  methodRetJt/newArray2D/NewArray rewrite/FieldAccessExpression.length/inferType/decodeString/
  System.out dispatch/String-methods dispatch;
  **P3: collectClass/invoke0/irType/fieldAddr/fieldAddrFrom/fieldThis (FieldAddr)/
  NewClassInstance→alloc_obj+st_hdr/object-field read+write; methods: MethSig + classMethods DB,
  methods: MethSig + classMethods DB, method mangling + скрит `this` param 0, classSigs/static-call
  dispatch, resolveSig overloads, bare-name scan, javaTypeOf class-method resolution;
  ctors: cd.constructors (ConstructorDeclarator), ctorSym `Foo_init*`, this(...)-chaining via
  constructorInvocation, NewClassInstance ctor call, void-return без args;
  vtable: sigKey/classSlots/buildVtables/vtableSymbol/virtualCall (icall) + bare-this virtual;
  statics: staticFields (FieldDeclaration.isStatic, init→error), staticField/staticAddr/
  staticAddrField/ambigStatic, static fallbacks в fieldAddr/fieldAddrFrom/fieldThis, expr(ThisReference);
  super: superCall (SuperclassMethodInvocation, direct call по super-верига + resolveSig),
  superFieldAddr (SuperclassFieldAccessExpression read+write), isSuperNode**;
  **Foo[]: без промени — elemOf `classIndex` branch + FieldAccessExpression/classSigs с
  ArrayAccessExpression база (обj9, проверено с ObjArrProbe2)**;
  **void: mapType("void")→"void" (обj10)**;
  **P4: labelBreak/labelCont карти, stmt() диспеч по While/Do/For/ForEach/Labeled, helpers
  doWhileStmt/doStmt/forStmt(lbl)/forEachStmt, scAndOr (&&/||), assignVal/Tgt/tgtFor/readTgt/
  writeTgt (compound += -= *= /= %=), crementVal (++/--), MethodInvocation args-eval bugfix**;
  **P6: mapOp << >> >>> & | ^ ~ + shift модели с &63/&31, compound bitwise, IntegerLiteral →
  parseLongSmart (hex/oct/bin/underscore)**;
  **import: main `-I/--src` parsing, addUnit/loadClosure/processImports/loadClassFile/loadFromPath/
  findFile/resolveRefs/onDemandPkgs, walkRefs (reflection, seen-guard), norm() (mapType/irType/
  elemOf/signatures/locals/cast/instanceof/new/array/try-catch), importedStaticField{,Load,Jt}/
  importedStaticMethod (read/write/infer), curUnit**).
- `common/Opt.java` — DCE; **root-ва `icall`/`ICALL_*` (P6 bugfix)**.
- `common/Emitter.java` — spill support (spillTemp/spillBytes/stemp/slotMem/spillLoad/spillStore/
  prepareSpills/saveSpills/maxSpillBytes), x86 ptr→movq, template interpreter + Ctx,
  **`${cargs}` loop-list (base=1), vtables в `.data` (R_AARCH64_RELATIVE → не .rodata),
  statics в `.bss` (p2align 3 + .globl + .zero)**.
- `common/Regalloc.java` — linear scan + preassign, loop/phi/void liveness, "stack -N" locs.
- `common/Ir.java` — Writer/Reader; **void `return` остава гол**; **`.vtables` блок (VTable →
  label+syms, readVtables)**; **`.statics` блок (Static → symbol+size, readStatics)**;
  **`lea_static` се пише/чете като call (name:type)**.
- `common/RuleParser.java` — rule v2 parser (Rules.width/isFpType); **`args...` патерн (име+any)**.
- `rules/arm.rule`, `rules/x86.rule` — include `alloc_i32/i64/f64/ptr`/`alloc_obj`, `st_hdr`, `len`,
  `chk`, `lea_i32/i64/f64/ptr`/`lea_field`, `ld_i32/i64/f64/ptr`, `st_i32/i64/f64/ptr`,
  CONST/MOV/ADD/…, **`return` (void: arm `mov x0, #0; b exit` / x86 `movl $0, %eax; jmp exit`)**
  , **`vt_ref`/`ICALL_i32/i64/f64` (`${cargs}`)**,
  **`lea_static` (${name}: adrp/add :lo12 / leaq sym(%rip))**, prologue/epilogue,
  **P6: SHL/SHR/USHR/XOR/NOT/AND/OR (i32+i64), `${imov}` 32-bit wrap**.
- `runtime/runtime.c`, `runtime/crt0.S` — k_* helpers (`k_print/k_println`, `k_print_i32/
  k_println_i32`, `k_newline`, **`k_string_equals`/`k_string_concat`**), mm_alloc, syscalls.
  `runtime/natives.c` — System/Math natives само (Random natives-ите са премахнати).
- `examples/*.mj` — hello, gcd, fib, forloop, dowhile, ternary, switch, print, dbl, lng, mix,
  arrays, oob, str, str2, md, str3, obj, obj2, **obj3**, **obj4**, **obj5**, **obj6**,
  **obj7**, **obj8**, **obj9**, **obj10**, **cflow**, native, **imports/** (imports.mj +
  geom/Geom.mj + shapes/{Shape,Circle,Square}.mj), **corelib.mj**, **bitop.mj**.
- `corelib/java/util/Random.mj` — чист MiniJ LCG (маска-литерал `281474976710655L`,
  rejection sampling), JDK bit-идентичен.
- `scripts/run_tests.sh` — regression harness (33 теста; `check corelib 0 "<JDK-точни>"` +
  `check bitop 0 "<oracle>"`; бивши `/tmp/opencode/run_tests.sh`
  беше с corrupt-edit — `run_out` без `}`/без `got=$?`, орязани expected-out за dbl/arrays/md;
  вече е в repo, self-contained, `TARGET` env (arm64), `--only=<name>`).
- `/tmp/opencode/*Probe.java` (SV/EV/CH/MDP/NAD/SE/**OProbe**/OProbe2/OProbe3/**StProbe**/
  StProbe2/StProbe3/StProbe4/**SupProbe**/SupProbe2/SupProbe3/SupProbe4, **BitOpOracle**,
  **r/R.java**) — Janino AST probes, преизползваеми. `/tmp/opencode/oracle.txt` — JDK oracle
  за `examples/bitop.mj` (36 стойности).
- `README.md`, `docs/ROADMAP.md`, `docs/ir-format.md`, `docs/rule-format.md`, `docs/corelib.md`.

## При съмнение

Първо попитай потребителя („за кое 'go' — X или Y?") при големи/свободни избори (напр. дали
next milestone е P3 objects или P2 tail); за малки неща действай по конвенцията на repo-то.