package com.pstranslator.tv

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

data class PhoneServerInfo(
    val ip: String,
    val token: String,
    val name: String
)

object TvPhoneDiscovery {
    const val PORT = 8791

    fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun tryOfferHost(ip: String, timeoutMs: Int = 700): PhoneServerInfo? = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return@withContext null
        try {
            val url = URL("http://$cleanIp:$PORT/vit-tv/offer")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val tvName = "Android TV (${Build.MODEL ?: "TV"})"
            val payload = JSONObject().put("name", tvName).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(respText)
                val token = json.optString("token", "")
                val pcName = json.optString("pc_name", "PS Translator")
                if (token.isNotEmpty()) {
                    return@withContext PhoneServerInfo(cleanIp, token, pcName)
                }
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun discoverOnLocalSubnet(lastSavedIp: String?): PhoneServerInfo? = coroutineScope {
        // 1. Try last saved IP immediately first
        if (!lastSavedIp.isNullOrBlank()) {
            val hit = tryOfferHost(lastSavedIp, timeoutMs = 1000)
            if (hit != null) return@coroutineScope hit
        }

        // 2. Parallel scan of the TV's local /24 subnet
        val myIp = getLocalIpv4Address() ?: return@coroutineScope null
        val prefix = myIp.substringBeforeLast('.')
        val myLastOctet = myIp.substringAfterLast('.').toIntOrNull() ?: -1

        val sem = Semaphore(48)
        val found = java.util.concurrent.atomic.AtomicReference<PhoneServerInfo?>(null)
        val jobs = (1..254).filter { it != myLastOctet }.map { last ->
            async(Dispatchers.IO) {
                if (found.get() != null) return@async null
                sem.withPermit {
                    if (found.get() != null) return@withPermit null
                    val res = tryOfferHost("$prefix.$last", timeoutMs = 600)
                    if (res != null) found.compareAndSet(null, res)
                    res
                }
            }
        }
        jobs.awaitAll()
        found.get()
    }

    suspend fun sendForget(ip: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$ip:$PORT/tv/forget")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1000
                readTimeout = 1000
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
