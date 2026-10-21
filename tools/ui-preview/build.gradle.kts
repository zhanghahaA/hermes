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
            // 直接编译 app 里的界面和数据代码，只排除依赖 Android 的文件（需要替身的放在 src/main/kotlin）
            srcDir("../../app/src/main/java")
            exclude(
                "com/hermes/mobile/MainActivity.kt",
                "com/hermes/mobile/HermesApplication.kt",
                "com/hermes/mobile/platform/RunService.kt",
                "com/hermes/mobile/platform/ReplyNotifier.kt",
                "com/hermes/mobile/platform/AttachmentImport.kt",
                "com/hermes/mobile/platform/Platform.kt",
                "com/hermes/mobile/platform/AndroidStorage.kt",
                "com/hermes/mobile/platform/ScreenWebView.kt",
            )
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.hierynomus:sshj:0.41.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    // 测试里起一个真正的 SSH 服务器，验证隧道
    testImplementation("org.apache.sshd:sshd-core:2.20.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.20.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

application {
    mainClass.set("com.hermes.mobile.preview.RenderKt")
}

tasks.named<JavaExec>("run") {
    // 输出目录：tools/ui-preview/screenshots
    args(layout.projectDirectory.dir("screenshots").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

// 仅本机假 SSH/API，供 adb reverse 后在 Android 上运行真实网络栈自检。
tasks.register<JavaExec>("androidSshFixture") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.hermes.mobile.data.ssh.AndroidSshFixture")
    args(providers.gradleProperty("androidSshFixtureDir").getOrElse("build/android-ssh-fixture"))
}
