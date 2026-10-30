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
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.preference.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
