package dev.neo.nanokvm

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * Debug-only: runs Tapo discovery in the foreground (background apps get EPERM on sockets) and logs it.
 * adb shell am start -n dev.neo.nanokvm.debug/dev.neo.nanokvm.TapoProbe [--es targets a,b] ; logcat -s TapoProbe
 */
class TapoProbe : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targets = intent.getStringExtra("targets")?.split(",").orEmpty()
        thread {
            val t = System.currentTimeMillis()
            val tapo = Tapo(this)
            val email = intent.getStringExtra("email")
            val r = if (email != null) {
                tapo.saveLogin(email, intent.getStringExtra("pass")!!)
                runCatching { tapo.setup() }.onFailure { Log.w("TapoProbe", "setup failed", it) }
            } else runCatching { tapo.discover(targets) }
            Log.i("TapoProbe", "discover $targets in ${System.currentTimeMillis() - t} ms: ${r.getOrNull() ?: r.exceptionOrNull()}")
            finish()
        }
    }
}
