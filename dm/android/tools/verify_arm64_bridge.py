#!/usr/bin/env python3
"""Execute the Android assembly as ELF in Unicorn; substitute only host dispatch."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import platform
import struct
import subprocess
import sys
import zipfile


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--instruction-budget", type=int, default=10000)
    parser.add_argument("--mutate", choices=["gpr", "simd", "flags", "below-sp"])
    parser.add_argument("--android-elf", type=Path, help="unstripped linked arm64-v8a libd2rm_probe.so")
    parser.add_argument("--android-apk", type=Path, help="APK whose ARM64 .text must match --android-elf")
    args = parser.parse_args()
    if not (args.ndk / "source.properties").is_file():
        parser.error("--ndk must name an installed Android NDK")
    if "28.2.13676358" not in (args.ndk / "source.properties").read_text():
        parser.error("this verifier requires NDK 28.2.13676358")
    if args.instruction_budget < 1:
        parser.error("--instruction-budget must be positive")
    for artifact in [args.android_elf, args.android_apk]:
        if artifact and not artifact.is_file():
            parser.error(f"artifact does not exist: {artifact}")
    if args.android_apk and not args.android_elf:
        parser.error("--android-apk requires --android-elf")
    return args


def inspect_branches(elf, apk=None):
    from elftools.elf.elffile import ELFFile
    raw = elf.read_bytes()
    image = ELFFile(io.BytesIO(raw))
    if image.header.e_machine != "EM_AARCH64":
        raise AssertionError("inspection requires an AArch64 ELF")
    table = image.get_section_by_name(".symtab")
    symbols = {s.name: s for s in table.iter_symbols()}
    code = image.get_section_by_name(".text")
    code_base, code_bytes = code.header.sh_addr, code.data()
    targets = [("d2rm_fixture_site", "d2rm_fixture_epilogue", "d2rm_gate", False),
               ("d2rm_gate", "d2rm_callback_stress", "d2rm_callback_stress", True),
               ("d2rm_callback_stress", "d2rm_host_wrapper", "d2rm_host_dispatch", True)]
    results = []
    for start, end, target, link in targets:
        for name in [start, end, target]:
            symbol = symbols[name]
            if symbol.entry.st_shndx == "SHN_UNDEF" or not (symbol.entry.st_info.bind == "STB_LOCAL" or symbol.entry.st_other.visibility == "STV_HIDDEN"):
                raise AssertionError(f"preemptible assembly boundary: {name}")
        first, last, expected = [symbols[name].entry.st_value for name in [start, end, target]]
        found = []
        for pc in range(first, last, 4):
            instruction = int.from_bytes(code_bytes[pc-code_base:pc-code_base+4], "little")
            if instruction & 0xfc000000 == (0x94000000 if link else 0x14000000):
                immediate = instruction & 0x3ffffff
                if immediate & 0x2000000:
                    immediate -= 0x4000000
                found.append(pc + immediate * 4)
        if found != [expected]:
            raise AssertionError(f"unexpected branch or veneer at {start}: {found}, target {expected:#x}")
        results.append({"from": start, "to": target, "direct": True})
    first = symbols["d2rm_fixture_site"].entry.st_value
    last = symbols["d2rm_fixture_epilogue"].entry.st_value
    adr_targets = []
    for pc in range(first, last, 4):
        instruction = int.from_bytes(code_bytes[pc-code_base:pc-code_base+4], "little")
        if instruction & 0x9f00001f == 0x10000011:
            immediate = ((instruction >> 5) & 0x7ffff) << 2 | ((instruction >> 29) & 3)
            if immediate & 0x100000:
                immediate -= 0x200000
            adr_targets.append(pc + immediate)
    if adr_targets != [last]:
        raise AssertionError("gate epilogue ADR target is not the local static epilogue")
    gate = symbols["d2rm_gate"].entry.st_value
    gate_end = symbols["d2rm_callback_stress"].entry.st_value
    if int.from_bytes(code_bytes[gate_end-code_base-4:gate_end-code_base], "little") != 0xd61f0200:
        raise AssertionError("gate must end in BR x16 to envelope epilogue")
    if last - first > 4096 or gate_end - gate > 4096:
        raise AssertionError("synthetic site/gate unexpectedly large")
    report = {"elf": str(elf.resolve()), "sha256": hashlib.sha256(raw).hexdigest(),
              "branches": results, "epilogue": "local ADR x17 / BR x16"}
    if apk:
        with zipfile.ZipFile(apk) as archive:
            packed = ELFFile(io.BytesIO(archive.read("lib/arm64-v8a/libd2rm_probe.so")))
            if packed.get_section_by_name(".text").data() != code_bytes:
                raise AssertionError("APK ARM64 instructions do not match inspected ELF")
        report["apkTextMatches"] = str(apk.resolve())
        report["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    return report


def build(args):
    tag = {"Windows": "windows-x86_64", "Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    binary = args.ndk / "toolchains/llvm/prebuilt" / tag / "bin"
    clang = binary / ("clang.exe" if platform.system() == "Windows" else "clang")
    native = Path(__file__).resolve().parents[1] / "native"
    args.output.mkdir(parents=True, exist_ok=True)
    stub = args.output / "dispatch_boundary.S"
    stub.write_text(".text\n.global d2rm_host_dispatch\n.hidden d2rm_host_dispatch\nd2rm_host_dispatch:\n ret\n.section .note.GNU-stack,\"\",%progbits\n")
    elf = args.output / "bridge-model.elf"
    command = [str(clang), "--target=aarch64-linux-android35", "-nostdlib", "-fno-pie", "-no-pie",
               "-Wl,--no-undefined", "-Wl,-e,d2rm_host_wrapper", "-Wl,-Ttext=0x100000",
               "-Wl,--build-id=none", str(native / "bridge_arm64.S"), str(stub), "-o", str(elf)]
    result = subprocess.run(command, text=True, capture_output=True)
    (args.output / "build.json").write_text(json.dumps({"command": command, "exitCode": result.returncode,
                                                       "stdout": result.stdout, "stderr": result.stderr}, indent=2))
    if result.returncode:
        raise RuntimeError(result.stderr)
    objdump = binary / ("llvm-objdump.exe" if platform.system() == "Windows" else "llvm-objdump")
    result = subprocess.run([str(objdump), "-d", "--no-show-raw-insn", str(elf)], text=True, capture_output=True, check=True)
    (args.output / "disassembly.txt").write_text(result.stdout)
    return elf


def execute(elf, budget, mutation=None):
    import unicorn as un
    from unicorn import arm64_const as reg
    from elftools.elf.elffile import ELFFile
    import elftools
    if un.__version__ != "2.1.4":
        raise RuntimeError("install pinned optional requirements-arm64-bridge.txt")
    if elftools.__version__ != "0.33":
        raise RuntimeError("install pinned optional requirements-arm64-bridge.txt")
    with elf.open("rb") as stream:
        image = ELFFile(stream)
        symbols = {s.name: s.entry.st_value for s in image.get_section_by_name(".symtab").iter_symbols()}
        segments = [(s.header.p_vaddr, s.header.p_memsz, s.header.p_flags, s.data())
                    for s in image.iter_segments() if s.header.p_type == "PT_LOAD"]
    layout = {name.removeprefix("layout_"): value for name, value in symbols.items() if name.startswith("layout_")}
    if len(layout) != 40:
        raise AssertionError(f"missing ELF layout symbols: {len(layout)}")
    uc = un.Uc(un.UC_ARCH_ARM64, un.UC_MODE_ARM)
    pages = {}
    for address, size, flags, data in segments:
        permissions = (un.UC_PROT_READ if flags & 4 else 0) | (un.UC_PROT_WRITE if flags & 2 else 0) | (un.UC_PROT_EXEC if flags & 1 else 0)
        for page in range(address & -4096, (address + size + 4095) & -4096, 4096):
            pages[page] = pages.get(page, 0) | permissions
    for page, permissions in pages.items():
        if permissions & un.UC_PROT_EXEC and permissions & un.UC_PROT_WRITE:
            raise AssertionError("ELF has writable executable page")
        uc.mem_map(page, 4096, permissions)
    for address, _, _, data in segments:
        uc.mem_write(address, data)
    data_base, host_low, guest_low = 0x40000000, 0x50001000, 0x60001000
    host_high, guest_high = host_low + 0x10000, guest_low + 0x10000
    uc.mem_map(data_base, 0x10000, un.UC_PROT_READ | un.UC_PROT_WRITE)
    uc.mem_map(host_low, host_high - host_low, un.UC_PROT_READ | un.UC_PROT_WRITE)
    uc.mem_map(guest_low, guest_high - guest_low, un.UC_PROT_READ | un.UC_PROT_WRITE)
    act, seed, observed, host_observed, host_seed, tls = [data_base + i * 0x1000 for i in range(6)]
    root = guest_high - layout["GR_SIZE"]
    initial_sp = host_high - 16
    xregs = [getattr(reg, f"UC_ARM64_REG_X{i}") for i in range(31)]
    qregs = [getattr(reg, f"UC_ARM64_REG_Q{i}") for i in range(32)]
    physical_x18, tp = 0x18181818, 0x71717000
    gprs = [0x5100000000000000 + i for i in range(31)]
    vectors = [(0x7200000000000000 + i) << 64 | (0x6100000000000000 + i) for i in range(32)]
    host_gprs = [0x73000000 + i for i in range(12)]
    host_vectors = [(0x75000000 + i) << 64 | (0x74000000 + i) for i in range(8)]
    checks = []

    def check(name, expected, actual):
        if expected != actual:
            raise AssertionError(f"{name}: expected {expected:#x}, observed {actual:#x}")
        checks.append(name)

    def put(address, value, size=8):
        uc.mem_write(address, value.to_bytes(size, "little"))

    def get(address, size=8):
        return int.from_bytes(uc.mem_read(address, size), "little")

    def field(base, name, value):
        put(base + layout[name], value)

    for i, value in enumerate(gprs):
        put(seed + layout["RF_GPR"] + i * 8, value)
    for i, value in enumerate(vectors):
        put(seed + layout["RF_Q"] + i * 16, value, 16)
    for name, value in [("RF_SP", root), ("RF_NZCV", 0xa0000000), ("RF_FPCR", 0x400000), ("RF_FPSR", 0x10)]:
        field(seed, name, value)
    for name, value in [("AP_GUEST_ROOT", root), ("AP_STACK_LOW", guest_low), ("AP_STACK_HIGH", guest_high),
                        ("AP_LOGICAL_X18", 0x1818abcd), ("AP_LOGICAL_TLS", tls), ("AP_SEED", seed),
                        ("ACT_OBSERVED", observed), ("ACT_OPERATION", 1)]:
        field(act, name, value)
    for i, value in enumerate(host_gprs):
        put(host_seed + layout["HF_GPR"] + i * 8, value)
    for i, value in enumerate(host_vectors):
        put(host_seed + layout["HF_Q"] + i * 16, value, 16)
    put(host_seed + layout["HF_NZCV"], 0x60000000, 4)
    for i, register in enumerate(xregs):
        uc.reg_write(register, 0x80000000 + i)
    for i, register in enumerate(qregs):
        uc.reg_write(register, (0x90000000 + i) << 64 | (0xa0000000 + i))
    uc.reg_write(xregs[18], physical_x18)
    uc.reg_write(reg.UC_ARM64_REG_TPIDR_EL0, tp)
    uc.reg_write(reg.UC_ARM64_REG_SP, initial_sp)
    uc.reg_write(reg.UC_ARM64_REG_FPCR, 0)
    uc.reg_write(reg.UC_ARM64_REG_FPSR, 0)
    uc.reg_write(reg.UC_ARM64_REG_NZCV, 0x90000000)
    uc.reg_write(xregs[0], act)
    uc.reg_write(xregs[1], host_observed)
    uc.reg_write(xregs[2], host_seed)
    stop = 0x70000000
    uc.mem_map(stop, 4096, un.UC_PROT_READ | un.UC_PROT_EXEC)
    uc.reg_write(xregs[30], stop)
    gate_start = symbols["d2rm_gate"]
    gate_end = symbols["d2rm_callback_stress"]
    gate_code = bytes(uc.mem_read(gate_start, gate_end - gate_start))
    mutation_address = None
    if mutation:
        # 修改实际可执行指令，保留预期状态数组。
        # Mutate actual executable instructions, not the expected-state arrays.
        pattern = {"gpr": 0xf9400000 | (10 << 10) | (31 << 5) | 10,
                   "simd": 0xad400000 | (16 << 15) | (1 << 10) | (31 << 5),
                   "flags": 0xd51b4200 | 9,
                   "below-sp": 0}[mutation]
        if mutation == "below-sp":
            mutation_address = gate_start + 4
        else:
            found = [gate_start + i for i in range(0, len(gate_code), 4)
                     if int.from_bytes(gate_code[i:i+4], "little") == pattern]
            if not found:
                raise AssertionError(f"mutation instruction absent: {mutation}")
            mutation_address = found[-1]
        uc.mem_write(mutation_address, struct.pack("<I", 0xf81f83e0 if mutation == "below-sp" else 0xd503201f))
    count, callbacks, stack_writes = 0, 0, 0
    allocated_sp = {host_low: initial_sp, guest_low: guest_high}
    expected_result = 0x1234abcddcba4321

    def on_write(machine, access, address, size, value, user):
        nonlocal stack_writes
        sp = machine.reg_read(reg.UC_ARM64_REG_SP)
        for low, high in [(host_low, host_high), (guest_low, guest_high)]:
            if low <= address < high:
                stack_writes += 1
                if address < allocated_sp[low] or address + size > high:
                    raise AssertionError(f"write outside allocated stack: {address:#x}, allocated SP={allocated_sp[low]:#x}, current SP={sp:#x}")

    def on_code(machine, address, size, user):
        nonlocal count, callbacks
        count += 1
        if count > budget:
            raise AssertionError("instruction budget exceeded")
        sp = machine.reg_read(reg.UC_ARM64_REG_SP)
        for low, high in [(host_low, host_high), (guest_low, guest_high)]:
            if low <= sp <= high:
                allocated_sp[low] = sp
        if sp & 15:
            raise AssertionError(f"unaligned SP at {address:#x}")
        if machine.reg_read(xregs[18]) != physical_x18 or machine.reg_read(reg.UC_ARM64_REG_TPIDR_EL0) != tp:
            raise AssertionError("physical x18 or host TPIDR_EL0 changed")
        if address == symbols["d2rm_host_dispatch"]:
            callbacks += 1
            check("dispatch activation", act, machine.reg_read(xregs[0]))
            check("dispatch operation", 1, machine.reg_read(xregs[2]))
            check("host stack", 1, int(host_low <= sp < get(act + layout["AP_HOST_FRAME"])))
            check("host FPCR before callback", 0, machine.reg_read(reg.UC_ARM64_REG_FPCR))
            check("host FPSR before callback", 0, machine.reg_read(reg.UC_ARM64_REG_FPSR))
            frame = machine.reg_read(xregs[1])
            for i, expected in enumerate(gprs):
                check(f"dispatch x{i}", 0x1818abcd if i == 18 else expected, get(frame + layout["RF_GPR"] + i * 8))
            for i, expected in enumerate(vectors):
                check(f"dispatch q{i}", expected, get(frame + layout["RF_Q"] + i * 16, 16))
            machine.reg_write(xregs[0], expected_result)
            # 执行已链接桩的 RET；不模拟 C++ 逻辑和平台 TLS。
            # The linked stub RET executes. C++ logic and platform TLS are not modeled.

    uc.hook_add(un.UC_HOOK_MEM_WRITE, on_write)
    uc.hook_add(un.UC_HOOK_CODE, on_code)
    uc.emu_start(symbols["d2rm_host_wrapper"], stop)
    check("callback count", 1, callbacks)
    check("normal return PC", stop, uc.reg_read(reg.UC_ARM64_REG_PC))
    check("normal return value", expected_result, uc.reg_read(xregs[0]))
    check("host outer SP", initial_sp, uc.reg_read(reg.UC_ARM64_REG_SP))
    check("host outer NZCV", 0x90000000, uc.reg_read(reg.UC_ARM64_REG_NZCV))
    for i in range(19, 30):
        check(f"host outer x{i}", 0x80000000 + i, uc.reg_read(xregs[i]))
    for i in range(8, 16):
        check(f"host outer q{i}", (0x90000000 + i) << 64 | (0xa0000000 + i), uc.reg_read(qregs[i]))
    for i, expected in enumerate(gprs):
        check(f"guest x{i}", expected_result if i == 0 else physical_x18 if i == 18 else expected,
              get(observed + layout["RF_GPR"] + i * 8))
    for i, expected in enumerate(vectors):
        check(f"guest q{i}", expected, get(observed + layout["RF_Q"] + i * 16, 16))
    for name in ["RF_SP", "RF_NZCV", "RF_FPCR", "RF_FPSR"]:
        check(name, get(seed + layout[name]), get(observed + layout[name]))
    for i in range(12):
        check(f"host bridge x{i+19}", get(host_seed + layout["HF_GPR"] + i * 8), get(host_observed + layout["HF_GPR"] + i * 8))
    for i, expected in enumerate(host_vectors):
        check(f"host bridge q{i+8}", expected, get(host_observed + layout["HF_Q"] + i * 16, 16))
    for name, width in [("HF_SP", 8), ("HF_FPCR", 4), ("HF_FPSR", 4), ("HF_NZCV", 4)]:
        check(name, get(host_seed + layout[name], width), get(host_observed + layout[name], width))
    check("root cookie", 0x5a5a, get(root + layout["GR_COOKIE"]))
    return {"status": "passed", "checks": len(checks), "instructions": count, "stackWrites": stack_writes,
            "layoutSymbols": len(layout), "modeledPhysicalX18": hex(physical_x18), "modeledHostTPIDR_EL0": hex(tp)}


def main():
    args = arguments()
    report = {"evidenceKind": "instruction_model", "hostDispatch": "substituted at d2rm_host_dispatch; linked stress shim executes",
              "androidRuntime": "not_run", "physicalDevice": "not_run"}
    try:
        elf = build(args)
        report["modelElfInspection"] = inspect_branches(elf)
        if args.android_elf:
            report["androidElfInspection"] = inspect_branches(args.android_elf, args.android_apk)
        report["positive"] = execute(elf, args.instruction_budget, args.mutate)
        if args.mutate:
            raise AssertionError("negative mutation escaped the oracle")
        report["negativeControls"] = []
        for mutation in ["gpr", "simd", "flags", "below-sp"]:
            try:
                execute(elf, args.instruction_budget, mutation)
            except AssertionError as failure:
                required = {"gpr": "guest x10:", "simd": "guest q0:", "flags": "RF_NZCV:",
                            "below-sp": "write outside allocated stack:"}[mutation]
                if required not in str(failure):
                    raise
                report["negativeControls"].append({"mutation": mutation, "status": "rejected", "reason": str(failure)})
            else:
                raise AssertionError(f"negative mutation escaped the oracle: {mutation}")
        report["status"] = "passed"
        code = 0
    except Exception as failure:
        report.update(status="failed", reason=str(failure), errorType=type(failure).__name__)
        code = 1
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
