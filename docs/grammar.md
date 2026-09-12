# Граматики на форматите

## .mj — MiniJ source (Java subset)

    program    = { class_decl } ;
    class_decl = "class" IDENT "{" { method } "}" ;
    method     = "static" type IDENT "(" [params] ")" block ;
    type       = "int" | "void" ;
    block      = "{" { stmt } "}" ;
    stmt       = var_decl | assign | if_stmt | while_stmt | for_stmt
               | return_stmt | expr_stmt | block
               | "break" ";" | "continue" ";" ;
    expr       = or_expr ;
    or_expr    = and_expr { "||" and_expr } ;
    and_expr   = cmp_expr { "&&" cmp_expr } ;
    cmp_expr   = add_expr { ("<"|">"|"<="|">="|"=="|"!=") add_expr } ;
    add_expr   = mul_expr { ("+"|"-") mul_expr } ;
    mul_expr   = unary { ("*"|"/"|"%") unary } ;
    atom       = INT | IDENT | call | "(" expr ")" ;

## .ast — Janino AST дъмп

`CompilationUnit.toString()` — човекочетим, не се парсва обратно.

## .lir — Linear IR

    lir_file = header { debug_section } { func } { locations_section } ;
    header   = '.module' STRING '.target' STRING ;
    func     = '.func' IDENT '(' params ')' '->' type block ;
    block    = IDENT '{' { instruction } '}' ;
    instruction = value_inst | store_inst | terminator ;
    value_inst  = REF '=' IDENT type { operand } [ ';' 'dbg' INT ] ;
    store_inst  = 'store' REF ',' REF [ ';' 'dbg' INT ] ;
    terminator  = 'jump' IDENT
                | 'branch' REF ',' IDENT ',' IDENT
                | 'return' [ REF ] ;

## .ssa — като .lir, но

- Без alloca/load/store
- С phi: `REF '=' 'phi' type { '[' IDENT ':' REF ']' }`

## .mir — като .ssa, но

- Ops са машинни: ADD_i32, CMPGT_i32, BRANCH, JMP, RETURN, MOV_i32
- След regalloc — `.locations` секция:
    `REF ':' 'reg' REG | REF ':' 'stack' INT`

## .s — GNU as

Стандартен GNU as синтаксис с `.loc` директиви.

## .rule — emit правила

    rule_file = { section } ;
    section   = regs_section | scratch_section
              | prologue_section | epilogue_section
              | emit_rule ;
    regs_section    = 'regs' ':' { IDENT } ';' ;
    scratch_section = 'scratch' ':' { IDENT } ';' ;
    prologue_section= 'prologue' '{' { line } '}' ;
    epilogue_section= 'epilogue' '{' { line } '}' ;
    emit_rule       = 'emit' pattern '{' { line } '}' ;
    pattern         = IDENT [ '(' { pat_arg } ')' ] ;
    line            = TEXT | '${' IDENT '}' | '$dst' | '$$' ;
