# Ai_voice_assistant

Android 우선 백그라운드 녹음 앱입니다.

## 동작 방식
- 앱에서 **음성 감지 시작**을 누르면 Foreground Service가 올라갑니다.
- 먼저 마이크를 모니터링하다가 **음성이 감지되면 녹음을 시작**합니다.
- 말소리가 이어지는 동안 하나의 회의 단위 파일로 유지하고, **무음이 설정 시간(기본 15초) 동안 지속되면 종료**합니다.
- 설정은 처음 1회 실행 시 자동으로 열리며, 이후에는 오른쪽 위 **설정** 메뉴에서 수정할 수 있습니다.
- 오른쪽 위 메뉴에서 **저장된 파일 / 업데이트 확인 / 설치 안내**도 바로 볼 수 있습니다.
- **업데이트 확인**은 GitHub Releases 페이지를 열어 최신 APK를 받게 해줍니다.
- 앱 실행 시 새 릴리스가 감지되면 자동으로 업데이트 알림이 뜹니다.
- 업로드 성공 시 휴대폰 로컬 파일은 삭제합니다.

## 필요한 권한
- 마이크
- 알림(Android 13+)
- Foreground service microphone

## 서버 설정
앱 설정의 `서버 URL`에는 VPS 주소를 넣습니다.
예:
- `https://your-domain.example`
- 업로드 경로: `/api/upload`

## 빌드/배포
- 로컬 이 환경에서는 JDK/Gradle/Android SDK가 없어 바로 APK를 만들 수 없습니다.
- GitHub Actions 워크플로우: `.github/workflows/build-debug-apk.yml`
- GitHub Secrets 필요값:
  - `TELEGRAM_BOT_TOKEN`
  - `TELEGRAM_CHAT_ID`
- 빌드된 APK를 텔레그램으로 보내는 스크립트: `scripts/send_apk_to_telegram.py`
- 현재 앱/프로젝트 이름은 `Ai_voice_assistant`로 맞춰둠

## 주의
- Android에서는 장시간 백그라운드 녹음에 Foreground Service가 필요합니다.
- 테스트 단계에서는 cleartext HTTP도 허용되어 있지만, 실사용은 HTTPS를 권장합니다.
- iPhone용은 별도 구현이 필요합니다.
