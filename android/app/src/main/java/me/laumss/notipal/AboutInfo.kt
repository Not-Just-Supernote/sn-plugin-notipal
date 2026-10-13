package me.laumss.notipal

import android.os.Build
import java.net.URLEncoder


object AboutInfo {

    
    const val DISCORD_URL = "https://discord.gg/Z8NwyGWrbj"

    
    const val FEEDBACK_EMAIL = "yalums@supernote.com"

    
    @Volatile var versionName: String = ""
    @Volatile var versionCode: String = ""

    fun versionLabel(): String = when {
        versionName.isEmpty() -> ""
        versionCode.isEmpty() -> versionName
        else -> "$versionName ($versionCode)"
    }

    fun deviceLabel(): String = "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}"

    
    fun feedbackMailto(): String {
        val subject = "Notipal ${NativeLocale.t("about_feedback_subject")} ${versionLabel()}".trim()
        val body = "${NativeLocale.t("about_feedback_body")}\n\n\n--\nNotipal ${versionLabel()}\n${deviceLabel()}"
        return "mailto:$FEEDBACK_EMAIL?subject=${encode(subject)}&body=${encode(body)}"
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
