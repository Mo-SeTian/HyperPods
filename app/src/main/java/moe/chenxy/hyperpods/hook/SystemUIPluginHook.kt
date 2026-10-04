package moe.chenxy.hyperpods.hook

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.hyperpods.utils.PodsIslandData


object SystemUIPluginHook : YukiBaseHooker() {
    override fun onHook() {
        var pluginLoaderClassLoader: ClassLoader? = null

        fun loadPluginHooker(hooker: YukiBaseHooker) {
            hooker.appClassLoader = pluginLoaderClassLoader
            loadHooker(hooker)
        }

        fun initPluginHook() {
            loadPluginHooker(DeviceCardHook)
            // OS4 reserves an extra half-small-island width on the left when a
            // music bubble coexists. Native type-1 START alignment leaves that
            // space next to the camera. Anchor only our two modules inward.
            for ((holder, side) in listOf("IslandImageTextViewHolder" to "Left", "IslandImageTextView2Holder" to "Right")) {
                val cls = runCatching { Class.forName("miui.systemui.dynamicisland.module.$holder", false, pluginLoaderClassLoader) }.getOrNull()
                    ?: continue // These templates do not exist on every OS3 build.
                cls.method { name = "bind"; paramCount = 2 }.hook {
                    after {
                        runCatching {
                            val view = XposedHelpers.callMethod(this.instance, "getView") as? View ?: return@runCatching
                            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@runCatching
                            val big = XposedHelpers.callMethod(this.args[0], "getBigIslandArea")
                            val area = big?.let { XposedHelpers.callMethod(it, "getImageTextInfo$side") }
                            val pic = area?.let { XposedHelpers.callMethod(it, "getPicInfo") }
                            val icon = pic?.let { XposedHelpers.callMethod(it, "getPic") as? String }
                            val original = XposedHelpers.getAdditionalInstanceField(view, "hyperpods.island.gravity") as? Int
                            val desired = PodsIslandData.contentGravity(icon, params.gravity)
                            val gravity = if (desired != null) {
                                if (original == null) XposedHelpers.setAdditionalInstanceField(view, "hyperpods.island.gravity", params.gravity)
                                desired
                            } else {
                                // A recycled module must not carry our alignment into another app.
                                XposedHelpers.removeAdditionalInstanceField(view, "hyperpods.island.gravity")
                                original ?: return@runCatching
                            }
                            if (params.gravity != gravity) {
                                params.gravity = gravity
                                view.layoutParams = params
                            }
                        }.onFailure { Log.w("Art_Chen", "Unable to align HyperPods island content", it) }
                    }
                }
            }
        }

        // Load plugin hooker
        // get Classloader for plugin on Android U
        "com.android.systemui.shared.plugins.PluginInstance".toClass().method {
            name = "loadPlugin"
        }.hook {
            after {
                val pkgName = runCatching {
                    XposedHelpers.callMethod(this.instance, "getPackageName") as String
                }.getOrElse {
                    // HyperOS 3 and older SystemUI plugin framework.
                    XposedHelpers.callMethod(this.instance, "getPackage") as String
                }
                if (pkgName == "miui.systemui.plugin") {
                    val clsLoader = runCatching {
                        // HyperOS 4 stores the loaded plugin context in PluginData.
                        val pluginData = XposedHelpers.getObjectField(this.instance, "pluginData")
                        val pluginContext = XposedHelpers.getObjectField(pluginData, "context")
                        XposedHelpers.callMethod(pluginContext, "getClassLoader") as ClassLoader
                    }.getOrElse {
                        // HyperOS 3 and older SystemUI plugin framework.
                        val factory = XposedHelpers.getObjectField(this.instance, "mPluginFactory")
                        XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(factory, "mClassLoaderFactory"),
                            "get"
                        ) as ClassLoader
                    }
                    if (pluginLoaderClassLoader != clsLoader) {
                        Log.i(
                            "Art_Chen",
                            "[loadPlugin] initPluginHook"
                        )
                        pluginLoaderClassLoader = clsLoader
                        initPluginHook()
                    }
                }
            }
        }
    }
}
