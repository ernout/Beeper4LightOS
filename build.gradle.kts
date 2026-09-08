buildscript {
    extra.apply {
        set("compileSdk", 36)
        set("minSdk", 33)
        set("jvmTarget", "21")
        set("sdkVersion", "1.0.0")
        set("sdkGroup", "com.thelightphone")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
