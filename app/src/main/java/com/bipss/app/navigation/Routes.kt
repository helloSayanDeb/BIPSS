package com.bipss.app.navigation

object Routes {
    const val ONBOARDING_WELCOME        = "onboarding_welcome"
    const val ONBOARDING_PROFILE        = "onboarding_profile"
    const val ONBOARDING_CONNECT_BAND   = "onboarding_connect_band"
    const val ONBOARDING_DOWNLOAD_MODEL = "onboarding_download_model"
    const val ONBOARDING_NOTIFICATIONS  = "onboarding_notifications"
    const val ONBOARDING_LOCATION       = "onboarding_location"
    const val ONBOARDING_SMS            = "onboarding_sms"
    const val ONBOARDING_CONTACTS       = "onboarding_contacts"
    /** Location setup after a later grant: the onboarding step on its own. */
    const val LOCATION_SETUP            = "location_setup"
    /** Catch-up only (see OnboardingStep). */
    const val ONBOARDING_GENDER         = "onboarding_gender"
    const val DASHBOARD_TODAY           = "dashboard_today"
    const val DASHBOARD_INSIGHTS        = "dashboard_insights"
    const val DASHBOARD_CHAT            = "dashboard_chat"
    const val DASHBOARD_PROFILE         = "dashboard_profile"
    const val SETTINGS                  = "settings"
    const val SETTINGS_CONTACT_PICKER   = "settings_contact_picker"
    /** Settings, scrolled to emergency contacts and SMS (where the Home SOS warning is fixed). */
    const val SETTINGS_EMERGENCY        = "settings?section=emergency"

    // Parameterized routes — reuse onboarding composables in edit/re-pair mode
    const val ONBOARDING_PROFILE_EDIT      = "onboarding_profile?editMode=true"
    const val ONBOARDING_CONNECT_BAND_REPAIR = "onboarding_connect_band?rePairMode=true"

    /** Sleep and the overnight breathing screening. */
    const val SLEEP_DETAIL = "sleep_detail"
    
    /** Recovery detail. */
    const val RECOVERY_DETAIL = "recovery_detail"
    
    /** Strain detail. */
    const val STRAIN_DETAIL = "strain_detail"

    /** Metric history. The argument is a MetricTypes key, e.g. "HR". */
    const val METRIC_DETAIL_PATTERN = "metric_detail/{metricType}"

    fun metricDetail(metricType: String) = "metric_detail/$metricType"
}
