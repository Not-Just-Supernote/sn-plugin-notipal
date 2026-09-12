package me.laumss.notipal.relay.core

import android.content.Context
import android.content.SharedPreferences


object AppPrefs {
    private const val FILE = "air_relay_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    
    fun phoneIp(ctx: Context): String =
        prefs(ctx).getString("phone_ip", "") ?: ""

    fun setPhoneIp(ctx: Context, ip: String) =
        prefs(ctx).edit().putString("phone_ip", ip).apply()

    
    fun phoneFingerprint(ctx: Context): String =
        prefs(ctx).getString("phone_fingerprint", "") ?: ""

    fun setPhoneFingerprint(ctx: Context, fingerprint: String) =
        prefs(ctx).edit().putString("phone_fingerprint", fingerprint).apply()

    
    fun phonePort(ctx: Context): Int =
        prefs(ctx).getInt("phone_port", 8080)

    fun setPhonePort(ctx: Context, port: Int) =
        prefs(ctx).edit().putInt("phone_port", port).apply()

    
    fun clearPhonePairing(ctx: Context) =
        prefs(ctx).edit()
            .remove("phone_ip")
            .remove("phone_fingerprint")
            .remove("phone_port")
            .remove("llm_token")
            .apply()

    
    fun llmToken(ctx: Context): String =
        prefs(ctx).getString("llm_token", "") ?: ""

    
    fun autoOpenDetail(ctx: Context): Boolean =
        prefs(ctx).getBoolean("auto_open_detail", true)

    fun setAutoOpenDetail(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean("auto_open_detail", value).apply()
}
