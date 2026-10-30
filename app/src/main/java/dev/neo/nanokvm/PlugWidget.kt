package dev.neo.nanokvm

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.Toast
import kotlin.concurrent.thread

/** 1x1 widget for one Tapo plug: tap toggles it. Which plug is chosen when the widget is placed. */
class PlugWidget : AppWidgetProvider() {
    companion object {
        private const val TOGGLE = "dev.neo.nanokvm.PLUG_TOGGLE"

        private fun prefs(c: Context) = c.getSharedPreferences("plug_widgets", Context.MODE_PRIVATE)

        fun assign(c: Context, id: Int, mac: String) = prefs(c).edit().putString("w$id", mac).apply()

        /** Redraws every plug widget from the last known states; [busyMac] shows "…" on that plug. */
        fun render(c: Context, busyMac: String? = null) {
            val manager = AppWidgetManager.getInstance(c)
            val tapo = Tapo(c)
            for (id in manager.getAppWidgetIds(ComponentName(c, PlugWidget::class.java))) {
                val mac = prefs(c).getString("w$id", null) ?: continue
                val plug = tapo.plug(mac)
                val on = tapo.lastOn(mac)
                val v = RemoteViews(c.packageName, R.layout.widget_plug)
                v.setTextViewText(R.id.name, if (mac == busyMac) "…" else plug?.name ?: "?")
                v.setInt(R.id.icon, "setBackgroundResource", if (on == true) R.drawable.w_btn_on else R.drawable.w_btn_off)
                v.setInt(R.id.icon, "setColorFilter", if (on == true) 0xFF3DDC84.toInt() else 0xFF9AA3AB.toInt())
                val intent = Intent(c, PlugWidget::class.java).setAction(TOGGLE).putExtra("mac", mac)
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                manager.updateAppWidget(id, v)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        val result = goAsync()
        thread {
            val tapo = Tapo(context)
            val macs = ids.toList().mapNotNull { id -> prefs(context).getString("w$id", null) }.toSet()
            for (mac in macs) runCatching { tapo.isOn(mac) }
            render(context)
            result.finish()
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove("w$it") } }.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TOGGLE) return super.onReceive(context, intent)
        val mac = intent.getStringExtra("mac") ?: return
        render(context, mac)
        val result = goAsync()
        thread {
            val tapo = Tapo(context)
            runCatching { tapo.set(mac, tapo.lastOn(mac) != true) }
            render(context)
            result.finish()
        }
    }
}

/** Picks which plug a newly placed widget controls. */
class PlugWidgetConfig : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val plugs = Tapo(this).plugs
        if (plugs.isEmpty()) {
            Toast.makeText(this, "Add your Tapo plugs in the app first", Toast.LENGTH_LONG).show()
            return finish()
        }
        title = "Choose a plug"
        setContentView(ListView(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, plugs.map { it.name })
            setOnItemClickListener { _, _, i, _ ->
                PlugWidget.assign(context, id, plugs[i].mac)
                PlugWidget.render(context)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        })
    }
}
