plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.example.hiderecents"
    compileSdk = 36

    // ADB host 依赖 BouncyCastle，几个 jar 里有同路径的 META-INF 条目，必须排除掉
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    defaultConfig {
        applicationId = "com.tools.systemtool"
        minSdk = 26
        targetSdk = 34
        versionCode = 490

        versionName = "3.3.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // 用debug keystore 签名，保证可原地覆盖同签名的旧包
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 服务�?AIDL（IShizukuService/IRemoteProcess）：用户服务连不上时用newProcess 兜底执行命令
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // SIM 国家码：ICarrierConfigLoader / IActivityManager 都是隐藏内部类，targetSdk 34 下
    // 不豁免 hidden API 就反射不到，这里不替换 android.jar，只加运行时豁免
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // ADB host（无线调试配对）：另一条 shell 授权来源，不依赖 Shizuku
    // ADB host：无线调试配对，另一条 shell 授权来源（beta 专用）
    implementation("com.github.Ernest-su:adb:v0.2.1")
    // 上面两个库带进来的 native 库版本偏旧，未做 16 KB 页对齐，系统会弹兼容性警告
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("androidx.graphics:graphics-path:1.1.0")

    // ---- Compose (UI 重写�? ----
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
