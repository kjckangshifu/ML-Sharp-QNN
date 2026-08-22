// qnn_runtime.cpp — QNN HTP runtime implementation
// Wraps QNN HTP backend loading, context binary loading, and graph inference
#include "qnn_runtime.h"
#include "qnn_tensor.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <vector>
#include <android/log.h>

#include "QnnInterface.h"
#include "QnnContext.h"
#include "QnnBackend.h"
#include "QnnDevice.h"
#include "QnnLog.h"
#include "QnnTensor.h"
#include "QnnCommon.h"
#include "QnnTypes.h"
#include "QnnError.h"
#include "System/QnnSystemContext.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpDeviceConfigShared.h"

// Current process RSS (kB) for peak tracking during load/execute (reads /proc/self/status)
static size_t selfVmRSS_kB() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    size_t rss = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            sscanf(line + 6, "%zu", &rss);
            break;
        }
    }
    fclose(f);
    return rss;
}

namespace qnn {

#define LOG_TAG "QNN-HTP"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Voltage corner enum -> readable name (1:1 with QnnHtpPerfInfrastructure_VoltageCorner_t)
static const char* voltageCornerName(uint32_t corner) {
    switch (corner) {
        case DCVS_VOLTAGE_VCORNER_MIN_VOLTAGE_CORNER: return "MIN (SVS2, 最低)";
        case DCVS_VOLTAGE_VCORNER_SVS2:               return "SVS2";
        case DCVS_VOLTAGE_VCORNER_SVS:                return "SVS";
        case DCVS_VOLTAGE_VCORNER_SVS_PLUS:           return "SVS_PLUS";
        case DCVS_VOLTAGE_VCORNER_NOM:                return "NOM";
        case DCVS_VOLTAGE_VCORNER_NOM_PLUS:           return "NOM_PLUS";
        case DCVS_VOLTAGE_VCORNER_TURBO:              return "TURBO";
        case DCVS_VOLTAGE_VCORNER_TURBO_PLUS:         return "TURBO_PLUS";
        case DCVS_VOLTAGE_VCORNER_TURBO_L2:           return "TURBO_L2";
        case DCVS_VOLTAGE_VCORNER_TURBO_L3:           return "TURBO_L3";
        case DCVS_VOLTAGE_VCORNER_TURBO_L4:           return "TURBO_L4";
        case DCVS_VOLTAGE_VCORNER_TURBO_L5:           return "TURBO_L5";
        case DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER: return "MAX";
        default: return "?";
    }
}

// DCVS PowerMode enum -> readable name (1:1 with QnnHtpPerfInfrastructure_PowerMode_t)
static const char* dcvsModeName(uint32_t mode) {
    switch (mode) {
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN:           return "ADJUST_UP_DOWN";
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_ONLY_UP:           return "ADJUST_ONLY_UP";
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE:         return "POWER_SAVER";
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_AGGRESSIVE_MODE: return "POWER_SAVER_AGGRESSIVE";
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE:         return "PERFORMANCE_MODE";
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_DUTY_CYCLE_MODE:          return "DUTY_CYCLE";
        default: return "?";
    }
}

// Validates a voltage corner against the legal range (0x20~0xA0, excluding DISABLE/UNKNOWN)
static bool isVoltageCornerValid(uint32_t corner) {
    switch (corner) {
        case DCVS_VOLTAGE_VCORNER_MIN_VOLTAGE_CORNER:
        case DCVS_VOLTAGE_VCORNER_SVS2:
        case DCVS_VOLTAGE_VCORNER_SVS:
        case DCVS_VOLTAGE_VCORNER_SVS_PLUS:
        case DCVS_VOLTAGE_VCORNER_NOM:
        case DCVS_VOLTAGE_VCORNER_NOM_PLUS:
        case DCVS_VOLTAGE_VCORNER_TURBO:
        case DCVS_VOLTAGE_VCORNER_TURBO_PLUS:
        case DCVS_VOLTAGE_VCORNER_TURBO_L2:
        case DCVS_VOLTAGE_VCORNER_TURBO_L3:
        case DCVS_VOLTAGE_VCORNER_TURBO_L4:
        case DCVS_VOLTAGE_VCORNER_TURBO_L5:
        case DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER:
            return true;
        default:
            return false;
    }
}

// Validates a DCVS PowerMode against the 6 legal modes
static bool isDcvsModeValid(uint32_t mode) {
    switch (mode) {
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN:
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_ONLY_UP:
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE:
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_AGGRESSIVE_MODE:
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE:
        case QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_DUTY_CYCLE_MODE:
            return true;
        default:
            return false;
    }
}

// QNN log callback: forwards to Android logcat
static void qnnLogCallback(const char* fmt, QnnLog_Level_t level,
                           uint64_t /*timestamp*/, va_list args) {
    android_LogPriority priority = ANDROID_LOG_DEFAULT;
    switch (level) {
        case QNN_LOG_LEVEL_ERROR:   priority = ANDROID_LOG_ERROR;   break;
        case QNN_LOG_LEVEL_WARN:    priority = ANDROID_LOG_WARN;    break;
        case QNN_LOG_LEVEL_INFO:   priority = ANDROID_LOG_INFO;    break;
        case QNN_LOG_LEVEL_VERBOSE: priority = ANDROID_LOG_VERBOSE; break;
        case QNN_LOG_LEVEL_DEBUG:   priority = ANDROID_LOG_DEBUG;   break;
        default: break;
    }
    __android_log_vprint(priority, LOG_TAG, fmt, args);
}

// 析构 ==============
// ============== Construction / destruction ==============

HtpRuntime::HtpRuntime(QnnSharedState* shared)
    : m_shared(shared),
      m_contextHandle(nullptr),
      m_graphHandle(nullptr),
      m_ready(false) {}

HtpRuntime::~HtpRuntime() {
    freeContext();
    releaseSharedState();
}

// 释放 ==============
// ============== Shared state creation / release ==============

// Creates the shared state: dlopen both libs -> match versioned interfaces -> logCreate(WARN)
// → backendCreate → deviceCreate(带 HTP arch config) → HTP 性能配置 (按 perf)
// -> backendCreate -> deviceCreate (with HTP arch config) -> HTP performance config (per `perf`)
// Runs only when refCount==0 (not yet created); cleans up already-created resources on failure
int HtpRuntime::createSharedState(const std::string& libDir, HtpArch arch, const PerfConfig& perf) {
    if (!m_shared) {
        LOGE("No shared state provided");
        return -1;
    }
    if (m_shared->refCount > 0 && m_shared->deviceHandle) {
        LOGE("Shared state already created");
        return -2;
    }

    // 1. dlopen libQnnSystem.so
    std::string systemLibPath = libDir + "/libQnnSystem.so";
    m_shared->libSystemHandle = dlopen(systemLibPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!m_shared->libSystemHandle) {
        LOGE("Failed to dlopen libQnnSystem.so: %s", dlerror());
        return -3;
    }

    // 2. 获取 QnnSystemInterface_getProviders → qnnSystemInterface 函数指针表
    // 2. Resolve QnnSystemInterface_getProviders -> the qnnSystemInterface function table
    typedef Qnn_ErrorHandle_t (*SystemGetProvidersFn_t)(
        const QnnSystemInterface_t***, uint32_t*);
    auto getSysProviders = reinterpret_cast<SystemGetProvidersFn_t>(
        dlsym(m_shared->libSystemHandle, "QnnSystemInterface_getProviders"));
    if (!getSysProviders) {
        LOGE("Failed to find QnnSystemInterface_getProviders: %s", dlerror());
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -4;
    }

    {
        QnnSystemInterface_t** sysProviders = nullptr;
        uint32_t numSysProviders = 0;
        if (QNN_SUCCESS != getSysProviders(
                (const QnnSystemInterface_t***)&sysProviders, &numSysProviders)) {
            LOGE("Failed to get system interface providers");
            dlclose(m_shared->libSystemHandle);
            m_shared->libSystemHandle = nullptr;
            return -5;
        }
        bool found = false;
        for (uint32_t i = 0; i < numSysProviders; i++) {
            if (QNN_SYSTEM_API_VERSION_MAJOR == sysProviders[i]->systemApiVersion.major &&
                QNN_SYSTEM_API_VERSION_MINOR <= sysProviders[i]->systemApiVersion.minor) {
                m_shared->qnnSystemInterface = *sysProviders[i];
                found = true;
                break;
            }
        }
        if (!found) {
            LOGE("No compatible QNN system interface found");
            dlclose(m_shared->libSystemHandle);
            m_shared->libSystemHandle = nullptr;
            return -6;
        }
    }

    // 3. dlopen libQnnHtp.so
    std::string htpLibPath = libDir + "/libQnnHtp.so";
    m_shared->libHtpHandle = dlopen(htpLibPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!m_shared->libHtpHandle) {
        LOGE("Failed to dlopen libQnnHtp.so: %s", dlerror());
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -7;
    }

    // 4. 获取 QnnInterface_getProviders → qnnInterface 函数指针表 (匹配 API 版本)
    // 4. Resolve QnnInterface_getProviders -> the qnnInterface function table (version-matched)
    typedef Qnn_ErrorHandle_t (*InterfaceGetProvidersFn_t)(
        const QnnInterface_t***, uint32_t*);
    auto getProviders = reinterpret_cast<InterfaceGetProvidersFn_t>(
        dlsym(m_shared->libHtpHandle, "QnnInterface_getProviders"));
    if (!getProviders) {
        LOGE("Failed to find QnnInterface_getProviders: %s", dlerror());
        dlclose(m_shared->libHtpHandle);
        m_shared->libHtpHandle = nullptr;
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -8;
    }

    {
        QnnInterface_t** providers = nullptr;
        uint32_t numProviders = 0;
        if (QNN_SUCCESS != getProviders(
                (const QnnInterface_t***)&providers, &numProviders)) {
            LOGE("Failed to get interface providers");
            dlclose(m_shared->libHtpHandle);
            m_shared->libHtpHandle = nullptr;
            dlclose(m_shared->libSystemHandle);
            m_shared->libSystemHandle = nullptr;
            return -9;
        }
        bool found = false;
        for (uint32_t i = 0; i < numProviders; i++) {
            if (QNN_API_VERSION_MAJOR == providers[i]->apiVersion.coreApiVersion.major &&
                QNN_API_VERSION_MINOR <= providers[i]->apiVersion.coreApiVersion.minor) {
                m_shared->qnnInterface = *providers[i];
                found = true;
                break;
            }
        }
        if (!found) {
            LOGE("No compatible QNN interface found");
            dlclose(m_shared->libHtpHandle);
            m_shared->libHtpHandle = nullptr;
            dlclose(m_shared->libSystemHandle);
            m_shared->libSystemHandle = nullptr;
            return -10;
        }
    }

    auto& qnn = m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;

    // 5. logCreate (官方建议 WARN, 避免生产环境 INFO 级别刷屏)
    // 5. logCreate (WARN as recommended by Qualcomm, avoiding INFO spam in production)
    Qnn_ErrorHandle_t err = qnn.logCreate(qnnLogCallback, QNN_LOG_LEVEL_WARN, &m_shared->logHandle);
    if (QNN_SUCCESS != err) {
        LOGE("Failed to create QNN log handle, error=0x%llx", (unsigned long long)err);
        dlclose(m_shared->libHtpHandle);
        m_shared->libHtpHandle = nullptr;
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -11;
    }

    // 6. backendCreate
    err = qnn.backendCreate(m_shared->logHandle, nullptr, &m_shared->backendHandle);
    if (QNN_SUCCESS != err) {
        LOGE("Failed to create QNN backend, error=0x%llx", (unsigned long long)err);
        qnn.logFree(m_shared->logHandle);
        m_shared->logHandle = nullptr;
        dlclose(m_shared->libHtpHandle);
        m_shared->libHtpHandle = nullptr;
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -12;
    }

    // 7. 构造 HTP device config (SOC + ARCH + SIGNEDPD)
    // 7. Build the HTP device config (SOC + ARCH + SIGNEDPD)
    QnnHtpDevice_Arch_t htpArch = static_cast<QnnHtpDevice_Arch_t>(arch);

    QnnHtpDevice_CustomConfig_t socConfig;
    memset(&socConfig, 0, sizeof(socConfig));
    socConfig.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
    socConfig.socModel = 0;  // 0 = let the backend auto-detect the SoC model

    QnnHtpDevice_CustomConfig_t archConfig;
    memset(&archConfig, 0, sizeof(archConfig));
    archConfig.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
    archConfig.arch.deviceId = 0;
    archConfig.arch.arch = htpArch;

    QnnHtpDevice_CustomConfig_t signedPdConfig;
    memset(&signedPdConfig, 0, sizeof(signedPdConfig));
    signedPdConfig.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    signedPdConfig.useSignedProcessDomain.deviceId = 0;
    signedPdConfig.useSignedProcessDomain.useSignedProcessDomain = false;

    QnnDevice_Config_t devCfgSoc;
    memset(&devCfgSoc, 0, sizeof(devCfgSoc));
    devCfgSoc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    devCfgSoc.customConfig = &socConfig;

    QnnDevice_Config_t devCfgArch;
    memset(&devCfgArch, 0, sizeof(devCfgArch));
    devCfgArch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    devCfgArch.customConfig = &archConfig;

    QnnDevice_Config_t devCfgSignedPd;
    memset(&devCfgSignedPd, 0, sizeof(devCfgSignedPd));
    devCfgSignedPd.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    devCfgSignedPd.customConfig = &signedPdConfig;

    const QnnDevice_Config_t* devCfgPtrs[4] = {
        &devCfgSoc, &devCfgArch, &devCfgSignedPd, nullptr
    };

    // 8. deviceCreate
    err = qnn.deviceCreate(m_shared->logHandle, devCfgPtrs, &m_shared->deviceHandle);
    if (QNN_SUCCESS != err) {
        LOGE("Failed to create QNN device, error=0x%llx", (unsigned long long)err);
        qnn.backendFree(m_shared->backendHandle);
        m_shared->backendHandle = nullptr;
        qnn.logFree(m_shared->logHandle);
        m_shared->logHandle = nullptr;
        dlclose(m_shared->libHtpHandle);
        m_shared->libHtpHandle = nullptr;
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
        return -13;
    }

    // 9. HTP 性能基础设施: 按性能配置下发 DCVS V3 (官方推荐做法)
    // 9. HTP performance infrastructure: push DCVS V3 per the performance config (Qualcomm's recommended flow)
    //    QnnDevice_getInfrastructure → QnnHtpDevice_Infrastructure_t.perfInfra
    //    QnnDevice_getInfrastructure -> QnnHtpDevice_Infrastructure_t.perfInfra
    //    → createPowerConfigId + setPowerConfig
    //    -> createPowerConfigId + setPowerConfig
    //    DcvsV3 semantics (QnnHtpPerfInfrastructure_DcvsV3_t / HAP_power_dcvs_v3_payload):
    //      bus/core triples min/target/max: min = lowest corner allowed down, max = highest corner allowed up,
    //      target = the voted target corner (DCVS adjusts dynamically in [min,max] per the powerMode policy);
    //      min=target=max locks a fixed frequency (the official standard pattern)
    //    Failure does not block init (only affects the frequency policy); a warning is logged
    QnnDevice_Infrastructure_t infra = nullptr;
    if (QNN_SUCCESS == qnn.deviceGetInfrastructure(&infra) && infra) {
        auto* htpInfra = reinterpret_cast<QnnHtpDevice_Infrastructure_t*>(infra);
        m_shared->perfInfra = htpInfra->perfInfra;

        if (m_shared->perfInfra.createPowerConfigId) {
            if (QNN_HTP_PERF_INFRASTRUCTURE_ERROR_UNSUPPORTED ==
                    m_shared->perfInfra.createPowerConfigId(0, 0, &m_shared->perfConfigId) ||
                m_shared->perfConfigId == 0) {
                LOGW("HTP perf infra: createPowerConfigId not available (perfConfigId=%u)",
                     m_shared->perfConfigId);
                m_shared->perfConfigId = 0;
            } else if (m_shared->perfInfra.setPowerConfig) {
                // Build the DCVS V3 config: compute bus/core triples from the user config, then clamp for validity
                QnnHtpPerfInfrastructure_PowerConfig_t powerConfig;
                memset(&powerConfig, 0, sizeof(powerConfig));
                powerConfig.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
                powerConfig.dcvsV3Config.contextId = m_shared->perfConfigId;
                // setDcvsEnable/dcvsEnable are uint32_t: non-zero = honor this parameter / take part in DCVS
                powerConfig.dcvsV3Config.setDcvsEnable = 1;
                powerConfig.dcvsV3Config.dcvsEnable = 1;

                uint32_t busMin = 0, busTarget = 0, busMax = 0;
                uint32_t coreMin = 0, coreTarget = 0, coreMax = 0;
                const char* modeDesc = "?";

                // Adaptive-mode condition: type==1 and all corners/modes are valid;
                // triple constraint min < target <= max with min != max (the UI layer is expected
                // to enforce it; the native layer clamps defensively: invalid triples degrade to locked maxCorner)
                bool useRange =
                    perf.type == 1 &&
                    isVoltageCornerValid(perf.minCorner) &&
                    isVoltageCornerValid(perf.targetCorner) &&
                    isVoltageCornerValid(perf.maxCorner) &&
                    isDcvsModeValid(perf.dcvsMode) &&
                    perf.minCorner < perf.targetCorner &&
                    perf.targetCorner <= perf.maxCorner &&
                    perf.minCorner < perf.maxCorner;
                if (perf.type == 1 && !useRange) {
                    LOGW("HTP perf infra: auto-range params invalid (min=%s target=%s max=%s dcvs=%u), "
                          "fallback to locked maxCorner",
                         voltageCornerName(perf.minCorner),
                         voltageCornerName(perf.targetCorner),
                         voltageCornerName(perf.maxCorner), perf.dcvsMode);
                }

                if (useRange) {
                    // Adaptive mode: DCVS adjusts dynamically in [min,max] per dcvsMode;
                    // idle sleep is allowed (sleepLatency=100ms) so DCVS can downclock to save power
                    busMin = coreMin = perf.minCorner;
                    busTarget = coreTarget = perf.targetCorner;
                    busMax = coreMax = perf.maxCorner;
                    powerConfig.dcvsV3Config.powerMode =
                        static_cast<QnnHtpPerfInfrastructure_PowerMode_t>(perf.dcvsMode);
                    modeDesc = dcvsModeName(perf.dcvsMode);
                    powerConfig.dcvsV3Config.setSleepLatency = 1;
                    powerConfig.dcvsV3Config.sleepLatency = 100000;  // 100ms (microseconds)
                } else {
                    // Locked-corner mode (default): min=target=max=lockedCorner, fixed frequency;
                    // sleep is disabled (sleepDisable=1), matching the official full-power locked config;
                    // an invalid corner value (defensive) falls back to locked MAX
                    uint32_t lock = isVoltageCornerValid(perf.lockedCorner)
                                        ? perf.lockedCorner
                                        : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                    busMin = busTarget = busMax = lock;
                    coreMin = coreTarget = coreMax = lock;
                    powerConfig.dcvsV3Config.setSleepDisable = 1;
                    powerConfig.dcvsV3Config.sleepDisable = 1;
                    // With a locked corner DCVS has no room to adjust, so powerMode is irrelevant (official default kept)
                    powerConfig.dcvsV3Config.powerMode =
                        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN;
                    modeDesc = "locked";
                }

                powerConfig.dcvsV3Config.setBusParams = 1;
                powerConfig.dcvsV3Config.busVoltageCornerMin =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(busMin);
                powerConfig.dcvsV3Config.busVoltageCornerTarget =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(busTarget);
                powerConfig.dcvsV3Config.busVoltageCornerMax =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(busMax);
                powerConfig.dcvsV3Config.setCoreParams = 1;
                powerConfig.dcvsV3Config.coreVoltageCornerMin =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(coreMin);
                powerConfig.dcvsV3Config.coreVoltageCornerTarget =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(coreTarget);
                powerConfig.dcvsV3Config.coreVoltageCornerMax =
                    static_cast<QnnHtpPerfInfrastructure_VoltageCorner_t>(coreMax);

                const QnnHtpPerfInfrastructure_PowerConfig_t* powerConfigs[] = {
                    &powerConfig, nullptr
                };
                Qnn_ErrorHandle_t perr = m_shared->perfInfra.setPowerConfig(
                    m_shared->perfConfigId, powerConfigs);
                if (QNN_SUCCESS != perr) {
                    LOGW("HTP perf infra: setPowerConfig failed, error=0x%llx (non-blocking init)",
                         (unsigned long long)perr);
                    m_shared->perfInfra.destroyPowerConfigId(m_shared->perfConfigId);
                    m_shared->perfConfigId = 0;
                } else {
        LOGI("HTP perf infra: [%s] bus=%s/%s/%s core=%s/%s/%s dcvs=%s enabled (configId=%u)",
             perf.type == 1 ? "auto-range" : "locked",
                         voltageCornerName(busMin), voltageCornerName(busTarget),
                         voltageCornerName(busMax), voltageCornerName(coreMin),
                         voltageCornerName(coreTarget), voltageCornerName(coreMax),
                         modeDesc, m_shared->perfConfigId);
                }
            }
        } else {
            LOGW("HTP perf infra: createPowerConfigId not available (old backend?)");
        }
    } else {
        LOGW("HTP perf infra: deviceGetInfrastructure not available; skip performance config");
    }

    m_shared->refCount = 1;
    LOGI("QNN shared state created (arch=%d, refCount=%d)",
         static_cast<int>(arch), m_shared->refCount);
    return 0;
}

void HtpRuntime::releaseSharedState() {
    if (!m_shared || m_shared->refCount <= 0) return;
    if (--m_shared->refCount > 0) {
        LOGI("QNN shared state refcount=%d (runtime released)", m_shared->refCount);
        return;
    }
    destroySharedState();
}

void HtpRuntime::destroySharedState() {
    if (!m_shared) return;
    auto& qnn = m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;

    // Release order: destroyPowerConfigId -> deviceFree -> backendFree -> logFree -> dlclose
    if (m_shared->perfConfigId != 0 && m_shared->perfInfra.destroyPowerConfigId) {
        m_shared->perfInfra.destroyPowerConfigId(m_shared->perfConfigId);
        m_shared->perfConfigId = 0;
    }
    if (m_shared->deviceHandle) {
        qnn.deviceFree(m_shared->deviceHandle);
        m_shared->deviceHandle = nullptr;
    }
    if (m_shared->backendHandle) {
        qnn.backendFree(m_shared->backendHandle);
        m_shared->backendHandle = nullptr;
    }
    if (m_shared->logHandle) {
        qnn.logFree(m_shared->logHandle);
        m_shared->logHandle = nullptr;
    }
    if (m_shared->libHtpHandle) {
        dlclose(m_shared->libHtpHandle);
        m_shared->libHtpHandle = nullptr;
    }
    if (m_shared->libSystemHandle) {
        dlclose(m_shared->libSystemHandle);
        m_shared->libSystemHandle = nullptr;
    }
    m_shared->qnnInterface = {};
    m_shared->qnnSystemInterface = {};
    m_shared->perfInfra = QNN_HTP_DEVICE_PERF_INFRASTRUCTURE_INIT;
    m_shared->refCount = 0;
    LOGI("QNN shared state destroyed");
}

// ==============  ==============
// ============== init: set env vars + ensure the shared state is ready ==============

int HtpRuntime::init(const std::string& libDir, const std::string& skelDir, HtpArch arch,
                     const PerfConfig& perf) {
    // 1. 设置环境变量 (HTP skel 库搜索路径)
    // 1. Set the environment variable (HTP skel library search path)
    //    Note: only ADSP_LIBRARY_PATH is set. LD_LIBRARY_PATH has no effect under the
    //    Android linker (bionic); Qualcomm only requires ADSP_LIBRARY_PATH (see the official QNN docs)
    int ret = setenv("ADSP_LIBRARY_PATH", skelDir.c_str(), 1);
    if (ret != 0) {
        LOGE("Failed to set ADSP_LIBRARY_PATH=%s", skelDir.c_str());
        return -1;
    }

    // 2. 创建共享状态 (仅首个 runtime 实际创建)
    // 2. Create the shared state (only the first runtime actually creates it)
    if (m_shared->refCount <= 0 || !m_shared->deviceHandle) {
        int rc = createSharedState(libDir, arch, perf);
        if (rc != 0) {
            LOGE("Failed to create shared QNN state, rc=%d", rc);
            return rc;
        }
    } else {
        m_shared->refCount++;
        LOGI("QNN shared state reused (refCount=%d)", m_shared->refCount);
    }

    m_ready = true;
    LOGI("QNN HTP runtime initialized (arch=%d)", static_cast<int>(arch));
    return 0;
}

// backend/log) ==============
// ============== freeGraph: free only context+graph (device/backend/log stay alive) ==============

void HtpRuntime::freeGraph() {
    if (!m_shared) return;

    auto& qnn = m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;

    m_graphHandle = nullptr;

    if (m_contextHandle) {
        qnn.contextFree(m_contextHandle, nullptr);
        m_contextHandle = nullptr;
    }

    m_inputInfos.clear();
    m_outputInfos.clear();
    m_graphName.clear();
    m_data.binaryBuffer.clear();
    m_data.inputIds.clear();
    m_data.outputIds.clear();

    // Clear the persistent buffers (different models have different in/out dims, so they cannot be reused)
    m_inQuantBuf.clear();
    m_outQuantBuf.clear();
    m_inDims.clear();
    m_outDims.clear();
}

// ==============  ==============
// ============== loadFromBinary: load a model from .bin ==============

int HtpRuntime::loadFromBinary(const std::string& binPath, std::string& graphName) {
    if (!m_ready) return -1;

    auto& qnn = m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;
    auto& sys = m_shared->qnnSystemInterface.QNN_SYSTEM_INTERFACE_VER_NAME;

    // If a context already exists, free it first (avoids leaking resources on a second loadFromBinary)
    if (m_contextHandle) {
        freeGraph();
    }

    // 1. 读取 .bin 文件到内存
    // 1. Read the .bin file into memory
    std::ifstream file(binPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOGE("Failed to open binary file: %s", binPath.c_str());
        return -2;
    }
    std::streamsize binSize = file.tellg();
    file.seekg(0, std::ios::beg);
    m_data.binaryBuffer.resize(binSize);
    if (!file.read(reinterpret_cast<char*>(m_data.binaryBuffer.data()), binSize)) {
        LOGE("Failed to read binary file: %s", binPath.c_str());
        return -3;
    }
    file.close();
    LOGI("loadFromBinary: %s binSize=%lld bytes, 读入后 RSS=%zuKB",
         binPath.c_str(), (long long)binSize, selfVmRSS_kB());

    // 2. 用 systemContextCreate 创建 system context
    // 2. Create a system context via systemContextCreate
    QnnSystemContext_Handle_t sysCtxHandle = nullptr;
    Qnn_ErrorHandle_t sysErr = sys.systemContextCreate(&sysCtxHandle);
    if (QNN_SUCCESS != sysErr) {
        LOGE("Failed to create system context, error=0x%llx", (unsigned long long)sysErr);
        return -4;
    }

    // 3. 用 systemContextGetBinaryInfo 读取 binary 元数据
    // 3. Read the binary metadata via systemContextGetBinaryInfo
    const QnnSystemContext_BinaryInfo_t* binaryInfo = nullptr;
    Qnn_ContextBinarySize_t binaryInfoSize = 0;
    sysErr = sys.systemContextGetBinaryInfo(
            sysCtxHandle,
            m_data.binaryBuffer.data(),
            m_data.binaryBuffer.size(),
            &binaryInfo,
            &binaryInfoSize);
    if (QNN_SUCCESS != sysErr) {
        LOGE("Failed to get binary info from context binary, error=0x%llx", (unsigned long long)sysErr);
        sys.systemContextFree(sysCtxHandle);
        return -5;
    }

    // m_outputInfos
    // 4. Parse the metadata into m_inputInfos / m_outputInfos
    m_inputInfos.clear();
    m_outputInfos.clear();
    m_data.inputIds.clear();
    m_data.outputIds.clear();

    auto parseTensors = [](const Qnn_Tensor_t* tensors, uint32_t count,
                           std::vector<TensorInfo>& outInfos,
                           std::vector<uint32_t>& outIds) {
        for (uint32_t i = 0; i < count; i++) {
            const Qnn_TensorV1_t& t = tensors[i].v1;
            TensorInfo info;
            info.name = t.name ? t.name : "";
            info.dataType = static_cast<uint32_t>(t.dataType);
            info.scale = 1.0f;
            info.offset = 0;
            info.quantEncoding = QNN_QUANTIZATION_ENCODING_UNDEFINED;
            info.bitwidth = 0;
            // Extract the quantization params (keep the original encoding/bitwidth; they are replayed in execute)
            const Qnn_QuantizeParams_t& qp = t.quantizeParams;
            info.quantEncoding = static_cast<uint32_t>(qp.quantizationEncoding);
            if (qp.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
                info.scale = qp.scaleOffsetEncoding.scale;
                info.offset = qp.scaleOffsetEncoding.offset;
            } else if (qp.quantizationEncoding == QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET) {
                info.scale = qp.bwScaleOffsetEncoding.scale;
                info.offset = qp.bwScaleOffsetEncoding.offset;
                info.bitwidth = qp.bwScaleOffsetEncoding.bitwidth;
            }
            // Extract the dims
            for (uint32_t d = 0; d < t.rank; d++) {
                info.dims.push_back(t.dimensions[d]);
            }
            outInfos.push_back(info);
            outIds.push_back(t.id);
        }
    };

    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    uint32_t numGraphs = 0;

    if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        graphs = binaryInfo->contextBinaryInfoV1.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV1.numGraphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        graphs = binaryInfo->contextBinaryInfoV2.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV2.numGraphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        graphs = binaryInfo->contextBinaryInfoV3.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV3.numGraphs;
    }

