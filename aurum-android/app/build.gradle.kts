import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class PackageMasterScripts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scripts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productData: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val historyData: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun packageScripts() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val destination = root.resolve("manual_js")
        check(destination.mkdirs() || destination.isDirectory) {
            "Unable to create generated master-script directory."
        }
        scripts.files.forEach { source ->
            source.copyTo(destination.resolve(source.name), overwrite = true)
        }
        val productDestination = root.resolve("seed/products")
        check(productDestination.mkdirs() || productDestination.isDirectory) {
            "Unable to create generated product seed directory."
        }
        productData.files.forEach { source ->
            source.copyTo(productDestination.resolve(source.name), overwrite = true)
        }
        val historyDestination = root.resolve("seed/history")
        check(historyDestination.mkdirs() || historyDestination.isDirectory) {
            "Unable to create generated history seed directory."
        }
        historyData.files.forEach { source ->
            source.copyTo(historyDestination.resolve(source.name), overwrite = true)
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.aurum.intelligence"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aurum.intelligence"
        minSdk = 26
        targetSdk = 36
        versionCode = 40923
        versionName = "4.9.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

val masterScriptNames = listOf(
    "ajio_gold_master.js",
    "amazon_gold_master_v14_3_final.js",
    "flipkart_gold_master_final.js",
    "myntra_gold_master_v7_final.js",
)
val productSeedNames = listOf("ajio-com.json", "amazon-in.json", "flipkart-com.json", "myntra-com.json")

val packageMasterScripts = tasks.register<PackageMasterScripts>("packageMasterScripts") {
    group = "build setup"
    description = "Packages the desktop master scripts as byte-identical Android assets."
    scripts.from(masterScriptNames.map(rootProject.layout.projectDirectory.dir("../aurum-desktop/manual_js")::file))
    productData.from(productSeedNames.map(rootProject.layout.projectDirectory.dir("../aurum-desktop/data/products")::file))
    historyData.from(
        listOf("aurum.sqlite", "aurum.sqlite-wal", "aurum.sqlite-shm")
            .map(rootProject.layout.projectDirectory.dir("../aurum-desktop/data")::file),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/masterAssets"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packageMasterScripts,
            PackageMasterScripts::outputDirectory,
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}