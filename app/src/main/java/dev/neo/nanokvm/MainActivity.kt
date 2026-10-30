package dev.neo.nanokvm

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private const val BG = 0xFF0B0D10.toInt()
private const val CARD = 0xFF151A20.toInt()
private const val GREEN = 0xFF3DDC84.toInt()
private const val GREEN_DARK = 0xFF16301F.toInt()
private const val GREY = 0xFF8A939C.toInt()
private const val OFF_FILL = 0xFF2A2F36.toInt()
private const val RED = 0xFFFF8A80.toInt()
private const val TEXT = 0xFFE8EAED.toInt()

class MainActivity : Activity() {
    companion object {
        const val EXTRA_RESET = "reset"
    }

    private lateinit var kvm: Kvm
    private val ui = Handler(Looper.getMainLooper())
    private var controls: Controls? = null
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 5000)
        }
    }

    private class Controls(
        val chip: TextView, val ring: View, val glow: View, val button: View, val icon: ImageView,
        val state: TextView, val hint: TextView, val info: LinearLayout, val web: WebView?,
    ) {
        var lit: Boolean? = null
        var breathing: Animator? = null
        var busy: Animator? = null
    }

    private val tablet get() = resources.configuration.smallestScreenWidthDp >= 600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        kvm = Kvm(this)
        if (kvm.hasLogin) showControls() else showLogin()
        if (intent.getBooleanExtra(EXTRA_RESET, false) && kvm.hasLogin) confirmReset()
    }

    override fun onResume() {
        super.onResume()
        if (controls != null) ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        controls?.let { it.breathing?.cancel(); it.breathing = null }
        super.onPause()
    }

    override fun onDestroy() {
        controls?.web?.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun oval(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), stroke)
    }

    private fun text(s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun insetPadded(v: View, h: Int) = v.apply {
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(dp(h), bars.top + dp(12), dp(h), bars.bottom + dp(16))
            insets
        }
    }

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun showLogin() {
        controls = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        insetPadded(col, 24)
        col.addView(text("NanoKVM", 26f, bold = true), lp(top = 24))
        col.addView(text("Log in with your NanoKVM account", 14f, GREY), lp(top = 4))
        fun field(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; inputType = type; setTextColor(TEXT); setHintTextColor(GREY)
            background = round(CARD, 14); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val address = field("Searching Wi-Fi…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("Username", InputType.TYPE_CLASS_TEXT).apply { setText("admin") }
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(address, lp(top = 28)); col.addView(user, lp(top = 12)); col.addView(pass, lp(top = 12))
        val err = text("", 14f, RED)
        col.addView(err, lp(top = 12))
        val login = text("Log in", 17f, BG, bold = true).apply {
            gravity = Gravity.CENTER; background = round(GREEN, 16); setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(login, lp(top = 12))
        pressable(login)
        thread {
            val found = Discovery.scan(this)
            runOnUiThread {
                if (found.isNotEmpty() && address.text.isEmpty()) address.setText(found.first())
                address.hint = if (found.isEmpty()) "Not found on Wi-Fi: address or Tailscale name" else "Address"
            }
        }
        login.setOnClickListener {
            kvm.saveLogin(address.text.toString(), user.text.toString().trim(), pass.text.toString())
            err.setTextColor(GREY); err.text = "Logging in…"
            thread {
                val e = runCatching { kvm.ensureSession() }.exceptionOrNull()
                runOnUiThread {
                    if (e == null) { showControls(); ui.post(poll) } else { kvm.forget(); err.setTextColor(RED); err.text = e.message ?: e.toString(); shake(login) }
                }
            }
        }
        setContentView(col)
        enter(col)
    }

    private fun showControls() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("PC", 22f, bold = true))
        titles.addView(text("NanoKVM", 14f, GREY))
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chip = text("…", 13f, 0xFFB8C0C8.toInt()).apply {
            background = round(0xFF1A1F26.toInt(), 99); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        top.addView(chip)
        col.addView(top, lp(top = 8))

        val size = if (tablet) 180 else 220
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), 0x336B747D) }
        }
        val glow = View(this).apply {
            alpha = 0f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(size / 2).toFloat()
                // Fully lit up to the button's edge, then fading out to the ring.
                setColors(intArrayOf(0x993DDC84.toInt(), 0x993DDC84.toInt(), 0x003DDC84), floatArrayOf(0f, 0.6f, 1f))
            }
        }
        ring.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val button = FrameLayout(this)
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_power_on) }
        button.addView(icon, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        ring.addView(button, FrameLayout.LayoutParams(dp(size * 15 / 22), dp(size * 15 / 22), Gravity.CENTER))
        col.addView(ring, LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(if (tablet) 28 else 48) })
        button.setOnClickListener { act("Power pressed") { kvm.press("power", 800) } }
        pressable(button)

        val state = text("…", 30f, GREEN, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f }
        col.addView(state, lp(top = 24))
        val hint = text("Tap to press power", 13f, GREY).apply { gravity = Gravity.CENTER }
        col.addView(hint, lp(top = 4))

        val row = LinearLayout(this)
        fun secondary(icon: Int, label: String, sub: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = round(CARD, 18); setPadding(0, dp(14), 0, dp(14))
            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { bottomMargin = dp(6) })
            addView(text(label, 15f, color).apply { gravity = Gravity.CENTER }); addView(text(sub, 12f, GREY).apply { gravity = Gravity.CENTER })
            setOnClickListener { onClick() }
            pressable(this)
        }
        row.addView(secondary(R.drawable.ic_reset, "Reset", "asks first", TEXT) { confirmReset() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(secondary(R.drawable.ic_force_off, "Force off", "holds 8 s", RED) { confirmForceOff() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row, lp(top = 36))

        if (!tablet) {
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = round(CARD, 18); setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener { startActivity(Intent(this@MainActivity, KvmActivity::class.java)) }
                pressable(this)
            }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text("KVM screen", 16f)); t.addView(text("Open the remote display", 13f, GREY))
            card.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            card.addView(text("›", 24f, GREY))
            col.addView(card, lp(top = 12))
        }

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(info, lp(top = 24))
        col.addView(text("Log out", 14f, GREY).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { kvm.forget(); PowerWidget.render(this@MainActivity); showLogin() }
        }, lp(top = 4))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val web: WebView?
        if (tablet) {
            val split = LinearLayout(this)
            insetPadded(split, 24)
            split.addView(scroll, LinearLayout.LayoutParams(dp(340), MATCH_PARENT))
            web = kvmWebView(this)
            split.addView(web, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(24) })
            setContentView(split)
            openKvm(this, web)
        } else {
            web = null
            insetPadded(scroll, 24)
            setContentView(scroll)
        }
        controls = Controls(chip, ring, glow, button, icon, state, hint, info, web)
        render(kvm.lastOn, null, null)
        enter(col)
    }

    /** Slow green "breathing" glow behind the power button while the PC is on. */
    private fun breathe(c: Controls, on: Boolean) {
        if (on && c.breathing == null) {
            c.glow.scaleX = 0.82f; c.glow.scaleY = 0.82f
            c.breathing = ObjectAnimator.ofPropertyValuesHolder(c.glow,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.82f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.82f, 1f),
            ).apply {
                duration = 1800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else if (!on && c.breathing != null) {
            c.breathing?.cancel(); c.breathing = null
            c.glow.animate().alpha(0f).setDuration(400).start()
        }
    }

    /** Shrinks a view slightly while it's held down. */
    private fun pressable(v: View) = v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(220).start()
        }
        false
    }

    /** Children fade and slide in one after another. */
    private fun enter(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            v.alpha = 0f; v.translationY = dp(24).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(380)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -18f, 18f, -12f, 12f, -6f, 6f, 0f).setDuration(420).start()
    }

    private fun confirmReset() = confirm("Reset the PC?") { act("Reset pressed") { kvm.press("reset", 800) } }

    private fun confirmForceOff() =
        confirm("Force the PC off? Unsaved work is lost.") { act("Forced off") { kvm.press("power", 8000) } }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes") { _, _ -> ok() }.setNegativeButton("No", null).show()
    }

    private fun act(done: String, block: () -> Unit) {
        controls?.let { c ->
            c.hint.text = "…"
            c.busy?.cancel()
            c.busy = ObjectAnimator.ofPropertyValuesHolder(c.button,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.93f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.93f),
            ).apply { duration = 450; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start() }
        }
        thread {
            val e = runCatching(block).exceptionOrNull()
            runOnUiThread {
                controls?.let { c ->
                    c.busy?.cancel(); c.busy = null
                    c.button.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    c.hint.text = if (e == null) done else "Error: ${e.message}"
                    if (e != null) shake(c.hint)
                }
            }
            if (e == null) { Thread.sleep(3000); refresh() }
        }
    }

    private class Details(val hdmi: Boolean?, val version: String?)

    private fun refresh() {
        thread {
            if (kvm.tailscale == null) runCatching { kvm.ensureSession() }
            val r = runCatching { kvm.isOn() }
            val d = if (r.isSuccess) Details(runCatching { kvm.hdmiSignal() }.getOrNull(), runCatching { kvm.version() }.getOrNull()) else null
            runOnUiThread { render(r.getOrNull(), d, r.exceptionOrNull()) }
            PowerWidget.render(this)
        }
    }

    private fun render(on: Boolean?, d: Details?, error: Throwable?) {
        val c = controls ?: return
        val live = error == null && on != null
        c.chip.text = when {
            error != null -> "● Unreachable"
            d == null -> "…"
            else -> "● ${if (kvm.viaTailscale) "Tailscale" else "LAN"}"
        }
        c.chip.setTextColor(if (error != null) RED else if (d != null) GREEN else GREY)
        val lit = on == true
        val changed = c.lit != null && c.lit != lit
        if (c.lit != lit) {
            (c.ring.background as GradientDrawable).setStroke(dp(3), if (lit) 0x553DDC84 else 0x336B747D)
            c.button.background = oval(if (lit) GREEN_DARK else OFF_FILL, if (lit) GREEN else 0xFF6B747D.toInt())
            c.icon.setImageResource(if (lit) R.drawable.ic_power_on else R.drawable.ic_power_off)
            ValueAnimator.ofArgb(c.state.currentTextColor, if (lit) GREEN else GREY).apply {
                duration = 400
                addUpdateListener { c.state.setTextColor(it.animatedValue as Int) }
                start()
            }
        }
        if (changed) {
            c.button.scaleX = 0.85f; c.button.scaleY = 0.85f
            c.button.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(450).start()
            c.state.alpha = 0f; c.state.translationY = dp(8).toFloat()
            c.state.animate().alpha(1f).translationY(0f).setDuration(300).start()
        }
        c.lit = lit
        c.state.text = when (on) { true -> "ON"; false -> "OFF"; null -> "?" }
        breathe(c, lit)
        if (live && c.hint.text == "…") c.hint.text = "Tap to press power"

        val since = kvm.lastChanged.takeIf { it > 0 }?.let {
            if (System.currentTimeMillis() - it < 60_000) "just now"
            else DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        renderInfo(c.info, on, d, error, since)
    }

    private fun header(s: String) = text(s.uppercase(), 12f, GREY, bold = true).apply { letterSpacing = 0.12f }

    /** Status tiles (2x2) and a network card with one row per address. */
    private fun renderInfo(box: LinearLayout, on: Boolean?, d: Details?, error: Throwable?, since: String?) {
        box.removeAllViews()
        box.addView(header("Status"), lp())
        fun tile(label: String, value: String, color: Int = TEXT) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 18); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(label, 12f, GREY))
            addView(text(value, 17f, color, bold = true).apply { maxLines = 1 }, lp(top = 4))
        }
        fun pair(a: View, b: View) = LinearLayout(this).apply {
            addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }
        val video = when (d?.hdmi) { true -> "Signal"; false -> "No signal"; null -> "—" }
        box.addView(pair(
            tile("Video", video, if (d?.hdmi == true) GREEN else TEXT),
            tile("Latency", if (d != null) "${kvm.latency} ms" else "—"),
        ), lp(top = 10))
        box.addView(pair(
            tile("Last change", since ?: "—"),
            tile("NanoKVM", d?.version?.takeIf { it.isNotEmpty() }?.let { "v$it" } ?: "—"),
        ), lp(top = 12))

        box.addView(header("Network"), lp(top = 24))
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 18); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        fun row(name: String, address: String?, active: Boolean, last: Boolean) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            r.addView(View(this).apply { background = oval(if (active) GREEN else 0xFF3A4049.toInt(), if (active) GREEN else 0xFF3A4049.toInt()) },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
            r.addView(text(name, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(text(address ?: "not found", 14f, if (address == null) GREY else 0xFFB8C0C8.toInt()))
            card.addView(r)
            if (!last) card.addView(View(this).apply { setBackgroundColor(0xFF1F252D.toInt()) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        }
        val live = error == null && on != null
        row("LAN", kvm.lan?.removePrefix("http://"), live && !kvm.viaTailscale, false)
        row("Tailscale", kvm.tailscale?.removePrefix("http://"), live && kvm.viaTailscale, true)
        box.addView(card, lp(top = 10))
        if (error != null) box.addView(text("Error: ${error.message}", 13f, RED), lp(top = 12))
    }
}