    if (numGraphs > 0 && graphs != nullptr) {
        const QnnSystemContext_GraphInfo_t& g = graphs[0];  // use the first graph
        if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
            graphName = g.graphInfoV1.graphName ? g.graphInfoV1.graphName : "";
            parseTensors(g.graphInfoV1.graphInputs, g.graphInfoV1.numGraphInputs,
                         m_inputInfos, m_data.inputIds);
            parseTensors(g.graphInfoV1.graphOutputs, g.graphInfoV1.numGraphOutputs,
                         m_outputInfos, m_data.outputIds);
        } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
            graphName = g.graphInfoV2.graphName ? g.graphInfoV2.graphName : "";
            parseTensors(g.graphInfoV2.graphInputs, g.graphInfoV2.numGraphInputs,
                         m_inputInfos, m_data.inputIds);
            parseTensors(g.graphInfoV2.graphOutputs, g.graphInfoV2.numGraphOutputs,
                         m_outputInfos, m_data.outputIds);
        } else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
            graphName = g.graphInfoV3.graphName ? g.graphInfoV3.graphName : "";
            parseTensors(g.graphInfoV3.graphInputs, g.graphInfoV3.numGraphInputs,
                         m_inputInfos, m_data.inputIds);
            parseTensors(g.graphInfoV3.graphOutputs, g.graphInfoV3.numGraphOutputs,
                         m_outputInfos, m_data.outputIds);
        }
    }

    m_graphName = graphName;

    // 5. 释放 system context (元数据已解析完成)
    // 5. Free the system context (metadata is fully parsed)
    sys.systemContextFree(sysCtxHandle);

    LOGI("Loaded binary: graph='%s', %u inputs, %u outputs",
         graphName.c_str(),
         (uint32_t)m_inputInfos.size(),
         (uint32_t)m_outputInfos.size());

    // Debug: log input/output tensor info (name, dims, data type, scale, offset)
    for (size_t i = 0; i < m_inputInfos.size(); i++) {
        const auto& info = m_inputInfos[i];
        std::string dimsStr;
        for (size_t d = 0; d < info.dims.size(); d++) {
            dimsStr += std::to_string(info.dims[d]);
            if (d + 1 < info.dims.size()) dimsStr += "x";
        }
        LOGI("  Input[%zu] name='%s' dims=[%s] dtype=%u enc=%u bw=%u scale=%.6f offset=%d",
             i, info.name.c_str(), dimsStr.c_str(), info.dataType, info.quantEncoding, info.bitwidth, info.scale, info.offset);
    }
    for (size_t i = 0; i < m_outputInfos.size(); i++) {
        const auto& info = m_outputInfos[i];
        std::string dimsStr;
        for (size_t d = 0; d < info.dims.size(); d++) {
            dimsStr += std::to_string(info.dims[d]);
            if (d + 1 < info.dims.size()) dimsStr += "x";
        }
        LOGI("  Output[%zu] name='%s' dims=[%s] dtype=%u enc=%u bw=%u scale=%.6f offset=%d",
             i, info.name.c_str(), dimsStr.c_str(), info.dataType, info.quantEncoding, info.bitwidth, info.scale, info.offset);
    }

    // 6. contextCreateFromBinary (binData 必须在 context 生命周期内保持有效)
    // 6. contextCreateFromBinary (binData must stay valid for the context's lifetime)
    //    QNN_CONTEXT_CONFIG_BINARY_COMPATIBILITY=STRICT: the binary must match the device exactly
    //    (能运行且充分利用硬件能力), 否则返回 QNN_CONTEXT_ERROR_BINARY_SUBOPTIMAL;
    //    (run and fully use the hardware); otherwise QNN_CONTEXT_ERROR_BINARY_SUBOPTIMAL is returned;
    //    on STRICT failure, fall back to permissive (default behavior = runnable is enough) and log a warning
    LOGI("loadFromBinary: contextCreateFromBinary 前 RSS=%zuKB", selfVmRSS_kB());
    QnnContext_Config_t binCompatCfg;
    memset(&binCompatCfg, 0, sizeof(binCompatCfg));
    binCompatCfg.option = QNN_CONTEXT_CONFIG_BINARY_COMPATIBILITY;
    binCompatCfg.binaryCompatibilityType = QNN_CONTEXT_BINARY_COMPATIBILITY_STRICT;
    const QnnContext_Config_t* ctxCfgPtrs[2] = {&binCompatCfg, nullptr};

    Qnn_ErrorHandle_t err = qnn.contextCreateFromBinary(
            m_shared->backendHandle,
            m_shared->deviceHandle,
            ctxCfgPtrs,
            m_data.binaryBuffer.data(),
            m_data.binaryBuffer.size(),
            &m_contextHandle,
            nullptr);
    if (QNN_SUCCESS != err && QNN_CONTEXT_ERROR_BINARY_SUBOPTIMAL == err) {
        LOGW("Binary device STRICT compatibility check failed (SUBOPTIMAL), retry with permissive");
        err = qnn.contextCreateFromBinary(
            m_shared->backendHandle,
            m_shared->deviceHandle,
            nullptr,
            m_data.binaryBuffer.data(),
            m_data.binaryBuffer.size(),
            &m_contextHandle,
            nullptr);
    }
    LOGI("loadFromBinary: contextCreateFromBinary after RSS=%zuKB", selfVmRSS_kB());
    if (QNN_SUCCESS != err) {
        LOGE("Failed to create context from binary, error=0x%llx, binSize=%zu",
             (unsigned long long)err, m_data.binaryBuffer.size());
        return -6;
    }

    // 7. graphRetrieve
    err = qnn.graphRetrieve(m_contextHandle, m_graphName.c_str(), &m_graphHandle);
    if (QNN_SUCCESS != err) {
        LOGE("Failed to retrieve graph handle for '%s', error=0x%llx",
             m_graphName.c_str(), (unsigned long long)err);
        return -7;
    }

    // 8. contextCreateFromBinary 已反序列化完毕, context 自包含, 二进制缓冲不再需要
    // 8. contextCreateFromBinary has fully deserialized; the context is self-contained, so the binary buffer is no longer needed
    //    (QnnContext.h 未对 binaryBuffer 提出生命周期要求), 立即释放归还分配器,
    //    (QnnContext.h imposes no lifetime requirement on binaryBuffer), release it back to the allocator
    //    so the 312MB (e/ie) ~ 67MB (rest) cache does not linger into later stages
    std::vector<uint8_t>().swap(m_data.binaryBuffer);

    LOGI("Binary loaded successfully, graph handle=%p, binaryBuffer released RSS=%zuKB",
         m_graphHandle, selfVmRSS_kB());
    return 0;
}

