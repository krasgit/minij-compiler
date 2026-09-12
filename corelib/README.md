# MiniJ core library (Draft)

API-огледало на `java.base` в MiniJ source. Договорът и статусът са в [`docs/corelib.md`](../docs/corelib.md).

Структура:
- `java/lang/` — Object, PrintStream, System, String, Integer, Long, Math
- `java/util/` — Random, Arrays

Всеки файл носи header `// requires: P…` — кои фази на пайплайн-а трябва да са живи, за да се компилира.
`native` методите се свързват към `k_native_<Class>_<name>_<arity>` в `natives.c` (ABI — P0).