package me.laumss.notipal

import android.view.View


internal data class HostEntrySnapshot(
    val lastShowType: Int,
    val pluginViewVisible: Boolean,
    val hasButtonEvent: Boolean,
    val hasConfigEvent: Boolean,
    val isCurrentOwner: Boolean,
) {
    fun opensConfig(selfInitiated: Boolean): Boolean =
        isCurrentOwner && pluginViewVisible && !selfInitiated &&
            (hasConfigEvent || (lastShowType == 1 && !hasButtonEvent))

    companion object {
        fun read(pluginApp: Any): HostEntrySnapshot {
            fun field(name: String): Any? {
                var type: Class<*>? = pluginApp.javaClass
                while (type != null) {
                    val found = type.declaredFields.firstOrNull { it.name == name }
                    if (found != null) return found.apply { isAccessible = true }.get(pluginApp)
                    type = type.superclass
                }
                return null
            }
            val managerType = Class.forName(
                "com.ratta.supernote.pluginhost.manager.PluginManager", false, pluginApp.javaClass.classLoader,
            )
            val manager = managerType.getMethod("getInstance").invoke(null)
            val owner = manager?.let { managerType.getMethod("getTopPluginApp").invoke(it) }
            val names = (field("pluginEventCacheList") as? List<*>)
                .orEmpty().map { it?.javaClass?.simpleName.orEmpty() }
            return HostEntrySnapshot(
                lastShowType = (field("lastShowType") as? Number)?.toInt() ?: -1,
                pluginViewVisible = (field("pluginView") as? View)?.visibility == View.VISIBLE,
                hasButtonEvent = names.any { it.contains("PluginButtonEvent") },
                hasConfigEvent = names.any { it.contains("ConfigButtonPressEvent") },
                isCurrentOwner = owner === pluginApp &&
                    pluginApp.javaClass.getMethod("isShowPluginView").invoke(pluginApp) == true,
            )
        }
    }
}
