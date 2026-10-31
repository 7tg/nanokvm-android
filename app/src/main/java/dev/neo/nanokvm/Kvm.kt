package dev.neo.nanokvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NanoKVM HTTP client. Blocking: call off the main thread.
 * Addresses are discovered, not configured: the LAN one by scanning the phone's Wi-Fi subnet, the Tailscale
 * one by asking the NanoKVM after login. The last address that answered is tried first; when none answers,
 * the subnet is scanned again (DHCP may have moved it) and the call retried once. Logs in again on 401.
 */
class Kvm(private val context: Context) {
    companion object {
        private const val COOKIE = "nano-kvm-token"
    }

    private val prefs = context.getSharedPreferences("kvm", Context.MODE_PRIVATE)

    /** Last address that answered, e.g. http://192.168.1.20. */
    val base: String? get() = prefs.getString("base", null)
    val lan: String? get() = prefs.getString("lan", null)
    val tailscale: String? get() = prefs.getString("ts", null)
    val viaTailscale: Boolean get() = base != null && base != lan

    val token: String? get() = prefs.getString("token", null)

    val hasLogin: Boolean get() = prefs.contains("user") && prefs.contains("pass")

    fun saveLogin(address: String, user: String, pass: String) {
        val a = address.trim().removeSuffix("/").let { if (it.startsWith("http")) it else "http://$it" }
        prefs.edit().putString("lan", a).putString("base", a).putString("user", user).putString("pass", Vault.seal(pass))
            .remove("token").remove("ts").apply()
    }

    fun forget() = prefs.edit().clear().apply()

    /** Round trip of the last successful call, in ms. */
    var latency = 0L
        private set

    /**
     * PC state from the power LED. Most boards blink it while the PC sleeps, so the LED is read every 80-280 ms for
     * about 2 s; readings from earlier calls (last 20 s) are kept to catch blinks slower than that window.
     * Two or more LED flips that recently are sleep, one flip is a real power change.
     */
    fun power(): Power {
        val samples = mutableListOf<Pair<Long, Boolean>>()
        val start = System.currentTimeMillis()
        while (true) {
            val t = System.nanoTime()
            val v = call("GET", "/api/vm/gpio", null).getJSONObject("data").getBoolean("pwr")
            if (samples.isEmpty()) latency = (System.nanoTime() - t) / 1_000_000
            samples += System.currentTimeMillis() to v
            if (flips(samples) >= 2 || System.currentTimeMillis() - start >= 2100) break
            Thread.sleep(80L + kotlin.random.Random.nextLong(200)) // uneven spacing, so no blink rate can alias
        }
        val now = System.currentTimeMillis()
        val history = (prefs.getString("hist", "").orEmpty().split(",").mapNotNull { e ->
            val (ts, v) = e.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            (ts.toLongOrNull() ?: return@mapNotNull null) to (v == "1")
        }.filter { now - it.first < 20_000 } + samples).sortedBy { it.first }
        val lastFlip = history.zipWithNext().lastOrNull { (a, b) -> a.second != b.second }?.second?.first ?: 0L
        val p = when {
            flips(samples) >= 2 || (flips(history) >= 2 && now - lastFlip < 6000) -> Power.SLEEP
            samples.last().second -> Power.ON
            else -> Power.OFF
        }
        val e = prefs.edit().putLong("seen", now).putString("power", p.name)
            .putString("hist", history.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" })
        if (prefs.getString("power", null) != p.name) e.putLong("changed", now)
        e.apply()
        return p
    }

    private fun flips(s: List<Pair<Long, Boolean>>) = s.zipWithNext().count { (a, b) -> a.second != b.second }

    /** Last known state, for widgets drawn without a network call. */
    val lastPower: Power? get() = prefs.getString("power", null)?.let { runCatching { Power.valueOf(it) }.getOrNull() }
    val lastSeen: Long get() = prefs.getLong("seen", 0)
    val lastChanged: Long get() = prefs.getLong("changed", 0)

    /** Whether the NanoKVM receives a picture from the PC. */
    fun hdmiSignal(): Boolean = call("GET", "/api/vm/hdmi", null).getJSONObject("data").optBoolean("signal")

    /** NanoKVM application version. */
    fun version(): String = call("GET", "/api/vm/info", null).getJSONObject("data").optString("application")

    fun press(type: String, ms: Int) {
        call("POST", "/api/vm/gpio", JSONObject().put("type", type).put("duration", ms))
    }

    /** Makes sure a valid token and a reachable address exist, and learns the Tailscale address. */
    fun ensureSession() {
        call("GET", "/api/auth/account", null)
        runCatching {
            val ts = call("GET", "/api/extensions/tailscale/status", null).getJSONObject("data")
            if (ts.optString("state") == "running" && ts.optString("ip").isNotEmpty()) {
                prefs.edit().putString("ts", "http://" + ts.getString("ip").substringBefore(",").substringBefore("/")).apply()
            }
        }
    }

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        try {
            return callAny(method, path, body)
        } catch (e: IOException) {
            val found = Discovery.scan(context).firstOrNull() ?: throw e
            if (found == lan) throw e
            prefs.edit().putString("lan", found).apply()
            return callAny(method, path, body)
        }
    }

