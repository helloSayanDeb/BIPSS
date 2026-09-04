package com.bipss.app.navigation

object Routes {
    const val ONBOARDING_WELCOME        = "onboarding_welcome"
    const val ONBOARDING_PROFILE        = "onboarding_profile"
    const val ONBOARDING_CONNECT_BAND   = "onboarding_connect_band"
    const val ONBOARDING_DOWNLOAD_MODEL = "onboarding_download_model"
    const val DASHBOARD_TODAY           = "dashboard_today"
    const val DASHBOARD_INSIGHTS        = "dashboard_insights"
    const val DASHBOARD_CHAT            = "dashboard_chat"
    const val DASHBOARD_PROFILE         = "dashboard_profile"
    const val SETTINGS                  = "settings"
    const val SETTINGS_CONTACT_PICKER   = "settings_contact_picker"

    // Parameterized routes — reuse onboarding composables in edit/re-pair mode
    const val ONBOARDING_PROFILE_EDIT      = "onboarding_profile?editMode=true"
    const val ONBOARDING_CONNECT_BAND_REPAIR = "onboarding_connect_band?rePairMode=true"
}
