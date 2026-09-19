import org.gradle.api.tasks.Copy
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 读取 local.properties 中的 QNN SDK 路径
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val qnnSdkDir: String = (project.findProperty("qnn.sdk.dir") as String?)
    ?: localProps.getProperty("qnn.sdk.dir")
    ?: error("请在 local.properties 中设置 qnn.sdk.dir")

android {
    namespace = "com.sharp.qnn"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.sharp.qnn"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O2 -fexceptions -frtti"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DQNN_SDK_DIR=$qnnSdkDir"
            }
        }
    }

    // 签名配置: Release 使用调试密钥库 (开发测试用)
    // Signing config: Release uses the debug keystore (for development testing)
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 避免 .so 打包冲突
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // QNN 的 .so 不参与 link, 仅打包
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// copyQnnSkel 输出到 src/main/assets, copyQnnLibs 输出到 src/main/jniLibs,
// 需与对应 merge 任务建立显式依赖, 避免 Gradle 隐式依赖校验报错
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn("copyQnnSkel")
}
tasks.matching { it.name.contains("Lint", ignoreCase = true) }.configureEach {
    dependsOn("copyQnnSkel")
}
tasks.matching { it.name.matches(Regex("merge.*JniLib.*")) }.configureEach {
    dependsOn("copyQnnLibs")
}

// 任务: 从 QNN SDK 复制 Android HTP 库到 jniLibs
// 执行: ./gradlew copyQnnLibs
tasks.register<Copy>("copyQnnLibs") {
    description = "从 QNN SDK 复制 Android HTP .so 到 jniLibs"
    group = "build setup"

    val jniDir = file("src/main/jniLibs/arm64-v8a")
    val sdkLibDir = file("$qnnSdkDir/lib/aarch64-android")

    // 核心 QNN 库
    from(sdkLibDir) {
        include(
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnSystem.so",
            "libQnnHtpNetRunExtensions.so",
            "libQnnModelDlc.so",
            // 6 个 HTP 版本的 Stub
            "libQnnHtpV68Stub.so",
            "libQnnHtpV69Stub.so",
            "libQnnHtpV73Stub.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV79Stub.so",
            "libQnnHtpV81Stub.so",
            // Calculator Stub
            "libQnnHtpV68CalculatorStub.so",
            "libQnnHtpV69CalculatorStub.so",
            "libQnnHtpV73CalculatorStub.so",
            "libQnnHtpV75CalculatorStub.so",
            "libQnnHtpV79CalculatorStub.so",
            "libQnnHtpV81CalculatorStub.so"
        )
    }
    into(jniDir)
}

// 任务: 从 QNN SDK 复制 Hexagon DSP Skel 库到 assets
// 执行: ./gradlew copyQnnSkel
tasks.register<Copy>("copyQnnSkel") {
    description = "从 QNN SDK 复制 Hexagon Skel .so 到 assets (运行时提取)"
    group = "build setup"

    val assetDir = file("src/main/assets/qnn_skel")
    val versions = listOf("v68", "v69", "v73", "v75", "v79", "v81")

    versions.forEach { ver ->
        from("$qnnSdkDir/lib/hexagon-$ver/unsigned") {
            include("libQnnHtp${ver.replaceFirstChar { it.uppercase() }}Skel.so")
            into(ver)
        }
    }
    into(assetDir)
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // DataStore (模型元数据持久化)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 文档/文件选择
    implementation("androidx.documentfile:documentfile:1.0.1")

    // EXIF 读取 (焦距等信息)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}