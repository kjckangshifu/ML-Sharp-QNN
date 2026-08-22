// qnn_runtime.h — QNN HTP runtime interface
// Wraps QNN HTP backend loading, context binary loading, and graph inference
#pragma once

#include <cstdint>
#include <cstdlib>
#include <new>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"
#include "HTP/QnnHtpDevice.h"

namespace qnn {

// HTP architecture versions
enum class HtpArch {
    V68 = 68,
    V69 = 69,
    V73 = 73,
    V75 = 75,
    V79 = 79,
    V81 = 81
};

// Inference input/output tensor
struct Tensor {
    std::string name;
    std::vector<uint32_t> dims;    // dims (NHWC or NCHW, model-dependent)
    float* data;                   // uint8)
                                   // data pointer: float32 by default; raw quantized bytes (uint16/uint8) when quantized=true
    size_t count;                  // element count
    bool quantized;                // 模型 info)
                                   // data points to raw quantized data (uint16/uint8, per dims/model info)
};

// Progress callback
using ProgressCallback = void (*)(int current, int total, long elapsedMs, const char* detail);

// 4KB 对齐缓冲: QNN clientBuf 官方建议对齐分配 (HTP DMA 传输最稳妥)
// 4KB-aligned buffer: QNN clientBuf is recommended to be aligned (safest for HTP DMA transfer)
// Replaces std::vector<uint8_t> (default 16-byte alignment, below the QNN recommendation)
class AlignedBuffer {
public:
    AlignedBuffer() : m_data(nullptr), m_size(0) {}
    ~AlignedBuffer() { deallocate(); }
    AlignedBuffer(const AlignedBuffer&) = delete;
    AlignedBuffer& operator=(const AlignedBuffer&) = delete;
    AlignedBuffer(AlignedBuffer&& other) noexcept : m_data(other.m_data), m_size(other.m_size) {
        other.m_data = nullptr;
        other.m_size = 0;
    }
    AlignedBuffer& operator=(AlignedBuffer&& other) noexcept {
        if (this != &other) {
            deallocate();
            m_data = other.m_data;
            m_size = other.m_size;
            other.m_data = nullptr;
            other.m_size = 0;
        }
        return *this;
    }

    uint8_t* data() { return m_data; }
    const uint8_t* data() const { return m_data; }
    size_t size() const { return m_size; }
    bool empty() const { return m_size == 0; }

    // Ensures at least `bytes` of capacity (4KB aligned); reuses the existing buffer when large enough
    void ensure(size_t bytes) {
        if (bytes <= m_size && m_data) return;
        deallocate();
        if (bytes == 0) return;
        void* p = nullptr;
        if (posix_memalign(&p, 4096, bytes) != 0) {
            throw std::bad_alloc();
        }
        m_data = static_cast<uint8_t*>(p);
        m_size = bytes;
    }

    void clear() { deallocate(); }

private:
    uint8_t* m_data;
    size_t m_size;

    void deallocate() {
        if (m_data) {
            ::free(m_data);
            m_data = nullptr;
            m_size = 0;
        }
    }
};

// HTP performance config (sent down via JNI by the caller, applied when the shared state is created in nativeInit)
// type: 0 = 锁角模式 (min=target=max=lockedCorner, 频率恒定);
//       0 = locked-corner mode (min=target=max=lockedCorner, fixed frequency);
//       1 = 自动调角模式 (DCVS 在 [minCorner, maxCorner] 区间内按 dcvsMode 策略动态调节)
//       1 = adaptive mode (DCVS adjusts dynamically in [minCorner, maxCorner] per the dcvsMode policy)
// Voltage corners per QnnHtpPerfInfrastructure_VoltageCorner_t (0x20 MIN ~ 0xA0 MAX, excluding DISABLE 0x10)
// dcvsMode per QnnHtpPerfInfrastructure_PowerMode_t (0x1 ADJUST_UP_DOWN ~ 0x20 DUTY_CYCLE)
struct PerfConfig {
    int type = 0;                            // 0=locked corner, 1=adaptive
    uint32_t lockedCorner = 0xA0;            // locked corner: the corner to lock (max by default)
    uint32_t minCorner = 0x60;               // adaptive: lowest corner DCVS may drop to (NOM)
    uint32_t targetCorner = 0xA0;            // adaptive: voting target corner (initial request, max by default)
    uint32_t maxCorner = 0xA0;               // adaptive: highest corner DCVS may raise to (MAX)
    uint32_t dcvsMode = 0x1;                 // adaptive: DCVS policy (ADJUST_UP_DOWN by default)
};

// QNN shared state: log/backend/device are shared across HtpRuntime instances
// (官方标准: 单 backend + 单 device + 多 context; 官方示例即此结构)
// (official pattern: one backend + one device + multiple contexts; the official samples use this layout)
// Owned globally by the caller (sharp_jni.cpp); HtpRuntime shares the lifetime via reference counting
struct QnnSharedState {
    void* libHtpHandle = nullptr;            // dlopen'd libQnnHtp.so (shared, ref-counted)
    void* libSystemHandle = nullptr;         // dlopen'd libQnnSystem.so (shared, ref-counted)
    void* logHandle = nullptr;               // QnnLog handle
    void* backendHandle = nullptr;           // QnnBackend handle
    void* deviceHandle = nullptr;            // QnnDevice handle
    QnnInterface_t qnnInterface{};           // QNN core interface after version matching
    QnnSystemInterface_t qnnSystemInterface{};  // QNN system interface after version matching
    QnnHtpDevice_PerfInfrastructure_t perfInfra = QNN_HTP_DEVICE_PERF_INFRASTRUCTURE_INIT;
    uint32_t perfConfigId = 0;               // 0 = no power config id created yet
    int refCount = 0;                        // refcount (number of HtpRuntime instances sharing this state)
};

// QNN HTP runtime
class HtpRuntime {
public:
    // shared: process-wide QnnSharedState (owned by the caller, outlives every runtime)
    explicit HtpRuntime(QnnSharedState* shared);
    ~HtpRuntime();

