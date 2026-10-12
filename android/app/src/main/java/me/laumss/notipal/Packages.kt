package me.laumss.notipal
import me.laumss.notipal.panels.*
import me.laumss.notipal.overlays.*
import me.laumss.notipal.bubbles.*

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class InklingPackages : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        val modules = mutableListOf<NativeModule>()
        fun tryAdd(name: String, factory: () -> NativeModule) {
            try {
                modules.add(factory())
                if (BuildConfig.ENABLE_DEBUG) {
                    android.util.Log.i("InklingPackages", "registered $name")
                }
            } catch (t: Throwable) {
                
                
                
                if (BuildConfig.ENABLE_DEBUG) {
                    android.util.Log.e(
                        "InklingPackages",
                        "register $name failed: ${t.javaClass.simpleName}: ${t.message}",
                        t
                    )
                }
            }
        }
        tryAdd("FloatingToolbar") { FloatingToolbarModule(reactContext) }
        tryAdd("LocalSendModule") { LocalSendModule(reactContext) }
        tryAdd("AIRelayModule") { AIRelayModule(reactContext) }
        tryAdd("FloatingBubble") { FloatingBubbleModule(reactContext) }
        tryAdd("AiBubble") { AiBubbleModule(reactContext) }
        tryAdd("PaletteBubble") { PaletteBubbleModule(reactContext) }
        tryAdd("TextLayoutEngine") { TextLayoutEngine(reactContext) }
        tryAdd("TextboxMetrics") { TextboxMetricsModule(reactContext) }
        tryAdd("InklingImageUtil") { ImageUtilModule(reactContext) }
        if (BuildConfig.ENABLE_DEBUG) {
            android.util.Log.i("InklingPackages", "registered module count=${modules.size}")
        }
        return modules
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }
}
