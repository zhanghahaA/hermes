plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    application
}

kotlin {
    // 不锁定 JDK 版本：用运行 Gradle 的那个 JDK（17 及以上）
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

sourceSets {
    main {
        kotlin {
            // 直接编译 app 里的界面代码，只排除依赖 Android 的两个文件（桌面版替身在 src/main/kotlin）
            srcDir("../../app/src/main/java")
            exclude("com/hermes/mobile/MainActivity.kt", "com/hermes/mobile/platform/Platform.kt")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

application {
    mainClass.set("com.hermes.mobile.preview.RenderKt")
}

tasks.named<JavaExec>("run") {
    // 输出目录：tools/ui-preview/screenshots
    args(layout.projectDirectory.dir("screenshots").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
