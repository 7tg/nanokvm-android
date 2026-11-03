package dev.neo.nanokvm

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Short tick for widget taps, which have no view to give haptic feedback from. The app is in the background
 * then, and Android drops background vibrations unless their usage is one it allows there (hardware feedback is).
 */
fun Context.tick() {
    val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    if (Build.VERSION.SDK_INT >= 33) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect)
    }
}
