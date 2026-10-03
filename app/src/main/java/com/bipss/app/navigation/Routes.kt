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
    const val INSIGHT_DETAIL_PATTERN    = "insight/{type}"
    fun insightDetail(type: com.bipss.app.domain.RiskScoreType) = "insight/${type.name}"
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
    
    /** Period tracking (Female profiles). */
    const val CYCLE_DETAIL = "cycle_detail"
    const val HYDRATION_DETAIL = "hydration_detail"
    const val ACTIVITY_DETAIL = "activity_detail"
    const val BODY_DETAIL = "body_detail"
    const val FITNESS = "cardio_fitness"
    const val BIOAGE = "bioage"
    const val SLEEP_PLANNER = "sleep_planner"
    const val WEEKLY_REPORT = "weekly_report"
    const val JOURNAL = "journal"
    const val EXPORT = "export_data"
    const val SLEEP_STAGES = "sleep_stages"
    const val BREATHING = "breathing"
    const val TRENDS = "trends"
    const val NUTRITION = "nutrition"
    const val ROUTINE = "sleep_routine"
    const val WORKOUT_LIVE = "workout_live"
    fun snapshot(day: Long) = "snapshot/$day"
    fun workout(id: String) = "workout/$id"

    /** Recovery detail. */
    const val RECOVERY_DETAIL = "recovery_detail"
    
    /** Strain detail. */
    const val STRAIN_DETAIL = "strain_detail"

    /** Metric history. The argument is a MetricTypes key, e.g. "HR". */
    const val METRIC_DETAIL_PATTERN = "metric_detail/{metricType}"

    fun metricDetail(metricType: String) = "metric_detail/$metricType"
}
