plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hermes.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hermes.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 37
        versionName = "0.13.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 云环境明确选择保留下来的调试签名，避免环境恢复后重新生成而无法覆盖旧版。
    signingConfigs.getByName("debug") {
        providers.gradleProperty("hermesDebugKeystore").orNull?.let { storeFile = file(it) }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 骨架阶段先用 debug 签名，方便直接安装 release 包体验性能
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.backdrop)
    implementation(libs.okhttp)
    // SSH 隧道。Android 自带的 BouncyCastle 是阉割版，打包完整版（启动时替换掉系统的）
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasklist)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
