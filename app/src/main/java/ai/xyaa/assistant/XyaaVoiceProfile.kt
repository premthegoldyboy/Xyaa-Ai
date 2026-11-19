package ai.xyaa.assistant

/**
 * XYAA's locally-rendered, Fiona-inspired delivery profile.
 *
 * This is a style approximation (bright, clear, gently upbeat American English),
 * not a clone or copy of Speechma Fiona's voice identity or recordings.
 * Keep engine-specific tuning here so the speech backend can be replaced later.
 */
internal object XyaaVoiceProfile {
    const val DISPLAY_NAME = "Fiona-inspired"
    const val KOKORO_VOICE_ID = 10 // Kokoro multi-lang v1.0: af_sky
    const val KOKORO_SPEED = 1.02f
    const val MAX_CHUNK_CHARS = 160

    const val DEVICE_LANGUAGE = "en"
    const val DEVICE_COUNTRY_PRIMARY = "US"
    const val DEVICE_COUNTRY_FALLBACK = "IN"
    const val DEVICE_SPEECH_RATE = 0.96f
    const val DEVICE_PITCH = 1.06f

    const val READY_STATUS = "Ready · XYAA voice profile (Fiona-inspired · Kokoro af_sky), offline"
}
