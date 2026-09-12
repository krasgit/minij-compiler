# janino-parse

Парсва MiniJ source с Janino и дъмпва AST.

## Употреба

    janino-parse <in.mj> <out.ast>

## Какво прави

1. Чете source.
2. Парсва с org.codehaus.janino.Parser.
3. Дъмпва `Java.CompilationUnit.toString()`.

## Инварианти

- Janino е reference.
- Ако Janino не парсне нещо, MiniJ не го поддържа.
