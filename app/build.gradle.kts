import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val releaseKeystore = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releaseKeystore.exists()) releaseKeystore.inputStream().use { load(it) }
}

android {
    namespace = "com.andrew.proxyapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andrew.proxyapp"
        minSdk = 26           // sing-box libbox 要求 API 26+
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 仅构建 arm64-v8a 架构（ARMv8 / 64位），减小 APK 体积并加快编译
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // libbox.aar is intentionally arm64-only; ChromeOS x86 translation is unsupported.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "ChromeOsAbiSupport")
    }

    // 压缩 APK 中的 .so 文件（libbox.so 从 60MB 压缩到 ~18MB）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ======================================================
    // libbox.aar 放在 app/libs/ 目录下
    // 下载地址（选其中之一）：
    //   官方：https://github.com/SagerNet/sing-box/releases
    //         找 libbox-android.aar 或同等文件
    //   或在 CI 构建: gomobile bind -target android ./mobile/...
    // ======================================================
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ★ sing-box libbox 本地 AAR（核心依赖，需手动下载放入 app/libs/）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.gson)
    implementation(libs.androidx.recyclerview)
    implementation(libs.yamlkt)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
