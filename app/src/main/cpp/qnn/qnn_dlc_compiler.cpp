// qnn_dlc_compiler.cpp — on-device DLC compiler implementation
// Compiles a DLC file into a context binary (.bin)
#include "qnn_dlc_compiler.h"

#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>
#include <chrono>

#include "QnnInterface.h"
#include "QnnContext.h"
#include "QnnBackend.h"
#include "QnnDevice.h"
#include "QnnLog.h"
#include "QnnTypes.h"
#include "QnnError.h"
#include "QnnGraph.h"
#include "QnnHtpGraph.h"
#include "System/QnnSystemContext.h"
#include "System/QnnSystemDlc.h"
#include "System/QnnSystemInterface.h"

#include <android/log.h>

namespace qnn {

#define LOG_TAG "QNN-DLC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Return value when the compile is cancelled (distinct from real error codes)
static const int COMPILE_CANCELLED = -100;

// Frees the graphInfos memory allocated by systemDlcComposeGraphs (handles V1/V2/V3)
static void freeGraphInfos(QnnSystemContext_GraphInfo_t* graphInfos, uint32_t numGraphs) {
    if (!graphInfos) return;
    for (uint32_t j = 0; j < numGraphs; j++) {
        if (graphInfos[j].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
            free(const_cast<char*>(graphInfos[j].graphInfoV1.graphName));
            free(graphInfos[j].graphInfoV1.graphInputs);
            free(graphInfos[j].graphInfoV1.graphOutputs);
        } else if (graphInfos[j].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
            free(const_cast<char*>(graphInfos[j].graphInfoV2.graphName));
            free(graphInfos[j].graphInfoV2.graphInputs);
            free(graphInfos[j].graphInfoV2.graphOutputs);
        } else if (graphInfos[j].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
            free(const_cast<char*>(graphInfos[j].graphInfoV3.graphName));
            free(graphInfos[j].graphInfoV3.graphInputs);
            free(graphInfos[j].graphInfoV3.graphOutputs);
        }
    }
    free(graphInfos);
}

// 析构 ==============
// ============== Construction / destruction ==============

DlcCompiler::DlcCompiler() : m_dlcHandle(nullptr), m_sysInterfaceValid(false),
                             m_cancelRequested(false) {}

DlcCompiler::~DlcCompiler() {
    // If compile failed or did not finish normally, m_dlcHandle may still be held
    // release it via the saved system interface copy, independent of runtime lifetime (avoids dangling pointers)
    if (m_dlcHandle && m_sysInterfaceValid) {
        m_sysInterface.QNN_SYSTEM_INTERFACE_VER_NAME.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        LOGI("DLC handle released in destructor");
    }
}

// ==============  ==============
// ============== compile: compile a DLC into a context binary ==============

int DlcCompiler::compile(HtpRuntime& runtime, const std::string& dlcPath,
                          const std::string& outBinPath, ProgressCallback progressCb) {
    if (!runtime.isReady()) {
        LOGE("Runtime not ready");
        return -1;
    }

    // Reset the cancellation flag before starting
    m_cancelRequested.store(false, std::memory_order_relaxed);

    // Save a copy of the system interface (so the destructor can free m_dlcHandle in abnormal cases)
    m_sysInterface = runtime.m_shared->qnnSystemInterface;
    m_sysInterfaceValid = true;

    // Access HtpRuntime internals via friend (shared state)
    auto& qnn = runtime.m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;
    auto& sys = runtime.m_shared->qnnSystemInterface.QNN_SYSTEM_INTERFACE_VER_NAME;
    Qnn_BackendHandle_t backend = runtime.m_shared->backendHandle;
    Qnn_DeviceHandle_t device = runtime.m_shared->deviceHandle;

    // Unified cleanup on cancel: release resources (the output file is only written after every cancel
    // check point, so deleting outBinPath here would only remove the previous build's artifact, so leave the file alone)
    auto cleanupOnCancel = [&](Qnn_ContextHandle_t context) -> int {
        if (m_dlcHandle) {
            sys.systemDlcFree(m_dlcHandle);
            m_dlcHandle = nullptr;
        }
        if (context) qnn.contextFree(context, nullptr);
        LOGI("DLC compile cancelled");
        return COMPILE_CANCELLED;
    };

    auto startTime = std::chrono::steady_clock::now();
    auto elapsedMs = [&]() -> long {
        auto now = std::chrono::steady_clock::now();
        return static_cast<long>(
            std::chrono::duration_cast<std::chrono::milliseconds>(now - startTime).count());
    };

    // 1. 创建新 context 用于 DLC 编译 (不复用 runtime 的推理 context)
    // 1. Create a fresh context for DLC compilation (the runtime's inference context is not reused)
    Qnn_ContextHandle_t context = nullptr;
    if (progressCb) progressCb(0, 1, 0, "Creating context");
    if (QNN_SUCCESS != qnn.contextCreate(backend, device, nullptr, &context)) {
        LOGE("Failed to create context for DLC compilation");
        return -2;
    }

    // Cancellation check
    if (m_cancelRequested.load()) return cleanupOnCancel(context);

    // 2. systemDlcCreateFromFile: 加载 DLC 文件
    // 2. systemDlcCreateFromFile: load the DLC file
    if (progressCb) progressCb(0, 1, elapsedMs(), "Loading DLC file");
    if (QNN_SUCCESS != sys.systemDlcCreateFromFile(
            runtime.m_shared->logHandle, dlcPath.c_str(), &m_dlcHandle)) {
        LOGE("Failed to create DLC handle from file: %s", dlcPath.c_str());
        qnn.contextFree(context, nullptr);
        return -3;
    }

    // Cancellation check
    if (m_cancelRequested.load()) return cleanupOnCancel(context);

    // 3. systemDlcComposeGraphs: 单次 compose (不带 graphConfigs, 官方 SampleApp 同款)
    // 3. systemDlcComposeGraphs: single compose (no graphConfigs, same as the official SampleApp)
    //    the returned graphInfos carry the graph names; HTP optimization config is pushed later via graphSetConfig
    if (progressCb) progressCb(0, 1, elapsedMs(), "Composing graphs from DLC");

    QnnSystemContext_GraphInfo_t* graphInfos = nullptr;
    uint32_t numGraphs = 0;
    if (QNN_SUCCESS != sys.systemDlcComposeGraphs(
            m_dlcHandle,
            nullptr,
            0,
            backend,
            context,
            runtime.m_shared->qnnInterface,
            QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3,
            &graphInfos,
            &numGraphs)) {
        LOGE("Failed to compose graphs from DLC");
        sys.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        qnn.contextFree(context, nullptr);
        return -4;
    }

    LOGI("Composed %u graphs from DLC", numGraphs);

    // Cancellation check
    if (m_cancelRequested.load()) {
        freeGraphInfos(graphInfos, numGraphs);
        return cleanupOnCancel(context);
    }

    // HTP graph compilation optimization config (QnnHtpGraph_CustomConfig_t, terminated by UNKNOWN)
    // pushed via QnnGraph_setConfig (QNN_GRAPH_CONFIG_OPTION_CUSTOM) before graphFinalize
    QnnHtpGraph_CustomConfig_t htpOptimizationFlags[3]{};
    htpOptimizationFlags[0].option = QNN_HTP_GRAPH_CONFIG_OPTION_OPTIMIZATION;
    htpOptimizationFlags[0].optimizationOption.type = QNN_HTP_GRAPH_OPTIMIZATION_TYPE_FINALIZE_OPTIMIZATION_FLAG;
    htpOptimizationFlags[0].optimizationOption.floatValue = 3.0f;  // O3
    htpOptimizationFlags[1].option = QNN_HTP_GRAPH_CONFIG_OPTION_OPTIMIZATION;
    htpOptimizationFlags[1].optimizationOption.type = QNN_HTP_GRAPH_OPTIMIZATION_TYPE_ENABLE_DLBC_WEIGHTS;
    htpOptimizationFlags[1].optimizationOption.floatValue = 1.0f;
    htpOptimizationFlags[2].option = QNN_HTP_GRAPH_CONFIG_OPTION_OPTIMIZATION;
    htpOptimizationFlags[2].optimizationOption.type = QNN_HTP_GRAPH_OPTIMIZATION_TYPE_ENABLE_SLC_ALLOCATOR;
    htpOptimizationFlags[2].optimizationOption.floatValue = 1.0f;

    QnnHtpGraph_CustomConfig_t htpExtraConfigs[3]{};
    htpExtraConfigs[0].option = QNN_HTP_GRAPH_CONFIG_OPTION_MONOLITHIC_LSTM;
    htpExtraConfigs[0].monolithicLstm = true;
    htpExtraConfigs[1].option = QNN_HTP_GRAPH_CONFIG_OPTION_WEIGHTS_PACKING;
    htpExtraConfigs[1].weightsPacking = true;
    htpExtraConfigs[2].option = QNN_HTP_GRAPH_CONFIG_OPTION_ADVANCED_ACTIVATION_FUSION;
    htpExtraConfigs[2].advancedActivationFusion = true;

    QnnHtpGraph_CustomConfig_t htpAllConfigs[7]{};
    for (int k = 0; k < 3; k++) htpAllConfigs[k] = htpOptimizationFlags[k];
    for (int k = 0; k < 3; k++) htpAllConfigs[3 + k] = htpExtraConfigs[k];
    htpAllConfigs[6] = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;

    // 4. 对每个 graph: graphRetrieve → graphSetConfig(HTP 优化) → graphFinalize (触发编译)
    // 4. Per graph: graphRetrieve -> graphSetConfig (HTP optimization) -> graphFinalize (triggers compilation)
    for (uint32_t i = 0; i < numGraphs; i++) {
        // Cancellation check (graphFinalize is blocking; cancellation takes effect right after it returns)
        if (m_cancelRequested.load()) {
            freeGraphInfos(graphInfos, numGraphs);
            return cleanupOnCancel(context);
        }

        const char* gname = nullptr;
        if (graphInfos[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
            gname = graphInfos[i].graphInfoV1.graphName;
        } else if (graphInfos[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
            gname = graphInfos[i].graphInfoV2.graphName;
        } else if (graphInfos[i].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
            gname = graphInfos[i].graphInfoV3.graphName;
        }

        if (progressCb) {
            char detail[256];
            std::snprintf(detail, sizeof(detail), "Compiling graph %u/%u: %s",
                          i + 1, numGraphs, gname ? gname : "");
            progressCb(static_cast<int>(i), static_cast<int>(numGraphs),
                       elapsedMs(), detail);
        }

        // graphRetrieve
        Qnn_GraphHandle_t graphHandle = nullptr;
        if (QNN_SUCCESS != qnn.graphRetrieve(context, gname, &graphHandle)) {
            LOGE("Failed to retrieve graph handle for '%s'", gname ? gname : "");
            freeGraphInfos(graphInfos, numGraphs);
            sys.systemDlcFree(m_dlcHandle);
            m_dlcHandle = nullptr;
            qnn.contextFree(context, nullptr);
            return -5;
        }

        // graphSetConfig: push the HTP optimization config (must happen before graphFinalize)
        QnnGraph_Config_t graphConfigs[2]{};
        graphConfigs[0].option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
        graphConfigs[0].customConfig = (QnnGraph_CustomConfig_t)htpAllConfigs;
        graphConfigs[1] = QNN_GRAPH_CONFIG_INIT;
        const QnnGraph_Config_t* graphConfigPtr[2] = {&graphConfigs[0], nullptr};

        if (QNN_SUCCESS != qnn.graphSetConfig(graphHandle, graphConfigPtr)) {
            LOGE("Failed to set HTP optimization config for graph '%s'", gname ? gname : "");
            freeGraphInfos(graphInfos, numGraphs);
            sys.systemDlcFree(m_dlcHandle);
            m_dlcHandle = nullptr;
            qnn.contextFree(context, nullptr);
            return -6;
        }

        // graphFinalize (triggers the actual compilation; may take a while)
        if (QNN_SUCCESS != qnn.graphFinalize(graphHandle, nullptr, nullptr)) {
            LOGE("Failed to finalize graph '%s'", gname ? gname : "");
            freeGraphInfos(graphInfos, numGraphs);
            sys.systemDlcFree(m_dlcHandle);
            m_dlcHandle = nullptr;
            qnn.contextFree(context, nullptr);
            return -7;
        }

        LOGI("Finalized graph %u/%u: %s", i + 1, numGraphs, gname ? gname : "");
    }

    // Free graphInfos (memory allocated by systemDlcComposeGraphs, freed by the caller)
    freeGraphInfos(graphInfos, numGraphs);
    graphInfos = nullptr;

    if (progressCb) {
        progressCb(numGraphs, numGraphs, elapsedMs(), "Compilation complete, extracting binary");
    }

    // Cancellation check
    if (m_cancelRequested.load()) return cleanupOnCancel(context);

    // 5. contextGetBinarySize: 获取编译后 binary 大小
    // 5. contextGetBinarySize: query the compiled binary size
    Qnn_ContextBinarySize_t binSize = 0;
    if (QNN_SUCCESS != qnn.contextGetBinarySize(context, &binSize)) {
        LOGE("Failed to get context binary size");
        sys.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        qnn.contextFree(context, nullptr);
        return -8;
    }

    if (binSize == 0) {
        LOGE("Context binary size is 0");
        sys.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        qnn.contextFree(context, nullptr);
        return -9;
    }

    // 6. contextGetBinary: 提取编译后的 binary
    // 6. contextGetBinary: extract the compiled binary
    std::vector<uint8_t> buffer(binSize);
    Qnn_ContextBinarySize_t writtenSize = 0;
    if (QNN_SUCCESS != qnn.contextGetBinary(context, buffer.data(), binSize, &writtenSize)) {
        LOGE("Failed to get context binary");
        sys.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        qnn.contextFree(context, nullptr);
        return -10;
    }

    LOGI("Context binary extracted: %llu bytes",
         (unsigned long long)writtenSize);

    // Cancellation check (the file was not written yet, just clean up)
    if (m_cancelRequested.load()) return cleanupOnCancel(context);

    // 7. 写入 .bin 文件
    // 7. Write the .bin file
    std::ofstream out(outBinPath, std::ios::binary);
    if (!out.is_open()) {
        LOGE("Failed to open output file: %s", outBinPath.c_str());
        sys.systemDlcFree(m_dlcHandle);
        m_dlcHandle = nullptr;
        qnn.contextFree(context, nullptr);
        return -11;
    }
    out.write(reinterpret_cast<const char*>(buffer.data()), writtenSize);
    out.close();

    if (progressCb) {
        progressCb(1, 1, elapsedMs(), "Binary saved successfully");
    }

    LOGI("DLC compiled to binary: %s (%llu bytes)",
         outBinPath.c_str(), (unsigned long long)writtenSize);

    // 8. 清理
    // 8. Cleanup
    sys.systemDlcFree(m_dlcHandle);
    m_dlcHandle = nullptr;
    qnn.contextFree(context, nullptr);

    return 0;
}

} // namespace qnn
