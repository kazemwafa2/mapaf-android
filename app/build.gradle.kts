plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "af.mapaf.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "af.mapaf.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // installable release build
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        // keep assets readable via AssetFileDescriptor (voice mp3, chime wav, glyph pbf...)
        noCompress.addAll(listOf("mp3", "wav", "pbf", "pmtiles", "geojson", "json", "png"))
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging {
        resources.excludes += setOf("META-INF/*.properties")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // MapLibre GL — free & open-source map engine (map.af itself is built on MapLibre)
    implementation("org.maplibre.gl:android-sdk:11.8.5")
}
