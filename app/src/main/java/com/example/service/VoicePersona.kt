package com.example.service

import java.util.Locale

/**
 * Android TextToSpeech Voice Personas for Jarvis.
 *
 * Speech synthesis is performed locally or via Google Android TTS voices installed
 * on the device (NOT via Gemini Live native audio streams). Availability of specific
 * male/female or offline voices depends on the voices installed in Android TTS settings.
 */
enum class VoicePersona(
    val id: String,
    val shortName: String,
    val displayName: String,
    val subtitle: String,
    val pitch: Float,
    val speechRate: Float,
    val primaryLocale: Locale,
    val fallbackLocale: Locale,
    val genderPreference: String,
    val voiceSubtagHint: String,
    val geminiLiveVoiceName: String,
    val sampleText: String
) {
    DIYA_HINDI(
        id = "diya_hindi",
        shortName = "Diya",
        displayName = "Diya (Hindi & Hinglish Female)",
        subtitle = "Warm Indian Hindi & Hinglish female cadence",
        pitch = 1.02f,
        speechRate = 0.98f,
        primaryLocale = Locale.Builder().setLanguage("hi").setRegion("IN").build(),
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "indian_female",
        voiceSubtagHint = "hie",
        geminiLiveVoiceName = "Aoede",
        sampleText = "नमस्ते! मैं जार्विस हूँ। आप मुझसे साफ़ हिंदी, इंग्लिश या हिंग्लिश में बात कर सकते हैं।"
    ),
    ARJUN_HINDI(
        id = "arjun_hindi",
        shortName = "Arjun",
        displayName = "Arjun (Hindi & Hinglish Male)",
        subtitle = "Clear Indian Hindi & English male cadence",
        pitch = 0.94f,
        speechRate = 0.98f,
        primaryLocale = Locale.Builder().setLanguage("hi").setRegion("IN").build(),
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "indian_male",
        voiceSubtagHint = "hid",
        geminiLiveVoiceName = "Puck",
        sampleText = "नमस्ते! मैं जार्विस हूँ। बताइए, आज मैं आपकी क्या मदद कर सकता हूँ?"
    ),
    PUCK(
        id = "puck",
        shortName = "Puck",
        displayName = "Puck (Upbeat English Male)",
        subtitle = "Bright, energetic English tone",
        pitch = 1.04f,
        speechRate = 1.02f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "male",
        voiceSubtagHint = "sfg",
        geminiLiveVoiceName = "Puck",
        sampleText = "Hey! I'm Jarvis. Ready to explore ideas together whenever you are."
    ),
    KORE(
        id = "kore",
        shortName = "Kore",
        displayName = "Kore (Calm English Female)",
        subtitle = "Soothing, steady English tone",
        pitch = 1.0f,
        speechRate = 0.95f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "female",
        voiceSubtagHint = "tpd",
        geminiLiveVoiceName = "Kore",
        sampleText = "Hello. I'm Jarvis in calm voice mode. Let's take things one step at a time."
    ),
    CHARON(
        id = "charon",
        shortName = "Charon",
        displayName = "Charon (Deep English Male)",
        subtitle = "Lower-pitched, articulate English tone",
        pitch = 0.84f,
        speechRate = 0.94f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "male",
        voiceSubtagHint = "iom",
        geminiLiveVoiceName = "Charon",
        sampleText = "Greetings. I provide clear, concise answers with depth and precision."
    ),
    FENRIR(
        id = "fenrir",
        shortName = "Fenrir",
        displayName = "Fenrir (Fast English Male)",
        subtitle = "Crisp, fast-paced English cadence",
        pitch = 0.96f,
        speechRate = 1.06f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "male",
        voiceSubtagHint = "ioh",
        geminiLiveVoiceName = "Fenrir",
        sampleText = "Let's dive right in and get things done quickly and clearly."
    ),
    AOEDE(
        id = "aoede",
        shortName = "Aoede",
        displayName = "Aoede (Thoughtful English Female)",
        subtitle = "Expressive, articulate English tone",
        pitch = 1.06f,
        speechRate = 0.98f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "female",
        voiceSubtagHint = "ioc",
        geminiLiveVoiceName = "Aoede",
        sampleText = "Hi there. Let's work through your questions thoughtfully."
    ),
    CAPELLA(
        id = "capella",
        shortName = "Capella",
        displayName = "Capella (Warm English Female)",
        subtitle = "Friendly conversational English tone",
        pitch = 1.03f,
        speechRate = 1.0f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "female",
        voiceSubtagHint = "ioe",
        geminiLiveVoiceName = "Kore",
        sampleText = "Hey! Just here to help you brainstorm, plan, and get things done."
    ),
    ORION(
        id = "orion",
        shortName = "Orion",
        displayName = "Orion (Confident English Male)",
        subtitle = "Steady, decisive English voice",
        pitch = 0.88f,
        speechRate = 0.98f,
        primaryLocale = Locale.US,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "male",
        voiceSubtagHint = "sfc",
        geminiLiveVoiceName = "Charon",
        sampleText = "Hello. Confident, direct, and ready to assist with whatever you need."
    ),
    PEGASUS(
        id = "pegasus",
        shortName = "Pegasus",
        displayName = "Pegasus (British English Male)",
        subtitle = "Patient, UK-accented English voice",
        pitch = 0.92f,
        speechRate = 0.96f,
        primaryLocale = Locale.UK,
        fallbackLocale = Locale.Builder().setLanguage("en").setRegion("IN").build(),
        genderPreference = "male",
        voiceSubtagHint = "tpb",
        geminiLiveVoiceName = "Charon",
        sampleText = "Hello. We can take our time and work through complex ideas step by step."
    );

    companion object {
        fun fromId(id: String): VoicePersona {
            return when (id.lowercase()) {
                "diya_hindi", "ananya_female" -> DIYA_HINDI
                "arjun_hindi", "aarav_hinglish" -> ARJUN_HINDI
                "puck" -> PUCK
                "kore", "samantha_warm" -> KORE
                "charon", "jarvis_deep" -> CHARON
                "fenrir" -> FENRIR
                "aoede" -> AOEDE
                "capella" -> CAPELLA
                "orion" -> ORION
                "pegasus" -> PEGASUS
                else -> entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DIYA_HINDI
            }
        }
    }
}
