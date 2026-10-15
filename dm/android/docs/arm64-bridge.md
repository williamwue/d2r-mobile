# Synthetic ARM64 bridge

`NativeProbe.runArm64BridgeSuite()` returns the report from the C++ function `d2rm::runArm64BridgeSuite()`. The suite runs a fixed linked guest. It does not load an NSO or game data. `NativeProbe.read()` keeps its capability-only contract, and `gameRuntime` remains `unavailable`.

## Private ABI

Each activation owns a guarded RW guest stack and logical TLS data. The stack has sixteen usable pages between two inaccessible guard pages. It accepts runtime page sizes of 4096 and 16384 bytes. Failed page-size queries and unsupported sizes fail setup before mapping. Entry validates page alignment and space for the root, envelope, and register frame before it changes the activation. The linked assembly is RX. Physical x18 and host TPIDR_EL0 are observed without writes. Logical guest x18 is a field in the activation and in the dispatch frame. Logical TLS is ordinary owned data, not an implementation of arbitrary guest TLS instructions.

`bridge_layout.h` defines the offsets for C++ and assembly. C++ verifies them with `static_assert`. The ELF exports absolute `layout_*` symbols that the Python model reads.

| Type | Bytes | Contents |
| --- | ---: | --- |
| `RegisterFrame` | 800 | x0 through x30, original SP, full q0 through q31, NZCV, FPCR, FPSR |
| `GateEnvelope` | 48 | original x16, x17, x30, activation, static epilogue, operation |
| `GuestRoot` | 16 | activation and cookie |
| `HostFrame` | 272 | x19 through x30, full q8 through q15, FP state, SP, activation, guest frame, result |
| `ActivationPrefix` | 64 | host frame, guest root, stack bounds, logical x18, logical TLS, expected seed, status |

The fixture enters the gate with a local `B`, using a stack envelope and a static epilogue. This is not a normal `BL` call. The gate preserves the full guest register state except x0, which receives the dispatcher result. It preserves SP and flags. It never restores logical x18 into physical x18. Every stack write uses allocated space. There is no below-SP red zone.

The dispatcher receives a const register frame and returns only x0. It runs on the suspended host stack after restoration of host FP state and x29. Operation 1 returns a value derived from the thread's logical TLS marker. Unknown operations return `UINT64_MAX` and record an error status, then return normally. The stress shim clobbers caller-saved registers and SIMD state within the ordinary host ABI, including the upper halves of q8 through q15.

The entry guard rejects same-thread reentry and a different owning thread before it changes activation state. The suite checks nested rejection, unchanged metadata, insufficient stack headroom, and the outer operation's normal return. A C++ thread-local pointer records the current activation. No process-global activation or guest TLS state exists.

Only normal and explicit synthetic error returns are supported. Exceptions, longjmp, cancellation, signal reentry, and asynchronous unwinding cannot cross this assembly boundary. The dispatcher is `noexcept` and uses preallocated observation records. Setup and worker exceptions produce failed receipts, and started threads are joined. JNI catches report-construction exceptions. Guard-page faults are process faults. Trusted fixture metadata is not a security boundary.

## Report contract

The top-level status is `passed`, `failed`, or `unsupported_abi`. Each check records a name, thread, phase, status, expected value, and observation. A check that did not execute has status `not_run`. Values are decimal strings to preserve all 64 bits in JSON consumers. Setup checks inject failure after the first thread starts and an exception inside a contained worker action. They verify that the started thread is joined and that the exception is contained.

On ARM64, two threads use distinct markers and rendezvous inside their callbacks. Each thread runs operation 1, an unknown operation, and operation 1 again on the same activation. Expected guest registers come from independent C++ seeds. Assembly captures the resumed state separately from the gate's saved frame. Checks cover every guest GPR, all 128 bits of every SIMD register, stack and flags, host ABI restoration, TLS, reentry rejection, sequential reuse, and cleanup of the active pointer.

