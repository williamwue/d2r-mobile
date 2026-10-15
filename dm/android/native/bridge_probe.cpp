// 基于固定合成来宾验证宿主桥接、线程隔离和状态恢复，不加载游戏。
// Verify host bridging, thread isolation, and restoration using a fixed synthetic guest, without loading a game.
#include "bridge.h"
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <sstream>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

namespace d2rm {
namespace {
struct Observation {
    const char* name;
    Status status;
    uint64_t expected, observed;
    unsigned phase = 0;
};
const char* statusName(Status status) {
    switch (status) {
        case Status::passed: return "passed";
        case Status::failed: return "failed";
        case Status::unsupported_abi: return "unsupported_abi";
        case Status::not_run: return "not_run";
    }
    return "failed";
}
#if defined(__aarch64__)
struct InjectedSetupFailure {};
template<class Worker>
bool startWorkers(Worker worker, std::atomic<bool>& aborted, bool injectSecondFailure = false) {
    std::thread threads[2];
    bool started = true;
    try {
        threads[0] = std::thread(worker, 0);
        if (injectSecondFailure) throw InjectedSetupFailure{};
        threads[1] = std::thread(worker, 1);
    } catch (...) { started = false; aborted.store(true); }
    for (auto& thread : threads) if (thread.joinable()) thread.join();
    return started;
}
template<class Action>
void containWorker(Action action, bool& failed, std::atomic<bool>& aborted) noexcept {
    try { action(); }
    catch (...) { failed = true; aborted.store(true); }
}
struct LogicalTls { uint64_t marker, calls; };
bool validGeometry(long page, uintptr_t low, uintptr_t high, uintptr_t root) {
    if (page != 4096 && page != 16384) return false;
    const auto alignment = static_cast<uintptr_t>(page);
    return low != 0 && low < high && low % alignment == 0 && high % alignment == 0 &&
        root >= low && root <= high && root % 16 == 0 &&
        root - low >= RF_SIZE + GE_SIZE && high - root >= GR_SIZE;
}
struct GuestStack {
    void* mapping = MAP_FAILED;
    size_t mappingSize = 0;
    uintptr_t low = 0, high = 0;
    long page = 0;
    GuestStack() {
        page = sysconf(_SC_PAGESIZE);
        if (page != 4096 && page != 16384) return;
        mappingSize = 18 * static_cast<size_t>(page);
        mapping = mmap(nullptr, mappingSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mapping == MAP_FAILED) return;
        low = reinterpret_cast<uintptr_t>(mapping) + page;
        high = low + 16 * page;
        if (mprotect(reinterpret_cast<void*>(low), 16 * page, PROT_READ | PROT_WRITE) != 0) {
            munmap(mapping, mappingSize); mapping = MAP_FAILED; low = high = 0;
        }
    }
    ~GuestStack() { if (mapping != MAP_FAILED) munmap(mapping, mappingSize); }
    GuestStack(const GuestStack&) = delete;
    GuestStack& operator=(const GuestStack&) = delete;
};
struct Activation {
    ActivationPrefix prefix{};
    RegisterFrame* observed;
    uint64_t operation;
    LogicalTls tls{};
    uint64_t physicalX18, hostTp, hostMarker;
    HostFrame hostExpected{}, hostObserved{};
    std::atomic<unsigned>* arrivals;
    std::atomic<bool>* aborted;
    unsigned phase;
    std::vector<Observation>* observations;
    std::thread::id owner;
    GuestStack stack;
};
static_assert(offsetof(Activation, observed) == ACT_OBSERVED);
static_assert(offsetof(Activation, operation) == ACT_OPERATION);
Activation*& active() { static thread_local Activation* value = nullptr; return value; }
uint64_t& hostMarker() { static thread_local uint64_t value = 0; return value; }
uint64_t readX18() { uint64_t value; asm volatile("mov %0, x18" : "=r"(value)); return value; }
uint64_t readTp() { uint64_t value; asm volatile("mrs %0, tpidr_el0" : "=r"(value)); return value; }
uint64_t readSp() { uint64_t value; asm volatile("mov %0, sp" : "=r"(value)); return value; }
void check(Activation& a, const char* name, uint64_t expected, uint64_t observed) {
    a.observations->push_back({name, expected == observed ? Status::passed : Status::failed, expected, observed, a.phase});
}
extern "C" uint64_t d2rm_host_wrapper(Activation*, HostFrame*, HostFrame*);
bool enter(Activation& a, uint64_t& result) {
    // 接触任一激活记录或栈之前拒绝重入。
    // Reject before touching either activation or either stack.
    if (active() || a.owner != std::this_thread::get_id() ||
        !validGeometry(a.stack.page, a.prefix.stackLow, a.prefix.stackHigh, a.prefix.guestRoot)) return false;
    active() = &a;
    result = d2rm_host_wrapper(&a, &a.hostObserved, &a.hostExpected);
    active() = nullptr;
    return true;
}
uint64_t expectedResult(const Activation& a) {
    return a.operation == 1 ? a.tls.marker ^ UINT64_C(0xa55a123456789abc) : UINT64_MAX;
}
unsigned gprMismatches(const RegisterFrame& expected, const RegisterFrame& observed, uint64_t x0, uint64_t x18) {
    unsigned count = 0;
    for (unsigned i = 0; i != 31; ++i)
        count += observed.gpr[i] != (i == 0 ? x0 : i == 18 ? x18 : expected.gpr[i]);
    return count;
}
unsigned vectorMismatches(const Vector128* expected, const Vector128* observed, unsigned count) {
    unsigned mismatches = 0;
    for (unsigned i = 0; i != count; ++i)
        mismatches += expected[i].low != observed[i].low || expected[i].high != observed[i].high;
    return mismatches;
}
extern "C" __attribute__((visibility("hidden"))) uint64_t
d2rm_host_dispatch(Activation* activation, const RegisterFrame* frame, uint64_t operation) noexcept {
    Activation& a = *activation;
    const auto& seed = *reinterpret_cast<const RegisterFrame*>(a.prefix.seed);
    check(a, "dispatch_active_owner", 1, active() == &a);
    check(a, "dispatch_host_stack", 1, readSp() < a.prefix.hostFrame &&
          !(readSp() >= a.prefix.stackLow && readSp() < a.prefix.stackHigh));
    check(a, "dispatch_stack_alignment", 0, readSp() & 15);
    check(a, "dispatch_host_tls", a.hostMarker, hostMarker());
    check(a, "dispatch_tpidr_el0", a.hostTp, readTp());
    check(a, "dispatch_physical_x18", a.physicalX18, readX18());
    uint64_t fpcr, fpsr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    asm volatile("mrs %0, fpsr" : "=r"(fpsr));
    check(a, "dispatch_host_fpcr", a.hostExpected.fpcr, fpcr);
    check(a, "dispatch_host_fpsr", a.hostExpected.fpsr, fpsr);
    check(a, "dispatch_input_gprs", 0, gprMismatches(seed, *frame, seed.gpr[0], a.prefix.logicalX18));
    check(a, "dispatch_input_vectors", 0, vectorMismatches(seed.q, frame->q, 32));
    check(a, "dispatch_input_sp", seed.sp, frame->sp);
    check(a, "dispatch_input_nzcv", seed.nzcv, frame->nzcv);
    check(a, "dispatch_input_fpcr", seed.fpcr, frame->fpcr);
    check(a, "dispatch_input_fpsr", seed.fpsr, frame->fpsr);
    check(a, "dispatch_operation", a.operation, operation);
    check(a, "logical_tls_owner", reinterpret_cast<uintptr_t>(&a.tls), a.prefix.logicalTls);
    check(a, "logical_tls_marker", a.hostMarker ^ UINT64_C(0x13579), a.tls.marker);
    const auto prefix = a.prefix;
    const auto root = *reinterpret_cast<const GuestRoot*>(a.prefix.guestRoot);
    uint64_t nestedResult = 0xeeee;
    check(a, "nested_entry_rejected", 0, enter(a, nestedResult));
    check(a, "nested_prefix_unchanged", 0, std::memcmp(&prefix, &a.prefix, sizeof(prefix)) != 0);
    check(a, "nested_root_unchanged", 0, std::memcmp(&root, reinterpret_cast<void*>(a.prefix.guestRoot), sizeof(root)) != 0);
    check(a, "nested_result_unchanged", 0xeeee, nestedResult);
    check(a, "nested_outer_owner", 1, active() == &a);
    a.arrivals->fetch_add(1);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (a.arrivals->load() < 2 * (a.phase + 1) && !a.aborted->load() && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    check(a, "concurrent_callbacks", 1, a.arrivals->load() >= 2 * (a.phase + 1));
    check(a, "concurrent_tls_retained", a.hostMarker, hostMarker());
    ++a.tls.calls;
    a.prefix.status = operation == 1 ? 0 : 1;
    return expectedResult(a);
}
void runThread(unsigned index, std::atomic<unsigned>& arrivals, std::atomic<bool>& aborted, std::vector<Observation>& observations) {
    observations.reserve(192);
    Activation a{};
    a.owner = std::this_thread::get_id();
    if (!a.stack.low) {
        observations.push_back({"guarded_stack_allocation", Status::failed, 1, 0});
        aborted.store(true);
        return;
    }
    hostMarker() = UINT64_C(0x41700000) + index * 0x10000;
    RegisterFrame seed{}, observed{};
    a.prefix.guestRoot = a.stack.high - GR_SIZE;
    a.prefix.stackLow = a.stack.low; a.prefix.stackHigh = a.stack.high;
    a.prefix.logicalX18 = UINT64_C(0x18180000) + index;
    a.prefix.logicalTls = reinterpret_cast<uintptr_t>(&a.tls);
    a.prefix.seed = reinterpret_cast<uintptr_t>(&seed);
    a.observed = &observed;
    a.physicalX18 = readX18(); a.hostTp = readTp(); a.hostMarker = hostMarker();
    a.tls.marker = a.hostMarker ^ UINT64_C(0x13579);
    a.arrivals = &arrivals; a.aborted = &aborted; a.observations = &observations;
    check(a, "geometry_negative_sysconf", 0, validGeometry(-1, 0x10000, 0x20000, 0x1fff0));
    check(a, "geometry_unsupported_page", 0, validGeometry(8192, 0x10000, 0x20000, 0x1fff0));
    check(a, "geometry_unaligned_mapping", 0, validGeometry(4096, 0x10001, 0x20000, 0x1fff0));
    const auto rootBefore = a.prefix.guestRoot;
    a.prefix.guestRoot = a.prefix.stackLow + 16;
    const auto prefixBefore = a.prefix;
    uint64_t rejectedResult = 0xeeee;
    check(a, "geometry_insufficient_headroom", 0, enter(a, rejectedResult));
    check(a, "geometry_rejection_unchanged", 0, std::memcmp(&prefixBefore, &a.prefix, sizeof(prefixBefore)) != 0);
    check(a, "geometry_rejection_result", 0xeeee, rejectedResult);
    check(a, "geometry_rejection_cleanup", 1, active() == nullptr);
    a.prefix.guestRoot = rootBefore;
    for (unsigned phase = 0; phase != 3; ++phase) {
        a.phase = phase; a.operation = phase == 1 ? 99 : 1;
        for (unsigned i = 0; i != 31; ++i) seed.gpr[i] = UINT64_C(0x5100000000000000) + index * 0x100000 + phase * 0x1000 + i;
        for (unsigned i = 0; i != 32; ++i) seed.q[i] = {UINT64_C(0x6100000000000000) + index * 0x100000 + phase * 0x1000 + i,
            UINT64_C(0x7200000000000000) + index * 0x100000 + phase * 0x1000 + i};
        seed.sp = a.prefix.guestRoot; seed.nzcv = 0xa0000000; seed.fpcr = 0x400000; seed.fpsr = 0x10;
        for (unsigned i = 0; i != 12; ++i) a.hostExpected.gpr[i] = 0x73000000 + index * 0x1000 + i;
        for (unsigned i = 0; i != 8; ++i) a.hostExpected.q[i] = {0x74000000 + i, 0x75000000 + i};
        a.hostExpected.fpcr = 0; a.hostExpected.fpsr = 0; a.hostExpected.nzcv = 0x60000000;
        uint64_t result = 0;
        check(a, "entry_accepted", 1, enter(a, result));
        check(a, "normal_root_return", expectedResult(a), result);
        check(a, "guest_gprs", 0, gprMismatches(seed, observed, expectedResult(a), a.physicalX18));
        check(a, "guest_full_vectors", 0, vectorMismatches(seed.q, observed.q, 32));
        check(a, "guest_sp", seed.sp, observed.sp);
        check(a, "guest_nzcv", seed.nzcv, observed.nzcv);
        check(a, "guest_fpcr", seed.fpcr, observed.fpcr);
        check(a, "guest_fpsr", seed.fpsr, observed.fpsr);
        unsigned badHost = 0;
        for (unsigned i = 0; i != 12; ++i) badHost += a.hostExpected.gpr[i] != a.hostObserved.gpr[i];
        check(a, "host_callee_gprs", 0, badHost);
        check(a, "host_full_q8_q15", 0, vectorMismatches(a.hostExpected.q, a.hostObserved.q, 8));
        check(a, "host_sp", a.hostExpected.sp, a.hostObserved.sp);
        check(a, "host_fpcr", a.hostExpected.fpcr, a.hostObserved.fpcr);
        check(a, "host_fpsr", a.hostExpected.fpsr, a.hostObserved.fpsr);
        check(a, "host_nzcv", a.hostExpected.nzcv, a.hostObserved.nzcv);
        check(a, "host_x18", a.physicalX18, readX18());
        check(a, "host_tpidr_el0", a.hostTp, readTp());
        check(a, "logical_tls_calls", phase + 1, a.tls.calls);
        check(a, "operation_status", phase == 1 ? 1 : 0, a.prefix.status);
        check(a, "active_cleanup", 1, active() == nullptr);
        check(a, "root_cookie", 0x5a5a, reinterpret_cast<GuestRoot*>(a.prefix.guestRoot)->cookie);
    }
}
#endif
}

std::string runArm64BridgeSuite() {
    std::vector<Observation> observations[2];
    Status status = Status::unsupported_abi;
#if defined(__aarch64__)
    std::atomic<bool> injectedAbort{false};
    std::atomic<bool> injectedWorkerFinished{false};
    const bool injectedStarted = startWorkers([&](unsigned) { injectedWorkerFinished.store(true); }, injectedAbort, true);
    const bool setupRecovered = !injectedStarted && injectedAbort.load() && injectedWorkerFinished.load();
    observations[0].push_back({"thread_setup_failure_joined", setupRecovered ? Status::passed : Status::failed, 1, setupRecovered});
    bool injectedWorkerFailed = false;
    injectedAbort.store(false);
    containWorker([] { throw InjectedSetupFailure{}; }, injectedWorkerFailed, injectedAbort);
    const bool workerRecovered = injectedWorkerFailed && injectedAbort.load();
    observations[0].push_back({"worker_exception_contained", workerRecovered ? Status::passed : Status::failed, 1, workerRecovered});
    std::atomic<unsigned> arrivals{0};
    std::atomic<bool> aborted{false};
    bool workerFailed[2] = {false, false};
    auto worker = [&](unsigned index) noexcept {
        containWorker([&] { runThread(index, arrivals, aborted, observations[index]); }, workerFailed[index], aborted);
    };
    const bool setupFailed = !startWorkers(worker, aborted);
    if (setupFailed) observations[0].push_back({"thread_setup_exception", Status::failed, 0, 1});
    for (unsigned index = 0; index != 2; ++index)
        if (workerFailed[index]) observations[index].push_back({"worker_exception", Status::failed, 0, 1});
    status = Status::passed;
    for (const auto& thread : observations) for (const auto& check : thread)
        if (check.status != Status::passed) status = Status::failed;
    constexpr char abi[] = "arm64-v8a";
#else
    constexpr char abi[] = "x86_64";
    observations[0].push_back({"arm64_execution", Status::not_run, 0, 0});
#endif
    std::ostringstream out;
    out << "{\"suite\":\"synthetic_arm64_bridge_v1\",\"status\":\"" << statusName(status)
        << "\",\"compiledAbi\":\"" << abi << "\",\"runtimeClassification\":\"unclassified\",\"gameRuntime\":\"unavailable\",\"checks\":[";
    bool comma = false;
    unsigned executed = 0;
    for (unsigned thread = 0; thread != 2; ++thread) {
        for (const auto& check : observations[thread]) {
            if (comma) out << ',';
            comma = true;
            executed += check.status == Status::passed || check.status == Status::failed;
            out << "{\"name\":\"" << check.name << "\",\"thread\":" << thread
                << ",\"phase\":" << check.phase
                << ",\"status\":\"" << statusName(check.status) << "\",\"expected\":\"" << check.expected
                << "\",\"observed\":\"" << check.observed << "\"}";
        }
    }
    out << "],\"executedChecks\":" << executed << '}';
    return out.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_d2rm_android_NativeProbe_runArm64BridgeSuite(JNIEnv* env, jclass) {
    try {
        const auto report = d2rm::runArm64BridgeSuite();
        return env->NewStringUTF(report.c_str());
    } catch (...) {
        return env->NewStringUTF(R"({"suite":"synthetic_arm64_bridge_v1","status":"failed","reason":"native_report_exception","gameRuntime":"unavailable","executedChecks":0,"checks":[]})");
    }
}
