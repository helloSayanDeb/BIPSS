package com.bipss.app.ui.theme

import com.bipss.app.localization.AppText
import com.bipss.app.R

enum class ThemePreference {
    SYSTEM,   // follows isSystemInDarkTheme(); ordinals are persisted, so keep this order
    LIGHT,
    DARK;     // the default when nothing is stored

    val displayName: String
        get() = when (this) {
            SYSTEM -> AppText.text(R.string.copy_6725e7bbcd28, "System")
            LIGHT  -> AppText.text(R.string.copy_dbcd5e7bb7a0, "Light")
            DARK   -> AppText.text(R.string.copy_60acc53f13a5, "Dark")
        }
}
