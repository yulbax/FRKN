import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun healthProbeUrl(name: String, defaultValue: String): String =
    providers.gradleProperty(name).orElse(defaultValue).get().trim().also { value ->
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            "$name must be an absolute HTTPS URL"
        }
}

val vpnHealthProbeUrl = healthProbeUrl(
    "frkn.vpnHealthProbeUrl",
    "https://www.gstatic.com/generate_204"
)
val byeDpiHealthProbeUrl = healthProbeUrl(
    "frkn.byeDpiHealthProbeUrl",
    "https://www.youtube.com/generate_204"
)

val releaseStoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val hasReleaseSigning = releaseStoreFile != null &&
    file(releaseStoreFile).isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.github.yulbax.frkn"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "io.github.yulbax.frkn"
        minSdk = 29
        targetSdk = 37
        versionCode = 10202
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "VPN_HEALTH_PROBE_URL",
            vpnHealthProbeUrl.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "BYEDPI_HEALTH_PROBE_URL",
            byeDpiHealthProbeUrl.asBuildConfigString()
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            val requested = (project.findProperty("abiFilter") as String?)?.split(",")
            include(*(requested ?: listOf("arm64-v8a", "x86_64", "armeabi-v7a")).toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            excludes += "lib/x86/**"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

koinCompiler {
    logSeverity = "info"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

val verifyLibbox = tasks.register("verifyLibbox") {
    group = "verification"
    description = "Fails early when the generated libbox AAR is missing."
    inputs.file(layout.projectDirectory.file("libs/libbox.aar"))
        .withPropertyName("libboxAar")

    doLast {
        check(inputs.files.singleFile.isFile) {
            "Missing app/libs/libbox.aar. Run scripts/build-libbox.sh before building the app."
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyLibbox) }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails early when release signing credentials are missing."
    inputs.property("configured", hasReleaseSigning)

    doLast {
        check(inputs.properties["configured"] == true) {
            "Release signing is not configured. Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

val renameReleaseApks = tasks.register("renameReleaseApks") {
    group = "build"
    description = "Renames release APKs to FRKN-<version>-<abi>.apk."
    inputs.dir(layout.buildDirectory.dir("outputs/apk/release"))
        .withPropertyName("releaseApks")
    inputs.property("versionName", android.defaultConfig.versionName.orEmpty())

    doLast {
        val version = inputs.properties.getValue("versionName")
        val pattern = Regex("app-(.+)-release\\.apk")
        var renamed = 0
        inputs.files.files.filter(File::isFile).forEach { file ->
            val abi = pattern.find(file.name)?.groupValues?.get(1) ?: return@forEach
            val target = File(file.parentFile, "FRKN-$version-$abi.apk")
            Files.move(
                file.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            renamed++
        }
        check(renamed > 0) { "No release APKs matched ${pattern.pattern}" }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(renameReleaseApks) }

dependencies {
    implementation(files("libs/libbox.aar"))

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3.v150alpha21)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.annotations)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

}
