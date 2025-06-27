plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services") version ("4.4.2")
    kotlin("plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.licenta.e_ajutor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.licenta.e_ajutor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OPENAI_API_KEY", "\"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\"")
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
    lint {
        baseline = file("lint-baseline.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.firebase.common.ktx)
    implementation(libs.firebase.auth)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(platform(libs.firebase.bom)) // Check for the latest BoM version
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(platform(libs.firebase.bom.v3310))
    implementation(libs.google.firebase.auth.ktx)
    implementation(libs.play.services.auth)
    implementation(libs.google.firebase.auth)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation(platform("com.google.firebase:firebase-bom:33.15.0"))
    implementation("com.google.android.gms:play-services-auth")
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("com.google.android.libraries.places:places:4.3.1")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.firebaseui:firebase-ui-firestore:8.0.0")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:vision-common:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:17.0.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

}