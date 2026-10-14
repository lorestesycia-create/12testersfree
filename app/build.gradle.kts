plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.testersfree.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.testersfree.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.4"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("CM_KEYSTORE_PATH"))
            storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CM_KEY_ALIAS")
            keyPassword = System.getenv("CM_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "USE_TEST_ADS", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // Explicitly opt into production ads only for a verified, signed release.
            val productionAds = providers.gradleProperty("productionAds").orNull == "true"
            buildConfigField("boolean", "USE_TEST_ADS", (!productionAds).toString())
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}
