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

// ---------------------------------------------------------------- 签名
// 覆盖安装要求每次构建用同一把密钥。优先级:
//   1) 环境变量 KEYSTORE_PATH(+ KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD),来自 GitHub Secrets
//   2) 仓库里的 signing/myime.jks(自用,密码见下)
//   3) 都没有才退回 debug 密钥(此时无法覆盖安装)
val envKeystore: String? = System.getenv("KEYSTORE_PATH")?.takeIf { file(it).exists() }
val repoKeystore: File? = rootProject.file("signing/myime.jks").takeIf { it.exists() }
val repoKeystorePassword = "myime-self-use"
val hasReleaseKey: Boolean = envKeystore != null || repoKeystore != null

android {
    namespace = "com.example.myime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myime"
        minSdk = 26
        targetSdk = 34
        versionCode = verMajor * 10_000_000 + verMinor * 100_000 + verPatch * 1_000 + buildNumber
        versionName = "$verMajor.$verMinor.$verPatch"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                if (envKeystore != null) {
                    storeFile = file(envKeystore)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                } else {
                    storeFile = repoKeystore
                    storePassword = repoKeystorePassword
                    keyAlias = "myime"
                    keyPassword = repoKeystorePassword
                }
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
