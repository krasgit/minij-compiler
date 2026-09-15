# Migrate: Termux → Debian + Maven (бранч `debianmvn_env`)

Целта на бранча е компилаторът MiniJ (`minij-compiler`) да се сглобява, тества и
изпълнява в чист Debian/Ubuntu x86-64 среда с Maven-ски Java инструментариум,
вместо оригиналната Termux/arm64 среда.

## 1. Какво беше счупено при първоначалното стартиране

При първия опит за `./build.sh && ./test.sh` всичките 4 регресии падаха на етап
`compile` с грешка от сорта на:

```
/home/kivanov/.../bin/janino-parse: cannot execute: required file not found
```

и дори самите `.sh` скриптове не можеха да се изпълнят. Причината беше, че целият
проект е писан за Termux и носи Termux-специфични `#!` шебангове и пътища.

## 2. Възстановени/V2 промени по файловете

### Шебангове (Termux → `/usr/bin/env bash`)
· `setup.sh`
· `build.sh`
· `test.sh`
· `mc` (главния драйвер)
· всички 7 wrapper-а в `bin/*` (`janino-parse`, `ast-lower`, `ssa-*`,
  `regalloc`, `phi-elim`, `emit-*`)

Първоначалният `#!` беше `/data/data/com.termux/files/usr/bin/bash`, който не
съществува на Debian → „required file not found“ още при стартиране на който и да
е tool wrapper.

### Jar-зависимости (Maven)
`setup.sh` и `mc` разчитат на Janino в `lib/`. Изтеглени са от Maven Central:

```
lib/janino.jar            ← janino-3.1.12.jar
lib/commons-compiler.jar  ← commons-compiler-3.1.12.jar
```

(проектът използва Janino като външен Java parser: `janino-parse`).

### Генераторите в `setup.sh`
`setup.sh` сама регенерира `bin/*` и `mc`/`build.sh`/`test.sh` през heredoc-ове.
Тези heredoc-ове **също носеха Termux `#!`** — поправени, така че повторно
регенериране отново да дава Debian-съвместими скриптове (иначе корекцията би
изчезнала при всяко `./setup.sh`).

## 3. x86-64: липсващ `crt0` и обърнат arg-staging

Компилаторът поддържа два таргета: **arm64** и **x86-64**. x86-64 беше
полуфункционален. Поправено:

### `runtime/crt0-x64.S` (нов файл)
`mc` (x86 клон) линкваше `$DIR/runtime/crt0.S`, но такъв файл **не съществува** —
имаше само `crt0.S` в ARM-вариант. Създаден е `runtime/crt0-x64.S` (x86 `_start`
→ `call main` → exit). Свързването на x86 програми вече работи.

### `rules/x86.rule` — поправен повреден arg-staging
Генерираният асемблер за извиквания имаше обърнати операнди в стейджинг-move-ите.
За вместо:

```
movl %edi, %ebx     ; ABI reg → временен (GREШНО, вземаше мръсен входен регистър)
```

вече се генерира правилната посока — стойността от staging-регистъра отива в ABI
аргумент-регистъра:

```
movl %ebx, %edi
```

Поправени са всички 6 места (`CALL_*` и `ICALL_*` × `i32/i64/f64`). Без това
`hello` връщаше мръсна стойност (~112) вместо 47.

## 4. Резултат от `./test.sh` на Debian/x86-64

| пример   | очаквано | резултат | бележка                             |
|----------|----------|----------|-------------------------------------|
| hello    | 47       | 47 ✅    | hello=47 (self)                     |
| fib      | 55       | 55 ✅    | fib(10)=55 (self)                   |
| gcd      | 12       | 48 ❌    | съществуващ x86 regalloc spill-бъг   |
| forloop  | 55       | висене   | съществуващ x86 for-инкремент бъг    |

hello и fib вече минават. Останалите два (gcd, forloop) са **съществуващи бъгове
в x86 backend-а** (regalloc spill-slot clobbering и for-loop increment), а не от
настройката на средата — те ще се възпроизведат и на `HEAD:master`, защото
регресията в оригиналния проект се поддържа на **arm64**, а x86-64 там е
текстово-emit-only таргет (~„без as/run“ в README).

## 5. arm64 (поддържаният таргет)

arm64 cross-инструментариумът не беше инсталиран на Debian хоста. След
`apt install gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu` всичките 4
примера се компилират **и линкват в static AArch64 ELF** успешно през
`./mc --target=arm64`.

За да се *изпълни* arm64 бинарен на x86 хост още липсва `qemu-user`.

### Инсталиране на `qemu-user` (за изпълнение на arm64 под x86 хост)

```bash
sudo apt-get install -y qemu-user
```

Проверка:

```bash
qemu-aarch64 --version
```

След това arm64 binaries се изпълняват директно:

```bash
./mc examples/hello.mj --target=arm64 -o a64_hello
qemu-aarch64 a64_hello; echo "exit=$?"     # очаквано 47
```

> Бележка: `qemu-user` (а не `qemu-system-*`) изпълнява user-mode arm64
> процеси директно върху текущия хост — това е достатъчно за regression-тестовете.
> (Ако хостът е сам X86-64, `qemu-aarch64`/`qemu-aarch64-static` вършат същата
> работа като системен еквивалент на `aarch64-linux-gnu-gcc -static` + `run`.)

## 6. Как се използва след миграцията

```bash
./setup.sh          # генерира bin/* и mc/build/test (Debian), тегли Janino
./build.sh          # javac → out/
./test.sh           # сглобява + регресии (x86-64 по подразбиране)
./mc examples/hello.mj --target=arm64 -o hello.arm   # независима цел
```

## 7. Некомитнато

Работното дърво съдържа само поправките по-горе и мръсни артефакти резултати от
`--keep`/`--stage` (не са част от миграцията и могат да се clean-нат).
