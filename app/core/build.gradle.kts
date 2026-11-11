plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hmgcarapp.core"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // 디자인시스템 (이 저장소에 포함된 소스 복사본)
    api(project(":designsystem"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.foundation)
    api(libs.androidx.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    // Pleos SDK
    api(libs.pleos.vehicle)
    api(libs.pleos.fusedlocation)
    api(libs.pleos.tts)
    api(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
