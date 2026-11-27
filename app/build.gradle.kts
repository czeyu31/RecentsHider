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

    // ADB host 渚濊禆 BouncyCastle锛屽嚑涓?jar 閲屾湁鍚岃矾寰勭殑 META-INF 鏉＄洰锛屽繀椤绘帓闄ゆ帀
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
        versionCode = 493

        versionName = "3.3.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // 鐢╠ebug keystore 绛惧悕锛屼繚璇佸彲鍘熷湴瑕嗙洊鍚岀鍚嶇殑鏃у寘
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
    // 鏈嶅姟锟?AIDL锛圛ShizukuService/IRemoteProcess锛夛細鐢ㄦ埛鏈嶅姟杩炰笉涓婃椂鐢╪ewProcess 鍏滃簳鎵ц鍛戒护
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // SIM 鍥藉鐮侊細ICarrierConfigLoader / IActivityManager 閮芥槸闅愯棌鍐呴儴绫伙紝targetSdk 34 涓?
    // 涓嶈眮鍏?hidden API 灏卞弽灏勪笉鍒帮紝杩欓噷涓嶆浛鎹?android.jar锛屽彧鍔犺繍琛屾椂璞佸厤
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // ADB host锛堟棤绾胯皟璇曢厤瀵癸級锛氬彟涓€鏉?shell 鎺堟潈鏉ユ簮锛屼笉渚濊禆 Shizuku
    // ADB host锛氭棤绾胯皟璇曢厤瀵癸紝鍙︿竴鏉?shell 鎺堟潈鏉ユ簮锛坆eta 涓撶敤锛?
    implementation("com.github.Ernest-su:adb:v0.2.1")
    // 涓婇潰涓や釜搴撳甫杩涙潵鐨?native 搴撶増鏈亸鏃э紝鏈仛 16 KB 椤靛榻愶紝绯荤粺浼氬脊鍏煎鎬ц鍛?
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("androidx.graphics:graphics-path:1.1.0")

    // ---- Compose (UI 閲嶅啓锟? ----
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

