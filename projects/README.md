# projects/ — MiniJ App като Maven проект

Тук вашата програма живее като **Maven проект**: `App.mj` (сортировка MiniJ
= исходен код) + `pom.xml`, който компилира и изпълнява нея чрез тулчейна от
repo-то и **без да използва Java за компилацията** — само нативния компилатор
(`mc`), rules, runtime, cross GCC и qemu-user.

## Структура

```
projects/
├── App.mj      ← програмата (клас App с main)
└── pom.xml     ← Maven build: сглобява тулчейна → компилира App.mj →
                 изпълнява под qemu → проверява exit кода
```

`target/app` (генерираният бинарен) не се commit-ва.

## Изисквания (host Debian/Ubuntu x86-64)

- JDK 17 (за Java mains в `bin/*`) + Maven
- aarch64 cross-toolchain: `gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu`
- `qemu-user` (изпълнение на arm64 под x86 хост)

## Как се build-ва

```bash
cd projects
mvn verify          # компилира App.mj → target/app, пуска под qemu, проверява exit 7
mvn -Dminij.expected=7 verify    # ако промените main(), обновете очаквания exit код
```

## Смяна на таргета: arm64 ↔ x86-64

Таргетът се управлява от **две Maven property-та** (без да се нуждае от промени
в pom.xml):

| Таргет | Команда |
|---|---|
| **arm64** (поддържан; по подразбиране) | `mvn verify` |
| **x86-64** (нативен хост, без qemu) | `mvn verify -Dminij.target=x86-64 -Dminij.qemu=` |

Обяснение на двата флага:

- `-Dminij.target=x86-64` — сменя `<minij.target>` в pom-а → `mc` получава
  `--target=x86-64`, rules = `rules/x86.rule`, линк = нативен gcc.
- `-Dminij.qemu=` — задава празна стойност → стъпката „изпълнение“ пуска
  бинарния **директно на хоста**, без qemu-aarch64 (нужно е само за arm64 под
  x86 хост).

Ако предпочитате да ги закотвите трайно, редактирайте `projects/pom.xml`:

```xml
<minij.target>x86-64</minij.target>          <!-- или arm64 -->
<minij.qemu></minij.qemu>                    <!-- празно на x86 -->
```

> ⚠️ **x86-64 regalloc**: поддържаният и валидиран таргет е **arm64** (4/4 в
> `test.sh`). На x86-64 задният-bend има съществуващи regalloc бъгове при
> while-условия/цикли — `hello`/`App`/`fib` минават, но `gcd` (12) и `forloop`
> връщат грешен резултат. Това не е от настройката, а е наследен бъг на x86
> таргета (подробности в `docs/MIGRATE.md`).

## Как работи (executions в pom.xml)

1. **`./build.sh`** (generate-resources) → сглобява локалния компилатор в repo-то.
2. **`mc --target=arm64 App.mj -o target/app`** (compile) — CWD е repo-root,
   защото rules/ се resolve-ва спрямо CWD (същото прави `test.sh`).
3. **`qemu-aarch64 -L /usr/aarch64-linux-gnu`** (verify) → изпълнява бинарния и
   проверява: `App.mj exit код = ${minij.expected}`.

## Промяна на очаквания резултат

`App.mj` връща целочислен exit код (тук `add(4,3)=7`). Ако го промените,
обновете `<minij.expected>` в `pom.xml` на същата стойност, иначе
`mvn verify` ще се провали по предназначение.

## Често срещани проблеми

| Грешка | Причина | Решение |
|---|---|---|
| `cannot find ../../../rules/arm.rule` | CWD не е repo-root | CWD = `projects/..` (правено в pom-а) |
| exit=255 при `qemu-aarch64` | липсва `-L` (динамичен loader) | `qemu-aarch64 -L /usr/aarch64-linux-gnu` |
| `exec-maven-plugin` сетва CWD на `projects/` | работи от `workingDirectory=${minij.repo}` | вече зададено в pom-а |


mvn verify -Dminij.target=x86-64 -Dminij.qemu=
mvn verify -Dminij.target=arm64 -Dminij.qemu="qemu-aarch64 -L /usr/aarch64-linux-gnu"