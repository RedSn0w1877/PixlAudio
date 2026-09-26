package com.theveloper.pixelplay.ui.theme

import android.os.Build

/**
 * The visual-style preference (`UserPreferencesRepository.appUiStyleFlow`, stored under
 * `APP_UI_STYLE`) and the single place that decides whether Liquid Glass mode is on.
 *
 * Glass mode is being rebuilt. Until it lands, every screen renders the Material 3 UI whatever
 * this returns; the rebuild reads [isGlassMode] rather than comparing the preference by hand.
 */
object VisualStyle {
    const val LIQUID_GLASS = "LiquidGlass"
    const val MATERIAL3 = "Material3"

    /**
     * Whether glass mode should be drawn: the user chose it ([preference] is anything but
     * [MATERIAL3], so the stored default and unknown values count as glass, as they always have),
     * "Disable blur all over" is off (it wins over the style choice), and the device can draw it
     * (API 31+; API 30 falls back to Material 3). The saved preference is never changed, so glass
     * comes back if either condition goes away.
     */
    fun isGlassMode(
        preference: String?,
        disableBlurAllOver: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = preference != MATERIAL3 && !disableBlurAllOver && sdkInt >= Build.VERSION_CODES.S
}
