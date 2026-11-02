plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.matryoshka.vm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.matryoshka.vm"
        minSdk = 26
        // targetSdk 28 on purpose: from 29 on Android forbids exec() of files in app data (W^X),
        // and the Nest engine runs the guest system's own binaries from there (like Termux).
        // Consequence: the APK is distributed outside Google Play.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0-nest"

        // Phones only: the Nest engine and the guest image are arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }

        buildConfigField("String", "SCRCPY_SERVER_VERSION", "\"2.7\"")
        // Temporary tester tools (log export button). Turn off for public releases.
        buildConfigField("boolean", "TESTER_TOOLS", "true")
        buildConfigField(
            "String",
            "DEFAULT_IMAGE_CATALOG",
            "\"https://github.com/wddis/pol/releases/latest/download/catalog.json\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI signs with the debug key unless a release keystore is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("../nest/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        // Intentional, see targetSdk above.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The scrcpy server is pushed into the guest as-is; never compress or alter it.
        noCompress += listOf("jar")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshot tests (Roborazzi) render the real Compose UI on the JVM.
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // Optional Maven mirror for Robolectric's android-all jars: -Probolectric.repo=<url>
            providers.gradleProperty("robolectric.repo").orNull?.let { repo ->
                it.systemProperty("robolectric.dependency.repo.url", repo)
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libnest_run.so is an executable: it must exist as a real file in nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
