import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val identity = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
// Secrets stay outside the repository. Every release must use the pinned Links certificate.
val releaseSigningFile = System.getenv("LINKS_SIGNING_PROPERTIES")?.let { File(it) }
    ?: File(System.getProperty("user.home"), ".agent-shared/credentials/links-release-signing/signing.properties")
val releaseSigning = Properties().apply { if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) } }
val releaseKeystore = releaseSigning.getProperty("storeFile")?.let { releaseSigningFile.parentFile.resolve(it) }
val linksCertificateSha256 = "501a5ab904e2e601d38cf8b04b19cc3165a503235710d888ff121d2c55d9ee29"
val verifyReleaseSigning by tasks.registering {
    doLast {
        require(releaseSigningFile.isFile) { "Links release signing.properties is missing; restore the existing signing backup. Never generate a replacement key." }
        require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !releaseSigning.getProperty(it).isNullOrBlank() }) { "Links release signing configuration is incomplete" }
        require(releaseSigning.getProperty("keyAlias") == "links-release") { "Links release key alias must remain links-release" }
        val keyStore = KeyStore.getInstance("JKS").apply { requireNotNull(releaseKeystore).inputStream().use { load(it, releaseSigning.getProperty("storePassword").toCharArray()) } }
        require(keyStore.getKey("links-release", releaseSigning.getProperty("keyPassword").toCharArray()) != null) { "Links release private key is unavailable" }
        val certificate = requireNotNull(keyStore.getCertificate("links-release"))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        require(fingerprint == linksCertificateSha256) { "Links release signing key changed; restore the original Links-release.jks" }
    }
}

android {
    namespace = "com.zane.zanebox"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zane.zanebox"
        minSdk = 23
        targetSdk = 35
        versionName = identity.getProperty("versionName")
        versionCode = identity.getProperty("versionCode").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs { create("linksRelease") {
        storeFile = releaseKeystore
        storePassword = releaseSigning.getProperty("storePassword")
        keyAlias = releaseSigning.getProperty("keyAlias")
        keyPassword = releaseSigning.getProperty("keyPassword")
        storeType = "JKS"
    } }
    buildTypes { release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
        signingConfig = signingConfigs.getByName("linksRelease")
    } }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(files("../native/OwnBoxForAndroid/libcore/.build/libcore.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val verifyNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash","native/build-native.sh")
}
tasks.named("preBuild").configure { dependsOn(verifyNative) }

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }
