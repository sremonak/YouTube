plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is read from environment variables (set by GitHub Actions secrets)
// so that no key material is ever committed to the repository.
val releaseKeystore: String? = System.getenv("VIDORA_KEYSTORE_FILE")
val hasReleaseKey = !releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()

// Derive the runtime pin from the actual signing certificate, never from an installed APK.
// The public fallback key preserves upgrades but is NOT a private anti-clone identity.
val signingCertificateSha256 = run {
    val store = java.security.KeyStore.getInstance("PKCS12")
    val path = if (hasReleaseKey) file(releaseKeystore!!) else file("debug.keystore")
    val password = if (hasReleaseKey) System.getenv("VIDORA_KEYSTORE_PASSWORD") else "android"
    val alias = if (hasReleaseKey) System.getenv("VIDORA_KEY_ALIAS") else "androiddebugkey"
    path.inputStream().use { store.load(it, requireNotNull(password).toCharArray()) }
    val certificate = requireNotNull(store.getCertificate(requireNotNull(alias))) { "Signing certificate missing" }
    java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
}

val ciVersionCode = System.getenv("VIDORA_VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("VIDORA_VERSION_NAME")

android {
    namespace = "com.vidora.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vidora.app"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
        buildConfigField("String", "TRUSTED_SIGNER_SHA256", "\"$signingCertificateSha256\"")
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Public, non-secret debug key committed to the repo so that every CI build is signed
        // with the same certificate (lets users update without uninstalling).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("VIDORA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VIDORA_KEY_ALIAS")
                keyPassword = System.getenv("VIDORA_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        getByName("release") {
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
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Compiles the bundled baseline profiles (Compose, Media3 + ours) on sideloaded installs → faster start & scrolling
    implementation(libs.androidx.profileinstaller)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.newpipe.extractor)

    testImplementation(libs.junit)

    // End-to-end playback checks on an emulator (.github/workflows/e2e.yml)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.junit)
}
