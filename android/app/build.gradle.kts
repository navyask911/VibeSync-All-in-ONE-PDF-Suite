import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URL
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val poiAndroidSourceJar = layout.buildDirectory.file("generated/poi/downloads/poishadow-all-5.2.5-4.jar")
val poiAndroidShadowJar = layout.buildDirectory.file("generated/poi/poishadow-all-5.2.5-4-api24.jar")
val downloadPoiAndroidShadowJar = tasks.register("downloadPoiAndroidShadowJar") {
    val sourceJar = poiAndroidSourceJar.get().asFile
    val outputJar = poiAndroidShadowJar.get().asFile
    outputs.file(poiAndroidShadowJar)
    outputs.upToDateWhen {
        outputJar.isFile && outputJar.length() in 10_000_000L until 20_438_966L &&
            runCatching {
                JarFile(outputJar).use { jar ->
                    jar.getJarEntry("org/apache/poi/xwpf/usermodel/XWPFDocument.class") != null &&
                        jar.getJarEntry("org/apache/logging/log4j/LogManager.class") != null &&
                        jar.getJarEntry("org/apache/logging/log4j/util/ServiceLoaderUtil.class") != null &&
                        jar.getJarEntry("org/apache/logging/log4j/util/ServiceLoaderUtil\$ServiceLoaderSpliterator.class") != null &&
                        jar.getJarEntry("org/apache/poi/poifs/nio/CleanerUtil.class") == null
                }
            }.getOrDefault(false)
    }

    doLast {
        val expectedSourceLength = 20_438_966L
        sourceJar.parentFile.mkdirs()
        if (!sourceJar.isFile || sourceJar.length() != expectedSourceLength) {
            val downloadFile = File(sourceJar.parentFile, sourceJar.name + ".download")
            try {
                val connection = URL(
                    "https://github.com/centic9/poi-on-android/releases/download/5.2.5-4/poishadow-all.jar"
                ).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input ->
                    downloadFile.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (downloadFile.length() != expectedSourceLength) {
                    throw GradleException("Unexpected Android POI shaded JAR size: ${downloadFile.length()} bytes.")
                }
                if (!downloadFile.renameTo(sourceJar)) {
                    downloadFile.copyTo(sourceJar, overwrite = true)
                    downloadFile.delete()
                }
            } finally {
                downloadFile.delete()
            }
        }

        JarFile(sourceJar).use { jar ->
            listOf(
                "org/apache/poi/xwpf/usermodel/XWPFDocument.class",
                "org/apache/poi/javax/xml/stream/XMLInputFactory.class",
                "com/fasterxml/aalto/stax/InputFactoryImpl.class"
            ).forEach { entry ->
                if (jar.getJarEntry(entry) == null) {
                    throw GradleException("Android POI shaded JAR is missing required entry: $entry")
                }
            }
        }

        outputJar.parentFile.mkdirs()
        val filteredFile = File(outputJar.parentFile, outputJar.name + ".filtered")
        try {
            ZipInputStream(sourceJar.inputStream().buffered()).use { input ->
                ZipOutputStream(filteredFile.outputStream().buffered()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        // The upstream bundle's Log4j 2.23 and POIFS cleaner contain invoke-polymorphic
                        // MethodHandle calls that D8 cannot package for this app's minSdk 24. DOCX
                        // creation uses OOXML/ZIP, not POIFS NIO; replace only the Log4j loader helper.
                        val excluded = entry.name.startsWith("org/apache/logging/log4j/") ||
                            entry.name.startsWith("org/apache/poi/poifs/nio/CleanerUtil")
                        if (!excluded) {
                            val targetEntry = ZipEntry(entry.name)
                            if (entry.time >= 0L) targetEntry.time = entry.time
                            output.putNextEntry(targetEntry)
                            if (!entry.isDirectory) input.copyTo(output)
                            output.closeEntry()
                        }
                        input.closeEntry()
                    }
                }
            }

            JarFile(filteredFile).use { jar ->
                listOf(
                    "org/apache/poi/xwpf/usermodel/XWPFDocument.class",
                    "org/apache/poi/javax/xml/stream/XMLInputFactory.class",
                    "com/fasterxml/aalto/stax/InputFactoryImpl.class"
                ).forEach { entry ->
                    if (jar.getJarEntry(entry) == null) {
                        throw GradleException("Filtered Android POI JAR is missing required entry: $entry")
                    }
                }
                if (jar.getJarEntry("org/apache/logging/log4j/LogManager.class") != null ||
                    jar.getJarEntry("org/apache/poi/poifs/nio/CleanerUtil.class") != null
                ) {
                    throw GradleException("Filtered Android POI JAR still contains API 26-only classes.")
                }
            }

            if (!filteredFile.renameTo(outputJar)) {
                filteredFile.copyTo(outputJar, overwrite = true)
                filteredFile.delete()
            }
        } finally {
            filteredFile.delete()
        }
    }
}
tasks.named("preBuild").configure {
    dependsOn(downloadPoiAndroidShadowJar)
}

val releaseKeystoreFile = rootProject.file("app/release-upload.jks")
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = releaseKeystoreFile.isFile && releaseKeystoreFile.length() > 0L &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.vibesync.toolkit.pdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibesync.toolkit.pdf"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "1.0.19"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-4783826505860771~4377815936"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-4783826505860771/4710308648\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-4783826505860771/9523651625\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            // Never create a Play-looking release artifact signed with the debug key.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        debug {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
            // Keep the first occurrence of this file (required by POI)
            pickFirsts += "Log4j-charsets.properties"

            // Exclude redundant metadata files that cause merge conflicts
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            merges += "META-INF/LICENSE.md"
            merges += "META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.testLogging {
                    events(
                        org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR
                    )
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Platform Architecture & Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:review:2.0.2")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Jetpack Compose BOM & UI
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Coroutines & Concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // PDFBox-Android (Apache 2.0 clean-room PDF manipulation)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Android-compatible shaded Apache POI distribution for XWPF/DOCX.
    implementation(files(poiAndroidShadowJar))
    // The 2.13 API exposes POI's LogBuilder methods without the API-26-only MethodHandle helper.
    implementation("org.apache.logging.log4j:log4j-api:2.13.3")
    // Pin the exact cryptographic stack used by PdfBox-Android's AES password protection.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.72")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.72")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.72")

    // Google Play Services: ML Kit Document Scanner
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("androidx.biometric:biometric:1.1.0")

    // Google Mobile Ads (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.3.0")

    // Jetpack Security (EncryptedSharedPreferences & MasterKey)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing: JUnit 5 Jupiter, MockK, Robolectric, Coroutines Test
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")

    // Android Instrumented & Jetpack Compose Tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease") {
        doFirst {
            if (!hasReleaseSigning) {
                throw GradleException(
                    "Release packaging requires app/release-upload.jks and RELEASE_STORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD. Configure your Play upload key; " +
                        "the debug key is intentionally not used for release artifacts."
                )
            }
        }
    }
}
