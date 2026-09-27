package com.bipss.app.ui.theme

enum class ThemePreference {
    SYSTEM,   // follows isSystemInDarkTheme(); ordinals are persisted, so keep this order
    LIGHT,
    DARK;     // the default when nothing is stored

    val displayName: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT  -> "Light"
            DARK   -> "Dark"
        }
}
