package dev.neo.nanokvm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlin.concurrent.thread

/** The NanoKVM web UI full screen (phone); the tablet layout embeds [kvmWebView] instead. */
class KvmActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = kvmWebView(this)
        setContentView(web)
        openKvm(this, web)
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun kvmWebView(context: Context) = WebView(context).apply {
    setBackgroundColor(0xFF000000.toInt())
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webViewClient = WebViewClient()
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.d("KvmWeb", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
            return true
        }
    }
}

/** Logs in if needed, hands the session cookie to the WebView and loads the NanoKVM UI. */
fun openKvm(activity: Activity, web: WebView) {
    val kvm = Kvm(activity)
    thread {
        val e = runCatching { kvm.ensureSession() }.exceptionOrNull()
        activity.runOnUiThread {
            val base = kvm.base
            if (e != null || base == null) {
                Toast.makeText(activity, "NanoKVM: ${e?.message ?: "not found"}", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            CookieManager.getInstance().setCookie(base, "nano-kvm-token=${kvm.token}; path=/")
            web.loadUrl("$base/")
        }
    }
}
