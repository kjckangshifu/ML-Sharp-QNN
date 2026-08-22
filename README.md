<div align="center">
  <img src="docs/icon.png" width="120" alt="SHARP QNN Icon" />
</div>

# SHARP QNN

[![License: GPL 3.0](https://img.shields.io/badge/License-GPL%203.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B-green)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/Platform-Snapdragon-blue)](https://www.qualcomm.com)

[中文版本](README_zh.md)

**SHARP QNN** is an Android app that brings [Apple's SHARP](https://github.com/apple/ml-sharp) — single-image 3D Gaussian Splatting — to Snapdragon-powered smartphones. It runs the full inference pipeline **on-device, offline** using the Qualcomm QNN HTP (Hexagon Tensor Processor) DSP.

> This project ports the SHARP model to on-device Android:
> *Sharp Monocular View Synthesis in Less Than a Second*  
> [arXiv:2512.10685](https://arxiv.org/abs/2512.10685)

---

<div align="center">
  <img src="docs/screenshots/screen1.jpg" width="30%" alt="Inference" />
  <img src="docs/screenshots/screen2.jpg" width="30%" alt="Models" />
  <img src="docs/screenshots/screen3.jpg" width="30%" alt="Settings" />
</div>

---

## Features

- **Fully offline** — no cloud, no API calls, everything runs on the Hexagon DSP
- **Single image → 3D Gaussian Splat** — pick a photo, get a `.ply` file
- **Multi-precision model support** — import DLC models of different quantization levels
- **One-tap model download** — download pre-converted DLC models from HuggingFace (or HF-Mirror for users in China)
- **EXIF-aware** — reads focal length from image metadata for accurate depth estimation
- **Bilingual UI** — Chinese & English, switchable at runtime
- **MD3 design** — follows Material Design 3 guidelines

---

## Architecture

```
┌─────────────────────────────────────┐
│  Kotlin / Jetpack Compose (UI)      │
│  ├─ ModelsScreen   (model manager)  │
│  ├─ SettingsScreen (preferences)    │
│  └─ PipelineScreen (inference)      │
├─────────────────────────────────────┤
│  JNI Bridge (sharp_jni.cpp)         │
├─────────────────────────────────────┤
│  QNN Runtime (C++)                  │
│  ├─ qnn_runtime.cpp   (HTP infer)   │
│  ├─ qnn_dlc_compiler  (model opt)   │
│  └─ qnn_tensor.cpp    (tensor mgr)  │
├─────────────────────────────────────┤
│  SHARP Core (C, ported from Apple)  │
│  ├─ prep_input      (image preproc) │
│  ├─ split_patches   (35 patches)    │
│  ├─ merge_patches   (merge results) │
│  ├─ depth_from_disparity            │
│  ├─ composer        (Gaussian gen)  │
│  └─ save_ply        (PLY export)    │
├─────────────────────────────────────┤
│  Qualcomm Hexagon DSP (HTP)         │
│  QNN SDK 2.48.0                     │
└─────────────────────────────────────┘
```

---

## Prerequisites

### Hardware

- Snapdragon device with Hexagon DSP (SD 8 Gen 2 or newer)
- Android 12+ (API 31+)
- ARM64-v8a architecture

> **Note**: For Snapdragon 8 Gen 2 and above (HTP v73+). Tested on HTP v79 (Snapdragon 8 Elite).

### Software

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Required for Kotlin compilation |
| Android SDK command-line tools | latest | Base package, provides `sdkmanager` only, [Download](https://developer.android.com/studio#command-line-tools-only) |
| SDK Platform | android-35 | Install via `sdkmanager "platforms;android-35"` |
| Build Tools | 35.0.0 | Install via `sdkmanager "build-tools;35.0.0"` |
| NDK | 29.0.14206865 | Install via `sdkmanager "ndk;29.0.14206865"` |
| CMake | 3.22.1+ | Install via `sdkmanager "cmake;3.22.1"` |
| Qualcomm QNN SDK | 2.48.0 | [Download](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.48.0.260626/v2.48.0.260626.zip) |

---

## Build

1. **Clone the repository**

    ```bash
    git clone https://github.com/kjckangshifu/ML-Sharp-QNN.git
    cd ML-Sharp-QNN
    ```

2. **Set up QNN SDK**

    Create `local.properties` in the project root:

    ```properties
    sdk.dir=/path/to/Android/Sdk
    ndk.dir=/path/to/android-ndk-r29
    qnn.sdk.dir=/path/to/qnn-sdk-2.48.0
    ```

3. **Copy QNN libraries**

    ```bash
    ./gradlew copyQnnLibs
    ./gradlew copyQnnSkel
    ```

4. **Build**

    ```bash
    ./gradlew assembleRelease
    ```

    APK output: `app/build/outputs/apk/release/app-release.apk`

---

## Model Download

Pre-converted DLC models are available on HuggingFace:

- **Repository**: 🤗 [kjcpc/ML-Sharp-QNN](https://huggingface.co/kjcpc/ML-Sharp-QNN)
- **Precision**: W8A16 (weights: 8-bit, activations: 16-bit)
- **Files**: 5 DLC files (~650 MB total)

You can download them directly in the app via the **Models** page, or manually:

```bash
hf download kjcpc/ML-Sharp-QNN dlc/w8a16/ --local-dir ./dlc
```

For users in China, the app supports **HF-Mirror** (hf-mirror.com) as an alternative download source. Switch it in Settings.

---

## Model Conversion Pipeline

`build_rest_pipeline.py` is the end-to-end model conversion pipeline. It converts the original SHARP PyTorch checkpoint into DLC files that the Android app can load.

### Overview

```
PyTorch (.pt)  ──→  ONNX  ──→  rest split  ──→  calibration  ──→  DLC (.dlc)
```

| Stage | Description |
|---|---|
| **ONNX** | Export the PyTorch checkpoint to ONNX (5 models: pe, ie, rest) |
| **Split** | Split the monolithic `rest` model into 3 segments (rest_a, rest_b, rest_c) for DSP memory |
| **Calibration** | Generate calibration data from sample images for quantization |
| **DLC** | Convert ONNX → FP32 DLC → Quantized DLC using QNN SDK tools |

### Usage

```bash
python build_rest_pipeline.py [OPTIONS]
```

### Options

| Flag | Default | Description |
|---|---|---|
| `-t, --task` | `dlc` | Task: `onnx` (export ONNX) / `dlc` (full pipeline to DLC) / `calib` (calibration only) |
| `-a, --scope` | `all` | Models: `all` / `pe` / `ie` / `rest` (with 3-seg split) |
| `-o, --out` | `output/` | Output root directory |
| `-f, --format` | `w8a16` | Quantization: `int16` / `int8` / `w8a16` |
| `--sdk` | (auto-detect) | QNN SDK root path |
| `-i, --img_dir` | `data/` | Calibration image directory |
| `-n, --n_calib` | `20` | Number of calibration images |

### Quick Examples

```bash
# Full pipeline: ONNX → split → calibrate → DLC (w8a16)
python build_rest_pipeline.py -t dlc

# Only export ONNX models
python build_rest_pipeline.py -t onnx

# Only generate calibration data
python build_rest_pipeline.py -t calib -i ./my_images/ -n 30

# Convert only pe (patch encoder) and ie (image encoder)
python build_rest_pipeline.py -t dlc -a pe -a ie

# Export with int8 quantization
python build_rest_pipeline.py -t dlc -f int8

# Custom QNN SDK path and output directory
python build_rest_pipeline.py --sdk /opt/qnn-sdk-2.48.0 -o ./build_out
```

### Output Structure

```
output/
├── onnx/                  # Intermediate ONNX files
│   ├── pe.onnx
│   ├── ie.onnx
│   ├── rest_a.onnx
│   ├── rest_b.onnx
│   └── rest_c.onnx
├── calib/                 # Calibration data (raw + input lists)
│   ├── pe/
│   ├── ie/
│   ├── rest_a/
│   ├── rest_b/
│   └── rest_c/
└── dlc/
    ├── fp32/              # Unquantized DLC (intermediate)
    │   ├── pe.dlc
    │   ├── ie.dlc
    │   ├── rest_a.dlc
    │   ├── rest_b.dlc
    │   └── rest_c.dlc
    └── w8a16/             # Quantized DLC (ready for upload)
        ├── pe.dlc
        ├── ie.dlc
        ├── rest_a.dlc
        ├── rest_b.dlc
        └── rest_c.dlc
```

### Requirements

- Python 3.9+
- PyTorch (with the original SHARP codebase accessible)
- `onnx`, `onnx-simplifier`
- QNN SDK 2.48.0 (with `qairt-converter` and `qairt-quantizer` in PATH)
- 10+ GB free disk space (for converter temp files)

Upload the `dlc/w8a16/` files to HuggingFace for distribution.

---

## Dependencies

| Dependency | License | Usage |
|---|---|---|
| AndroidX (Compose, Lifecycle, Navigation, DataStore) | Apache 2.0 | UI & architecture |
| Kotlin | Apache 2.0 | Language |
| stb_image v2.30 | Public Domain | JPEG/PNG decoding |
| Qualcomm QNN SDK | Proprietary | HTP DSP inference |
| Apple SHARP | AML-R | Original research codebase |
| [GaussSimplify](https://github.com/3dgscloud/GaussSimplify) | GPL 3.0 | 3D Gaussian simplification |
| [GaussForge](https://github.com/3dgscloud/GaussForge) | Apache 2.0 | Gaussian Splat I/O data types |

---

## License

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for details.

Third-party components:
- `stb_image.h` — Public Domain (Sean Barrett)
- Apple SHARP — [AML-R](https://github.com/apple/ml-sharp/blob/main/LICENSE)
- Qualcomm QNN SDK — Proprietary (not distributed with this project)
- GaussSimplify — [GPL 3.0](https://github.com/3dgscloud/GaussSimplify/blob/main/LICENSE)
- GaussForge — [Apache 2.0](https://github.com/3dgscloud/GaussForge/blob/main/LICENSE)

---

## Acknowledgements

- [Apple SHARP](https://github.com/apple/ml-sharp) — the original research and codebase
- [stb](https://github.com/nothings/stb) — public domain single-file libraries by Sean Barrett
- [Qualcomm AI Engine Direct SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk) — QNN HTP runtime
- [GaussSimplify](https://github.com/3dgscloud/GaussSimplify) — 3D Gaussian simplification library
- [GaussForge](https://github.com/3dgscloud/GaussForge) — Gaussian Splat I/O library

---

## Citation

If you use this project in your research, please cite the original SHARP paper:

```bibtex
@inproceedings{Sharp2025:arxiv,
  title      = {Sharp Monocular View Synthesis in Less Than a Second},
  author     = {Lars Mescheder and Wei Dong and Shiwei Li and Xuyang Bai and Marcel Santos
                and Peiyun Hu and Bruno Lecouat and Mingmin Zhen and Ama\"{e}l Delaunoy
                and Tian Fang and Yanghai Tsin and Stephan R. Richter and Vladlen Koltun},
  journal    = {arXiv preprint arXiv:2512.10685},
  year       = {2025},
  url        = {https://arxiv.org/abs/2512.10685},
}
```