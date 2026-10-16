#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::atomic<uint64_t> created{0}, destroyed{0};
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
}
std::string quote(const char* value) {
    std::ostringstream out;
    out << '"';
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') out << '\\' << *p;
        else if (*p < 32) out << '?';
        else out << *p;
    }
    return out.str() + '"';
}

struct Renderer {
    ANativeWindow* window = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> frames;
    std::vector<VkSemaphore> finished;
    VkExtent2D extent{};
    VkSurfaceTransformFlagBitsKHR transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    VkPhysicalDeviceProperties properties{};
    uint64_t session = 0, presents = 0;

    ~Renderer() {
        if (device) {
            // This small probe drains before releasing a Surface or any in-flight resource.
            vkDeviceWaitIdle(device);
            for (auto frame : frames) vkDestroyFramebuffer(device, frame, nullptr);
            for (auto view : views) vkDestroyImageView(device, view, nullptr);
            for (auto semaphore : finished) vkDestroySemaphore(device, semaphore, nullptr);
            if (fence) vkDestroyFence(device, fence, nullptr);
            if (acquired) vkDestroySemaphore(device, acquired, nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            if (pass) vkDestroyRenderPass(device, pass, nullptr);
            if (swapchain) vkDestroySwapchainKHR(device, swapchain, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        if (window) ANativeWindow_release(window);
        if (session) ++destroyed;
    }

    void init(JNIEnv* env, jobject javaSurface) {
        window = ANativeWindow_fromSurface(env, javaSurface);
        if (!window) throw std::runtime_error("Surface has no native window");
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "D2RM Vulkan clear probe";
        app.apiVersion = VK_API_VERSION_1_0;
        const char* instanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkInstanceCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ci.pApplicationInfo = &app;
        ci.enabledExtensionCount = 2;
        ci.ppEnabledExtensionNames = instanceExtensions;
        check(vkCreateInstance(&ci, nullptr, &instance), "vkCreateInstance");
        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window;
        check(vkCreateAndroidSurfaceKHR(instance, &sci, nullptr, &surface), "vkCreateAndroidSurfaceKHR");
        uint32_t count = 0;
        check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "enumerate devices");
        if (!count || count > 64) throw std::runtime_error("No bounded Vulkan device list");
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "enumerate devices");
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        uint32_t family = 0;
        for (auto candidate : devices) {
            uint32_t n = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, nullptr);
            if (!n || n > 64) continue;
            std::vector<VkQueueFamilyProperties> families(n);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &n, families.data());
            for (uint32_t i = 0; i < n; ++i) {
                VkBool32 canPresent = VK_FALSE;
                check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, &canPresent), "surface support");
                if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && canPresent) {
                    physical = candidate; family = i; break;
                }
            }
            if (physical) break;
        }
        if (!physical) throw std::runtime_error("No graphics and presentation queue");
        vkGetPhysicalDeviceProperties(physical, &properties);
        float priority = 1;
        VkDeviceQueueCreateInfo qi{};
        qi.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qi.queueFamilyIndex = family;
        qi.queueCount = 1;
        qi.pQueuePriorities = &priority;
        const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceCreateInfo di{};
        di.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        di.enabledExtensionCount = 1;
        di.ppEnabledExtensionNames = deviceExtensions;
        check(vkCreateDevice(physical, &di, nullptr, &device), "vkCreateDevice");
        vkGetDeviceQueue(device, family, 0, &queue);
        VkSurfaceCapabilitiesKHR caps{};
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps), "surface capabilities");
        if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
            throw std::runtime_error("Surface cannot be a color attachment");
        // Clear rectangles are expressed in the SurfaceView's coordinates.
        // Asking for the current display rotation would pre-rotate the columns.
        if (!(caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR))
            throw std::runtime_error("Probe requires identity Surface transform");
        count = 0;
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, nullptr), "surface formats");
        if (!count || count > 256) throw std::runtime_error("No bounded surface format list");
        std::vector<VkSurfaceFormatKHR> formats(count);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, formats.data()), "surface formats");
        auto format = formats.front();
        if (format.format == VK_FORMAT_UNDEFINED) format.format = VK_FORMAT_R8G8B8A8_UNORM;
        for (auto f : formats) if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) { format = f; break; }
        extent = caps.currentExtent;
        if (extent.width == UINT32_MAX) {
            int width = ANativeWindow_getWidth(window), height = ANativeWindow_getHeight(window);
            if (width <= 0 || height <= 0) throw std::runtime_error("Surface has zero extent");
            extent = {std::clamp(static_cast<uint32_t>(width), caps.minImageExtent.width, caps.maxImageExtent.width),
                      std::clamp(static_cast<uint32_t>(height), caps.minImageExtent.height, caps.maxImageExtent.height)};
        }
        if (!extent.width || !extent.height) throw std::runtime_error("Surface has zero extent");
        uint32_t images = caps.minImageCount + 1;
        if (caps.maxImageCount && images > caps.maxImageCount) images = caps.maxImageCount;
        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        for (auto a : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                       VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})
            if (caps.supportedCompositeAlpha & a) { alpha = a; break; }
        VkSwapchainCreateInfoKHR si{};
        si.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        si.surface = surface; si.minImageCount = images; si.imageFormat = format.format;
        si.imageColorSpace = format.colorSpace; si.imageExtent = extent; si.imageArrayLayers = 1;
        si.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; si.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        si.preTransform = transform; si.compositeAlpha = alpha;
        si.presentMode = VK_PRESENT_MODE_FIFO_KHR; si.clipped = VK_TRUE;
        check(vkCreateSwapchainKHR(device, &si, nullptr, &swapchain), "vkCreateSwapchainKHR");
        count = 0;
        check(vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr), "swapchain images");
        if (!count || count > 64) throw std::runtime_error("Invalid swapchain image count");
        std::vector<VkImage> swapImages(count);
        check(vkGetSwapchainImagesKHR(device, swapchain, &count, swapImages.data()), "swapchain images");
        VkAttachmentDescription attachment{};
        attachment.format = format.format; attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference color{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS; subpass.colorAttachmentCount = 1; subpass.pColorAttachments = &color;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL; dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        pi.attachmentCount = 1; pi.pAttachments = &attachment; pi.subpassCount = 1; pi.pSubpasses = &subpass;
        pi.dependencyCount = 1; pi.pDependencies = &dependency;
        check(vkCreateRenderPass(device, &pi, nullptr, &pass), "vkCreateRenderPass");
        views.resize(count); frames.resize(count); finished.resize(count);
        for (uint32_t i = 0; i < count; ++i) {
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = swapImages[i]; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = format.format;
            vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            check(vkCreateImageView(device, &vi, nullptr, &views[i]), "vkCreateImageView");
            VkFramebufferCreateInfo fi{};
            fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fi.renderPass = pass; fi.attachmentCount = 1; fi.pAttachments = &views[i];
            fi.width = extent.width; fi.height = extent.height; fi.layers = 1;
            check(vkCreateFramebuffer(device, &fi, nullptr, &frames[i]), "vkCreateFramebuffer");
            VkSemaphoreCreateInfo sem{}; sem.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            check(vkCreateSemaphore(device, &sem, nullptr, &finished[i]), "vkCreateSemaphore");
        }
        VkCommandPoolCreateInfo cp{};
        cp.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cp.queueFamilyIndex = family; cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device, &cp, nullptr, &pool), "vkCreateCommandPool");
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = pool; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &ai, &command), "vkAllocateCommandBuffers");
        VkSemaphoreCreateInfo sem{}; sem.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        check(vkCreateSemaphore(device, &sem, nullptr, &acquired), "vkCreateSemaphore");
        VkFenceCreateInfo fc{}; fc.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fc.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        check(vkCreateFence(device, &fc, nullptr, &fence), "vkCreateFence");
        session = ++created;
    }

    std::string draw() {
        constexpr uint64_t timeout = 2000000000ULL;
        check(vkWaitForFences(device, 1, &fence, VK_TRUE, timeout), "wait render fence");
        uint32_t index = 0;
        VkResult result = vkAcquireNextImageKHR(device, swapchain, timeout, acquired, VK_NULL_HANDLE, &index);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) return report("recreate");
        if (result == VK_TIMEOUT || result == VK_NOT_READY) return report("waiting");
        if (result != VK_SUBOPTIMAL_KHR) check(result, "vkAcquireNextImageKHR");
        check(vkResetCommandBuffer(command, 0), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo begin{}; begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer");
        VkClearValue background{};
        background.color.float32[3] = 1;
        VkRenderPassBeginInfo rp{};
        rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO; rp.renderPass = pass;
        rp.framebuffer = frames.at(index); rp.renderArea.extent = extent; rp.clearValueCount = 1; rp.pClearValues = &background;
        vkCmdBeginRenderPass(command, &rp, VK_SUBPASS_CONTENTS_INLINE);
        const float colors[3][4] = {{0.85f,0.08f,0.08f,1}, {0.08f,0.75f,0.12f,1}, {0.06f,0.18f,0.9f,1}};
        for (uint32_t i = 0; i < 3; ++i) {
            VkClearAttachment clear{}; clear.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            std::copy(colors[i], colors[i] + 4, clear.clearValue.color.float32);
            uint32_t left = extent.width * i / 3, right = extent.width * (i + 1) / 3;
            VkClearRect rect{{{static_cast<int32_t>(left), 0}, {right - left, extent.height}}, 0, 1};
            vkCmdClearAttachments(command, 1, &clear, 1, &rect);
        }
        vkCmdEndRenderPass(command);
        check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{}; submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &acquired; submit.pWaitDstStageMask = &stage;
        submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
        // A present-wait semaphore belongs to the image, not the frame fence.
        submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &finished.at(index);
        check(vkResetFences(device, 1, &fence), "vkResetFences");
        check(vkQueueSubmit(queue, 1, &submit, fence), "vkQueueSubmit");
        check(vkWaitForFences(device, 1, &fence, VK_TRUE, timeout), "wait clear completion");
        VkPresentInfoKHR present{}; present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = 1; present.pWaitSemaphores = &finished[index];
        present.swapchainCount = 1; present.pSwapchains = &swapchain; present.pImageIndices = &index;
        result = vkQueuePresentKHR(queue, &present);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) return report("recreate");
        if (result != VK_SUBOPTIMAL_KHR) check(result, "vkQueuePresentKHR");
        ++presents;
        return report("present_submitted");
    }

    std::string report(const char* status) const {
#if defined(__aarch64__)
        const char* abi = "arm64-v8a";
#else
        const char* abi = "x86_64";
#endif
        std::ostringstream out;
        out << "{\"probe\":\"vulkan-clear-v1\",\"gameRuntime\":\"unavailable\",\"status\":" << quote(status)
            << ",\"abi\":" << quote(abi) << ",\"session\":" << session << ",\"queuedPresents\":" << presents
            << ",\"device\":" << quote(properties.deviceName) << ",\"deviceType\":" << properties.deviceType
            << ",\"surfaceTransform\":" << transform << ",\"width\":" << extent.width << ",\"height\":" << extent.height << '}';
        return out.str();
    }
};

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_d2rm_android_GraphicsProbe_create(JNIEnv* env, jclass, jobject surface) {
    if (!surface) { fail(env, "A valid Surface is required"); return 0; }
    try {
        auto renderer = std::make_unique<Renderer>();
        renderer->init(env, surface);
        return reinterpret_cast<jlong>(renderer.release());
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "Native graphics setup failed"); }
    return 0;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_d2rm_android_GraphicsProbe_draw(JNIEnv* env, jclass, jlong handle) {
    if (!handle) { fail(env, "No graphics session"); return nullptr; }
    try { return env->NewStringUTF(reinterpret_cast<Renderer*>(handle)->draw().c_str()); }
    catch (const std::exception& error) { fail(env, error.what()); }
    catch (...) { fail(env, "Native graphics draw failed"); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_org_d2rm_android_GraphicsProbe_close(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Renderer*>(handle);
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_d2rm_android_GraphicsProbe_stats(JNIEnv* env, jclass) {
    std::ostringstream out;
    out << "{\"created\":" << created.load() << ",\"destroyed\":" << destroyed.load() << '}';
    return env->NewStringUTF(out.str().c_str());
}