    // init: if the shared state does not exist yet, dlopen libQnnHtp.so + libQnnSystem.so,
    // create log/backend/device + HTP performance config; then bump the refcount
    // libDir: directory of the QNN .so files (e.g. /data/data/com.sharp.qnn/lib/arm64)
    // skelDir: directory of the Skel .so files (set as ADSP_LIBRARY_PATH)
    // arch: HTP architecture version (e.g. V79, probed via QnnDevice_getPlatformInfo)
    // perf: HTP performance config (locked-corner/adaptive, see PerfConfig)
    // Returns 0 on success
    int init(const std::string& libDir, const std::string& skelDir, HtpArch arch,
             const PerfConfig& perf = PerfConfig());

    // Loads a model from a context binary (.bin)
    // path to the .bin file
    // output graph name (read from the binary metadata)
    // Returns 0 on success
    int loadFromBinary(const std::string& binPath, std::string& graphName);

    // Runs inference
    // inputs: input tensor array
    // outputs: output tensor array (out; the caller allocates `data`;
    //           with keepOutputQuantized=true, data may be null and results stay in the internal quantized buffer)
    // keepOutputQuantized: when true, outputs are not dequantized; raw quantized data stays in an internal buffer,
    //                      read via getQuantizedOutputData() and released via releaseQuantizedOutput()
    // Returns 0 on success
    int execute(const std::vector<Tensor>& inputs, std::vector<Tensor>& outputs,
                bool keepOutputQuantized = false);

    // Reads the raw quantized data of output i in keepOutputQuantized mode (byte pointer)
    const uint8_t* getQuantizedOutputData(size_t i) const {
        return (i < m_outQuantBuf.size() && !m_outQuantBuf[i].empty())
                   ? m_outQuantBuf[i].data() : nullptr;
    }

    // Releases the quantized buffer of output i (call after persisting, lowers the write-phase memory footprint)
    void releaseQuantizedOutput(size_t i) {
        if (i < m_outQuantBuf.size()) m_outQuantBuf[i].clear();
    }

    // Frees this runtime's context+graph (shared device/backend/log stay alive for other runtimes)
    void freeGraph();

    // Frees this runtime's context+graph and releases the shared-state reference
    // device/log 在最后一个引用释放时销毁)
    // (shared backend/device/log are destroyed when the last reference is released)
    void freeContext();

    // Whether initialized
    bool isReady() const { return m_ready; }

    // Gets input/output tensor metadata (dims, dataType, quantParams)
    // Read from the binary metadata, available after loadFromBinary
    struct TensorInfo {
        std::string name;
        std::vector<uint32_t> dims;
        uint32_t dataType;       // QNN_DATATYPE_*
        float scale;             // quantization scale
        int32_t offset;          // quantization offset
        uint32_t quantEncoding;  // raw quantization encoding
        uint32_t bitwidth;       // BW_SCALE_OFFSET bitwidth (16 or 8)
    };
    const std::vector<TensorInfo>& getInputInfos() const { return m_inputInfos; }
    const std::vector<TensorInfo>& getOutputInfos() const { return m_outputInfos; }

    friend class DlcCompiler;

private:
    // Creates the shared state (dlopen + log/backend/device + HTP performance config); runs only when refCount==0
    int createSharedState(const std::string& libDir, HtpArch arch, const PerfConfig& perf);
    void releaseSharedState();
    void destroySharedState();

    QnnSharedState* m_shared;   // shared state (not owned; lifetime guaranteed by the caller)
    void* m_contextHandle;
    void* m_graphHandle;
    bool m_ready;

    std::vector<TensorInfo> m_inputInfos;
    std::vector<TensorInfo> m_outputInfos;
    std::string m_graphName;

    // Context binary data and tensor ids (owned by this runtime)
    struct RuntimeData {
        std::vector<uint8_t> binaryBuffer;
        std::vector<uint32_t> inputIds;
        std::vector<uint32_t> outputIds;
    };
    RuntimeData m_data;

    // Persistent input/output buffers (the HTP context binds the clientBuf.data
    // pointer address across graphExecute calls, so pointers must stay stable; 4KB aligned)
    std::vector<AlignedBuffer> m_inQuantBuf;
    std::vector<AlignedBuffer> m_outQuantBuf;
    std::vector<std::vector<uint32_t>> m_inDims;
    std::vector<std::vector<uint32_t>> m_outDims;
};

} // namespace qnn
