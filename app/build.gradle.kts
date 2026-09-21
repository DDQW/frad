import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// M6: an optional real release-signing key, so APKs published from GitHub Releases
// (not F-Droid's own build - see the "F-Droid release packaging" README section)
// can eventually move off the auto-generated debug keystore without ever committing
// a real key to this repo. Supplied either via a local, gitignored
// `keystore.properties` (see `keystore.properties.sample`) or via environment
// variables of the same names (how CI injects its secrets - see
// `.github/workflows/release.yml`); either source left unset just means "no real
// key yet," and the release build type falls back to debug signing exactly as
// before.
val releaseKeystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun releaseSigningValue(propertyKey: String, envVar: String): String? =
    System.getenv(envVar)?.takeIf { it.isNotBlank() } ?: releaseKeystoreProperties.getProperty(propertyKey)

val releaseStoreFile = releaseSigningValue("storeFile", "FRAD_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "FRAD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "FRAD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "FRAD_RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig =
    releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

// M4's wide-range layer (p2p-go/) is built by gomobile into an Android .aar,
// entirely outside this module's normal build path: `./gradlew test`/
// `assembleDebug` must keep working with no Go/gomobile/NDK installed at all.
// When the .aar hasn't been built, `kotlin-p2p-stub` compiles in its place -
// see wideradius/WideRangeNode.kt in each variant for why the two are
// source-compatible. Run `./gradlew gomobileBind` (Go + gomobile + Android
// NDK required - see p2p-go/README.md) to produce the real .aar.
val p2pAarFile = rootProject.file("p2p-go/build/p2pgo.aar")
val hasP2pGoAar = p2pAarFile.exists()

android {
    namespace = "me.woelki.frad"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "me.woelki.frad"
        // BLE presence/pairing (M1) needs the modern Android 12+ Bluetooth runtime
        // permission model (BLUETOOTH_SCAN/CONNECT/ADVERTISE); Wi-Fi Aware (planned
        // for a later milestone) needs API 26+. 26 is the floor for both.
        minSdk = 26
        targetSdk = 36
        // Keep this under 1.0.0 until M4-M6 (see README "Project status") land -
        // a 1.0 tag implies feature-complete, which this isn't yet. Patch digit bumps
        // per commit; the minor digit only moves when a whole lettered milestone lands.
        versionCode = 16
        versionName = "0.3.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        kotlin.srcDir(if (hasP2pGoAar) "src/main/kotlin-p2p-real" else "src/main/kotlin-p2p-stub")
    }

    signingConfigs {
        getByName("debug") {
            // AGP's *implicit* debug signing config auto-generates
            // ~/.android/debug.keystore with fresh random key material the
            // first time anything needs it on a given machine - which on
            // GitHub Actions means every run, since each one starts from a
            // clean VM. Every CI-built release (signed with this "debug"
            // config as the fallback below, since no real release key exists
            // yet) therefore got a different signing certificate, and Android
            // refuses to install an update whose signature doesn't match
            // what's already installed ("signing certificates do not match" /
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE). ci-debug.keystore is a
            // fixed, checked-in, deliberately non-secret keystore (standard
            // debug alias/passwords) that replaces that per-machine default,
            // so consecutive CI builds - and local debug builds, which now
            // share it too - keep the same identity and install as updates
            // over each other. It is not a substitute for a real release key
            // (see the M6 `hasReleaseSigningConfig` block below and the
            // README's "F-Droid release packaging" section) and must never be
            // treated as one - anyone can rebuild an APK that verifies
            // against previously-published ones with it.
            storeFile = rootProject.file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the real release key once one is configured (see the
            // `hasReleaseSigningConfig` block above); otherwise fall back to the
            // auto-generated debug keystore, which keeps CI able to produce an
            // installable APK with no signing secrets to manage. F-Droid signs its
            // own build with its own key regardless of either of these.
            signingConfig = if (hasReleaseSigningConfig) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Noise_XX handshake primitives (X25519 / ChaCha20-Poly1305 / HKDF) via
    // Bouncy Castle so the crypto layer behaves identically on minSdk 26+
    // instead of depending on platform JCA algorithm availability, which
    // varies by Android version/OEM.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // M4 wide-range layer: only present once `./gradlew gomobileBind` has produced
    // p2p-go/build/p2pgo.aar (see the comment above the `android {}` block).
    if (hasP2pGoAar) implementation(files(p2pAarFile))

    testImplementation("junit:junit:4.13.2")
    // Local unit tests run against the real JDK, not a device, so the Android SDK's org.json
    // classes (which BleChatController/ChatHistoryStore use) are stub-only there; this real
    // implementation shadows those stubs so ChatMessageJson can be tested directly.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Builds p2p-go/node into p2p-go/build/p2pgo.aar via `gomobile bind`. Never a
// dependency of `test`/`assembleDebug` (see the comment above the `android {}`
// block) - run it explicitly, then re-run a normal build to pick up the .aar.
// Requires Go 1.22+, `go install golang.org/x/mobile/cmd/gomobile@latest` +
// `gomobile init`, and the Android NDK (ANDROID_HOME/ANDROID_NDK_HOME set).
tasks.register<Exec>("gomobileBind") {
    workingDir = rootProject.projectDir
    inputs.dir("p2p-go/node")
    inputs.file("p2p-go/go.mod")
    outputs.file(p2pAarFile)
    commandLine(
        "gomobile", "bind",
        "-target=android", "-androidapi=26",
        "-javapkg=me.woelki.frad.p2pgo",
        "-o", p2pAarFile.path,
        "./p2p-go/node",
    )
}
