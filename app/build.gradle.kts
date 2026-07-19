import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val expectedReleaseCertificateSha256 = "fbd5642c3c1b5882545f6f1227cf2dc38a54bcd18609203935eedbef408d1382"
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("release-signing.properties")
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun releaseSigningValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStorePath = releaseSigningValue("QMUSICWATCH_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = releaseSigningValue("QMUSICWATCH_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSigningValue("QMUSICWATCH_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseSigningValue("QMUSICWATCH_RELEASE_KEY_PASSWORD", "keyPassword")
val releaseStoreType = releaseSigningValue("QMUSICWATCH_RELEASE_STORE_TYPE", "storeType") ?: "PKCS12"
val releaseSigningConfigured = listOf(
    releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { !it.isNullOrBlank() }
val releaseStoreFile = releaseStorePath?.let(rootProject::file)

fun releaseCertificateSha256(): String {
    check(releaseSigningConfigured && releaseStoreFile?.isFile == true) {
        "Release signing is not configured. Copy release-signing.properties.example to release-signing.properties."
    }
    val keyStore = KeyStore.getInstance(releaseStoreType)
    releaseStoreFile!!.inputStream().use { keyStore.load(it, releaseStorePassword!!.toCharArray()) }
    val certificate = keyStore.getCertificate(releaseKeyAlias)
        ?: error("Release key alias '$releaseKeyAlias' was not found in ${releaseStoreFile.path}")
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

if (releaseSigningConfigured) {
    check(releaseCertificateSha256() == expectedReleaseCertificateSha256) {
        "Release signing certificate mismatch. Expected $expectedReleaseCertificateSha256."
    }
}

android {
    namespace = "com.ronan.qmusicwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ronan.qmusicwatch"
        minSdk = 24
        targetSdk = 36
        versionCode = 36
        versionName = "0.9.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartext"] = "false"
        buildConfigField("String", "QMUSIC_SERVER_BASE_URL", "\"https://8.138.134.236/\"")
        buildConfigField("String", "QMUSIC_RELEASE_CERT_SHA256", "\"$expectedReleaseCertificateSha256\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) create("canonicalRelease") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = releaseStoreType
        }
    }
    buildTypes {
        debug {
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            signingConfig = signingConfigs.findByName("canonicalRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

val verifyCanonicalReleaseSigning = tasks.register("verifyCanonicalReleaseSigning") {
    group = "verification"
    description = "Fails if the configured Release key is missing or is not the canonical QMusic Watch signer."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Copy release-signing.properties.example to release-signing.properties."
        }
        val actual = releaseCertificateSha256()
        check(actual == expectedReleaseCertificateSha256) {
            "Release signing certificate mismatch. Expected $expectedReleaseCertificateSha256, got $actual."
        }
    }
}
tasks.matching { it.name in setOf("packageRelease", "assembleRelease", "bundleRelease") }.configureEach {
    dependsOn(verifyCanonicalReleaseSigning)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
}

kapt { correctErrorTypes = true }
