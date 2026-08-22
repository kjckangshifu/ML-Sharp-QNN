// qnn_dlc_compiler.h — on-device DLC compiler
// Compiles a DLC file into a context binary (.bin)
#pragma once

#include <atomic>
#include <string>
#include "qnn_runtime.h"
#include "System/QnnSystemInterface.h"

namespace qnn {

// DLC compiler: compiles a DLC into a context binary on-device
// Flow: systemDlcCreateFromFile -> systemDlcComposeGraphs (once, returns graph info)
//       → 每 graph: graphRetrieve → graphSetConfig(HTP 优化) → graphFinalize
//       -> per graph: graphRetrieve -> graphSetConfig (HTP optimization) -> graphFinalize
//       → contextGetBinary
//       -> contextGetBinary
// (官方 SampleApp 同款流程: compose 一次 + HTP config 经 graphSetConfig 下发)
// (same flow as the official SampleApp: compose once, HTP config pushed via graphSetConfig)
class DlcCompiler {
public:
    DlcCompiler();
    ~DlcCompiler();

    // Compiles a DLC into a context binary
    // runtime: an initialized HtpRuntime (provides backend/device/context)
    // dlcPath: path to the DLC file
    // outBinPath: path to save the compiled .bin
    // progressCb: compilation progress callback (optional)
    // Returns 0 on success; -100 means cancelled (partial resources already cleaned up internally)
    int compile(HtpRuntime& runtime, const std::string& dlcPath,
                const std::string& outBinPath, ProgressCallback progressCb = nullptr);

    // Requests cancellation of the current compile (thread-safe; the compile checks the flag between steps and aborts)
    // Note: QNN graphFinalize is blocking and cannot be interrupted; cancellation takes effect right after it returns
    void requestCancel() { m_cancelRequested.store(true, std::memory_order_relaxed); }

    // Whether a cancellation was requested
    bool isCancelRequested() const { return m_cancelRequested.load(std::memory_order_relaxed); }

private:
    void* m_dlcHandle;
    // Keeps a copy of the QNN system interface used by the last compile,
    // so the destructor can call systemDlcFree on a leftover m_dlcHandle (independent of runtime lifetime)
    QnnSystemInterface_t m_sysInterface;
    bool m_sysInterfaceValid;
    // Cancellation flag (set by the JNI cancelCompile entry point)
    std::atomic<bool> m_cancelRequested;
};

} // namespace qnn
