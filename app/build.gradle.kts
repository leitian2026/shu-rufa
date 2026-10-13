import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 版本号
// versionName 给人看:主.次.修订,取自根目录 version.properties
// versionCode 给系统比较,必须只增不减,覆盖安装才会成功:
//   主*10,000,000 + 次*100,000 + 修订*1,000 + 构建号(CI 的运行序号,本地为 0,最大 999)
// 同一个版本号重复构建,构建号也会让 versionCode 继续变大。
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val verMajor = versionProps.getProperty("MAJOR").trim().toInt()
val verMinor = versionProps.getProperty("MINOR").trim().toInt()
val verPatch = versionProps.getProperty("PATCH").trim().toInt()
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0).coerceIn(0, 999)

// ---------------------------------------------------------------- 签名 & 备份密码(都从环境变量读取)
// 覆盖安装要求每次构建用同一把密钥。以下环境变量由 GitHub Secrets 提供(见 .github/workflows/build-release.yml):
//   KEYSTORE_PATH(工作流用 KEYSTORE_BASE64 解出的 .jks 路径) / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//   BACKUP_PASSWORD  备份文件的加密密码(编进 BuildConfig,不写在源码里)
// 打 release 包时任何一项缺失都会直接报错,避免悄悄用 debug 密钥打出无法覆盖安装的包。
val envKeystore: String? = System.getenv("KEYSTORE_PATH")?.takeIf { file(it).exists() }
val backupPassword: String = System.getenv("BACKUP_PASSWORD") ?: ""
val hasReleaseKey: Boolean = envKeystore != null &&
    !System.getenv("KEYSTORE_PASSWORD").isNullOrEmpty() &&
    !System.getenv("KEY_ALIAS").isNullOrEmpty() &&
    !System.getenv("KEY_PASSWORD").isNullOrEmpty()

gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":app:assembleRelease" || it.path == ":app:packageRelease" }) {
        if (!hasReleaseKey) throw GradleException("缺少签名环境变量:KEYSTORE_PATH(指向存在的 .jks)、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD")
        if (backupPassword.isEmpty()) throw GradleException("缺少环境变量 BACKUP_PASSWORD(备份密码)")
    }
}

android {
    namespace = "com.example.myime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myime"
        minSdk = 26
        targetSdk = 34
        versionCode = verMajor * 10_000_000 + verMinor * 100_000 + verPatch * 1_000 + buildNumber
        versionName = "$verMajor.$verMinor.$verPatch"
        val esc = backupPassword.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "BACKUP_PASSWORD", "\"$esc\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(envKeystore!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8:去掉没用到的代码和资源,APK 更小、启动略快。
            // 本项目没有反射和第三方库,清单里声明的组件会自动保留;若发布版出现闪退,把下面两个改回 false 排查
            isMinifyEnabled = true
            isShrinkResources = true
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
