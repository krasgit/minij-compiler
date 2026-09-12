# janino-parse

Парсва MiniJ source с Janino и дъмпва AST.

## Употреба

    janino-parse <in.mj> <out.ast>

## Какво прави

1. Чете source.
2. Парсва с org.codehaus.janino.Parser.
3. Дъмпва `Java.CompilationUnit.toString()`.

## Формат на изхода (.ast)

Каноничен Janino `Unparser` текст на Java-програмата (пре-форматиран source, без
коментари). Това е **debug dump за хора** — `ast-lower` НЕ го чете, той парсва `.mj`
повторно. Реалните IR етапи започват от `.lir` (виж [docs/ir-format.md](../../docs/ir-format.md)).

## Инварианти

- Janino е reference.
- Ако Janino не парсне нещо, MiniJ не го поддържа.