On x86_64, the report contains `unsupported_abi`, zero executed ARM64 checks, and a `not_run` observation. The native report uses `runtimeClassification: unclassified`. A host collector can classify translation from recorded Android properties. Compiling as ARM64 does not prove direct hardware execution.

## Verification commands

The build uses JDK 21, Android SDK 37, NDK 28.2.13676358, and CMake 3.22.1. The existing Gradle build packages both native ABIs.

```powershell
	$env:JAVA_HOME = 'G:/Dev/SDKs/jdk-21'
	$env:ANDROID_HOME = 'G:/Dev/SDKs/Android'
	./gradlew.bat :app:assembleFixtureDebug :app:assembleGameDebug :app:assembleFixtureDebugAndroidTest
```

The optional instruction model requires Python 3.10 or later and the pinned `tools/requirements-arm64-bridge.txt`. Existing resource-tool tests remain stdlib-only. On Windows, the prepared interpreter is `G:/DevCaches/venvs/d2r-arm64-probe/Scripts/python.exe`.

```powershell
	python -m pip install -r tools/requirements-arm64-bridge.txt
	python tools/verify_arm64_bridge.py --ndk G:/Dev/SDKs/Android/ndk/28.2.13676358 --output build/arm64-model
```

The corresponding Apple Silicon macOS invocation uses the NDK's `darwin-x86_64` host-tool directory. The script selects that directory automatically.

```sh
	python3 -m pip install -r tools/requirements-arm64-bridge.txt
	python3 tools/verify_arm64_bridge.py --ndk "$HOME/Library/Android/sdk/ndk/28.2.13676358" --output build/arm64-model
```

`--android-elf` accepts the unstripped ARM64 `libd2rm_probe.so` under `app/build/intermediates/cxx/Debug/<build-id>/obj/arm64-v8a/`. `--android-apk` additionally compares its `.text` bytes with the ARM64 library in the APK. This inspection verifies local direct branch targets and the static epilogue in the linked artifact.

```powershell
	python tools/verify_arm64_bridge.py --ndk G:/Dev/SDKs/Android/ndk/28.2.13676358 --output build/arm64-model --android-elf app/build/intermediates/cxx/Debug/1y6o6f1w/obj/arm64-v8a/libd2rm_probe.so --android-apk app/build/outputs/apk/fixture/debug/app-fixture-debug.apk
```

The model builds the same `bridge_arm64.S` into an ELF and executes its instructions in Unicorn 2.1.4. It substitutes only `d2rm_host_dispatch` with a checked return value. The assembly callback stress shim still executes. ELF permissions, allocated stack bounds, 16-byte alignment, register state, return, and an instruction budget are checked. The model initializes physical x18 and TPIDR_EL0 as modeled registers only. These initial values are not written on Android.

The default run also patches executable instructions to omit a GPR restore, a SIMD restore, and an NZCV restore, and to write below SP. Each mutation must fail its corresponding oracle. `--mutate gpr`, `--mutate simd`, `--mutate flags`, and `--mutate below-sp` run a failing mutation directly and exit 1. `--instruction-budget 1` also exits 1. Missing or malformed arguments exit 2. A successful full run exits 0. Reports and disassembly are written under `--output`.

The installed test APK exposes the separate suite through the existing runner:

```sh
	adb shell am instrument -w -e suite arm64-bridge org.d2rm.android.fixture.test/org.d2rm.android.ImportInstrumentation
```

The instrumentation result contains `bridgeReport` and `ARM64_BRIDGE_REPORT`. An x86_64 unsupported receipt is a successful check of reporting behavior, not ARM64 execution. ABI selection during installation and device-property collection belong to the host acceptance procedure.

## Evidence limits

Cross-building, linked ELF inspection, instruction modeling, translated Android ARM64 execution, direct ARM64 Android execution, and physical-device acceptance are separate evidence categories. The model does not execute Android C++, bionic TLS, JNI, or the thread scheduler. Android instrumentation runs those native checks. No result establishes arbitrary guest compatibility, game execution, signal recovery, or performance. Direct ARM64 Android and physical-device acceptance still require their own runs.
