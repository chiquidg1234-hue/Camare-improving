plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lumacam"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.lumacam"
        // Android 10+. El Honor X7c trae Android 14 (API 34).
        minSdk = 29
        targetSdk = 35
        // En GitHub Actions cada compilación sube el número de versión para poder instalar encima.
        // +100 para quedar por encima de las compilaciones del repo anterior (Camera-).
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + buildNumber
        versionName = "0.2.$buildNumber"

        // Enlace fijo de descarga del último APK (GitHub Releases); lo usa el QR de "Invitar".
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "chiquidg1234-hue/Camare-improving"
        buildConfigField("String", "DOWNLOAD_URL", "\"https://github.com/$repo/releases/latest/download/LumaCam.apk\"")
        buildConfigField("String", "RELEASES_URL", "\"https://github.com/$repo/releases/latest\"")
    }

    signingConfigs {
        // Clave fija (no secreta) para que cada APK nuevo se instale encima del anterior.
        getByName("debug") {
            storeFile = file("lumacam-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // El procesado de fotos es pesado; con R8 desactivado en debug igual va bien,
            // pero el código Kotlin optimizado de release es bastante más rápido.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // APK para instalar y compartir: sin "debuggable" (el procesado de fotos va mucho
            // más rápido) y sin R8 para no arriesgar que quite clases de CameraX/ML Kit.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Misma clave fija que debug: cada versión se instala encima de la anterior.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.lumacam.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
