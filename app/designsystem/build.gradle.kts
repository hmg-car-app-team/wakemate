plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Pleos 디자인시스템 0.2.0 소스 복사본(:designsystem)
group = "com.hmgcarapp.pleos"
version = "0.2.0"

android {
    namespace = "com.hmgcarapp.pleos.designsystem"
    compileSdk = 35

    defaultConfig {
        // Pleos 공식 샘플(minSdk 23)에도 붙도록 23. 공모전 앱 자체는 API 28 이상 권장.
        // API 28 미만에서는 그림자(setShadowLayer)와 가변 폰트 굵기만 생략됩니다.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    // CarUxRestrictionsManager(주행 규제)를 쓰기 위한 AAOS 선택 라이브러리
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}


dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.foundation)
    api(libs.androidx.material3)
    api(libs.androidx.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
