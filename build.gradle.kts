// 최상위 빌드 파일. 플러그인 버전은 gradle/libs.versions.toml 에서 관리한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
