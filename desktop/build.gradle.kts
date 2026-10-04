import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

val appVersion = providers.gradleProperty("frkn.versionName").get()
val byeDpiVersion = "17.3"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
}

val bundledResourcesDir = layout.buildDirectory.dir("bundled-resources")

val windowsCoreDir = layout.projectDirectory.dir("libs/windows")

val prepareWindowsBinaries = tasks.register("prepareWindowsBinaries") {
    group = "distribution"
    description = "Bundles the Amnezia sing-box core built by scripts/build-libbox.sh and the official byedpi release."
    val byeDpiArchive = Triple(
        "https://github.com/hufrea/byedpi/releases/download/v0.$byeDpiVersion/byedpi-$byeDpiVersion-x86_64-w64.zip",
        "70d2c94147193cb915f9c6eb5144b8d404dacbcfa90bda2383b6b211afafa456",
        mapOf("ciadpi.exe" to "ciadpi.exe")
    )
    val coreFiles = listOf("sing-box.exe", "sing-box-LICENSE.txt").map { windowsCoreDir.file(it).asFile }
    val outputDir = bundledResourcesDir.map { it.dir("windows") }
    inputs.property("byeDpiArchive", byeDpiArchive.toString())
    inputs.files(coreFiles).withPropertyName("core").optional()
    outputs.dir(outputDir)
    doLast {
        val missing = coreFiles.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Missing ${missing.joinToString { it.name }} in desktop/libs/windows. " +
                "Run CORE_TARGETS=windows scripts/build-libbox.sh first."
        }
        val target = outputDir.get().asFile.apply { mkdirs() }
        coreFiles.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        val (url, sha256, entries) = byeDpiArchive
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "Checksum mismatch for $url: $actual" }
        val found = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = entries[entry.name] ?: return@forEach
                File(target, name).writeBytes(zip.readBytes())
                found += entry.name
            }
        }
        check(found.containsAll(entries.keys)) { "Missing ${entries.keys - found} in $url" }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.yulbax.frkn.desktop.MainKt"
        jvmArgs += listOf("-Dfrkn.version=$appVersion", "-Dfrkn.byedpi.version=$byeDpiVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "FRKN"
            packageVersion = appVersion
            vendor = "yulbax"
            description = "FRKN VPN with per-app routing and ByeDPI"
            modules("java.sql", "java.naming", "jdk.unsupported")
            appResourcesRootDir.set(bundledResourcesDir)

            windows {
                iconFile.set(project.file("icons/frkn.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = false
                upgradeUuid = "6f0b3a2e-6c1b-4a8e-9c7e-3f4d5a1b2c90"
            }
        }
    }
}

if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareWindowsBinaries) }
}
