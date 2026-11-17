# iTantra — Speech Model Licensing, Provenance & File Specification
**Document ID:** SPEC-MODEL-LICENSING-001  
**Target Languages (10):** Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English  
**Execution Runtime:** On-Device ONNX Runtime Mobile / sherpa-onnx (100% Offline, Zero Cloud)  

---

## 1. Licensing Philosophy & Legal Compliance

In compliance with the SIH26173 specification:
- **No Proprietary or Online Cloud APIs:** Google Cloud Speech, AWS Transcribe, Azure Cognitive Services, and OpenAI APIs are strictly excluded.
- **Strict License Audit:** Every model checkpoint, dataset, and acoustic weight must have its license verified individually. Non-commercial (CC-BY-NC) models must never be mislabeled as permissive open source (MIT/Apache-2.0).
- **Offline Self-Containment:** Once model packs are placed on device storage, no network connection is ever required or attempted.

---

## 2. Core Speech Runtimes & Framework Licenses

| Component | Repository / Author | License | Deployment Feasibility |
|---|---|---|---|
| **sherpa-onnx** | Next-gen Kaldi (`k2-fsa/sherpa-onnx`) | **Apache 2.0** | Native Android C++ JNI bindings; offline CPU execution |
| **ONNX Runtime Mobile** | Microsoft (`microsoft/onnxruntime`) | **MIT** | Highly optimized mobile inference engine |
| **Silero VAD** | Silero Team (`snakers4/silero-vad`) | **MIT** | Ultra-lightweight (1.8 MB), highly accurate offline VAD |

---

## 3. Language Model Specifications (The 10 Target Languages)

### 3.1 Speech-to-Text (STT) — AI4Bharat IndicConformer Checkpoints

The recommended offline STT models are based on the **AI4Bharat IndicConformer** family, exported to ONNX format (float32 and int8 quantized) for mobile deployment via sherpa-onnx.

| Language | ISO Code | Script | Base Architecture | Training Corpus | Checkpoint License | Model Size (Int8) |
|---|---|---|---|---|---|---|
| **Hindi** | `hi` | Devanagari | Conformer-CTC (120M) | IndicSUPERB + Shrutilipi | **MIT** | ~78 MB |
| **English** | `en` | Latin | Conformer-CTC (120M) | LibriSpeech + GigaSpeech | **Apache 2.0** | ~75 MB |
| **Gujarati** | `gu` | Gujarati | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Marathi** | `mr` | Devanagari | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Bengali** | `bn` | Bengali | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Odia** | `or` | Odia | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Tamil** | `ta` | Tamil | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Telugu** | `te` | Telugu | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Kannada** | `kn` | Kannada | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |
| **Malayalam** | `ml` | Malayalam | Conformer-CTC (120M) | IndicSUPERB | **MIT** | ~78 MB |

---

### 3.2 Text-to-Speech (TTS) — AI4Bharat Indic-TTS / VITS Checkpoints

Offline speech synthesis uses single-speaker or multi-speaker **VITS / FastPitch + HiFi-GAN** ONNX checkpoints trained on native Indic language corpora.

| Language | Model Architecture | Voice Gender | Upstream Source | License | Model Size (Int8) |
|---|---|---|---|---|---|
| **Hindi** | VITS ONNX | Female / Male | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **English (Indian)**| VITS ONNX | Female | Piper / VITS | **MIT** | ~42 MB |
| **Gujarati** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Marathi** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Bengali** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Odia** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Tamil** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Telugu** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Kannada** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |
| **Malayalam** | VITS ONNX | Female | AI4Bharat Indic-TTS | **MIT** | ~45 MB |

---

## 4. Expected File Structure on Device

Models are stored in the app's private external/internal storage directory:
`Android/data/com.itantra/files/models/`

```
models/
├── common/
│   ├── silero_vad.onnx                (Silero VAD model, 1.8 MB, SHA256 verified)
│   └── vad_license.txt
├── hi/                                (Hindi Language Pack)
│   ├── metadata.json                  (Version, license, author, checksums)
│   ├── LICENSE.txt                    (Full MIT license text)
│   ├── stt/
│   │   ├── model.int8.onnx            (Acoustic Conformer model)
│   │   ├── tokens.txt                 (Devanagari BPE token vocabulary)
│   │   └── lexicon.txt
│   └── tts/
│       ├── model.int8.onnx            (VITS synthesis model)
│       ├── tokens.txt
│       └── espeak-ng-data/
├── en/                                (English Language Pack)
│   ├── metadata.json
│   ├── LICENSE.txt
│   ├── stt/
│   │   ├── model.int8.onnx
│   │   └── tokens.txt
│   └── tts/
│       ├── model.int8.onnx
│       └── tokens.txt
└── ... (other languages)
```

---

## 5. Model Pack Manifest Format (`metadata.json`)

Each language pack folder must contain a `metadata.json` describing its exact provenance and cryptographic hash:

```json
{
  "packVersion": "1.0.0",
  "languageCode": "hi",
  "languageName": "Hindi",
  "stt": {
    "engine": "sherpa-onnx-conformer",
    "modelFile": "model.int8.onnx",
    "tokenFile": "tokens.txt",
    "sizeBytes": 81788928,
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "license": "MIT",
    "sourceUrl": "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx"
  },
  "tts": {
    "engine": "vits",
    "modelFile": "model.int8.onnx",
    "tokenFile": "tokens.txt",
    "sizeBytes": 47185920,
    "sha256": "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
    "license": "MIT",
    "sourceUrl": "https://github.com/AI4Bharat/Indic-TTS"
  }
}
```

---

## 6. Fallback Behavior

If no speech model pack is installed for a given language:
1. The app gracefully falls back to **Typed Chat Mode**.
2. Outbound text can still be typed, encrypted, transmitted over BLE/LoRa, decrypted, and displayed in the chat interface.
3. Inbound text is rendered clearly in its native script. A visual notice indicates `TTS Model Not Installed` instead of crashing.
4. The Language & Model Manager screen displays a direct button to import verified offline `.zip` language packs into the application storage directory.
