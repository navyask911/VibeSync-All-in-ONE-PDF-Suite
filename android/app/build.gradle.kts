plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 6
        versionName = "1.0.5"

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
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        allWarningsAsErrors = false
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

dependencies {
    // Platform Architecture & Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:review:2.0.2")
    implementation("androidx.activity:activity-compose:1.9.3")

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
    // Pin the exact cryptographic stack used by PdfBox-Android's AES password protection.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.72")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.72")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.72")

    // Google Play Services: ML Kit Document Scanner
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

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
    testImplementation("org.robolectric:robolectric:4.11.1")
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
