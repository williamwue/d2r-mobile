// 合成桥接的数据布局与独立测试入口，依据本项目的私有测试 ABI。
// Synthetic bridge layout and isolated suite entry, based on this project's private test ABI.
#pragma once
#include "bridge_layout.h"
#include <cstddef>
#include <cstdint>
#include <string>

namespace d2rm {
enum class Status { passed, failed, unsupported_abi, not_run };
struct alignas(16) Vector128 { uint64_t low, high; };
struct alignas(16) RegisterFrame {
    uint64_t gpr[31], sp;
    Vector128 q[32];
    uint64_t nzcv, fpcr, fpsr, reserved;
};
struct alignas(16) GateEnvelope {
    uint64_t x16, x17, x30, activation, epilogue, operation;
};
struct alignas(16) GuestRoot { uint64_t activation, cookie; };
struct alignas(16) HostFrame {
    uint64_t gpr[12];
    Vector128 q[8];
    uint32_t fpcr, fpsr, nzcv, reserved;
    uint64_t sp, activation, guestFrame, result;
};
struct alignas(16) ActivationPrefix {
    uint64_t hostFrame, guestRoot, stackLow, stackHigh;
    uint64_t logicalX18, logicalTls, seed, status;
};
#define FIELD(type, field, value) static_assert(offsetof(type, field) == value)
FIELD(RegisterFrame, gpr, RF_GPR); FIELD(RegisterFrame, sp, RF_SP);
FIELD(RegisterFrame, q, RF_Q); FIELD(RegisterFrame, nzcv, RF_NZCV);
FIELD(RegisterFrame, fpcr, RF_FPCR); FIELD(RegisterFrame, fpsr, RF_FPSR);
FIELD(RegisterFrame, reserved, RF_RESERVED);
FIELD(GateEnvelope, x16, GE_X16); FIELD(GateEnvelope, x17, GE_X17);
FIELD(GateEnvelope, x30, GE_X30); FIELD(GateEnvelope, activation, GE_ACTIVATION);
FIELD(GateEnvelope, epilogue, GE_EPILOGUE); FIELD(GateEnvelope, operation, GE_OPERATION);
FIELD(GuestRoot, activation, GR_ACTIVATION); FIELD(GuestRoot, cookie, GR_COOKIE);
FIELD(HostFrame, gpr, HF_GPR); FIELD(HostFrame, q, HF_Q);
FIELD(HostFrame, fpcr, HF_FPCR); FIELD(HostFrame, fpsr, HF_FPSR);
FIELD(HostFrame, nzcv, HF_NZCV); FIELD(HostFrame, reserved, HF_RESERVED);
FIELD(HostFrame, sp, HF_SP); FIELD(HostFrame, activation, HF_ACTIVATION);
FIELD(HostFrame, guestFrame, HF_GUEST_FRAME); FIELD(HostFrame, result, HF_RESULT);
FIELD(ActivationPrefix, hostFrame, AP_HOST_FRAME); FIELD(ActivationPrefix, guestRoot, AP_GUEST_ROOT);
FIELD(ActivationPrefix, stackLow, AP_STACK_LOW); FIELD(ActivationPrefix, stackHigh, AP_STACK_HIGH);
FIELD(ActivationPrefix, logicalX18, AP_LOGICAL_X18); FIELD(ActivationPrefix, logicalTls, AP_LOGICAL_TLS);
FIELD(ActivationPrefix, seed, AP_SEED); FIELD(ActivationPrefix, status, AP_STATUS);
static_assert(sizeof(RegisterFrame) == RF_SIZE && alignof(RegisterFrame) == 16);
static_assert(sizeof(HostFrame) == HF_SIZE && alignof(HostFrame) == 16);
static_assert(sizeof(GateEnvelope) == GE_SIZE && sizeof(GuestRoot) == GR_SIZE);
static_assert(sizeof(ActivationPrefix) == AP_SIZE);
#undef FIELD
std::string runArm64BridgeSuite();
}
