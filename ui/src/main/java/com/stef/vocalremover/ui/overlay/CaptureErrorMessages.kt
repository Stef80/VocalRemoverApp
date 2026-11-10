package com.stef.vocalremover.ui.overlay

import androidx.annotation.StringRes
import com.example.vocalremover.capture.CaptureErrorCode
import com.example.vocalremover.ui.R

/** Localized text for a capture failure reported by the engine; null falls back to the generic error. */
@StringRes
internal fun CaptureErrorCode?.messageRes(): Int = when (this) {
    CaptureErrorCode.INVALID_START_DATA -> R.string.capture_error_invalid_start_data
    CaptureErrorCode.INVALID_CONSENT -> R.string.capture_error_invalid_consent
    CaptureErrorCode.CANCELLED_BEFORE_START -> R.string.capture_error_cancelled_before_start
    CaptureErrorCode.PERMISSION_DENIED -> R.string.capture_error_permission_denied
    CaptureErrorCode.UNSUPPORTED_CONFIGURATION -> R.string.capture_error_unsupported_configuration
    CaptureErrorCode.INIT_FAILED -> R.string.capture_error_init_failed
    CaptureErrorCode.START_FAILED -> R.string.capture_error_start_failed
    CaptureErrorCode.NO_AUDIO_CAPTURED -> R.string.capture_error_no_audio_captured
    CaptureErrorCode.READ_FAILED -> R.string.capture_error_read_failed
    CaptureErrorCode.SIZE_LIMIT_REACHED -> R.string.capture_error_size_limit_reached
    CaptureErrorCode.INTERRUPTED_BY_SYSTEM -> R.string.capture_error_interrupted_by_system
    CaptureErrorCode.FINALIZE_FAILED -> R.string.capture_error_finalize_failed
    CaptureErrorCode.UNEXPECTED_END -> R.string.capture_error_unexpected_end
    CaptureErrorCode.CAPTURE_FAILED, null -> R.string.bubble_error_capture_failed
}
