import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        manifestPlaceholders["sdkVersion"] = "0.0.4"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // The push gateway URL is personal to this phone, so it lives in
        // local.properties (git-ignored) as pushGatewayUrl=https://...
        // Without it the app simply does not register a pusher.
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "PUSH_GATEWAY_URL",
            "\"${localProperties.getProperty("pushGatewayUrl", "")}\"",
        )
    }

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Only there for its manifest: a provider that captures the application
    // Context at process start, which the SDK gives a tool no other way to get.
    implementation(project(":appcontext"))

    implementation("com.thelightphone:client:0.0.11")
    implementation("com.thelightphone:ui:0.0.11")
    
    // Trixnity Matrix SDK
    implementation("net.folivo:trixnity-client:4.22.7")
    implementation("net.folivo:trixnity-client-repository-room:4.22.7")
    implementation("net.folivo:trixnity-client-media-okio:4.22.7")
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")

    // Camera capture for sending photos
    implementation("androidx.camera:camera-core:1.5.0")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")

    testImplementation(libs.kotlin.test)
}
