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
  `b9e3499` P2 String.equals/concat → `7840f2c` docs CONTEXT HEAD →
  `081e7a2` P3 classes: fields+new+access → `da04822` docs →
  `8f11393` P3 methods + overloads → `16b46dc` docs →
  `27d0ac9` P3 конструктори + `this` chaining → `e31e319` docs →
  `ed21fa3` P3 instanceof/cast exact-class (obj4) → `6f056f7` P3 extends/super/subtype (obj5) →
  **`580c5fd` P3 vtable dispatch + override (обj6; README/ROADMAP вкл в същия комит)**
  → **`5a17f16` P3 static полета (обj7)`.** → **P3 явен super.method/field (обj8, HEAD)**

## Status (актуално към HEAD = 2934afc, регресия 26/26)

- DONE: P0 infra; P1 long/double + native; P2 arrays; P2 String/char/System.out;
  **P2 multi-D arrays (17/17 regression, pushed)**; **P2 String.equals/concat
  (18/18 regression — str3.mj)**; **P3 classes: fields+new+access (19/19 — obj.mj)**;
  **P3 methods + overloads (20/20 — obj2.mj)**;
  **P3 конструктори с аргументи + `this` chaining (21/21 — obj3.mj)**;
  **P3 instanceof/cast exact-class (22/22 — obj4.mj)**;
  **P3 наследяване `extends` + `super(...)` + subtype instanceof/cast (23/23 — obj5.mj)**;
  **P3 vtable dispatch + override (24/24 — obj6.mj)**;
  **P3 static полета (25/25 — obj7.mj)**;
  **P3 явен `super.method()`/`super.field` (26/26 — obj8.mj)**.
- ACTIVE: P3 в ход — static полета + super dispatch готови; остава **`Foo[]` насочване** (виж NEXT MOVE).
- Regression: **26/26 PASS на arm64** (run_tests.sh): hello 47, gcd 12, fib 55, forloop 55,
  dowhile 55, ternary 5, switch 92, print, dbl, lng, mix, arrays, oob 134, str, str2, md, native(-lc),
  str3 (`1/0/0/7`, `abcdef`, `6/6`, `xabc`, `abcABcd`, `1`),
  **obj** (`5/7/6/12`, `1/2/3/1`, exit 2),
  **obj2** (`6/7/7/17/117`, `7/14/8`, exit 10),
  **obj3** (`3/30/10/20`, `5/6/100/15`, exit 26),
  **obj4** (`1/0/0/1`, `7/9/0`, exit 16),
  **obj5** (`15/7/1/1/0/1/7/7/2/1/4/0`, exit 77),
  **obj6** (`18/64/8/11/-1/64`, exit 34),
  **obj7** (`2/2/3/4/100/100/107/2/2`, exit 42),
  **obj8** (`3/8/10/6/96/996/11/102/15/15`, exit 42).

## NEXT MOVE (при "continue")

P3 super dispatch е **завършен** → следва:
1. **`Foo[]` масиви от обекти** — по `elemOf("Foo[]")`→`"ptr"` трябва да работят (alloc_ptr клетки,
   `new Foo[n]`, `a[i].m()`, `a[i].fld`). Пример `examples/obj9.mj` (масив от Box/Shape + override
   dispatch през себе-то); проверка за операторите `a[i].method()` (AmbigName [a, i, method]? или
   ArrayAccessExpression + MethodInvocation target) ПЪРВО с probe.
2. След това: `static void` за main без return (или следващ P4/P3.5 решение).

Цикъл: frontend → rules (ако нови ops) → пример (`examples/*.mj`) → `run_tests.sh`
(26/26→N/N) → README/ROADMAP/CONTEXT → комит+push.

## Pipelines-факти (проверени, няма нужда да се преоткриват)

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
  matches"); (3) SsaLower guard същия: void → стар `"return"`; (4) **нови rule-и
  `emit return()`** в arm.rule (`b ${exit}`) и x86.rule (`jmp ${exit}`).
  Мантика: `Reader` double-suffixing-а `return` при всеки re-read беше скрит източник на бъгове.
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

- `cd /tmp/opencode && bash run_tests.sh` → build + 26 теста; текущ резултат **26/26**.
  (Може да отнеме >2 мин — таймаут-ът на bash tool трябва да е ~400s.)
- Тест функции: `run name src exitcode`, `run_out name src exitcode $'expected\nout\n'`;
  добавяне на нов пример = `run_out obj8 "$DIR/examples/obj8.mj" 42 $'3\n8\n10\n6\n96\n996\n11\n102\n15\n15\n'`
  + обновяват се броя и README/ROADMAP/CONTEXT.
- Примерни файлове за multi-D: `examples/md.mj`. OOB multi-D (m7/m8 .mj в /tmp/opencode) → exit 134.
- Отделни минимални програми за бисouter (m1..m8.mj) стоят в /tmp/opencode; не се комитват.

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
  superFieldAddr (SuperclassFieldAccessExpression read+write), isSuperNode**).
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
  CONST/MOV/ADD/…, **`return` (void)**, **`vt_ref`/`ICALL_i32/i64/f64` (`${cargs}`)**,
  **`lea_static` (${name}: adrp/add :lo12 / leaq sym(%rip))**, prologue/epilogue.
- `runtime/runtime.c`, `runtime/crt0.S` — k_* helpers (`k_print/k_println`, `k_print_i32/
  k_println_i32`, `k_newline`, **`k_string_equals`/`k_string_concat`**), mm_alloc, syscalls.
- `examples/*.mj` — hello, gcd, fib, forloop, dowhile, ternary, switch, print, dbl, lng, mix,
  arrays, oob, str, str2, md, str3, obj, obj2, **obj3**, **obj4**, **obj5**, **obj6**,
  **obj7**, **obj8**, native.
- `/tmp/opencode/run_tests.sh` — regression harness (26 теста; беше оправен след corrupt edit:
  `run_out` без `}`/без `got=$?`, орязани expected-out за dbl/arrays/md).
- `/tmp/opencode/*Probe.java` (SV/EV/CH/MDP/NAD/SE/**OProbe**/OProbe2/OProbe3/**StProbe**/
  StProbe2/StProbe3/StProbe4/**SupProbe**/SupProbe2/SupProbe3/SupProbe4) — Janino AST probes,
  преизползваеми.
- `README.md`, `docs/ROADMAP.md`, `docs/ir-format.md`, `docs/rule-format.md`, `docs/corelib.md`.

## При съмнение

Първо попитай потребителя („за кое 'go' — X или Y?") при големи/свободни избори (напр. дали
next milestone е P3 objects или P2 tail); за малки неща действай по конвенцията на repo-то.