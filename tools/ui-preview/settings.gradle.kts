// 独立的桌面预览工程：用 Compose Desktop 编译 app 里的界面代码并渲染手机尺寸截图。
// 不需要 Android SDK，可在 CI 或没有 SDK 的机器上检查界面能否编译、布局长什么样。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "hermes-ui-preview"
