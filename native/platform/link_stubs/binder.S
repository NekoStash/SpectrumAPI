// Link-only AArch64 symbol declarations; never a runtime implementation.
.text
.macro link_only_trap name
    .p2align 2
    .global \name
    .type \name, %function
\name:
    .inst 0xd503245f // BTI c (a hint on older AArch64 implementations).
    brk #0
    .size \name, .-\name
.endm

link_only_trap _ZN7android7BBinder11wasParceledEv
link_only_trap _ZN7android7BBinder12getExtensionEv
link_only_trap _ZN7android7BBinder12setExtensionERKNS_2spINS_7IBinderEEE

.section .note.GNU-stack,"",%progbits
