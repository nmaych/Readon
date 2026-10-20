import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Version. major*10000 + minor*100 + patch, so 1.0.0 is 10000, 1.0.11 is
// 10011 and 1.1.0 is 10100. CI overrides these from the git tag, which keeps
// the tag, the APK metadata and dist/update.json from drifting apart.
// ---------------------------------------------------------------------------
val appVersionCode: Int = (System.getenv("READON_VERSION_CODE") ?: "10101").toIntOrNull()
    ?: error("READON_VERSION_CODE must be an integer")
val appVersionName: String = System.getenv("READON_VERSION_NAME") ?: "1.1.1"

// ---------------------------------------------------------------------------
// Release signing: environment variables first (CI), then keystore.properties
// beside this file (local release). All four inputs or none -- a partial
// configuration would silently produce an unsigned APK under a different name.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val ciKeystorePath: String? = System.getenv("READON_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

val signingStoreFile: File? = when {
    ciKeystorePath != null -> File(ciKeystorePath).takeIf { it.exists() }
    else -> keystoreProps.getProperty("storeFile")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }
}

val signingStorePassword: String? =
    System.getenv("READON_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val signingKeyAlias: String? =
    System.getenv("READON_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val signingKeyPassword: String? =
    System.getenv("READON_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")

val signingInputs = listOf(
    "storeFile (READON_KEYSTORE_FILE)" to signingStoreFile?.absolutePath,
    "storePassword (READON_KEYSTORE_PASSWORD)" to signingStorePassword,
    "keyAlias (READON_KEY_ALIAS)" to signingKeyAlias,
    "keyPassword (READON_KEY_PASSWORD)" to signingKeyPassword,
)
val providedSigningInputs = signingInputs.count { !it.second.isNullOrBlank() }
val hasSigning = providedSigningInputs == signingInputs.size

if (providedSigningInputs in 1 until signingInputs.size) {
    val missing = signingInputs.filter { it.second.isNullOrBlank() }.joinToString(", ") { it.first }
    error(
        "Incomplete release signing configuration: $providedSigningInputs of " +
            "${signingInputs.size} inputs present, missing $missing. Signing is " +
            "all-or-nothing: provide every input, or none to build unsigned.",
    )
}

android {
    namespace = "com.readon.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readon.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("en", "zh")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        logger.lifecycle("Building Readon $appVersionName ($appVersionCode), signed=$hasSigning")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
