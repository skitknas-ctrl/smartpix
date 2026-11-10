package com.skitknas.smartpix

import android.content.Context
import android.util.Size

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("smartpix", Context.MODE_PRIVATE)

    var lensKey: String?
        get() = sp.getString("lensKey", null)
        set(v) = sp.edit().putString("lensKey", v).apply()

    var lastBackKey: String?
        get() = sp.getString("lastBackKey", null)
        set(v) = sp.edit().putString("lastBackKey", v).apply()

    var lastFrontKey: String?
        get() = sp.getString("lastFrontKey", null)
        set(v) = sp.edit().putString("lastFrontKey", v).apply()

    /** true = save front photos mirrored (like the preview), false = true-to-life. */
    var mirrorFront: Boolean
        get() = sp.getBoolean("mirrorFront", false)
        set(v) = sp.edit().putBoolean("mirrorFront", v).apply()

    /** null = original sensor size. */
    var target: Size?
        get() {
            val w = sp.getInt("targetW", 0)
            val h = sp.getInt("targetH", 0)
            return if (w > 0 && h > 0) Size(w, h) else null
        }
        set(v) = sp.edit().putInt("targetW", v?.width ?: 0).putInt("targetH", v?.height ?: 0).apply()

    var quality: Int
        get() = sp.getInt("quality", 95)
        set(v) = sp.edit().putInt("quality", v.coerceIn(1, 100)).apply()

    var presets: List<Size>
        get() {
            val raw = sp.getString("presets", null) ?: return DEFAULT_PRESETS
            return raw.split(',').mapNotNull { parse(it) }
        }
        set(v) = sp.edit().putString("presets", v.joinToString(",") { "${it.width}x${it.height}" }).apply()

    companion object {
        val DEFAULT_PRESETS = listOf(
            Size(3840, 2160), Size(1920, 1080), Size(1080, 1920),
            Size(1080, 1080), Size(800, 600), Size(640, 480),
        )

        fun parse(s: String): Size? {
            val p = s.trim().split('x', '×', 'X')
            if (p.size != 2) return null
            val w = p[0].trim().toIntOrNull() ?: return null
            val h = p[1].trim().toIntOrNull() ?: return null
            return if (w > 0 && h > 0) Size(w, h) else null
        }
    }
}
