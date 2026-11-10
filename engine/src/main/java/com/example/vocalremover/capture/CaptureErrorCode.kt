package com.example.vocalremover.capture

/**
 * Why a system-audio capture failed. Sent with [SystemAudioCaptureService.ACTION_CAPTURE_ERROR]
 * (extra [SystemAudioCaptureService.EXTRA_ERROR_CODE], as [name]) and persisted in
 * [CaptureTerminalResult.errorCode]: the UI maps it to localized text, while the English
 * error message is meant for logs only.
 */
enum class CaptureErrorCode {
    INVALID_START_DATA,
    INVALID_CONSENT,
    CANCELLED_BEFORE_START,
    PERMISSION_DENIED,
    UNSUPPORTED_CONFIGURATION,
    INIT_FAILED,
    START_FAILED,
    NO_AUDIO_CAPTURED,
    READ_FAILED,
    SIZE_LIMIT_REACHED,
    INTERRUPTED_BY_SYSTEM,
    FINALIZE_FAILED,
    UNEXPECTED_END,
    CAPTURE_FAILED;

    companion object {
        fun fromName(name: String?): CaptureErrorCode? =
            name?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
