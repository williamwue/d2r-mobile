// 固定合成来宾的 ARM64 桥接，遵循 bridge_layout.h 的私有布局。
// ARM64 bridge for the fixed synthetic guest, using the private bridge_layout.h layout.
#include "bridge_layout.h"
.text
.p2align 4

.macro save_registers delta
    stp x0, x1, [sp, #0]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x8, x9, [sp, #64]
    stp x10, x11, [sp, #80]
    stp x12, x13, [sp, #96]
    stp x14, x15, [sp, #112]
    stp x16, x17, [sp, #128]
    stp x18, x19, [sp, #144]
    stp x20, x21, [sp, #160]
    stp x22, x23, [sp, #176]
    stp x24, x25, [sp, #192]
    stp x26, x27, [sp, #208]
    stp x28, x29, [sp, #224]
    str x30, [sp, #240]
    stp q0, q1, [sp, #(RF_Q + 0)]
    stp q2, q3, [sp, #(RF_Q + 32)]
    stp q4, q5, [sp, #(RF_Q + 64)]
    stp q6, q7, [sp, #(RF_Q + 96)]
    stp q8, q9, [sp, #(RF_Q + 128)]
    stp q10, q11, [sp, #(RF_Q + 160)]
    stp q12, q13, [sp, #(RF_Q + 192)]
    stp q14, q15, [sp, #(RF_Q + 224)]
    stp q16, q17, [sp, #(RF_Q + 256)]
    stp q18, q19, [sp, #(RF_Q + 288)]
    stp q20, q21, [sp, #(RF_Q + 320)]
    stp q22, q23, [sp, #(RF_Q + 352)]
    stp q24, q25, [sp, #(RF_Q + 384)]
    stp q26, q27, [sp, #(RF_Q + 416)]
    stp q28, q29, [sp, #(RF_Q + 448)]
    stp q30, q31, [sp, #(RF_Q + 480)]
    mrs x9, nzcv
    str x9, [sp, #RF_NZCV]
    mrs x9, fpcr
    str x9, [sp, #RF_FPCR]
    mrs x9, fpsr
    str x9, [sp, #RF_FPSR]
    add x9, sp, #\delta
    str x9, [sp, #RF_SP]
    str xzr, [sp, #RF_RESERVED]
.endm

.macro save_host delta
    stp x19, x20, [sp, #(HF_GPR + 0)]
    stp x21, x22, [sp, #(HF_GPR + 16)]
    stp x23, x24, [sp, #(HF_GPR + 32)]
    stp x25, x26, [sp, #(HF_GPR + 48)]
    stp x27, x28, [sp, #(HF_GPR + 64)]
    stp x29, x30, [sp, #(HF_GPR + 80)]
    stp q8, q9, [sp, #(HF_Q + 0)]
    stp q10, q11, [sp, #(HF_Q + 32)]
    stp q12, q13, [sp, #(HF_Q + 64)]
    stp q14, q15, [sp, #(HF_Q + 96)]
    mrs x9, fpcr
    str w9, [sp, #HF_FPCR]
    mrs x9, fpsr
    str w9, [sp, #HF_FPSR]
    mrs x9, nzcv
    str w9, [sp, #HF_NZCV]
    add x9, sp, #\delta
    str x9, [sp, #HF_SP]
.endm

.macro restore_host
    ldp x19, x20, [sp, #(HF_GPR + 0)]
    ldp x21, x22, [sp, #(HF_GPR + 16)]
    ldp x23, x24, [sp, #(HF_GPR + 32)]
    ldp x25, x26, [sp, #(HF_GPR + 48)]
    ldp x27, x28, [sp, #(HF_GPR + 64)]
    ldp x29, x30, [sp, #(HF_GPR + 80)]
    ldp q8, q9, [sp, #(HF_Q + 0)]
    ldp q10, q11, [sp, #(HF_Q + 32)]
    ldp q12, q13, [sp, #(HF_Q + 64)]
    ldp q14, q15, [sp, #(HF_Q + 96)]
    ldr w9, [sp, #HF_FPCR]
    msr fpcr, x9
    ldr w9, [sp, #HF_FPSR]
    msr fpsr, x9
    ldr w9, [sp, #HF_NZCV]
    msr nzcv, x9
.endm

.global d2rm_bridge_enter
.hidden d2rm_bridge_enter
.type d2rm_bridge_enter, %function
d2rm_bridge_enter:
    sub sp, sp, #HF_SIZE
    save_host HF_SIZE
    mov x9, sp
    str x9, [x0, #AP_HOST_FRAME]
    str x0, [sp, #HF_ACTIVATION]
    ldr x9, [x0, #AP_GUEST_ROOT]
    mov sp, x9
    str x0, [x9, #GR_ACTIVATION]
    mov x10, #0x5a5a
    str x10, [x9, #GR_COOKIE]
    ldr x30, [x0, #AP_SEED]
    b d2rm_fixture_seed
.size d2rm_bridge_enter, .-d2rm_bridge_enter

d2rm_fixture_seed:
    ldr x9, [x30, #RF_NZCV]
    msr nzcv, x9
    ldr x9, [x30, #RF_FPCR]
    msr fpcr, x9
    ldr x9, [x30, #RF_FPSR]
    msr fpsr, x9
    ldp q0, q1, [x30, #(RF_Q + 0)]
    ldp q2, q3, [x30, #(RF_Q + 32)]
    ldp q4, q5, [x30, #(RF_Q + 64)]
    ldp q6, q7, [x30, #(RF_Q + 96)]
    ldp q8, q9, [x30, #(RF_Q + 128)]
    ldp q10, q11, [x30, #(RF_Q + 160)]
    ldp q12, q13, [x30, #(RF_Q + 192)]
    ldp q14, q15, [x30, #(RF_Q + 224)]
    ldp q16, q17, [x30, #(RF_Q + 256)]
    ldp q18, q19, [x30, #(RF_Q + 288)]
    ldp q20, q21, [x30, #(RF_Q + 320)]
    ldp q22, q23, [x30, #(RF_Q + 352)]
    ldp q24, q25, [x30, #(RF_Q + 384)]
    ldp q26, q27, [x30, #(RF_Q + 416)]
    ldp q28, q29, [x30, #(RF_Q + 448)]
    ldp q30, q31, [x30, #(RF_Q + 480)]
    ldr x0, [x30, #0]
    ldr x1, [x30, #8]
    ldr x2, [x30, #16]
    ldr x3, [x30, #24]
    ldr x4, [x30, #32]
    ldr x5, [x30, #40]
    ldr x6, [x30, #48]
    ldr x7, [x30, #56]
    ldr x8, [x30, #64]
    ldr x9, [x30, #72]
    ldr x10, [x30, #80]
    ldr x11, [x30, #88]
    ldr x12, [x30, #96]
    ldr x13, [x30, #104]
    ldr x14, [x30, #112]
    ldr x15, [x30, #120]
    ldr x16, [x30, #128]
    ldr x17, [x30, #136]
    ldr x19, [x30, #152]
    ldr x20, [x30, #160]
    ldr x21, [x30, #168]
    ldr x22, [x30, #176]
    ldr x23, [x30, #184]
    ldr x24, [x30, #192]
    ldr x25, [x30, #200]
    ldr x26, [x30, #208]
    ldr x27, [x30, #216]
    ldr x28, [x30, #224]
    ldr x29, [x30, #232]
    ldr x30, [x30, #240]

// 此私有调用点采用 B 与静态尾声序列，并非 AAPCS64 调用。
// This private site is a B/epilogue sequence, not an AAPCS64 call.
.global d2rm_fixture_site
.hidden d2rm_fixture_site
d2rm_fixture_site:
    sub sp, sp, #GE_SIZE
    stp x16, x17, [sp, #GE_X16]
    str x30, [sp, #GE_X30]
    ldr x16, [sp, #(GE_SIZE + GR_ACTIVATION)]
    str x16, [sp, #GE_ACTIVATION]
    ldr x17, [x16, #ACT_OPERATION]
    str x17, [sp, #GE_OPERATION]
    adr x17, d2rm_fixture_epilogue
    str x17, [sp, #GE_EPILOGUE]
    b d2rm_gate

.global d2rm_fixture_epilogue
.hidden d2rm_fixture_epilogue
d2rm_fixture_epilogue:
    ldp x16, x17, [sp, #GE_X16]
    ldr x30, [sp, #GE_X30]
    add sp, sp, #GE_SIZE
    sub sp, sp, #RF_SIZE
    save_registers RF_SIZE
    ldr x19, [sp, #(RF_SIZE + GR_ACTIVATION)]
    ldr x20, [x19, #ACT_OBSERVED]
    mov x21, sp
    mov x22, #(RF_SIZE / 16)
.Lcopy:
    ldp x9, x10, [x21], #16
    stp x9, x10, [x20], #16
    subs x22, x22, #1
    b.ne .Lcopy
    ldr x0, [sp, #RF_GPR]
    ldr x9, [x19, #AP_HOST_FRAME]
    mov sp, x9
    str x0, [sp, #HF_RESULT]
    restore_host
    ldr x0, [sp, #HF_RESULT]
    add sp, sp, #HF_SIZE
    ret

.global d2rm_gate
.hidden d2rm_gate
.type d2rm_gate, %function
d2rm_gate:
    sub sp, sp, #RF_SIZE
    save_registers (RF_SIZE + GE_SIZE)
    ldr x19, [sp, #(RF_SIZE + GE_ACTIVATION)]
    ldr x9, [x19, #AP_LOGICAL_X18]
    str x9, [sp, #(RF_GPR + 18 * 8)]
    ldr x9, [sp, #(RF_SIZE + GE_X16)]
    ldr x10, [sp, #(RF_SIZE + GE_X17)]
    stp x9, x10, [sp, #(RF_GPR + 16 * 8)]
    ldr x9, [sp, #(RF_SIZE + GE_X30)]
    str x9, [sp, #(RF_GPR + 30 * 8)]
    mov x1, sp
    ldr x2, [sp, #(RF_SIZE + GE_OPERATION)]
    ldr x20, [x19, #AP_HOST_FRAME]
    str x1, [x20, #HF_GUEST_FRAME]
    mov sp, x20
    ldr w9, [sp, #HF_FPCR]
    msr fpcr, x9
    ldr w9, [sp, #HF_FPSR]
    msr fpsr, x9
    ldr w9, [sp, #HF_NZCV]
    msr nzcv, x9
    mov x0, x19
    ldr x29, [sp, #(HF_GPR + 10 * 8)]
    bl d2rm_callback_stress
    ldr x9, [sp, #HF_GUEST_FRAME]
    mov sp, x9
    str x0, [sp, #RF_GPR]
    ldr x9, [sp, #RF_NZCV]
    msr nzcv, x9
    ldr x9, [sp, #RF_FPCR]
    msr fpcr, x9
    ldr x9, [sp, #RF_FPSR]
    msr fpsr, x9
    ldp q0, q1, [sp, #(RF_Q + 0)]
    ldp q2, q3, [sp, #(RF_Q + 32)]
    ldp q4, q5, [sp, #(RF_Q + 64)]
    ldp q6, q7, [sp, #(RF_Q + 96)]
    ldp q8, q9, [sp, #(RF_Q + 128)]
    ldp q10, q11, [sp, #(RF_Q + 160)]
    ldp q12, q13, [sp, #(RF_Q + 192)]
    ldp q14, q15, [sp, #(RF_Q + 224)]
    ldp q16, q17, [sp, #(RF_Q + 256)]
    ldp q18, q19, [sp, #(RF_Q + 288)]
    ldp q20, q21, [sp, #(RF_Q + 320)]
    ldp q22, q23, [sp, #(RF_Q + 352)]
    ldp q24, q25, [sp, #(RF_Q + 384)]
    ldp q26, q27, [sp, #(RF_Q + 416)]
    ldp q28, q29, [sp, #(RF_Q + 448)]
    ldp q30, q31, [sp, #(RF_Q + 480)]
    ldr x0, [sp, #0]
    ldr x1, [sp, #8]
    ldr x2, [sp, #16]
    ldr x3, [sp, #24]
    ldr x4, [sp, #32]
    ldr x5, [sp, #40]
    ldr x6, [sp, #48]
    ldr x7, [sp, #56]
    ldr x8, [sp, #64]
    ldr x9, [sp, #72]
    ldr x10, [sp, #80]
    ldr x11, [sp, #88]
    ldr x12, [sp, #96]
    ldr x13, [sp, #104]
    ldr x14, [sp, #112]
    ldr x15, [sp, #120]
    ldr x19, [sp, #152]
    ldr x20, [sp, #160]
    ldr x21, [sp, #168]
    ldr x22, [sp, #176]
    ldr x23, [sp, #184]
    ldr x24, [sp, #192]
    ldr x25, [sp, #200]
    ldr x26, [sp, #208]
    ldr x27, [sp, #216]
    ldr x28, [sp, #224]
    ldr x29, [sp, #232]
    ldr x16, [sp, #(RF_SIZE + GE_EPILOGUE)]
    add sp, sp, #RF_SIZE
    br x16
.size d2rm_gate, .-d2rm_gate

.global d2rm_callback_stress
.hidden d2rm_callback_stress
.type d2rm_callback_stress, %function
d2rm_callback_stress:
    sub sp, sp, #16
    str x30, [sp]
    bl d2rm_host_dispatch
    mov x1, #53249
    mov x2, #53250
    mov x3, #53251
    mov x4, #53252
    mov x5, #53253
    mov x6, #53254
    mov x7, #53255
    mov x8, #53256
    mov x9, #53257
    mov x10, #53258
    mov x11, #53259
    mov x12, #53260
    mov x13, #53261
    mov x14, #53262
    mov x15, #53263
    mov x16, #53264
    mov x17, #53265
    movi v0.16b, #128
    movi v1.16b, #129
    movi v2.16b, #130
    movi v3.16b, #131
    movi v4.16b, #132
    movi v5.16b, #133
    movi v6.16b, #134
    movi v7.16b, #135
    ins v8.d[1], x9
    ins v9.d[1], x9
    ins v10.d[1], x9
    ins v11.d[1], x9
    ins v12.d[1], x9
    ins v13.d[1], x9
    ins v14.d[1], x9
    ins v15.d[1], x9
    movi v16.16b, #144
    movi v17.16b, #145
    movi v18.16b, #146
    movi v19.16b, #147
    movi v20.16b, #148
    movi v21.16b, #149
    movi v22.16b, #150
    movi v23.16b, #151
    movi v24.16b, #152
    movi v25.16b, #153
    movi v26.16b, #154
    movi v27.16b, #155
    movi v28.16b, #156
    movi v29.16b, #157
    movi v30.16b, #158
    movi v31.16b, #159
    msr nzcv, xzr
    ldr x30, [sp]
    add sp, sp, #16
    ret
.size d2rm_callback_stress, .-d2rm_callback_stress

.global d2rm_host_wrapper
.hidden d2rm_host_wrapper
.type d2rm_host_wrapper, %function
d2rm_host_wrapper:
    sub sp, sp, #(HF_SIZE + 32)
    save_host (HF_SIZE + 32)
    stp x1, x2, [sp, #HF_SIZE]
    str x0, [sp, #(HF_SIZE + 16)]
    mov x9, sp
    str x9, [x2, #HF_SP]
    adr x9, d2rm_host_return
    str x9, [x2, #(HF_GPR + 11 * 8)]
    ldr x19, [x2, #(HF_GPR + 0)]
    ldr x20, [x2, #(HF_GPR + 8)]
    ldr x21, [x2, #(HF_GPR + 16)]
    ldr x22, [x2, #(HF_GPR + 24)]
    ldr x23, [x2, #(HF_GPR + 32)]
    ldr x24, [x2, #(HF_GPR + 40)]
    ldr x25, [x2, #(HF_GPR + 48)]
    ldr x26, [x2, #(HF_GPR + 56)]
    ldr x27, [x2, #(HF_GPR + 64)]
    ldr x28, [x2, #(HF_GPR + 72)]
    add x29, sp, #(HF_GPR + 10 * 8)
    str x29, [x2, #(HF_GPR + 10 * 8)]
    ldp q8, q9, [x2, #(HF_Q + 0)]
    ldp q10, q11, [x2, #(HF_Q + 32)]
    ldp q12, q13, [x2, #(HF_Q + 64)]
    ldp q14, q15, [x2, #(HF_Q + 96)]
    ldr w9, [x2, #HF_FPCR]
    msr fpcr, x9
    ldr w9, [x2, #HF_FPSR]
    msr fpsr, x9
    ldr w9, [x2, #HF_NZCV]
    msr nzcv, x9
    bl d2rm_bridge_enter
.global d2rm_host_return
.hidden d2rm_host_return
d2rm_host_return:
    ldr x9, [sp, #HF_SIZE]
    stp x19, x20, [x9, #(HF_GPR + 0)]
    stp x21, x22, [x9, #(HF_GPR + 16)]
    stp x23, x24, [x9, #(HF_GPR + 32)]
    stp x25, x26, [x9, #(HF_GPR + 48)]
    stp x27, x28, [x9, #(HF_GPR + 64)]
    stp x29, x30, [x9, #(HF_GPR + 80)]
    stp q8, q9, [x9, #(HF_Q + 0)]
    stp q10, q11, [x9, #(HF_Q + 32)]
    stp q12, q13, [x9, #(HF_Q + 64)]
    stp q14, q15, [x9, #(HF_Q + 96)]
    mrs x10, fpcr
    str w10, [x9, #HF_FPCR]
    mrs x10, fpsr
    str w10, [x9, #HF_FPSR]
    mrs x10, nzcv
    str w10, [x9, #HF_NZCV]
    mov x10, sp
    str x10, [x9, #HF_SP]
    restore_host
    add sp, sp, #(HF_SIZE + 32)
    ret
.size d2rm_host_wrapper, .-d2rm_host_wrapper

.global layout_RF_GPR
.hidden layout_RF_GPR
.set layout_RF_GPR, RF_GPR
.global layout_RF_SP
.hidden layout_RF_SP
.set layout_RF_SP, RF_SP
.global layout_RF_Q
.hidden layout_RF_Q
.set layout_RF_Q, RF_Q
.global layout_RF_NZCV
.hidden layout_RF_NZCV
.set layout_RF_NZCV, RF_NZCV
.global layout_RF_FPCR
.hidden layout_RF_FPCR
.set layout_RF_FPCR, RF_FPCR
.global layout_RF_FPSR
.hidden layout_RF_FPSR
.set layout_RF_FPSR, RF_FPSR
.global layout_RF_RESERVED
.hidden layout_RF_RESERVED
.set layout_RF_RESERVED, RF_RESERVED
.global layout_RF_SIZE
.hidden layout_RF_SIZE
.set layout_RF_SIZE, RF_SIZE
.global layout_GE_X16
.hidden layout_GE_X16
.set layout_GE_X16, GE_X16
.global layout_GE_X17
.hidden layout_GE_X17
.set layout_GE_X17, GE_X17
.global layout_GE_X30
.hidden layout_GE_X30
.set layout_GE_X30, GE_X30
.global layout_GE_ACTIVATION
.hidden layout_GE_ACTIVATION
.set layout_GE_ACTIVATION, GE_ACTIVATION
.global layout_GE_EPILOGUE
.hidden layout_GE_EPILOGUE
.set layout_GE_EPILOGUE, GE_EPILOGUE
.global layout_GE_OPERATION
.hidden layout_GE_OPERATION
.set layout_GE_OPERATION, GE_OPERATION
.global layout_GE_SIZE
.hidden layout_GE_SIZE
.set layout_GE_SIZE, GE_SIZE
.global layout_GR_ACTIVATION
.hidden layout_GR_ACTIVATION
.set layout_GR_ACTIVATION, GR_ACTIVATION
.global layout_GR_COOKIE
.hidden layout_GR_COOKIE
.set layout_GR_COOKIE, GR_COOKIE
.global layout_GR_SIZE
.hidden layout_GR_SIZE
.set layout_GR_SIZE, GR_SIZE
.global layout_HF_GPR
.hidden layout_HF_GPR
.set layout_HF_GPR, HF_GPR
.global layout_HF_Q
.hidden layout_HF_Q
.set layout_HF_Q, HF_Q
.global layout_HF_FPCR
.hidden layout_HF_FPCR
.set layout_HF_FPCR, HF_FPCR
.global layout_HF_FPSR
.hidden layout_HF_FPSR
.set layout_HF_FPSR, HF_FPSR
.global layout_HF_NZCV
.hidden layout_HF_NZCV
.set layout_HF_NZCV, HF_NZCV
.global layout_HF_RESERVED
.hidden layout_HF_RESERVED
.set layout_HF_RESERVED, HF_RESERVED
.global layout_HF_SP
.hidden layout_HF_SP
.set layout_HF_SP, HF_SP
.global layout_HF_ACTIVATION
.hidden layout_HF_ACTIVATION
.set layout_HF_ACTIVATION, HF_ACTIVATION
.global layout_HF_GUEST_FRAME
.hidden layout_HF_GUEST_FRAME
.set layout_HF_GUEST_FRAME, HF_GUEST_FRAME
.global layout_HF_RESULT
.hidden layout_HF_RESULT
.set layout_HF_RESULT, HF_RESULT
.global layout_HF_SIZE
.hidden layout_HF_SIZE
.set layout_HF_SIZE, HF_SIZE
.global layout_AP_HOST_FRAME
.hidden layout_AP_HOST_FRAME
.set layout_AP_HOST_FRAME, AP_HOST_FRAME
.global layout_AP_GUEST_ROOT
.hidden layout_AP_GUEST_ROOT
.set layout_AP_GUEST_ROOT, AP_GUEST_ROOT
.global layout_AP_STACK_LOW
.hidden layout_AP_STACK_LOW
.set layout_AP_STACK_LOW, AP_STACK_LOW
.global layout_AP_STACK_HIGH
.hidden layout_AP_STACK_HIGH
.set layout_AP_STACK_HIGH, AP_STACK_HIGH
.global layout_AP_LOGICAL_X18
.hidden layout_AP_LOGICAL_X18
.set layout_AP_LOGICAL_X18, AP_LOGICAL_X18
.global layout_AP_LOGICAL_TLS
.hidden layout_AP_LOGICAL_TLS
.set layout_AP_LOGICAL_TLS, AP_LOGICAL_TLS
.global layout_AP_SEED
.hidden layout_AP_SEED
.set layout_AP_SEED, AP_SEED
.global layout_AP_STATUS
.hidden layout_AP_STATUS
.set layout_AP_STATUS, AP_STATUS
.global layout_AP_SIZE
.hidden layout_AP_SIZE
.set layout_AP_SIZE, AP_SIZE
.global layout_ACT_OBSERVED
.hidden layout_ACT_OBSERVED
.set layout_ACT_OBSERVED, ACT_OBSERVED
.global layout_ACT_OPERATION
.hidden layout_ACT_OPERATION
.set layout_ACT_OPERATION, ACT_OPERATION
.section .note.GNU-stack,"",%progbits

