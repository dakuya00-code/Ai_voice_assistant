# Ai_voice_assistant

Android 우선 백그라운드 녹음 앱 + 서버 분석 파이프라인 프로젝트입니다.

## 동작 방식
- 앱에서 **시작**을 누르면 Foreground Service가 올라갑니다.
- 서비스는 **오전 7시부터 오후 8시까지**만 녹음합니다.
- 음성이 감지되면 세그먼트 단위로 서버에 업로드합니다.
- 업로드 성공 시 휴대폰 로컬 파일은 삭제합니다.

## 서버 파이프라인 목표
- 모바일 음성 파일 업로드
- 서버 측 음성인식(STT)
- 노트/리서치 컨텍스트 기반 요약/행동추출
- 결과를 TODO, Google Calendar, Telegram으로 전송

## 현재 구조
- `scripts/voice_journal.py`: 메인 파이프라인 엔트리 (초안)
- `data/inbox`: 원본 음성 파일
- `data/processed`: 처리 완료 파일
- `logs`: 실행 로그
- `docs`: 설계/운영 문서

## 필요한 권한
- 마이크
- 알림(Android 13+)
- Foreground service microphone

## 서버 설정
앱에는 Gemini API 키를 넣지 않습니다. 전사는 서버(VPS)에서 처리하며, API 키는 서버 환경변수(`voice_journal.env` 등)로 관리합니다.

앱 설정의 `서버 URL`에는 VPS 주소를 넣습니다.
예:
- `https://your-domain.example`
- 업로드 경로: `/api/upload`

## 빠른 시작 (서버)
```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python scripts/voice_journal.py --help
```
