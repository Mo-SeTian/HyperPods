package moe.chenxy.hyperpods.hook

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.YukiHookAPI.configs
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import moe.chenxy.hyperpods.BuildConfig

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {
    override fun onHook()  = YukiHookAPI.encase {
        loadApp("com.android.systemui") {
            loadHooker(ModuleStatusHook("com.android.systemui"))
            loadHooker(SystemUIPluginHook)
        }
        loadApp("com.android.bluetooth") {
            loadHooker(ModuleStatusHook("com.android.bluetooth"))
            loadHooker(HeadsetStateDispatcher)
        }
        loadApp("com.xiaomi.bluetooth") {
            loadHooker(ModuleStatusHook("com.xiaomi.bluetooth"))
            loadHooker(MiBluetoothToastHook)
        }
    }

    override fun onInit() = configs {
        isDebug = BuildConfig.DEBUG
    }
}
