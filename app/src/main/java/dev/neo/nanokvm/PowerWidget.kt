package dev.neo.nanokvm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** Widgets: 1x1 power button, 2x1 status + power, 4x1 status + power + reset + KVM screen. Tap the text to refresh. */
open class PowerWidget : AppWidgetProvider() {
    open val layout = R.layout.widget_2x1

    companion object {
        private const val POWER = "dev.neo.nanokvm.POWER"
        private const val REFRESH = "dev.neo.nanokvm.REFRESH"
        private val all = listOf(
            PowerWidget::class.java to R.layout.widget_2x1,
            PowerWidget1x1::class.java to R.layout.widget_1x1,
            PowerWidget4x1::class.java to R.layout.widget_4x1,
        )

        /** Redraws every placed widget from the app's last known state; [note] replaces the subtitle. */
        fun render(context: Context, note: String? = null) {
            val kvm = Kvm(context)
            val power = kvm.lastPower
            val on = power == Power.ON
            val manager = AppWidgetManager.getInstance(context)
            for ((cls, layout) in all) {
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isEmpty()) continue
                val v = RemoteViews(context.packageName, layout)
                v.setImageViewResource(R.id.power, if (on) R.drawable.ic_power_on else if (power == Power.SLEEP) R.drawable.ic_power_sleep else R.drawable.ic_power_off)
                v.setInt(R.id.power, "setBackgroundResource", if (on) R.drawable.w_btn_on else if (power == Power.SLEEP) R.drawable.w_btn_sleep else R.drawable.w_btn_off)
                v.setOnClickPendingIntent(R.id.power, broadcast(context, cls, POWER))
                if (layout != R.layout.widget_1x1) {
                    v.setTextViewText(R.id.title, when (power) { Power.ON -> "PC on"; Power.OFF -> "PC off"; Power.SLEEP -> "PC asleep"; null -> "PC ?" })
                    val ago = if (System.currentTimeMillis() - kvm.lastSeen < 60_000) "now" else DateUtils.getRelativeTimeSpanString(kvm.lastSeen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    val via = if (kvm.viaTailscale) "Tailscale" else "LAN"
                    v.setTextViewText(R.id.sub, note ?: if (!kvm.hasLogin) "open app to log in" else if (power == Power.OFF || power == Power.SLEEP) "tap power to wake" else "$via · $ago")
                    v.setOnClickPendingIntent(R.id.status, broadcast(context, cls, REFRESH))
                }
                if (layout == R.layout.widget_4x1) {
                    v.setOnClickPendingIntent(R.id.reset, activity(context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_RESET, true)))
                    v.setOnClickPendingIntent(R.id.screen, activity(context, 2, Intent(context, KvmActivity::class.java)))
                }
                manager.updateAppWidget(ids, v)
            }
        }

        private fun broadcast(context: Context, cls: Class<*>, action: String) = PendingIntent.getBroadcast(
            context, (cls.name + action).hashCode(), Intent(context, cls).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
            context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REFRESH -> refresh(context)
            POWER -> work(context, "pressing…") { kvm ->
                kvm.press("power", 800)
                Thread.sleep(5000)
                kvm.power()
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun refresh(context: Context) = work(context, "…") { it.power() }

    private fun work(context: Context, note: String, block: (Kvm) -> Unit) {
        val kvm = Kvm(context)
        if (!kvm.hasLogin) return render(context)
        render(context, note)
        val result = goAsync()
        thread {
            val e = runCatching { block(kvm) }.exceptionOrNull()
            render(context, if (e != null) "unreachable" else null)
            result.finish()
        }
    }
}

class PowerWidget1x1 : PowerWidget()
class PowerWidget4x1 : PowerWidget()
