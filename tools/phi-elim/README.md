# phi-elim

Премахва phi. Вмъква MOV в predecessor блоковете.

## Употреба

    phi-elim <in.mir> <out.mir>

## Формат

Вход: `.mir` с `PHI_i32` инструкции (след `regalloc`, с `.locations`).
Изход: същият `.mir` **без** phi; за всеки phi се вмъква по един `MOV_i32` в края на
predecessor-блока:

    head {
      :::
      %5 = MOV_i32 i32 %1 %2   ; стойността на phi-клетката за входа (dst = %2)
    }

Форматът на редовете е общият IR текстов (виж [docs/ir-format.md](../../docs/ir-format.md)).
Това е последната IR фаза — изходът `.final.mir` се подава директно на `emit`.