    private fun callAny(method: String, path: String, body: JSONObject?): JSONObject {
        val order = listOfNotNull(base, lan, tailscale).distinct()
        if (order.isEmpty()) throw IOException("NanoKVM not found")
        var last: IOException? = null
        for (b in order) {
            try {
                var r = request(b, method, path, body, token)
                if (r.first == 401) {
                    login(b)
                    r = request(b, method, path, body, token)
                }
                prefs.edit().putString("base", b).apply()
                if (r.first != 200) throw KvmError("HTTP ${r.first}")
                val json = JSONObject(r.second)
                if (json.optInt("code", 0) != 0) throw KvmError(json.optString("msg"))
                return json
            } catch (e: KvmError) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last!!
    }

    private fun login(b: String) {
        val user = prefs.getString("user", null) ?: throw KvmError("not logged in")
        val pass = Vault.open(prefs.getString("pass", null)!!)
        val enc = URLEncoder.encode(CryptoJs.encrypt(pass, "nanokvm-sipeed-2024"), "UTF-8")
        val conn = open(b, "POST", "/api/auth/login", null)
        conn.outputStream.use { it.write(JSONObject().put("username", user).put("password", enc).toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code != 200 || json?.optInt("code", -1) != 0) throw KvmError("login failed: ${json?.optString("msg") ?: code}")
        val cookie = conn.headerFields["Set-Cookie"].orEmpty().firstOrNull { it.startsWith("$COOKIE=") }
            ?: throw KvmError("login: no token")
        prefs.edit().putString("token", cookie.substringAfter("=").substringBefore(";")).apply()
    }

    private fun request(b: String, method: String, path: String, body: JSONObject?, tok: String?): Pair<Int, String> {
        val conn = open(b, method, path, tok)
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    private fun open(b: String, method: String, path: String, tok: String?): HttpURLConnection =
        (URL(b + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = if (b == lan) 1500 else 5000
            readTimeout = 15000
            if (tok != null) setRequestProperty("Cookie", "$COOKIE=$tok")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
}

enum class Power { ON, OFF, SLEEP }

class KvmError(msg: String) : Exception(msg)

/** Finds NanoKVMs on the phone's Wi-Fi /24 by probing every host for the NanoKVM web page. */
object Discovery {
    fun scan(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return emptyList()
        val own = cm.getLinkProperties(wifi)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
            ?: return emptyList()
        val prefix = own.hostAddress!!.substringBeforeLast(".")
        val pool = Executors.newFixedThreadPool(64)
        try {
            val jobs = (1..254).map { "http://$prefix.$it" }.map { url -> url to pool.submit<Boolean> { isNanoKvm(wifi, url) } }
            return jobs.filter { runCatching { it.second.get() }.getOrDefault(false) }.map { it.first }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isNanoKvm(net: Network, url: String): Boolean = try {
        (net.openConnection(URL("$url/")) as HttpURLConnection).run {
            connectTimeout = 600
            readTimeout = 1500
            responseCode == 200 && inputStream.bufferedReader().use { it.readText() }.contains("<title>NanoKVM</title>")
        }
    } catch (e: IOException) {
        false
    }
}

/** CryptoJS.AES.encrypt(text, passphrase) compatible output (OpenSSL "Salted__" + EVP_BytesToKey MD5). */
object CryptoJs {
    fun encrypt(text: String, passphrase: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val pass = passphrase.toByteArray()
        var derived = ByteArray(0)
        var block = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")
        while (derived.size < 48) {
            block = md5.digest(block + pass + salt)
            derived += block
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(derived.copyOfRange(32, 48)))
        val out = "Salted__".toByteArray() + salt + cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
}

/** Keeps the NanoKVM password encrypted with a hardware-backed Android Keystore key. */
object Vault {
    private const val ALIAS = "nanokvm"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}
