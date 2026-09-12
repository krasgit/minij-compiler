# ssa-lower

Generic SSA → Machine IR.

## Употреба

    ssa-lower <in.ssa> <out.mir>

## Mapping

| Generic | Machine |
|---|---|
| add | ADD_i32 |
| cmplt | CMPLT_i32 |
| jump | JMP |
| branch | BRANCH |
| return | RETURN |
| phi | PHI_i32 |
| copy | MOV_i32 |
