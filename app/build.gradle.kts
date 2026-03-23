plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)

    id("com.google.gms.google-services")
}

android {
    namespace = "com.abang.prayerzones"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abang.prayerzones"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DONATION_WORKER_BASE_URL", "\"https://prayer-zones.pages.dev/\"")
    }

    buildTypes {
        // This is the part that fixes "Application not inspectable"
        getByName("debug") {
            isDebuggable = true
            // Important for Xiaomi/HyperOS to allow tools to connect
            manifestPlaceholders["android:testOnly"] = false

            // ✅ FIX #4: Build timestamp for tracking test builds
            buildConfigField("String", "BUILD_TIMESTAMP", "\"${System.currentTimeMillis()}\"")

            // Disabling coverage can speed up the build slightly
            enableAndroidTestCoverage = false
            enableUnitTestCoverage = false
        }

        getByName("release") {
            // KEEP these for your actual app store version
            isMinifyEnabled = true
            isShrinkResources = true

            // ✅ ADD THIS (fix warning about native debug symbols)
            ndk {
                debugSymbolLevel = "FULL"
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this
            if (output is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                // Example: PrayerTimeCompare-1.0-release.apk
                output.outputFileName = "${rootProject.name}-${variant.versionName}-${variant.buildType.name}.apk"
            }
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // AndroidX & Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.constraintlayout.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Networking (Retrofit)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (for TTS announcements)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Preferences (for Settings screens)
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Media
    implementation(libs.androidx.media)

    // Google Play Services Location for Qibla compass
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.truth)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.espresso.core)

    implementation("androidx.browser:browser:1.8.0")

    // Adhan library for offline prayer time calculations (v1.x for Kotlin 1.9 compatibility can use latest version also)
    implementation("com.batoulapps.adhan:adhan:1.2.1")

    // Firebase Analytics (using BOM for version management)
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    //implementation("com.google.firebase:firebase-analytics")

    // Optional Firebase dependencies (add as needed)
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")

}
