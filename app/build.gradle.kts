plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.qtwl.gateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qtwl.gateway"
        minSdk = 24
        targetSdk = 36
versionCode = 224
        versionName = "3.18.35"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 只保留 arm64：llama.cpp 等 .so 需要
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ★★ 双轨包名：正式版 formal（com.qtwl.gateway）/ 测试版 cs（com.qtwl.gateway.cs），同一 qitong.jks 签名可共存 ★★
    flavorDimensions += "track"
    productFlavors {
        create("formal") {
            dimension = "track"
            applicationId = "com.qtwl.gateway"
            versionName = "3.18.35"
        }
        create("cs") {
            dimension = "track"
            applicationId = "com.qtwl.gateway.cs"
            versionName = "3.18.35-21"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    val releaseKeystore = file("qitong.jks")
    val releaseStorePassword = providers.gradleProperty("QITONG_STORE_PASSWORD")
        .orElse(providers.environmentVariable("QITONG_STORE_PASSWORD"))
    val releaseKeyAlias = providers.gradleProperty("QITONG_KEY_ALIAS")
        .orElse(providers.environmentVariable("QITONG_KEY_ALIAS"))
    val releaseKeyPassword = providers.gradleProperty("QITONG_KEY_PASSWORD")
        .orElse(providers.environmentVariable("QITONG_KEY_PASSWORD"))

    signingConfigs {
        if (releaseKeystore.exists() && releaseStorePassword.isPresent && releaseKeyAlias.isPresent && releaseKeyPassword.isPresent) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // llama.cpp 运行时以 lib*.so 形式打包，关闭压缩以便安装时解压到 nativeLibraryDir
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Force ARM64 AAPT2 only on Linux/aarch64 (for on-device/Termux builds).
// Desktop Windows/macOS/Linux builds must use Gradle's native host artifact.
val isLinuxArm64Host = System.getProperty("os.name").contains("linux", ignoreCase = true) &&
    System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
if (isLinuxArm64Host) {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Ktor Server
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager (定时备份)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Shizuku（本地模型保活 + 后台命令）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // SSH 隧道（公网映射）
    implementation("com.github.mwiede:jsch:0.2.20")
    // Miuix（HyperOS 组件色板）
    implementation(libs.miuix)
    // LiquidGlass 玻璃拟态（kyant）
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.capsule)
    implementation(libs.kyant.shapes)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
