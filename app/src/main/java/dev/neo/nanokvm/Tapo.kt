package dev.neo.nanokvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A Tapo plug as found on the LAN. [mac] identifies it; [ip] can change with DHCP. */
data class Plug(val mac: String, val ip: String, val port: Int, val name: String, val model: String) {
    fun json(): JSONObject = JSONObject().put("mac", mac).put("ip", ip).put("port", port).put("name", name).put("model", model)

    companion object {
        fun of(o: JSONObject) = Plug(o.getString("mac"), o.getString("ip"), o.optInt("port", 80), o.optString("name"), o.optString("model"))
    }
}

/**
 * TP-Link Tapo plugs over the local KLAP protocol (needs "Third-Party Compatibility" on in the Tapo app).
 * Login is the TP-Link account; plugs are found by UDP discovery on port 20002 and re-found by MAC when
 * their address changes. Blocking: call off the main thread. LAN only.
 */
class Tapo(private val context: Context) {
    private val prefs = context.getSharedPreferences("tapo", Context.MODE_PRIVATE)

    val hasLogin: Boolean get() = prefs.contains("email") && prefs.contains("pass")

    val plugs: List<Plug>
        get() = JSONArray(prefs.getString("plugs", "[]")).let { a -> (0 until a.length()).map { Plug.of(a.getJSONObject(it)) } }

