#include "hook_base.h"
#include "bounded_scan.h"
#include <android/log.h>
#include <atomic>
#include <jni.h>
#include <link.h>
#include <string_view>

static HookFunType hook_func = nullptr;
static std::atomic_bool isHookSuccess{false};
static uint8_t (*l2c_fcr_chk_chan_modes_backup)(void* p_ccb) = nullptr;

static uint8_t l2c_fcr_chk_chan_modes_hook(void* p_ccb) {
    return p_ccb == nullptr ? 0 : 1;
}

static void on_library_loaded(const char* name, void*) {
    if (name == nullptr || !std::string_view(name).ends_with("libbluetooth_jni.so") ||
        hook_func == nullptr || isHookSuccess.load()) return;
#if defined(__aarch64__)
    std::vector<hyperpods::MemoryRange> ranges;
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* data) {
        if (info->dlpi_name == nullptr || !std::string_view(info->dlpi_name).ends_with("libbluetooth_jni.so")) return 0;
        auto& result = *static_cast<std::vector<hyperpods::MemoryRange>*>(data);
        for (int i = 0; i < info->dlpi_phnum; ++i) {
            const auto& header = info->dlpi_phdr[i];
            if (header.p_type != PT_LOAD || !(header.p_flags & PF_R) || header.p_memsz == 0) continue;
            const uintptr_t begin = info->dlpi_addr + header.p_vaddr;
            result.push_back({begin, begin + header.p_memsz, (header.p_flags & PF_X) != 0});
        }
        return 1;
    }, &ranges);
    const auto functionName = hyperpods::findString(ranges, "l2c_fcr_chk_chan_modes");
    const bool isMtk = hyperpods::findString(ranges, "vendor/mediatek/proprietary/packages/modules") != 0;
    const auto assertion = hyperpods::findString(ranges, isMtk
        ? "L2CAP - Peer does not support our desired channel types" : "assert failed: p_ccb != NULL");
    const auto target = hyperpods::findFunction(ranges, functionName, assertion, isMtk);
    if (target == 0) {
        __android_log_print(ANDROID_LOG_WARN, "Art_Chen-Hook", "No unique, mapped L2CAP hook target; hook skipped");
        return;
    }
    const int result = hook_func(reinterpret_cast<void*>(target),
        reinterpret_cast<void*>(l2c_fcr_chk_chan_modes_hook),
        reinterpret_cast<void**>(&l2c_fcr_chk_chan_modes_backup));
    isHookSuccess.store(result == 0);
    __android_log_print(ANDROID_LOG_INFO, "Art_Chen-Hook", "L2CAP hook result: %d", result);
#else
    __android_log_print(ANDROID_LOG_WARN, "Art_Chen-Hook", "L2CAP instruction hook requires ARM64");
#endif
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (entries != nullptr) hook_func = entries->hook_func;
    return on_library_loaded;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_chenxy_hyperpods_hook_HeadsetStateDispatcher_nativeGetHookResult(JNIEnv*, jobject) {
    return isHookSuccess.load();
}
