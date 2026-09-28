package com.bipss.app.sos

import java.util.UUID

const val SOS_COUNTDOWN_SECONDS = 15

/**
 * TODO: Fall detection automatic trigger integration point.
 *
 * When the on-device LiteRT fall-detection model (trained on SisFall dataset,
 * scheduled for a future plan) classifies an accelerometer window as a fall event,
 * call [SosViewModel.triggerSos()] from the classifier's output callback.
 *
 * The full SOS pipeline — countdown, cancel window, location fetch, SMS dispatch,
 * audit log — is identical for both manual and automatic triggers.
 * The only difference is the [SosTriggerSource] passed to [triggerSos()],
 * stored in [SosEventEntity] for later review.
 *
 * Integration sketch (do NOT implement until LiteRT model is ready):
 * ```kotlin
 * // Inside FallDetectionClassifier.onFallDetected():
 * coroutineScope.launch {
 *     sosViewModel.triggerSos(source = SosTriggerSource.FALL_DETECTION_ML)
 * }
 * ```
 */

enum class SosTriggerSource {
    MANUAL,               // User pressed FAB
    FALL_DETECTION_ML     // TODO: LiteRT SisFall classifier (future plan)
}

/** Stored as its name in sos_events.outcome (a TEXT column), so adding values needs no migration. */
enum class SosOutcome {
    SENT,
    /** Some contacts were confirmed sent, others were not. */
    PARTIALLY_SENT,
    CANCELLED,
    NO_CONTACTS,
    NO_SMS_PERMISSION,
    NO_TELEPHONY,
    /** Sent, but without any location. */
    LOCATION_DENIED,
    /** Handed to the SMS service but no send confirmation arrived for anyone before the timeout. */
    UNCONFIRMED,
    SEND_FAILED
}
