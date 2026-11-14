import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase Remote Config (SPEC 4.2) is optional: without app/google-services.json the app runs on its in-code defaults.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

/** AdMob IDs (SPEC 4.2): from -P / gradle.properties / local.properties; Google's public test IDs otherwise. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun adProp(key: String, testId: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase()) ?: testId
val testAds = mapOf(
    "admob.appId" to "ca-app-pub-3940256099942544~3347511713",
    "admob.banner" to "ca-app-pub-3940256099942544/9214589741",
    "admob.interstitial" to "ca-app-pub-3940256099942544/1033173712",
    "admob.rewarded" to "ca-app-pub-3940256099942544/5224354917",
    "admob.native" to "ca-app-pub-3940256099942544/2247696110",
    "admob.appOpen" to "ca-app-pub-3940256099942544/9257395921",
)

android {
    namespace = "com.vintagelab.camera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vintagelab.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes.all {
        // Debug builds always use test ads (AdMob policy: never click your own live ads).
        val live = name != "debug"
        fun id(key: String) = if (live) adProp(key, testAds.getValue(key)) else testAds.getValue(key)
        manifestPlaceholders["admobAppId"] = id("admob.appId")
        buildConfigField("String", "AD_BANNER", "\"${id("admob.banner")}\"")
        buildConfigField("String", "AD_INTERSTITIAL", "\"${id("admob.interstitial")}\"")
        buildConfigField("String", "AD_REWARDED", "\"${id("admob.rewarded")}\"")
        buildConfigField("String", "AD_NATIVE", "\"${id("admob.native")}\"")
        buildConfigField("String", "AD_APP_OPEN", "\"${id("admob.appOpen")}\"")
        buildConfigField("boolean", "TEST_ADS", (id("admob.appId") == testAds["admob.appId"]).toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = false
    }
    testOptions {
        unitTests.all { test ->
            // LUT regeneration: ./gradlew :app:testDebugUnitTest -PgenerateLuts --tests '*GenerateLuts*'
            test.systemProperty("generateLuts", project.hasProperty("generateLuts").toString())
            test.systemProperty("repoRoot", rootProject.projectDir.absolutePath)
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    // Ads/Firebase pull Guava in at runtime and swap the ListenableFuture stub out of the compile path; CameraController needs it.
    implementation(libs.guava)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.billing.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
