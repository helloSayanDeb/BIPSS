package com.bipss.app.ui.theme

enum class ThemePreference {
    SYSTEM,   // default — uses isSystemInDarkTheme()
    LIGHT,
    DARK;

    val displayName: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT  -> "Light"
            DARK   -> "Dark"
        }
}