// ==============  ==============
// ============== execute: run inference ==============

int HtpRuntime::execute(const std::vector<Tensor>& inputs, std::vector<Tensor>& outputs,
                        bool keepOutputQuantized) {
    if (!m_ready || !m_graphHandle) return -1;

    auto& qnn = m_shared->qnnInterface.QNN_INTERFACE_VER_NAME;

    uint32_t numInputs = static_cast<uint32_t>(inputs.size());
    uint32_t numOutputs = static_cast<uint32_t>(outputs.size());

    // Build the input Qnn_Tensor_t array
    // Note: quantized buffers live in the member m_inQuantBuf so the clientBuf.data pointer
    // stays stable across graphExecute calls (the HTP context binds the input pointers)
    std::vector<Qnn_Tensor_t> inTensors(numInputs);
    if (m_inQuantBuf.size() < numInputs) m_inQuantBuf.resize(numInputs);
    if (m_inDims.size() < numInputs) m_inDims.resize(numInputs);

    for (uint32_t i = 0; i < numInputs; i++) {
        inTensors[i] = QNN_TENSOR_INIT;
        Qnn_TensorV1_t& t = inTensors[i].v1;
        t.name = inputs[i].name.c_str();
        t.type = QNN_TENSOR_TYPE_APP_WRITE;

        // Match the input info by name
        const TensorInfo* info = nullptr;
        uint32_t infoIdx = 0;
        for (uint32_t j = 0; j < m_inputInfos.size(); j++) {
            if (m_inputInfos[j].name == inputs[i].name) {
                info = &m_inputInfos[j];
                infoIdx = j;
                break;
            }
        }

        if (info) {
            t.id = m_data.inputIds[infoIdx];
            t.dataType = static_cast<Qnn_DataType_t>(info->dataType);
            t.rank = static_cast<uint32_t>(info->dims.size());
            m_inDims[i] = info->dims;
            t.dimensions = m_inDims[i].data();
            t.memType = QNN_TENSORMEMTYPE_RAW;

            // Set the quantization params (replay the original encoding; BW_SCALE_OFFSET requires bitwidth)
            if (info->quantEncoding == QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET) {
                t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET;
                t.quantizeParams.bwScaleOffsetEncoding.bitwidth = info->bitwidth;
                t.quantizeParams.bwScaleOffsetEncoding.scale = info->scale;
                t.quantizeParams.bwScaleOffsetEncoding.offset = info->offset;
            } else if (info->quantEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
                t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
                t.quantizeParams.scaleOffsetEncoding.scale = info->scale;
                t.quantizeParams.scaleOffsetEncoding.offset = info->offset;
            } else {
                t.quantizeParams.quantizationEncoding = static_cast<Qnn_QuantizationEncoding_t>(info->quantEncoding);
            }

            // Quantize float -> fixed
            // Note: QNN's offset for UFIXED_POINT_16 is int32_t, range [-32768, 32767]
            // Formula: quantized = clamp(round(float/scale) - offset, 0, 65535)
            // (offset 是零点偏移, float=0 时 quantized = -offset)
            // (offset is the zero-point shift; float=0 gives quantized = -offset)
            if (inputs[i].quantized) {
                // The caller already provides raw quantized data (streamed quantization while reading the file),
                // so just copy it and skip holding a float copy
                if (info->dataType == QNN_DATATYPE_UFIXED_POINT_16) {
                    m_inQuantBuf[i].ensure(inputs[i].count * sizeof(uint16_t));
                    std::memcpy(m_inQuantBuf[i].data(), inputs[i].data,
                                inputs[i].count * sizeof(uint16_t));
                } else if (info->dataType == QNN_DATATYPE_UFIXED_POINT_8) {
                    m_inQuantBuf[i].ensure(inputs[i].count * sizeof(uint8_t));
                    std::memcpy(m_inQuantBuf[i].data(), inputs[i].data,
                                inputs[i].count * sizeof(uint8_t));
                } else {
                    // Should not occur in practice (flagged quantized but the info is not quantized)
                    m_inQuantBuf[i].ensure(inputs[i].count * sizeof(float));
                    std::memcpy(m_inQuantBuf[i].data(), inputs[i].data,
                                inputs[i].count * sizeof(float));
                }
                t.clientBuf.data = m_inQuantBuf[i].data();
                t.clientBuf.dataSize = m_inQuantBuf[i].size();
            } else if (info->dataType == QNN_DATATYPE_UFIXED_POINT_16) {
                m_inQuantBuf[i].ensure(inputs[i].count * sizeof(uint16_t));
                floatToUfixed16(inputs[i].data,
                                reinterpret_cast<uint16_t*>(m_inQuantBuf[i].data()),
                                inputs[i].count, info->scale, info->offset);
                t.clientBuf.data = m_inQuantBuf[i].data();
                t.clientBuf.dataSize = inputs[i].count * sizeof(uint16_t);
            } else if (info->dataType == QNN_DATATYPE_UFIXED_POINT_8) {
                m_inQuantBuf[i].ensure(inputs[i].count * sizeof(uint8_t));
                floatToUfixed8(inputs[i].data, m_inQuantBuf[i].data(),
                              inputs[i].count, info->scale, info->offset);
                t.clientBuf.data = m_inQuantBuf[i].data();
                t.clientBuf.dataSize = inputs[i].count * sizeof(uint8_t);
            } else {
                // float32 passed through directly
                t.clientBuf.data = inputs[i].data;
                t.clientBuf.dataSize = inputs[i].count * sizeof(float);
            }
        } else {
            // No matching info, default to float32
            t.dataType = QNN_DATATYPE_FLOAT_32;
            t.memType = QNN_TENSORMEMTYPE_RAW;
            t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_UNDEFINED;
            t.clientBuf.data = inputs[i].data;
            t.clientBuf.dataSize = inputs[i].count * sizeof(float);
        }
    }

    // Build the output Qnn_Tensor_t array (member buffers keep the pointers stable as well)
    std::vector<Qnn_Tensor_t> outTensors(numOutputs);
    if (m_outQuantBuf.size() < numOutputs) m_outQuantBuf.resize(numOutputs);
    if (m_outDims.size() < numOutputs) m_outDims.resize(numOutputs);

    for (uint32_t i = 0; i < numOutputs; i++) {
        outTensors[i] = QNN_TENSOR_INIT;
        Qnn_TensorV1_t& t = outTensors[i].v1;
        t.name = outputs[i].name.c_str();
        t.type = QNN_TENSOR_TYPE_APP_READ;

        const TensorInfo* info = nullptr;
        uint32_t infoIdx = 0;
        for (uint32_t j = 0; j < m_outputInfos.size(); j++) {
            if (m_outputInfos[j].name == outputs[i].name) {
                info = &m_outputInfos[j];
                infoIdx = j;
                break;
            }
        }

        if (info) {
            t.id = m_data.outputIds[infoIdx];
            t.dataType = static_cast<Qnn_DataType_t>(info->dataType);
            t.rank = static_cast<uint32_t>(info->dims.size());
            m_outDims[i] = info->dims;
            t.dimensions = m_outDims[i].data();
            t.memType = QNN_TENSORMEMTYPE_RAW;

            // Set the quantization params (replay the original encoding; BW_SCALE_OFFSET requires bitwidth)
            if (info->quantEncoding == QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET) {
                t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET;
                t.quantizeParams.bwScaleOffsetEncoding.bitwidth = info->bitwidth;
                t.quantizeParams.bwScaleOffsetEncoding.scale = info->scale;
                t.quantizeParams.bwScaleOffsetEncoding.offset = info->offset;
            } else if (info->quantEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
                t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
                t.quantizeParams.scaleOffsetEncoding.scale = info->scale;
                t.quantizeParams.scaleOffsetEncoding.offset = info->offset;
            } else {
                t.quantizeParams.quantizationEncoding = static_cast<Qnn_QuantizationEncoding_t>(info->quantEncoding);
            }

            // Allocate the quantized receive buffer
            if (info->dataType == QNN_DATATYPE_UFIXED_POINT_16) {
                m_outQuantBuf[i].ensure(outputs[i].count * sizeof(uint16_t));
                t.clientBuf.data = m_outQuantBuf[i].data();
                t.clientBuf.dataSize = outputs[i].count * sizeof(uint16_t);
            } else if (info->dataType == QNN_DATATYPE_UFIXED_POINT_8) {
                m_outQuantBuf[i].ensure(outputs[i].count * sizeof(uint8_t));
                t.clientBuf.data = m_outQuantBuf[i].data();
                t.clientBuf.dataSize = outputs[i].count * sizeof(uint8_t);
            } else {
                t.clientBuf.data = outputs[i].data;
                t.clientBuf.dataSize = outputs[i].count * sizeof(float);
            }
        } else {
            t.dataType = QNN_DATATYPE_FLOAT_32;
            t.memType = QNN_TENSORMEMTYPE_RAW;
            t.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_UNDEFINED;
            t.clientBuf.data = outputs[i].data;
            t.clientBuf.dataSize = outputs[i].count * sizeof(float);
        }
    }

    // Run graphExecute
    LOGI("graphExecute before RSS=%zuKB", selfVmRSS_kB());
    Qnn_ErrorHandle_t err = qnn.graphExecute(
        m_graphHandle,
        inTensors.data(), numInputs,
        outTensors.data(), numOutputs,
        nullptr, nullptr);
    LOGI("graphExecute after RSS=%zuKB", selfVmRSS_kB());

    if (QNN_SUCCESS != err) {
        LOGE("graphExecute failed, error=0x%llx", (unsigned long long)err);
        return -2;
    }

    // Debug: log input tensor stats (min, max, mean)
    // Note: with quantized=true, data holds raw uint16/uint8 bytes; reading them as float is out of bounds and meaningless, so skip
    for (uint32_t i = 0; i < numInputs; i++) {
        if (inputs[i].quantized) continue;
        float mn = 1e30f, mx = -1e30f, sum = 0.0f;
        size_t cnt = std::min((size_t)inputs[i].count, (size_t)1000);
        for (size_t j = 0; j < cnt; j++) {
            float v = inputs[i].data[j];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            sum += v;
        }
        LOGI("  EXEC Input[%u] '%s' count=%zu min=%.4f max=%.4f mean=%.4f (sampled %zu)",
             i, inputs[i].name.c_str(), (size_t)inputs[i].count, mn, mx, sum / cnt, cnt);
    }

    // keepOutputQuantized: outputs keep the raw quantized data (the caller dequantizes per tensor while persisting),
    // skipping dequantization and stats to avoid double-storing full float output buffers alongside the quantized ones
    if (keepOutputQuantized) {
        return 0;
    }

    // Dequantize outputs: fixed -> float
    for (uint32_t i = 0; i < numOutputs; i++) {
        const TensorInfo* info = nullptr;
        for (const auto& oi : m_outputInfos) {
            if (oi.name == outputs[i].name) {
                info = &oi;
                break;
            }
        }
        if (info) {
            if (info->dataType == QNN_DATATYPE_UFIXED_POINT_16) {
                ufixed16ToFloat(reinterpret_cast<const uint16_t*>(m_outQuantBuf[i].data()),
                                outputs[i].data, outputs[i].count,
                                info->scale, info->offset);
            } else if (info->dataType == QNN_DATATYPE_UFIXED_POINT_8) {
                ufixed8ToFloat(m_outQuantBuf[i].data(),
                               outputs[i].data, outputs[i].count,
                               info->scale, info->offset);
            }
            // float32 was already written directly to outputs[i].data
        }
    }

    // Debug: log output tensor stats (min, max, mean)
    for (uint32_t i = 0; i < numOutputs; i++) {
        int dtype = -1;
        for (const auto& oi : m_outputInfos) {
            if (oi.name == outputs[i].name) {
                dtype = (int)oi.dataType;
                break;
            }
        }
        float mn = 1e30f, mx = -1e30f, sum = 0.0f;
        size_t cnt = std::min((size_t)outputs[i].count, (size_t)1000);
        for (size_t j = 0; j < cnt; j++) {
            float v = outputs[i].data[j];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            sum += v;
        }
        LOGI("  EXEC Output[%u] '%s' dtype=%d count=%zu min=%.4f max=%.4f mean=%.4f (sampled %zu)",
             i, outputs[i].name.c_str(), dtype,
             (size_t)outputs[i].count, mn, mx, sum / cnt, cnt);
    }

    return 0;
}

// ==============  ==============
// ============== freeContext: free context+graph + the shared-state reference ==============

void HtpRuntime::freeContext() {
    // Free this runtime's context+graph (shared backend/device/log stay alive for other runtimes)
    freeGraph();
    m_ready = false;
}

} // namespace qnn
