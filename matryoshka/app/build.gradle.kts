import javax.inject.Inject
import org.gradle.process.ExecOperations

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
        versionCode = 141
        versionName = "14.1-nest"

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

    signingConfigs {
        // Shared tester key (app/signing/README.md): every build installs over the previous one.
        getByName("debug") {
            storeFile = file("signing/tester.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the shared tester key (see signingConfigs) unless a release key is configured.
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

/**
 * The guest agent (nest/guest-agent): Java code that runs inside the guest under app_process. It is
 * compiled against stubs of the guest's framework classes and dexed on its own into
 * assets/nest-agent.jar; the app binds it into the guest at boot.
 */
abstract class GuestAgentTask : DefaultTask() {
    @get:InputDirectory abstract val sourceDir: DirectoryProperty
    @get:Input abstract val d8: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val classes = File(temporaryDir, "classes").apply { deleteRecursively(); mkdirs() }
        val sources = sourceDir.get().asFile.walk().filter { it.isFile && it.name.endsWith(".java") }.map { it.absolutePath }.toList()
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler() ?: throw GradleException("No Java compiler")
        val rc = javac.run(null, null, null, *(listOf("--release", "8", "-nowarn", "-d", classes.absolutePath) + sources).toTypedArray())
        if (rc != 0) throw GradleException("Guest agent does not compile")
        // Only our own classes: the framework stubs are provided by the guest.
        val own = File(classes, "dev").walk().filter { it.isFile && it.name.endsWith(".class") }.map { it.absolutePath }.toList()
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        exec.exec { commandLine(listOf(d8.get(), "--release", "--min-api", "30", "--output", File(out, "nest-agent.jar").absolutePath) + own) }
    }
}

val guestAgent = tasks.register<GuestAgentTask>("buildGuestAgent") {
    sourceDir.set(rootProject.layout.projectDirectory.dir("nest/guest-agent"))
    // d8 of AGP's build-tools, or the newest installed ones
    d8.set(provider {
        val bt = android.sdkDirectory.resolve("build-tools")
        val preferred = bt.resolve("${android.buildToolsVersion}/d8")
        (if (preferred.exists()) preferred
        else bt.listFiles().orEmpty().map { it.resolve("d8") }.filter { it.exists() }.maxByOrNull { it.parentFile.name }
            ?: throw GradleException("No build-tools with d8 in ${bt}")).absolutePath
    })
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(guestAgent, GuestAgentTask::outputDir) }
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

// ---------------------------------------------------------------------------------------------
// 32-bit ARM copy of the fast-path library for 32-bit guest processes (zygote32, 32-bit games).
// The app installs as 64-bit, and Android extracts only lib/arm64-v8a, so the armeabi-v7a build
// is shipped inside that directory as libnest_preload32.so.
val nest32Out = layout.buildDirectory.dir("generated/nest32/jniLibs")
val buildNestPreload32 by tasks.registering {
    val sdkDir = android.sdkDirectory
    val ndkVersion = android.ndkVersion
    val src = file("../nest")
    val work = layout.buildDirectory.dir("nest32").get().asFile
    val out = nest32Out.get().dir("arm64-v8a").asFile
    inputs.dir(src)
    outputs.dir(out)
    doLast {
        val ndk = File(sdkDir, "ndk/$ndkVersion")
        val cmakeBin = File(sdkDir, "cmake/3.22.1/bin")
        work.mkdirs()
        out.mkdirs()
        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).directory(work).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("nest32 build failed: ${cmd.joinToString(" ")}\n$log")
        }
        run(
            File(cmakeBin, "cmake").path, "-S", src.path, "-B", work.path, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${File(cmakeBin, "ninja").path}",
            "-DCMAKE_TOOLCHAIN_FILE=${File(ndk, "build/cmake/android.toolchain.cmake").path}",
            "-DANDROID_ABI=armeabi-v7a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
            "-DCMAKE_BUILD_TYPE=Release", "-DNEST_PRELOAD_ONLY=ON",
        )
        run(File(cmakeBin, "cmake").path, "--build", work.path)
        File(work, "libnest_preload.so").copyTo(File(out, "libnest_preload32.so"), overwrite = true)
    }
}
android.sourceSets["main"].jniLibs.srcDir(nest32Out)
tasks.named("preBuild") { dependsOn(buildNestPreload32) }
