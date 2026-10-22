package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.PodsFocusCardFooter
import moe.chenxy.hyperpods.utils.PodsIslandData
import moe.chenxy.hyperpods.utils.PodsIslandLayout
import moe.chenxy.hyperpods.utils.PodsIslandMiniBar
import moe.chenxy.hyperpods.utils.PodsExpandedMeasure


object SystemUIPluginHook : YukiBaseHooker() {
    @Volatile var pluginObserved = false
        private set
    override fun onHook() {
        var pluginLoaderClassLoader: ClassLoader? = null

        fun loadPluginHooker(hooker: YukiBaseHooker) {
            hooker.appClassLoader = pluginLoaderClassLoader
            loadHooker(hooker)
        }

        fun initPluginHook() {
            loadPluginHooker(DeviceCardHook)
            val footerHolder = runCatching {
                Class.forName("miui.systemui.notification.focus.moduleV3.ModuleTextButtonViewHolder", false, pluginLoaderClassLoader)
            }.getOrNull()
            if (footerHolder != null) footerHolder.method { name = "bind"; paramCount = 2 }.hook {
                before {
                    runCatching {
                        val root = XposedHelpers.callMethod(this.instance, "getView") as? View ?: return@runCatching
                        PodsFocusCardFooter.restore(focusPart(root, "line"))
                    }.onFailure { Log.w("Art_Chen", "Unable to restore HyperPods focus footer") }
                }
                after {
                    runCatching {
                        if (!isHyperPods(this.args[1] as? StatusBarNotification)) return@runCatching
                        val root = XposedHelpers.callMethod(this.instance, "getView") as? View ?: return@runCatching
                        // Keep the stock template's margins, font metrics and height.
                        // Hide only the decoration; do not compact its layout.
                        PodsFocusCardFooter.apply(focusPart(root, "line"))
                    }.onFailure { Log.w("Art_Chen", "Unable to hide HyperPods focus divider") }
                }
            }
            val baseIslandView = runCatching {
                Class.forName("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView", false, pluginLoaderClassLoader)
            }.getOrNull()
            if (baseIslandView != null) baseIslandView.method { name = "updateMiniBar"; paramCount = 1 }.hook {
                after {
                    runCatching {
                        // Animation copies receive the real content view as their argument.
                        val owner = this.args[0] ?: this.instance
                        val data = XposedHelpers.callMethod(owner, "getCurrentIslandData") ?: return@runCatching
                        val extras = XposedHelpers.callMethod(data, "getExtras") as? Bundle ?: return@runCatching
                        @Suppress("DEPRECATION")
                        val sbn = extras.getParcelable<StatusBarNotification>("miui.sbn")
                        val bar = XposedHelpers.callMethod(this.instance, "getMiniBar") as? View
                        // Native updateMiniBar resets visibility on every bind, including other apps.
                        PodsIslandMiniBar.hide(bar, isHyperPods(sbn))
                    }.onFailure { Log.w("Art_Chen", "Unable to hide HyperPods mini-window hint") }
                }
            }
            val islandWindow = runCatching {
                Class.forName("miui.systemui.dynamicisland.window.DynamicIslandWindowView", false, pluginLoaderClassLoader)
            }.getOrNull()
            if (islandWindow != null) islandWindow.method { name = "measureExpandedViewHeight"; paramCount = 1 }.hook {
                before {
                    runCatching {
                        val data = XposedHelpers.callMethod(this.args[0], "getCurrentIslandData") ?: return@runCatching
                        val extras = XposedHelpers.callMethod(data, "getExtras") as? Bundle ?: return@runCatching
                        @Suppress("DEPRECATION")
                        val sbn = extras.getParcelable<StatusBarNotification>("miui.sbn")
                        if (!isHyperPods(sbn)) return@runCatching
                        val view = XposedHelpers.callMethod(data, "getView") as? View ?: return@runCatching
                        XposedHelpers.setAdditionalInstanceField(this.instance, EXPANDED_MEASUREMENT, view)
                        // Release only our content's previous fixed height. The native
                        // method still measures, limits and updates both island views.
                        PodsExpandedMeasure.prepare(view, isHyperPods = true)
                    }.onFailure { Log.w("Art_Chen", "Unable to prepare HyperPods expanded measurement") }
                }
                after {
                    val view = XposedHelpers.removeAdditionalInstanceField(this.instance, EXPANDED_MEASUREMENT) as? View
                    runCatching { PodsExpandedMeasure.finish(view) }
                        .onFailure { Log.w("Art_Chen", "Unable to restore HyperPods expanded measurement") }
                }
            }
            val islandView = runCatching {
                Class.forName("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", false, pluginLoaderClassLoader)
            }.getOrNull()
            if (islandView != null) {
                islandView.method { name = "calculateBigIslandWidth"; paramCount = 0 }.hook {
                    before {
                        runCatching { restoreIslandLayout(this.instance) }
                            .onFailure { Log.w("Art_Chen", "Unable to restore HyperPods island dimensions") }
                    }
                }
                islandView.method { name = "updateBigIslandLayout"; paramCount = 0 }.hook {
                    before {
                        runCatching {
                            val view = this.instance as? View ?: return@runCatching
                            restoreIslandLayout(view)
                            val data = XposedHelpers.callMethod(view, "getCurrentIslandData") ?: return@runCatching
                            val extras = XposedHelpers.callMethod(data, "getExtras") as? Bundle ?: return@runCatching
                            @Suppress("DEPRECATION")
                            val sbn = extras.getParcelable<StatusBarNotification>("miui.sbn")
                            if (!isHyperPods(sbn) || XposedHelpers.callMethod(view, "hasSmallIsland") != true ||
                                XposedHelpers.callMethod(view, "getHelper")?.javaClass?.simpleName != "DynamicIslandContentViewPhoneHelper") return@runCatching
                            val original = readIslandLayout(view)
                            val left = XposedHelpers.callMethod(view, "getBigIslandAreaLeft") as? View ?: return@runCatching
                            val right = XposedHelpers.callMethod(view, "getBigIslandAreaRight") as? View ?: return@runCatching
                            // Different digit counts must never be clipped to gain symmetry.
                            val adjusted = original.symmetric(view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                                maxOf(left.measuredWidth, right.measuredWidth)) ?: return@runCatching
                            XposedHelpers.setAdditionalInstanceField(view, ISLAND_LAYOUT, original to adjusted)
                            writeIslandLayout(view, adjusted)
                        }.onFailure { Log.w("Art_Chen", "Unable to adjust HyperPods coexistence dimensions") }
                    }
                }
            }
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
                        pluginObserved = true
                        initPluginHook()
                    }
                }
            }
        }
    }

    private const val ISLAND_LAYOUT = "hyperpods.island.layout"
    private const val EXPANDED_MEASUREMENT = "hyperpods.expanded.measurement"

    private fun isHyperPods(sbn: StatusBarNotification?): Boolean =
        sbn?.packageName == HyperPodsBroadcasts.XIAOMI_BLUETOOTH &&
            sbn.notification.extras.getCharSequence("miui.targetPkg")?.toString() == BuildConfig.APPLICATION_ID

    @SuppressLint("DiscouragedApi")
    private fun focusPart(root: View?, name: String): View? {
        root ?: return null
        val id = root.resources.getIdentifier(name, "id", "miui.systemui.plugin")
        return if (id == 0) null else root.findViewById(id)
    }

    private fun readIslandLayout(view: Any): PodsIslandLayout {
        fun get(name: String) = XposedHelpers.callMethod(view, "getBigIsland${name}HasSmallIsland") as Int
        return PodsIslandLayout(get("LeftWidth"), get("RightWidth"), get("ViewWidth"), get("X"))
    }

    private fun writeIslandLayout(view: Any, layout: PodsIslandLayout) {
        for ((name, value) in listOf("LeftWidth" to layout.left, "RightWidth" to layout.right,
            "ViewWidth" to layout.width, "X" to layout.x))
            XposedHelpers.callMethod(view, "setBigIsland${name}HasSmallIsland", value)
    }

    private fun restoreIslandLayout(view: Any) {
        val saved = XposedHelpers.removeAdditionalInstanceField(view, ISLAND_LAYOUT) as? Pair<*, *> ?: return
        // Do not overwrite a newer system measurement. Restore only our exact edit.
        if (readIslandLayout(view) == saved.second) writeIslandLayout(view, saved.first as PodsIslandLayout)
    }
}
