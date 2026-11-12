# 세대차 release 빌드 R8 규칙

# Pleos Connect SDK: 차량·내비 서비스와 AIDL/리플렉션으로 통신하므로 건드리지 않는다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# AAOS 차량 API (useLibrary("android.car"), 기기에 있는 라이브러리)
-dontwarn android.car.**

# 크래시 로그를 읽을 수 있게 줄 번호 유지
-keepattributes SourceFile,LineNumberTable
