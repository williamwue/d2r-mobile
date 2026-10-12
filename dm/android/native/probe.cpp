#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <sstream>
#include <string>
#include <vector>

namespace {
std::string quote(const char* input) {
    std::ostringstream out;
    out << '"';
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(input); *p; ++p) {
        if (*p == '"' || *p == '\\') out << '\\' << *p;
        else if (*p < 0x20) {
            constexpr char hex[] = "0123456789abcdef";
            out << "\\u00" << hex[*p >> 4] << hex[*p & 15];
        } else out << *p;
    }
    out << '"';
    return out.str();
}

std::string vulkanReport() {
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return R"({"status":"unavailable","reason":"Vulkan loader unavailable"})";
    auto getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
    if (!getProc) {
        dlclose(library);
        return R"({"status":"unavailable","reason":"Vulkan entry point unavailable"})";
    }
    auto create = reinterpret_cast<PFN_vkCreateInstance>(getProc(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!create) {
        dlclose(library);
        return R"({"status":"unavailable","reason":"vkCreateInstance unavailable"})";
    }
    VkApplicationInfo application{};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.pApplicationName = "D2RM capability probe";
    application.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &application;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = create(&info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        dlclose(library);
        return "{\"status\":\"unavailable\",\"vkResult\":" + std::to_string(result) + "}";
    }
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(getProc(instance, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(getProc(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(getProc(instance, "vkGetPhysicalDeviceProperties"));
    auto features = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(getProc(instance, "vkGetPhysicalDeviceFeatures"));
    std::ostringstream out;
    if (!destroy || !enumerate || !properties || !features) {
        out << R"({"status":"error","reason":"Missing Vulkan instance functions"})";
    } else {
        uint32_t count = 0;
        result = enumerate(instance, &count, nullptr);
        if (result != VK_SUCCESS || count > 64) {
            out << "{\"status\":\"error\",\"vkResult\":" << result << "}";
        } else {
            std::vector<VkPhysicalDevice> devices(count);
            if (count) result = enumerate(instance, &count, devices.data());
            if (result != VK_SUCCESS) out << "{\"status\":\"error\",\"vkResult\":" << result << "}";
            else {
                out << "{\"status\":\"" << (count ? "available" : "no_devices") << "\",\"devices\":[";
                for (uint32_t i = 0; i < count; ++i) {
                    VkPhysicalDeviceProperties props{};
                    VkPhysicalDeviceFeatures feats{};
                    properties(devices[i], &props);
                    features(devices[i], &feats);
                    if (i) out << ',';
                    out << "{\"name\":" << quote(props.deviceName)
                        << ",\"apiVersion\":" << props.apiVersion
                        << ",\"vendorId\":" << props.vendorID
                        << ",\"deviceId\":" << props.deviceID
                        << ",\"deviceType\":" << props.deviceType
                        << ",\"textureCompressionBC\":" << (feats.textureCompressionBC ? "true" : "false")
                        << ",\"textureCompressionASTC_LDR\":" << (feats.textureCompressionASTC_LDR ? "true" : "false")
                        << ",\"shaderFloat64\":" << (feats.shaderFloat64 ? "true" : "false") << '}';
                }
                out << "]}";
            }
        }
    }
    if (destroy) destroy(instance, nullptr);
    dlclose(library);
    return out.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_d2rm_android_NativeProbe_read(JNIEnv* env, jclass) {
#if defined(__aarch64__)
    constexpr char abi[] = "arm64-v8a";
#elif defined(__x86_64__)
    constexpr char abi[] = "x86_64";
#else
    constexpr char abi[] = "unsupported";
#endif
    std::ostringstream report;
    report << "{\"probeVersion\":1,\"jniSentinel\":4242,\"abi\":" << quote(abi)
           << ",\"pageSize\":" << sysconf(_SC_PAGESIZE)
           << ",\"gameRuntime\":\"unavailable\",\"vulkan\":" << vulkanReport() << '}';
    return env->NewStringUTF(report.str().c_str());
}
