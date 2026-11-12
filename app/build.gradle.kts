plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose) // Jetpack Compose 화면
    alias(libs.plugins.ksp)            // Room DB 코드 자동 생성
}

android {
    namespace = "com.kumhoht.sedaecha"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kumhoht.sedaecha"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 심사용 데모(가상 주행) 켜기. 출품 빌드는 true.
        // 서비스 빌드는 false 로 바꾸면 홈의 데모 입구가 사라지고, 설정의 기본값이 '기본 모드'가 된다.
        buildConfigField("boolean", "DEMO_ENABLED", "true")
    }

    buildTypes {
        release {
            // 공모전 제출용: 디버그 키로 서명해 바로 설치·실행되게 한다 (차곡차곡과 동일)
            signingConfig = signingConfigs.getByName("debug")
            // R8 은 끈다: 붕붕냥에서 켰을 때 kotlin 라이브러리 IllegalAccessError 로 실행 직후 종료됨
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // 내장 음성 파일(WAV)은 압축하지 않는다 (assets.openFd 로 바로 재생)
    androidResources {
        noCompress += listOf("wav")
    }
    // Android Automotive 표준 차량 API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 로컬 DB (Room): 배운 말·남은 말·클래스 기록·주행 기록·거리
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Pleos Connect SDK (차량: 기어·깜빡이·주행 거리 / 내비: 경로 시작·도착)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

    testImplementation(libs.junit)
    // 단위 테스트에서 콘텐츠 팩 JSON 을 읽기 위해 (android.jar 의 org.json 은 테스트에서 동작하지 않음)
    testImplementation(libs.org.json)
}
