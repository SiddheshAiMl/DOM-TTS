package com.itantra.speech.compression

import com.itantra.core.model.Language
import java.io.ByteArrayOutputStream

/**
 * Result of decompressing a semantic payload.
 */
data class DecompressedResult(
    val text: String,
    val language: Language
)

/**
 * Interface defining contract for semantic compression of Indic utterances.
 */
interface CompressionEngine {
    fun compress(text: String, languageCode: String): ByteArray
    fun compress(text: String, language: Language): ByteArray
    fun decompress(payload: ByteArray): Pair<String, String>
    fun decompressResult(payload: ByteArray): DecompressedResult
}

/**
 * Multilingual Semantic Compression Engine for 10 Indian Languages.
 * Encodes frequent domain/tactical words to single-byte dictionary tokens (0x80 | (idx & 0x7F))
 * and falls back losslessly to length-prefixed UTF-8 for out-of-vocabulary terms.
 *
 * Byte 0: Language code index (0=hi, 1=bn, 2=mr, 3=te, 4=ta, 5=gu, 6=kn, 7=ml, 8=or, 9=en).
 * Following bytes: Sequence of tokens (dictionary token or length + raw UTF-8 bytes).
 */
object SemanticCompressionEngine : CompressionEngine {

    val INDIC_LANGUAGES: List<String> = listOf(
        "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or", "en"
    )

    val DICTIONARY_PRESETS: Map<String, List<String>> = mapOf(
        "hi" to listOf("नमस्ते", "तुरंत", "बेस", "कैंप", "पर", "रिपोर्ट", "करो", "मदद", "चाहिए", "सुरक्षित", "पानी", "दवा"),
        "bn" to listOf("নমস্কার", "আমরা", "নিরাপদ", "স্থানে", "আছি", "সাহায্য", "জরুরী", "জল", "ওষুধ", "বন্যা", "আশ্রয়"),
        "mr" to listOf("नमस्कार", "आम्ही", "सुरक्षित", "आहोत", "तात्काळ", "मदत", "हवी", "डॉक्टर", "पाणी", "अन्न"),
        "te" to listOf("నమస్కారం", "రవాణా", "సిద్ధంగా", "ఉంది", "సహాయం", "కావాలి", "నీరు", "ఆహారం", "రక్షణ"),
        "ta" to listOf("வணக்கம்", "உதவி", "தேவைப்படுகிறது", "பாதுகாப்பான", "இடத்தில்", "இருக்கிறோம்", "மருந்து", "உணவு"),
        "gu" to listOf("નમસ્તે", "અમને", "મદદની", "જરૂર", "છે", "સુરક્ષિત", "પાણી", "ખોરાક", "ડોક્ટર"),
        "kn" to listOf("ನಮಸ್ಕಾರ", "ತುರ್ತು", "ಸಹಾಯ", "ಬೇಕಾಗಿದೆ", "ಸುರಕ್ಷಿತ", "ಸ್ಥಳ", "ಔಷಧ", "ಆಹಾರ"),
        "ml" to listOf("നമസ്കാരം", "സഹായം", "ആവശ്യമാണ്", "സുരക്ഷിതമാണ്", "വെള്ളം", "മരുന്ന്", "രക്ഷാപ്രവർത്തനം"),
        "or" to listOf("ନମସ୍କାର", "ଆମେ", "ସୁରକ୍ଷିତ", "ଅଛୁ", "ସାହାଯ୍ୟ", "ଦରକାର", "ଡାକ୍ତର", "ଜଳ", "ଖାଦ୍ୟ"),
        "en" to listOf("hello", "immediate", "base", "camp", "report", "help", "needed", "safe", "water", "medical", "evacuate")
    )

    override fun compress(text: String, languageCode: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ByteArray(0)

        val tokens = trimmed.split(Regex("\\s+"))
        val normalizedLang = languageCode.lowercase()
        val lang = if (normalizedLang in INDIC_LANGUAGES) normalizedLang else "hi"
        val langIdx = INDIC_LANGUAGES.indexOf(lang).coerceAtLeast(0)

        val out = ByteArrayOutputStream()
        out.write(langIdx)

        val vocab = DICTIONARY_PRESETS[lang] ?: DICTIONARY_PRESETS["en"]!!
        for (token in tokens) {
            val idx = vocab.indexOf(token)
            if (idx in 0..127) {
                out.write(0x80 or (idx and 0x7F))
            } else {
                val raw = token.toByteArray(Charsets.UTF_8)
                require(raw.size <= 127) { "Token exceeds max byte length of 127: $token" }
                out.write(raw.size)
                out.write(raw)
            }
        }
        return out.toByteArray()
    }

    override fun compress(text: String, language: Language): ByteArray =
        compress(text, language.code)

    override fun decompress(payload: ByteArray): Pair<String, String> {
        val result = decompressResult(payload)
        return Pair(result.text, result.language.code)
    }

    override fun decompressResult(payload: ByteArray): DecompressedResult {
        if (payload.isEmpty()) {
            return DecompressedResult("", Language.ENGLISH)
        }

        val langIdx = payload[0].toInt() and 0xFF
        val langCode = if (langIdx < INDIC_LANGUAGES.size) INDIC_LANGUAGES[langIdx] else "en"
        val language = Language.fromCode(langCode)
        val vocab = DICTIONARY_PRESETS[langCode] ?: DICTIONARY_PRESETS["en"]!!

        val tokens = mutableListOf<String>()
        var i = 1
        while (i < payload.size) {
            val byte = payload[i].toInt() and 0xFF
            if ((byte and 0x80) != 0) {
                val idx = byte and 0x7F
                tokens.add(if (idx < vocab.size) vocab[idx] else "<unk_$idx>")
                i += 1
            } else {
                val tokenLen = byte
                i += 1
                if (i + tokenLen > payload.size) {
                    throw IllegalArgumentException("Truncated compressed token stream")
                }
                val tokenBytes = ByteArray(tokenLen)
                System.arraycopy(payload, i, tokenBytes, 0, tokenLen)
                tokens.add(String(tokenBytes, Charsets.UTF_8))
                i += tokenLen
            }
        }

        return DecompressedResult(tokens.joinToString(" "), language)
    }
}
