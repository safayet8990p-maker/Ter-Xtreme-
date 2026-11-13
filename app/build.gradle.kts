import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.secrets)
}

abstract class ExtractXtermAssetsTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val xtermJars: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun extract() {
    val vendorDir = outputDir.get().dir("vendor").asFile
    vendorDir.deleteRecursively()
    vendorDir.mkdirs()

    val targets =
      mapOf(
        "/lib/xterm.js" to "xterm.js",
        "/css/xterm.css" to "xterm.css",
        "/lib/addon-fit.js" to "addon-fit.js",
      )

    xtermJars.files.forEach { jarFile ->
      if (!jarFile.exists() || !jarFile.name.endsWith(".jar")) return@forEach
      ZipFile(jarFile).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (entry.isDirectory) continue
          val destName =
            targets.entries.firstOrNull { entry.name.endsWith(it.key) }?.value ?: continue
          zip.getInputStream(entry).use { input ->
            vendorDir.resolve(destName).outputStream().use { output -> input.copyTo(output) }
          }
        }
      }
    }

    check(vendorDir.resolve("xterm.js").exists()) {
      "xterm.js not found in xtermAssets jars: ${xtermJars.files.map { it.name }}"
    }
    check(vendorDir.resolve("xterm.css").exists()) {
      "xterm.css not found in xtermAssets jars: ${xtermJars.files.map { it.name }}"
    }
    check(vendorDir.resolve("addon-fit.js").exists()) {
      "addon-fit.js not found in xtermAssets jars: ${xtermJars.files.map { it.name }}"
    }
  }
}

val xtermAssets: Configuration by configurations.creating { isCanBeConsumed = false }

val extractXtermAssets =
  tasks.register<ExtractXtermAssetsTask>("extractXtermAssets") {
    xtermJars.from(xtermAssets)
  }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.terxtreme.vqxrmt"
    minSdk = 28
    // Target Android 14 (API 34) for compatibility with 4KB-aligned community proot builds,
    // while our C JNI compiles with 16KB page alignment (-Wl,-z,max-page-size=16384) for Android 15+.
    targetSdk = 34
    versionCode = 201
    versionName = "2.0.1"

    ndk { abiFilters += "arm64-v8a" }

    externalNativeBuild {
      cmake {
        arguments += listOf("-DANDROID_STL=none")
        cFlags += listOf("-Wall", "-Wextra", "-O2")
      }
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  packaging {
    jniLibs {
      // Required so libproot.so and libproot-loader.so extract to nativeLibraryDir (RX executable).
      useLegacyPackaging = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
      extractXtermAssets,
      ExtractXtermAssetsTask::outputDir,
    )
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {
  xtermAssets(libs.xterm.js)
  xtermAssets(libs.xterm.addon.fit)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.webkit)
  implementation(libs.commons.compress)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
