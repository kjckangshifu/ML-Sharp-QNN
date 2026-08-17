<div align="center">
  <img src="docs/icon.png" width="120" alt="SHARP QNN 图标" />
</div>

# SHARP QNN

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B-green)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/Platform-Snapdragon-blue)](https://www.qualcomm.com)

[English](README.md)

**SHARP QNN** 是一款 Android 应用，将 [Apple SHARP](https://github.com/apple/ml-sharp) 的单图 3D 高斯泼溅（3D Gaussian Splatting）移植到搭载骁龙处理器的智能手机上。全部推理流水线在 Qualcomm QNN HTP（Hexagon Tensor Processor）DSP 上**端侧离线**运行。

> 本项目将 SHARP 模型移植到 Android 端侧：
> *Sharp Monocular View Synthesis in Less Than a Second*  
> [arXiv:2512.10685](https://arxiv.org/abs/2512.10685)

---

<div align="center">
  <img src="docs/screenshots/screen1.jpg" width="30%" alt="模型推理" />
  <img src="docs/screenshots/screen2.jpg" width="30%" alt="模型管理" />
  <img src="docs/screenshots/screen3.jpg" width="30%" alt="设置" />
</div>

---

## 功能特性

- **完全离线** — 无需云端、无需 API，一切在 Hexagon DSP 上运行
- **单张照片 → 3D 高斯泼溅** — 选一张照片，得到一个 `.ply` 文件
- **多精度模型支持** — 可导入不同量化级别的 DLC 模型
- **一键下载模型** — 从 HuggingFace 下载预转换的 DLC 模型（国内用户可切换 HF-Mirror）
- **EXIF 感知** — 从照片元数据读取焦距，实现精确深度估计
- **双语界面** — 中文 / 英文，运行时随时切换
- **MD3 设计** — 遵循 Material Design 3 设计规范

---

## 架构

```
┌─────────────────────────────────────┐
│  Kotlin / Jetpack Compose (界面层)   │
│  ├─ ModelsScreen   (模型管理)        │
│  ├─ SettingsScreen (偏好设置)        │
│  └─ PipelineScreen (推理执行)        │
├─────────────────────────────────────┤
│  JNI 桥接 (sharp_jni.cpp)           │
├─────────────────────────────────────┤
│  QNN 运行时 (C++)                    │
│  ├─ qnn_runtime.cpp   (HTP 推理)    │
│  ├─ qnn_dlc_compiler  (模型优化)    │
│  └─ qnn_tensor.cpp    (张量管理)    │
├─────────────────────────────────────┤
│  SHARP 核心 (C, 从 Apple 移植)       │
│  ├─ prep_input      (图像预处理)     │
│  ├─ split_patches   (切分为35块)     │
│  ├─ merge_patches   (合并结果)       │
│  ├─ depth_from_disparity (视差→深度) │
│  ├─ composer        (高斯生成)       │
│  └─ save_ply        (PLY 导出)       │
├─────────────────────────────────────┤
│  Qualcomm Hexagon DSP (HTP)         │
│  QNN SDK 2.48.0                     │
└─────────────────────────────────────┘
```

---

## 环境要求

### 硬件

- 搭载 Hexagon DSP 的骁龙设备（骁龙 8 Gen 1 或更新）
- Android 12+ (API 31+)
- ARM64-v8a 架构

> **注意**: 目前仅在 HTP v79（如骁龙 8 Elite）设备上测试通过，不保证其他 HTP 版本（v68/v69/v73/v75/v81）的设备能完美运行。

### 软件

| 工具 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | Kotlin 编译所需 |
| Android SDK command-line tools | latest | 基础工具包，仅提供 `sdkmanager`，[下载](https://developer.android.com/studio#command-line-tools-only) |
| SDK Platform | android-35 | 通过 `sdkmanager "platforms;android-35"` 安装 |
| Build Tools | 35.0.0 | 通过 `sdkmanager "build-tools;35.0.0"` 安装 |
| NDK | 29.0.14206865 | 通过 `sdkmanager "ndk;29.0.14206865"` 安装 |
| CMake | 3.22.1+ | 通过 `sdkmanager "cmake;3.22.1"` 安装 |
| Qualcomm QNN SDK | 2.48.0 | [下载](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.48.0.260626/v2.48.0.260626.zip) |

---

## 构建

1. **克隆仓库**

    ```bash
    git clone https://github.com/kjckangshifu/ML-Sharp-QNN.git
    cd ML-Sharp-QNN
    ```

2. **配置 QNN SDK**

    在项目根目录创建 `local.properties`：

    ```properties
    sdk.dir=/path/to/Android/Sdk
    ndk.dir=/path/to/android-ndk-r29
    qnn.sdk.dir=/path/to/qnn-sdk-2.48.0
    ```

3. **复制 QNN 库**

    ```bash
    ./gradlew copyQnnLibs
    ./gradlew copyQnnSkel
    ```

4. **构建**

    ```bash
    ./gradlew assembleRelease
    ```

    APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

---

## 模型下载

预转换的 DLC 模型托管在 HF-Mirror（镜像）：

- **仓库**: 🤗 [kjcpc/ML-Sharp-QNN](https://hf-mirror.com/kjcpc/ML-Sharp-QNN)
- **精度**: W8A16（权重 8 位，激活 16 位）
- **文件**: 5 个 DLC 文件（共约 650 MB）

可以直接在 App 的 **模型** 页面一键下载，或手动下载：

```bash
HF_ENDPOINT=https://hf-mirror.com huggingface-cli download kjcpc/ML-Sharp-QNN dlc/w8a16/ --local-dir ./dlc
```

海外用户可在设置中切换至 **HuggingFace**（huggingface.co）原始下载源。

---

## 模型转换管线

`build_rest_pipeline.py` 是端到端模型转换管线，将原始 SHARP PyTorch 权重转换为 Android App 可加载的 DLC 文件。

### 总览

```
PyTorch (.pt)  ──→  ONNX  ──→  rest 拆分  ──→  校准  ──→  DLC (.dlc)
```

| 阶段 | 说明 |
|---|---|
| **ONNX** | 将 PyTorch 权重导出为 ONNX（5 个模型：pe, ie, rest） |
| **拆分** | 将单块 `rest` 模型拆为 3 段（rest_a, rest_b, rest_c），适配 DSP 内存 |
| **校准** | 从样本图片生成量化校准数据 |
| **DLC** | 使用 QNN SDK 工具：ONNX → FP32 DLC → 量化 DLC |

### 用法

```bash
python build_rest_pipeline.py [选项]
```

### 选项

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-t, --task` | `dlc` | 任务：`onnx`（导出ONNX）/ `dlc`（完整管线到DLC）/ `calib`（仅校准） |
| `-a, --scope` | `all` | 模型范围：`all` / `pe` / `ie` / `rest`（含三段拆分） |
| `-o, --out` | `output/` | 输出根目录 |
| `-f, --format` | `w8a16` | 量化格式：`int16` / `int8` / `w8a16` |
| `--sdk` | (自动探测) | QNN SDK 根目录路径 |
| `-i, --img_dir` | `data/` | 校准图片目录 |
| `-n, --n_calib` | `20` | 校准图片数量 |

### 快速示例

```bash
# 完整管线：ONNX → 拆分 → 校准 → DLC (w8a16)
python build_rest_pipeline.py -t dlc

# 仅导出 ONNX
python build_rest_pipeline.py -t onnx

# 仅生成校准数据
python build_rest_pipeline.py -t calib -i ./my_images/ -n 30

# 仅转换 pe（图块编码器）和 ie（图像编码器）
python build_rest_pipeline.py -t dlc -a pe -a ie

# 导出 int8 量化
python build_rest_pipeline.py -t dlc -f int8

# 自定义 QNN SDK 路径和输出目录
python build_rest_pipeline.py --sdk /opt/qnn-sdk-2.48.0 -o ./build_out
```

### 输出结构

```
output/
├── onnx/                  # 中间 ONNX 文件
│   ├── pe.onnx
│   ├── ie.onnx
│   ├── rest_a.onnx
│   ├── rest_b.onnx
│   └── rest_c.onnx
├── calib/                 # 校准数据（raw + 输入列表）
│   ├── pe/
│   ├── ie/
│   ├── rest_a/
│   ├── rest_b/
│   └── rest_c/
└── dlc/
    ├── fp32/              # 未量化 DLC（中间产物）
    │   ├── pe.dlc
    │   ├── ie.dlc
    │   ├── rest_a.dlc
    │   ├── rest_b.dlc
    │   └── rest_c.dlc
    └── w8a16/             # 量化 DLC（可上传发布）
        ├── pe.dlc
        ├── ie.dlc
        ├── rest_a.dlc
        ├── rest_b.dlc
        └── rest_c.dlc
```

### 环境要求

- Python 3.9+
- PyTorch（需能访问原始 SHARP 代码库）
- `onnx`, `onnx-simplifier`
- QNN SDK 2.48.0（`qairt-converter` 和 `qairt-quantizer` 需在 PATH 中）
- 10+ GB 可用磁盘空间（converter 临时文件需要）

将 `dlc/w8a16/` 下的文件上传至 HuggingFace 即可分发。

---

## 依赖

| 依赖 | 许可证 | 用途 |
|---|---|---|
| AndroidX (Compose, Lifecycle, Navigation, DataStore) | Apache 2.0 | 界面与架构 |
| Kotlin | Apache 2.0 | 开发语言 |
| stb_image v2.30 | Public Domain | JPEG/PNG 解码 |
| Qualcomm QNN SDK | Proprietary | HTP DSP 推理 |
| Apple SHARP | AML-R | 原始研究代码 |

---

## 许可

本项目采用 **MIT 许可证** — 详见 [LICENSE](LICENSE)。

第三方组件：
- `stb_image.h` — Public Domain (Sean Barrett)
- Apple SHARP — [AML-R](https://github.com/apple/ml-sharp/blob/main/LICENSE)
- Qualcomm QNN SDK — 专有许可（不随本项目分发）

---

## 致谢

- [Apple SHARP](https://github.com/apple/ml-sharp) — 原始研究与代码
- [stb](https://github.com/nothings/stb) — Sean Barrett 的单文件公共领域库
- [Qualcomm AI Engine Direct SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk) — QNN HTP 运行时

---

## 引用

如果你在研究中使用本项目，请引用原始 SHARP 论文：

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
