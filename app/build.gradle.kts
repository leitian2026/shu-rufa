plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名:CI 里如果配置了 KEYSTORE_* 环境变量就用你自己的密钥,
// 否则退回 debug 密钥(自用能装,但每次构建的密钥不同,覆盖安装需先卸载旧版)。
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasReleaseKey: Boolean = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.example.myime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myime"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 先关闭,以后要开:把下面两个改成 true 即可
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 自用项目,不让 lint 阻塞发布构建
        checkReleaseBuilds = false
        abortOnError = false
    }
}
