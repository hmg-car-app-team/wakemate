# 깨워드림 WakeMate

졸음이 오는 걸 스스로 알아차리기 어려운 장거리·야간 운전을, 차량 신호로 졸음 위험을 추정해 단계별로 알리고 쉼터를 안내하는 보조 앱입니다. 위험지수는 차량 신호 기반 추정이며 직접 측정이 아닙니다.

## 기능
- **졸음 위험지수(0~100)**: 시간대, 연속 주행, 조향 패턴, 속도 변동성, 내기순환 지속을 합쳐 주의/경고/위험 단계로 나눕니다.
- **단계별 깨우기**: 주의는 음성 안내 후 외기 전환 제안, 경고는 경고음과 외기 순환·풍량·온도 조절, 위험은 가까운 졸음쉼터·휴게소 안내 제안(수락 시 내비 경로 요청)입니다. 주행 중에는 음성·경고음으로만 알립니다.
- **백그라운드 감시**: 설정 스위치(기본 꺼짐)로 켜며 알림 권한이 필요합니다. 켜면 화면 밖에서도 차량 신호만 읽고 위치는 수집하지 않습니다.

## 실행 방법
```
adb install -r apk/hmg-car-app-team_WakeMate_release.apk
adb shell am start -n com.hmgcarapp.wakemate/.MainActivity
```

## 테스트 환경
- Pleos Connect v2.1.0 에뮬레이터(AVD 이름 pleos21), Android 14, 2560x1440 가로
- APK 위치: `apk/hmg-car-app-team_WakeMate_release.apk`

## 데이터 출처
- 한국도로공사 졸음쉼터(공공데이터포털 15028203), 휴게소정보(15025446). 수집일 2026-11-11, 열 이름 정규화·중복 제거 등 가공.
- 이용허락범위는 공공데이터포털 안내에 따르며, 스냅샷이며 실시간 정보가 아니므로 현재 운영 상태를 보증하지 않습니다.

## 개인정보
위치는 가까운 쉼터 계산에만 메모리에서 쓰고 저장하지 않습니다. 설정·개인 보정값·휴식 기록은 기기 안에만 저장하며 서버로 보내지 않습니다.

## 소스 빌드·테스트
`app/` 폴더만으로 단독 빌드됩니다(JDK 17, Android SDK 필요, Pleos SDK 는 공개 Maven 에서 받으므로 인터넷 필요).

```bash
cd app
./gradlew :wake-mate:assembleDebug :wake-mate:assembleRelease
./gradlew :core:test :wake-mate:testDebugUnitTest
```

서드파티 고지는 `app/NOTICE`, 폰트 라이선스는 `app/licenses/` 에 있습니다.
