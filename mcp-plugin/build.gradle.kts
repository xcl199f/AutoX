plugins {
    id("com.android.application")
    id("kotlin-android")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versions.javaVersionInt))
    }
}

android {
    // 与源码包名保持一致，插件内部的 R 解析到本模块资源
    namespace = "org.autojs.autoxjs.mcp"
    compileSdk = versions.compile

    defaultConfig {
        applicationId = "org.autojs.autoxjs.mcp.plugin"
        minSdk = versions.mini
        targetSdk = versions.target
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 插件以独立 APK 安装，release 沿用 debug 签名以便本地安装验证
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 宿主提供：插件不打包宿主代码，运行时由宿主 ClassLoader 提供
    compileOnly(project(":autojs"))
    compileOnly(project(":plugin-api"))
    // 宿主 API 签名中出现的类型，同样由宿主在运行时提供
    compileOnly(libs.mozilla.rhino)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.gson)
    implementation(libs.bundles.ktor)
    // 插件不自带界面，因此不依赖 appcompat / preference
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    // 单测要构造 PluginConfigHost 的桩实现，而主源码集只声明了 compileOnly
    testImplementation(project(":plugin-api"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// compileOnly 引入的宿主依赖只用于编译期取签名，插件不会打包宿主代码。
// 但 AGP 的 consistent resolution 会把 compileClasspath 严格锁定在 runtimeClasspath
// 解析出的版本，而宿主依赖图（经 :common 的 Compose）带来的是更高版本，
// 两者无法同时满足时编译类路径会整体解析失败，并让 processReleaseResources
// 抛出一个被 Gradle 吞掉真实原因的 NPE（Cannot invoke "java.util.List.get(int)"）。
// 这里把宿主侧较高的版本强制对齐过来，保证编译类路径可解析。
// 若日后升级依赖后再次出现该 NPE，按 memory/2026-10-30.md 里的探针方法列出冲突坐标再补到这里。
configurations.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.20",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.20",
            "org.jetbrains:annotations:23.0.0",
            "androidx.annotation:annotation:1.8.1",
            "androidx.annotation:annotation-jvm:1.8.1",
            "androidx.lifecycle:lifecycle-runtime:2.8.3",
            "androidx.lifecycle:lifecycle-common:2.8.3",
        )
    }
}
