import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. Credentials live in keystore.properties (gitignored, never
// committed); the CI workflow recreates it from GitHub secrets. Release APK/
// bundle tasks fail closed when valid signing material is not available.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasSigning = requiredSigningKeys.all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    rootProject.file(keystoreProps.getProperty("storeFile", "")).isFile

android {
    namespace = "com.opencam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opencam"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.6.4"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.ui.tooling)
}


tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            check(hasSigning) {
                "Release signing is not configured. Add a gitignored keystore.properties file " +
                    "with storeFile, storePassword, keyAlias and keyPassword."
            }
        }
    }
}
