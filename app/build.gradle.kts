import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.nocturne"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "app.nocturne.piano"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                targets += listOf("nocturne")
            }
        }
    }

    // Release builds are signed with a private upload key that is never committed:
    // either keystore.properties in the project root or NOCTURNE_* environment variables (CI).
    val releaseSigning = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
        System.getenv("NOCTURNE_KEYSTORE")?.let { setProperty("storeFile", it) }
        System.getenv("NOCTURNE_KEYSTORE_PASSWORD")?.let { setProperty("storePassword", it) }
        System.getenv("NOCTURNE_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
        System.getenv("NOCTURNE_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
    }
    val hasReleaseKey = releaseSigning.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

    signingConfigs {
        getByName("debug") {
            // Shared debug key so debug builds install over each other on any machine.
            storeFile = rootProject.file("keystore/debug.jks")
            storePassword = "nocturne"
            keyAlias = "nocturne"
            keyPassword = "nocturne"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a private key the release APK is produced unsigned (never with the debug key).
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    androidResources {
        // SF3 packs are already Ogg-compressed; storing them uncompressed lets the
        // native transcoder memory-map them straight out of the APK.
        noCompress += listOf("sf3")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("com.google.oboe:oboe:1.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
}
