# Ai_voice_assistant

Android 우선 백그라운드 녹음 앱입니다.

## 동작 방식
- 앱에서 **시작**을 누르면 Foreground Service가 올라갑니다.
- 서비스는 **오전 7시부터 오후 8시까지**만 녹음합니다.
- 녹음은 **1시간 단위**로 잘라 서버로 업로드합니다.
- 업로드 성공 시 휴대폰 로컬 파일은 삭제합니다.
- 설정은 처음 1회 실행 시 자동으로 열리며, 이후에는 오른쪽 위 **설정** 메뉴에서 수정할 수 있습니다.
- 오른쪽 위 메뉴에서 **저장된 파일 / 업데이트 확인 / 설치 안내**도 바로 볼 수 있습니다.
- **업데이트 확인**은 GitHub Releases 페이지를 열어 최신 APK를 받게 해줍니다.

## 필요한 권한
- 마이크
- 알림(Android 13+)
- Foreground service microphone

## 서버 설정
앱에는 Gemini API 키를 넣지 않습니다. 전사는 서버(VPS)에서 처리하며, **Gemini 3.5 Flash**와 **Google AI Studio API 키**는 서버 쪽 `voice_journal.env`에 설정합니다.

앱 설정의 `서버 URL`에는 VPS 주소를 넣습니다.
예:
- `https://your-domain.example`
- 업로드 경로: `/api/upload`

## 빌드/배포
- 빌드 결과 APK를 텔레그램으로 보내는 스크립트: `scripts/send_apk_to_telegram.py`
- GitHub Actions나 로컬 CI에서 `assembleDebug`로 빌드한 뒤 위 스크립트를 실행하면 됩니다.
- 현재 앱/프로젝트 이름은 `Ai_voice_assistant`로 맞춰둠

## 주의
- Android에서는 장시간 백그라운드 녹음에 Foreground Service가 필요합니다.
- 테스트 단계에서는 cleartext HTTP도 허용되어 있지만, 실사용은 HTTPS를 권장합니다.
- iPhone용은 별도 구현이 필요합니다.
