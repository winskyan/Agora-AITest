import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val properties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    properties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "io.agora.ai.rtm.test"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.agora.ai.rtm.test"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "2.0317.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_ID", "\"${properties.getProperty("APP_ID", "")}\"")
        buildConfigField(
            "String",
            "APP_CERTIFICATE",
            "\"${properties.getProperty("APP_CERTIFICATE", "")}\""
        )
        buildConfigField(
            "String",
            "RTC_TOKEN",
            "\"${properties.getProperty("RTC_TOKEN", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = "key0"
            keyPassword = "123456"
            storeFile = file("./keystore/testkey.jks")
            storePassword = "123456"
        }
    }

    applicationVariants.all {
        outputs.all {
            val now = Date()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
            val formatTime = sdf.format(now)
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "${project.rootProject.name}-${defaultConfig.versionName}-${buildType.name}-$formatTime.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    viewBinding {
        enable = true
    }

    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    implementation(files("libs/agora-rtm-sdk.jar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.easypermissions)
    implementation(libs.xpopup)
    implementation(libs.agora.authentication)
    implementation(libs.commons.codec)
//    implementation(libs.agora.rtm)
    implementation(libs.okhttp3)
}