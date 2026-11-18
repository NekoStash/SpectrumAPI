// Link-only AArch64 symbol declarations; never a runtime implementation.
.text
.macro link_only_trap name
    .p2align 2
    .global \name
    .type \name, %function
\name:
    .inst 0xd503245f // BTI c.
    brk #0
    .size \name, .-\name
.endm

link_only_trap _ZNK7android7RefBase9decStrongEPKv
link_only_trap _ZNK7android8String164sizeEv
link_only_trap _ZN7android14sp_report_raceEv

.section .note.GNU-stack,"",%progbits