    private fun savePlugs(list: List<Plug>) =
        prefs.edit().putString("plugs", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    fun plug(mac: String) = plugs.firstOrNull { it.mac == mac }

    /** Last known on/off per plug, for drawing without a network call. */
    fun lastOn(mac: String): Boolean? = if (prefs.contains("on_$mac")) prefs.getBoolean("on_$mac", false) else null

    fun saveLogin(email: String, pass: String) {
        prefs.edit().putString("email", email).putString("pass", Vault.seal(pass)).apply()
    }

    fun forget() = prefs.edit().clear().apply()

    /** Discovers plugs, reads their names with the stored login, and saves them. Returns what was found. */
    fun setup(): List<Plug> {
        val plain = discover()
        if (plain.isEmpty()) throw IOException("No Tapo plugs found on this Wi-Fi")
        // One plug that doesn't answer shouldn't hide the others; a wrong login fails on every plug.
        val results = plain.map { p -> p to runCatching { info(p) } }
        if (results.all { it.second.isFailure }) throw results.first().second.exceptionOrNull()!!
        val found = results.map { (p, r) -> r.getOrNull()?.let { p.copy(name = it.nickname) } ?: p }
        savePlugs(found)
        return found
    }

    fun isOn(mac: String): Boolean = withPlug(mac) { info(it).on }

    fun set(mac: String, on: Boolean): Boolean = withPlug(mac) {
        request(it, JSONObject().put("method", "set_device_info").put("params", JSONObject().put("device_on", on)))
        prefs.edit().putBoolean("on_${it.mac}", on).apply()
        on
    }

    private class Info(val on: Boolean, val nickname: String)

    private fun info(p: Plug): Info {
        val r = request(p, JSONObject().put("method", "get_device_info")).getJSONObject("result")
        val nick = runCatching { String(Base64.decode(r.getString("nickname"), Base64.DEFAULT)) }.getOrDefault(p.name)
        val on = r.getBoolean("device_on")
        prefs.edit().putBoolean("on_${p.mac}", on).apply()
        return Info(on, nick.ifEmpty { p.model })
    }

    /** Runs [block] on the plug, re-finding it by MAC once if its saved address doesn't answer. */
    private fun <T> withPlug(mac: String, block: (Plug) -> T): T {
        val p = plug(mac) ?: throw IOException("Unknown plug")
        return try {
            block(p)
        } catch (e: IOException) {
            val moved = discover().firstOrNull { it.mac == mac && it.ip != p.ip } ?: throw e
            val updated = moved.copy(name = p.name)
            savePlugs(plugs.map { if (it.mac == mac) updated else it })
            block(updated)
        }
    }

    // --- KLAP ---

    private fun request(p: Plug, body: JSONObject): JSONObject {
        val email = prefs.getString("email", null) ?: throw KvmError("No Tapo login")
        val pass = Vault.open(prefs.getString("pass", null)!!)
        val auth = sha256(sha1(email.toByteArray()) + sha1(pass.toByteArray()))
        val base = "http://${p.ip}:${p.port}/app"

        val local = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val h1 = post("$base/handshake1", local, null)
        if (h1.code != 200 || h1.body.size < 48) throw IOException("Tapo handshake failed (${h1.code})")
        val remote = h1.body.copyOfRange(0, 16)
        if (!sha256(local + remote + auth).contentEquals(h1.body.copyOfRange(16, 48))) throw KvmError("Wrong TP-Link email or password")
        val cookie = h1.cookie ?: throw IOException("Tapo handshake: no session")
        val h2 = post("$base/handshake2", sha256(remote + local + auth), cookie)
        if (h2.code != 200) throw KvmError("Tapo login refused (${h2.code})")

        val seed = local + remote + auth
        val key = sha256("lsk".toByteArray() + seed).copyOf(16)
        val ivHash = sha256("iv".toByteArray() + seed)
        val sig = sha256("ldk".toByteArray() + seed).copyOf(28)
        val seq = ByteBuffer.wrap(ivHash, 28, 4).int + 1
        val iv = ivHash.copyOf(12) + ByteBuffer.allocate(4).putInt(seq).array()

        val enc = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(body.toString().toByteArray())
        val signed = sha256(sig + ByteBuffer.allocate(4).putInt(seq).array() + enc) + enc
        val r = post("$base/request?seq=$seq", signed, cookie)
        if (r.code != 200 || r.body.size <= 32) throw IOException("Tapo request failed (${r.code})")
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(r.body, 32, r.body.size - 32)
        val json = JSONObject(String(plain))
        if (json.optInt("error_code", 0) != 0) throw KvmError("Tapo error ${json.optInt("error_code")}")
        return json
    }

    private class Reply(val code: Int, val body: ByteArray, val cookie: String?)

    private fun post(url: String, data: ByteArray, cookie: String?): Reply {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 2500; readTimeout = 5000
            setRequestProperty("Content-Type", "application/octet-stream")
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        c.outputStream.use { it.write(data) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val session = c.headerFields["Set-Cookie"].orEmpty().firstOrNull { it.startsWith("TP_SESSIONID=") }?.substringBefore(";")
        return Reply(code, body, session)
    }

    // --- Discovery ---

    /**
     * Broadcasts the Tapo discovery query on Wi-Fi (limited and subnet broadcast, plus known plugs directly) every
     * 500 ms and collects KLAP plugs for 4 s. Some plugs answer UDP only now and then, hence the repeats.
     */
    fun discover(extraTargets: List<String> = emptyList()): List<Plug> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val query = discoveryQuery()
        val found = linkedMapOf<String, Plug>()
        DatagramSocket().use { s ->
            wifi?.bindSocket(s)
            s.broadcast = true
            s.soTimeout = 300
            val subnet = wifi?.let { cm.getLinkProperties(it) }?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.let { la ->
                    val ip = ByteBuffer.wrap(la.address.address).int
                    val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                    InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array()).hostAddress
                }
            val targets = (if (extraTargets.isEmpty()) listOfNotNull("255.255.255.255", subnet) else extraTargets) + plugs.map { it.ip }
            val end = System.currentTimeMillis() + 4000
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets.distinct()) runCatching { s.send(DatagramPacket(query, query.size, InetAddress.getByName(t), 20002)) }
                        .onFailure { android.util.Log.w("Tapo", "discovery send to $t failed", it) }
                    nextSend = System.currentTimeMillis() + 500
                }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (pkt.length <= 16) continue
                val r = runCatching { JSONObject(String(buf, 16, pkt.length - 16)).getJSONObject("result") }.getOrNull() ?: continue
                if (!r.optString("device_type").contains("PLUG")) continue
                val scheme = r.optJSONObject("mgt_encrypt_schm") ?: continue
                if (scheme.optString("encrypt_type") != "KLAP") continue
                val mac = r.optString("mac").uppercase().replace("-", ":")
                found[mac] = Plug(mac, r.optString("ip", pkt.address.hostAddress!!), scheme.optInt("http_port", 80), r.optString("device_model"), r.optString("device_model"))
            }
        }
        return found.values.toList()
    }

    private fun discoveryQuery(): ByteArray {
        val pub = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(pub.encoded, Base64.DEFAULT) + "-----END PUBLIC KEY-----\n"
        val payload = JSONObject().put("params", JSONObject().put("rsa_key", pem)).toString().toByteArray()
        val b = ByteBuffer.allocate(16 + payload.size)
            .put(2).put(0).putShort(1).putShort(payload.size.toShort()).put(17).put(0)
            .putInt(SecureRandom().nextInt()).putInt(0x5A6B7C8D).put(payload)
        val q = b.array()
        val crc = CRC32().apply { update(q) }.value.toInt()
        ByteBuffer.wrap(q).putInt(12, crc)
        return q
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)
}
