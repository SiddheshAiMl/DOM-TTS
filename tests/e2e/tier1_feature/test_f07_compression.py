"""
Tier 1: Feature F07 Multilingual Semantic Compression Engine Tests.
Conforms strictly to TEST_INFRA.md specifications.
"""

from tests.e2e.runner.test_harness import (
    SemanticCompressionEngine, LoRaPacket, MessageType, PacketFlag
)


# ==============================================================================
# F07: Multilingual Semantic Compression Engine
# ==============================================================================

def test_t1_f07_01_hindi_compression_under_60_bytes():
    """T1_F07_01: Compress Hindi utterance -> verify payload < 60 bytes."""
    hindi_text = "नमस्ते तुरंत बेस कैंप पर रिपोर्ट करो"
    compressed = SemanticCompressionEngine.compress(hindi_text, lang="hi")
    assert len(compressed) < 60, f"Compressed size {len(compressed)} bytes is not < 60 bytes"
    assert len(compressed) > 0, "Compressed payload is empty"


def test_t1_f07_02_bengali_compression_ratio():
    """T1_F07_02: Compress Bengali utterance -> verify compression ratio > 2.5:1."""
    bengali_text = "নমস্কার আমরা নিরাপদ স্থানে আছি"
    raw_utf8_len = len(bengali_text.encode("utf-8"))
    compressed = SemanticCompressionEngine.compress(bengali_text, lang="bn")
    ratio = raw_utf8_len / len(compressed)
    assert ratio > 2.5, f"Compression ratio {ratio:.2f}:1 is not > 2.5:1 (raw {raw_utf8_len}B vs comp {len(compressed)}B)"


def test_t1_f07_03_tamil_roundtrip_lossless():
    """T1_F07_03: Compress Tamil utterance -> verify roundtrip lossless decompress."""
    tamil_text = "வணக்கம் உதவி தேவைப்படுகிறது"
    compressed = SemanticCompressionEngine.compress(tamil_text, lang="ta")
    decompressed, lang = SemanticCompressionEngine.decompress(compressed)
    assert lang == "ta", f"Expected language 'ta', got '{lang}'"
    assert decompressed == tamil_text, f"Roundtrip mismatch: '{decompressed}' != '{tamil_text}'"


def test_t1_f07_04_telugu_header_flag():
    """T1_F07_04: Compress Telugu utterance -> verify compression flag set in header."""
    telugu_text = "నమస్కారం రవాణా సిద్ధంగా ఉంది"
    compressed = SemanticCompressionEngine.compress(telugu_text, lang="te")
    pkt = LoRaPacket(
        flags=PacketFlag.FLAG_COMPRESSED | PacketFlag.FLAG_NEEDS_ACK,
        ciphertext=compressed
    )
    assert pkt.is_compressed, "PacketFlag.FLAG_COMPRESSED bit was not set"
    frame = pkt.pack_frame()
    unpacked = LoRaPacket.unpack_frame(frame)
    assert unpacked.is_compressed, "De-serialized packet lost FLAG_COMPRESSED flag"


def test_t1_f07_05_all_10_indic_languages_clean_roundtrip():
    """T1_F07_05: Validate all 10 Indic languages compress and decompress cleanly."""
    samples = {
        "hi": "नमस्ते मदद चाहिए",
        "bn": "নমস্কার জল ওষুধ",
        "mr": "नमस्कार तात्काळ मदत हवी",
        "te": "నమస్కారం సహాయం కావాలి",
        "ta": "வணக்கம் உதவி தேவைப்படுகிறது",
        "gu": "નમસ્તે અમને મદદની જરૂર છે",
        "kn": "ನಮಸ್ಕಾರ ತುರ್ತು ಸಹಾಯ",
        "ml": "നമസ്കാരം സഹായം ആവശ്യമാണ്",
        "or": "ନମସ୍କାର ଆମେ ସୁରକ୍ଷିତ ଅଛୁ",
        "en": "hello immediate help needed"
    }
    for lang, text in samples.items():
        compressed = SemanticCompressionEngine.compress(text, lang=lang)
        decompressed, detected_lang = SemanticCompressionEngine.decompress(compressed)
        assert detected_lang == lang, f"Language mismatch for {lang}: got {detected_lang}"
        assert decompressed == text, f"Decompression mismatch for {lang}: '{decompressed}' != '{text}'"
