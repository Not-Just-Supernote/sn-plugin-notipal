package me.laumss.notipal

import android.graphics.Rect
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log


object PenDisableClient {

    private const val TAG = "PenDisableClient"

    private val SERVICE_NAME_CANDIDATES = arrayOf("service_myservice", "service.myservice")
    private const val INTERFACE_TOKEN = "android.demo.IMyService"

    const val CODE_DISABLE_AREA_INFO = 1

    enum class Result {
        OK,
        
        NO_SERVICE,
        
        FAILED,
    }

    @Volatile private var cachedBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        cachedBinder = null
        Log.w(TAG, "drawPath service binder died; will look it up again on next send")
    }

    private fun serviceBinder(): IBinder? {
        cachedBinder?.let { binder ->
            if (binder.isBinderAlive) return binder
            cachedBinder = null
        }
        val binder = lookupServiceBinder() ?: return null
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "linkToDeath failed: ${e.message}")
        }
        cachedBinder = binder
        return binder
    }

    private fun dropBinder(binder: IBinder) {
        if (cachedBinder !== binder) return
        cachedBinder = null
        try { binder.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
    }

    private fun lookupServiceBinder(): IBinder? {
        for (name in SERVICE_NAME_CANDIDATES) {
            val binder = try {
                val sm = Class.forName("android.os.ServiceManager")
                val get = sm.getMethod("getService", String::class.java)
                get.invoke(null, name) as? IBinder
            } catch (e: Throwable) {
                Log.e(TAG, "getService reflection failed: ${e.javaClass.name}: ${e.message}")
                return null
            }
            if (binder != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[diag] got binder via \"$name\"")
                return binder
            }
        }
        Log.e(TAG, "drawPath service not found: ${SERVICE_NAME_CANDIDATES.joinToString()}")
        if (BuildConfig.ENABLE_DEBUG) logServiceList()
        return null
    }

    private fun logServiceList() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val list = sm.getMethod("listServices")
            @Suppress("UNCHECKED_CAST")
            val services = list.invoke(null) as? Array<String>
            if (services == null) {
                Log.e(TAG, "[diag] listServices returned null")
                return
            }
            Log.i(TAG, "[diag] listServices total=${services.size}")
            val hits = services.filter {
                it.contains("myservice", ignoreCase = true) || it.contains("demo", ignoreCase = true)
            }
            if (hits.isEmpty()) {
                Log.i(TAG, "[diag] no service name contains 'myservice'/'demo'")
            } else {
                for (name in hits) {
                    Log.i(TAG, "[diag] matched service: $name")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[diag] listServices failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    fun sendDisableAreas(
        appName: String,
        rects: List<Rect>,
    ): Result {
        if (rects.isEmpty()) return Result.FAILED
        val code = CODE_DISABLE_AREA_INFO
        val binder = serviceBinder() ?: return Result.NO_SERVICE
        
        
        
        
        val token = INTERFACE_TOKEN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token)
            data.writeString(appName)
            data.writeInt(rects.size)
            for (r in rects) {
                data.writeInt(r.left)
                data.writeInt(r.top)
                data.writeInt(r.width())
                data.writeInt(r.height())
                data.writeInt(0)
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[diag] payload token=\"$token\" app=\"$appName\" count=${rects.size} " +
                    rects.joinToString(" ") { "[l=${it.left},t=${it.top},w=${it.width()},h=${it.height()},f=0]" })
                Log.i(TAG, "[diag] parcel dataSize=${data.dataSize()} hex=${data.marshall().toHexPreview(96)}")
            }
            val ok = binder.transact(code, data, reply, 0)
            if (BuildConfig.ENABLE_DEBUG) {
                val replyStr = reply.readString()
                val replySize = reply.dataSize()
                val replyPos = reply.dataPosition()
                var extra = ""
                if (replyPos < replySize) {
                    extra = try {
                        " extraInt=${reply.readInt()} (remain=${replySize - reply.dataPosition()}B)"
                    } catch (_: Exception) {
                        " (unreadable remain=${replySize - replyPos}B)"
                    }
                }
                Log.i(TAG, "send code=$code app=\"$appName\" transact=$ok " +
                    "reply=$replyStr replySize=$replySize$extra rects=$rects")
            }
            if (ok) Result.OK else {
                Log.e(TAG, "send rejected code=$code app=\"$appName\" rects=${rects.size}")
                Result.FAILED
            }
        } catch (e: DeadObjectException) {
            dropBinder(binder)
            Log.e(TAG, "send failed, binder dead app=\"$appName\"")
            Result.NO_SERVICE
        } catch (e: Exception) {
            Log.e(TAG, "send failed code=$code app=\"$appName\": ${e.message}", e)
            Result.FAILED
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ByteArray.toHexPreview(max: Int): String {
        val n = minOf(size, max)
        val sb = StringBuilder(n * 2 + 8)
        for (i in 0 until n) sb.append(String.format("%02x", this[i]))
        if (size > max) sb.append("…(${size}B)")
        return sb.toString()
    }

    fun clearDisableAreas(appName: String): Result =
        sendDisableAreas(appName, listOf(PenAreaGeometry.clearSentinel()))
}
