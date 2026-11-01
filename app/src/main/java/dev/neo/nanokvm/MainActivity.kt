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
private const val AMBER = 0xFFFFB74D.toInt()
private const val AMBER_DARK = 0xFF33270F.toInt()
private const val OFF_FILL = 0xFF2A2F36.toInt()
private const val RED = 0xFFFF8A80.toInt()
private const val TEXT = 0xFFE8EAED.toInt()

class MainActivity : Activity() {
    companion object {
        const val EXTRA_RESET = "reset"
    }

    private lateinit var kvm: Kvm
    private lateinit var tapo: Tapo
    private val busyPlugs = mutableSetOf<String>()
    /** Last Tapo error, shown in the Devices section until the next success (a toast is easy to miss). */
    private var tapoError: String? = null
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
        val state: TextView, val hint: TextView, val info: LinearLayout, val devices: LinearLayout, val web: WebView?,
    ) {
        var mode: Power? = null
        var animMode: Power? = null
        var breathing: Animator? = null
        var busy: Animator? = null
    }

    private val tablet get() = resources.configuration.smallestScreenWidthDp >= 600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        kvm = Kvm(this)
        tapo = Tapo(this)
        if (kvm.hasLogin) showControls() else showLogin()
        if (intent.getBooleanExtra(EXTRA_RESET, false) && kvm.hasLogin) confirmReset()
    }

    override fun onResume() {
        super.onResume()
        if (controls != null) ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        controls?.let { it.breathing?.cancel(); it.breathing = null; it.animMode = null }
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
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { typeface = Typeface.DEFAULT }
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

        val devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devices, lp(top = 24))
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
        controls = Controls(chip, ring, glow, button, icon, state, hint, info, devices, web)
        renderDevices()
        render(kvm.lastPower, null, null)
        enter(col)
    }

    /** Slow green "breathing" glow behind the power button while the PC is on. */
    /** Glow behind the power button: slow green breathing while on, amber blinking like the LED while asleep. */
    private fun breathe(c: Controls, power: Power?) {
        if (power == c.animMode && (c.breathing != null || power != Power.ON && power != Power.SLEEP)) return
        c.breathing?.cancel(); c.breathing = null
        c.animMode = power
        if (power != Power.ON && power != Power.SLEEP) {
            c.glow.animate().alpha(0f).setDuration(400).start()
            return
        }
        val color = if (power == Power.ON) 0x003DDC84 else 0x00FFB74D
        (c.glow.background as GradientDrawable).setColors(
            intArrayOf(color or 0x99000000.toInt(), color or 0x99000000.toInt(), color), floatArrayOf(0f, 0.6f, 1f))
        c.glow.scaleX = 0.82f; c.glow.scaleY = 0.82f
        c.breathing = if (power == Power.ON) ObjectAnimator.ofPropertyValuesHolder(c.glow,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.82f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.82f, 1f),
        ).apply {
            duration = 1800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        } else ObjectAnimator.ofFloat(c.glow, View.ALPHA, 0.05f, 0.9f).apply {
            c.glow.scaleX = 0.9f; c.glow.scaleY = 0.9f
            duration = 1100; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
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
            // on the LAN, ask again every time: the NanoKVM may have moved to another tailnet
            if (kvm.tailscale == null || !kvm.viaTailscale) runCatching { kvm.ensureSession() }
            val r = runCatching { kvm.power() }
            val d = if (r.isSuccess) Details(runCatching { kvm.hdmiSignal() }.getOrNull(), runCatching { kvm.version() }.getOrNull()) else null
            runOnUiThread { render(r.getOrNull(), d, r.exceptionOrNull()) }
            PowerWidget.render(this)
            tapo.plugs.forEach { p -> if (p.mac !in busyPlugs) runCatching { tapo.isOn(p.mac) } }
            runOnUiThread { renderDevices() }
            PlugWidget.render(this)
        }
    }

    private fun render(power: Power?, d: Details?, error: Throwable?) {
        val on: Boolean? = power?.let { it != Power.OFF }
        val c = controls ?: return
        val live = error == null && on != null
        c.chip.text = when {
            error != null -> "● Unreachable"
            d == null -> "…"
            else -> "● ${if (kvm.viaTailscale) "Tailscale" else "LAN"}"
        }
        c.chip.setTextColor(if (error != null) RED else if (d != null) GREEN else GREY)
        val changed = c.mode != null && c.mode != power
        if (c.mode != power) {
            val (fill, accent, ring) = when (power) {
                Power.ON -> Triple(GREEN_DARK, GREEN, 0x553DDC84)
                Power.SLEEP -> Triple(AMBER_DARK, AMBER, 0x55FFB74D)
                else -> Triple(OFF_FILL, 0xFF6B747D.toInt(), 0x336B747D)
            }
            (c.ring.background as GradientDrawable).setStroke(dp(3), ring)
            c.button.background = oval(fill, accent)
            c.icon.setImageResource(when (power) { Power.ON -> R.drawable.ic_power_on; Power.SLEEP -> R.drawable.ic_power_sleep; else -> R.drawable.ic_power_off })
            ValueAnimator.ofArgb(c.state.currentTextColor, if (power == Power.OFF || power == null) GREY else accent).apply {
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
        c.mode = power
        c.state.text = when (power) { Power.ON -> "ON"; Power.OFF -> "OFF"; Power.SLEEP -> "SLEEP"; null -> "?" }
        breathe(c, power)
        if (live) c.hint.text = when {
            c.hint.text != "…" && c.hint.text.startsWith("Error") -> c.hint.text
            power == Power.SLEEP -> "Asleep · tap to wake"
            power == Power.OFF -> "Tap to power on"
            else -> "Tap to press power"
        }

        val since = kvm.lastChanged.takeIf { it > 0 }?.let {
            if (System.currentTimeMillis() - it < 60_000) "just now"
            else DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        renderInfo(c.info, on, d, error, since)
    }

    private fun header(s: String) = text(s.uppercase(), 12f, GREY, bold = true).apply { letterSpacing = 0.12f }

    /** Tapo plugs: one card per plug (tap toggles), or a card to add them. */
    private fun renderDevices() {
        val box = controls?.devices ?: return
        box.removeAllViews()
        box.addView(header("Devices"), lp())
        if (!tapo.hasLogin || tapo.plugs.isEmpty()) {
            val add = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = round(CARD, 18); setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text("Add Tapo plugs", 16f))
                addView(text("Switch smart plugs on this Wi-Fi with your TP-Link account", 13f, GREY))
                tapoError?.let { addView(text(it, 13f, RED), lp(top = 6)) }
                setOnClickListener { tapoLogin() }
            }
            pressable(add)
            box.addView(add, lp(top = 10))
            return
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tapo.plugs.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEachIndexed { i, plug ->
                val on = tapo.lastOn(plug.mac)
                val busy = plug.mac in busyPlugs
                val card = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = round(if (on == true) 0xFF13261B.toInt() else CARD, 18, if (on == true) 0x553DDC84 else 0)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    val dot = FrameLayout(context).apply { background = oval(if (on == true) GREEN_DARK else OFF_FILL, if (on == true) GREEN else 0xFF6B747D.toInt()) }
                    dot.addView(ImageView(context).apply { setImageResource(R.drawable.ic_plug); setColorFilter(if (on == true) GREEN else 0xFF9AA3AB.toInt()) },
                        FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    addView(dot, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
                    val t = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    t.addView(text(plug.name, 15f).apply { maxLines = 1 })
                    t.addView(text(if (busy) "…" else when (on) { true -> "On"; false -> "Off"; null -> "?" }, 12f, if (on == true) GREEN else GREY))
                    addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    setOnClickListener { togglePlug(plug.mac) }
                }
                pressable(card)
                row.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(6) })
            grid.addView(row, lp(top = 10))
        }
        box.addView(grid)
        tapoError?.let { box.addView(text(it, 13f, RED), lp(top = 8)) }
        box.addView(text("Rescan · Log out of Tapo", 12f, GREY).apply {
            gravity = Gravity.END; setPadding(0, dp(8), dp(4), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Rescan plugs", "Log out of Tapo")) { _, i ->
                    if (i == 0) thread { val e = runCatching { tapo.setup() }.exceptionOrNull(); runOnUiThread { tapoError = e?.let(::describe); renderDevices() } }
                    else { tapo.forget(); renderDevices(); PlugWidget.render(this@MainActivity) }
                }.show()
            }
        }, lp())
    }

    /** Plain-language Tapo error. */
    private fun describe(e: Throwable): String = when (e) {
        is KvmError -> e.message ?: "Tapo error"
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Couldn't reach the plug. Is the phone on the same Wi-Fi?"
        else -> e.message ?: e.toString()
    }.also { android.util.Log.w("Tapo", it, e) }

    private fun togglePlug(mac: String) {
        if (!busyPlugs.add(mac)) return
        renderDevices()
        thread {
            val e = runCatching { tapo.set(mac, tapo.lastOn(mac) != true) }.exceptionOrNull()
            runOnUiThread { busyPlugs.remove(mac); tapoError = e?.let(::describe); renderDevices() }
            PlugWidget.render(this)
        }
    }

    private fun tapoLogin() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val email = EditText(this).apply { hint = "TP-Link email"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pass = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; typeface = Typeface.DEFAULT }
        col.addView(email); col.addView(pass)
        col.addView(text("Needs \"Third-Party Compatibility\" on in the Tapo app (Me → Third-Party Services).", 12f, GREY), lp(top = 8))
        AlertDialog.Builder(this).setTitle("Tapo account").setView(col).setNegativeButton("Cancel", null)
            .setPositiveButton("Find plugs") { _, _ ->
                tapo.saveLogin(email.text.toString().trim(), pass.text.toString())
                busyPlugs.clear()
                controls?.devices?.let { it.removeAllViews(); it.addView(header("Devices")); it.addView(text("Searching for plugs…", 14f, GREY), lp(top = 10)) }
                thread {
                    val e = runCatching { tapo.setup() }.exceptionOrNull()
                    runOnUiThread {
                        if (e != null) tapo.forget()
                        tapoError = e?.let(::describe)
                        renderDevices()
                    }
                }
            }.show()
    }

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
