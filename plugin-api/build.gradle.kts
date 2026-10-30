plugins {
    id("com.android.library")
    id("kotlin-android")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versions.javaVersionInt))
    }
}

android {
    namespace = "org.autojs.plugin.api"
    compileSdk = versions.compile

    defaultConfig {
        minSdk = versions.mini
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 纯契约模块：不引入任何第三方依赖，插件与宿主各自提供实现
}
