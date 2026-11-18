package com.durai.soundguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Ev(val t: Long, val k: Int)

data class Status(
    val st: Int, val cal: Int, val db: Double, val floor: Int, val al: Int,
    val ring: Boolean, val err: String, val warn: String,
    val loud: Int, val voice: Int, val spl: Int, val abs: Boolean,
    val adb: Int, val off: Int, val clients: Int, val up: Long, val hist: List<Ev>
)

object Guard {
    const val BASE = "http://192.168.4.1"

    val status = MutableStateFlow<Status?>(null)
    val connected = MutableStateFlow(false)
    val monitoring = MutableStateFlow(false)
    val callKind = MutableStateFlow(0)          // 0 none, 1 loud, 2 cry, 3 help, 4 connection lost

    @Volatile private var net: Network? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun kindName(k: Int) = when (k) {
        1 -> "Loud noise"; 2 -> "Baby crying"; 3 -> "Help / scream"; 4 -> "Connection lost"; else -> "Alert"
    }

    // Ask Android for the Wi-Fi network itself, so traffic to 192.168.4.1 never goes out over mobile data
    fun bind(ctx: Context) {
        if (cb != null) return
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { net = network }
            override fun onLost(network: Network) { if (net == network) net = null }
        }
        cb = c
        cm.requestNetwork(req, c)
    }

    fun unbind(ctx: Context) {
        val c = cb ?: return
        try {
            ctx.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(c)
        } catch (_: Exception) {}
        cb = null; net = null
    }

    fun http(path: String): String? {
        val n = net ?: return null
        return try {
            val c = n.openConnection(URL(BASE + path)) as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 2000; c.useCaches = false
            try {
                if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
            } finally { c.disconnect() }
        } catch (e: Exception) { null }
    }

    fun send(path: String) { Thread { http(path) }.start() }

    fun setCfg(loud: Int, voice: Int, abs: Boolean, adb: Int, off: Int) =
        send("/set?loud=$loud&voice=$voice&abs=${if (abs) 1 else 0}&adb=$adb&off=$off")

    fun parse(s: String): Status? = try {
        val o = JSONObject(s)
        val h = o.getJSONArray("h")
        Status(
            o.getInt("st"), o.getInt("cal"), o.getDouble("db"), o.getInt("fl"), o.getInt("al"),
            o.getBoolean("ring"), o.getString("err"), o.getString("warn"),
            o.getInt("loud"), o.getInt("voice"), o.getInt("spl"), o.getInt("abs") == 1,
            o.getInt("adb"), o.getInt("off"), o.getInt("cl"), o.getLong("up"),
            (0 until h.length()).map { val a = h.getJSONArray(it); Ev(a.getLong(0), a.getInt(1)) }
        )
    } catch (e: Exception) { null }
}
