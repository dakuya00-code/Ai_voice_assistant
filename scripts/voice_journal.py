#!/usr/bin/env python3
import argparse
import base64
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import requests
from dotenv import load_dotenv

AUDIO_EXTS = {".wav", ".mp3", ".m4a", ".ogg", ".webm", ".flac"}


@dataclass
class JournalResult:
    file_name: str
    transcript: str
    summary: str
    actions: list[dict[str, Any]]


def preprocess_audio_with_ffmpeg(audio_path: Path, *, denoise_enabled: bool, denoise_level: int) -> Path:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return audio_path

    tmp_dir = Path(tempfile.mkdtemp(prefix="vj_denoise_"))
    out = tmp_dir / f"{audio_path.stem}_clean.wav"

    filters = ["highpass=f=120", "lowpass=f=7000", "loudnorm"]
    if denoise_enabled:
        nr = max(5, min(40, denoise_level))
        filters.insert(0, f"afftdn=nr={nr}")

    cmd = [
        ffmpeg,
        "-y",
        "-i",
        str(audio_path),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-af",
        ",".join(filters),
        str(out),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not out.exists():
        return audio_path
    return out


def _gemini_generate(model: str, api_key: str, payload: dict[str, Any]) -> str:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    resp = requests.post(url, json=payload, timeout=180)
    resp.raise_for_status()
    data = resp.json()
    candidates = data.get("candidates") or []
    if not candidates:
        return ""
    parts = candidates[0].get("content", {}).get("parts", [])
    texts = [p.get("text", "") for p in parts if p.get("text")]
    return "\n".join(texts).strip()


def transcribe_with_gemini(audio_path: Path, model: str, language: str, api_key: str) -> str:
    mime = "audio/wav" if audio_path.suffix.lower() == ".wav" else "audio/mpeg"
    encoded = base64.b64encode(audio_path.read_bytes()).decode("utf-8")
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": f"다음 오디오를 {language}로 정확히 받아쓰기 하세요. 설명 없이 전사 텍스트만 출력하세요."},
                    {"inline_data": {"mime_type": mime, "data": encoded}},
                ]
            }
        ]
    }
    return _gemini_generate(model, api_key, payload)


def transcribe_with_whisper_local(audio_path: Path, model_name: str, language: str) -> str:
    import whisper

    model = whisper.load_model(model_name)
    result = model.transcribe(str(audio_path), language=language)
    return (result.get("text") or "").strip()


def transcribe_audio(
    audio_path: Path,
    *,
    stt_provider: str,
    local_model_name: str,
    gemini_stt_model: str,
    language: str,
    gemini_key: str | None,
    denoise_enabled: bool,
    denoise_level: int,
) -> str:
    provider = (stt_provider or "gemini").strip().lower()
    cleaned = preprocess_audio_with_ffmpeg(audio_path, denoise_enabled=denoise_enabled, denoise_level=denoise_level)

    if provider == "gemini":
        if not gemini_key:
            raise RuntimeError("VOICE_STT_PROVIDER=gemini 인데 GEMINI_API_KEY가 비어 있습니다.")
        return transcribe_with_gemini(cleaned, gemini_stt_model, language, gemini_key)
    if provider == "local":
        return transcribe_with_whisper_local(cleaned, local_model_name, language)
    raise RuntimeError(f"지원하지 않는 VOICE_STT_PROVIDER: {stt_provider}")


def summarize_with_gemini(transcript: str, model: str, api_key: str) -> tuple[str, list[dict[str, Any]]]:
    prompt = (
        "당신은 음성 저널 분석기입니다. 한국어로 답하세요.\n"
        "1) transcript를 5줄 이내 핵심요약\n"
        "2) 실행 액션 TODO를 JSON 배열로 추출\n"
        "JSON 스키마: [{\"title\": str, \"due\": str|null, \"priority\": \"high\"|\"medium\"|\"low\"}]\n"
        "가능한 날짜/시간 표현은 ISO-like(예: 2026-05-27 14:00)로 정규화 시도.\n"
        "반드시 아래 형식으로만 출력:\n"
        "SUMMARY:\n<요약>\n\nACTIONS_JSON:\n<json>\n\n"
        f"TRANSCRIPT:\n{transcript}"
    )
    payload = {"contents": [{"parts": [{"text": prompt}]}]}
    content = _gemini_generate(model, api_key, payload)

    if "ACTIONS_JSON:" not in content:
        return content.strip(), []

    summary_part, actions_part = content.split("ACTIONS_JSON:", 1)
    summary = summary_part.replace("SUMMARY:", "").strip()
    actions_raw = actions_part.strip()
    try:
        actions = json.loads(actions_raw)
        if not isinstance(actions, list):
            actions = []
    except json.JSONDecodeError:
        actions = []
    return summary, actions


def fallback_summary(transcript: str) -> tuple[str, list[dict[str, Any]]]:
    lines = [x.strip() for x in transcript.split(".") if x.strip()]
    summary = "\n".join(f"- {line[:120]}" for line in lines[:5])
    return summary or "(요약 불가)", []


def send_telegram(bot_token: str, chat_id: str, message: str) -> None:
    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    resp = requests.post(url, json={"chat_id": chat_id, "text": message}, timeout=30)
    resp.raise_for_status()


def process_file(
    f: Path,
    out_dir: Path,
    whisper_model: str,
    language: str,
    gemini_key: str | None,
    gemini_model: str,
    stt_provider: str,
    gemini_stt_model: str,
    denoise_enabled: bool,
    denoise_level: int,
) -> JournalResult:
    transcript = transcribe_audio(
        f,
        stt_provider=stt_provider,
        local_model_name=whisper_model,
        gemini_stt_model=gemini_stt_model,
        language=language,
        gemini_key=gemini_key,
        denoise_enabled=denoise_enabled,
        denoise_level=denoise_level,
    )

    if gemini_key:
        summary, actions = summarize_with_gemini(transcript, gemini_model, gemini_key)
    else:
        summary, actions = fallback_summary(transcript)

    result = JournalResult(file_name=f.name, transcript=transcript, summary=summary, actions=actions)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    json_path = out_dir / f"{f.stem}_{stamp}.json"
    md_path = out_dir / f"{f.stem}_{stamp}.md"

    json_path.write_text(
        json.dumps(
            {
                "file": result.file_name,
                "transcript": result.transcript,
                "summary": result.summary,
                "actions": result.actions,
                "stt_provider": stt_provider,
                "stt_model": gemini_stt_model if stt_provider == "gemini" else whisper_model,
                "denoise_enabled": denoise_enabled,
                "created_at": datetime.now().isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    md_path.write_text(
        f"# Voice Journal Result\n\n"
        f"- file: {result.file_name}\n"
        f"- created_at: {datetime.now().isoformat()}\n\n"
        f"## Summary\n{result.summary}\n\n"
        f"## Actions\n```json\n{json.dumps(result.actions, ensure_ascii=False, indent=2)}\n```\n\n"
        f"## Transcript\n{result.transcript}\n",
        encoding="utf-8",
    )

    return result


def main() -> None:
    load_dotenv()

    parser = argparse.ArgumentParser(description="Voice Journal Pipeline")
    parser.add_argument("--inbox", default="data/inbox", help="음성 파일 입력 폴더")
    parser.add_argument("--processed", default="data/processed", help="처리 완료 폴더")
    parser.add_argument("--results", default="data/results", help="분석 결과 저장 폴더")
    parser.add_argument("--dry-run", action="store_true", help="실제 처리 없이 파일 탐색만 수행")
    args = parser.parse_args()

    inbox = Path(args.inbox)
    processed = Path(args.processed)
    results = Path(args.results)
    inbox.mkdir(parents=True, exist_ok=True)
    processed.mkdir(parents=True, exist_ok=True)
    results.mkdir(parents=True, exist_ok=True)

    whisper_model = os.getenv("WHISPER_MODEL", "base")
    stt_provider = os.getenv("VOICE_STT_PROVIDER", "gemini")
    gemini_stt_model = os.getenv("GEMINI_STT_MODEL", "gemini-1.5-flash")
    language = os.getenv("VOICE_JOURNAL_LANGUAGE", "ko")
    max_files = int(os.getenv("VOICE_JOURNAL_MAX_FILES", "10"))
    gemini_key = os.getenv("GEMINI_API_KEY")
    gemini_model = os.getenv("GEMINI_MODEL", "gemini-1.5-flash")
    denoise_enabled = os.getenv("VOICE_DENOISE_ENABLED", "1") == "1"
    denoise_level = int(os.getenv("VOICE_DENOISE_LEVEL", "20"))

    tg_token = os.getenv("TELEGRAM_BOT_TOKEN")
    tg_chat_id = os.getenv("TELEGRAM_CHAT_ID")

    files = [p for p in inbox.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTS][:max_files]
    print(
        f"[voice-journal] inbox={inbox} files={len(files)} dry_run={args.dry_run} "
        f"stt_provider={stt_provider} stt_model={gemini_stt_model if stt_provider == 'gemini' else whisper_model} denoise={denoise_enabled}"
    )

    for f in files:
        print(f"- 처리 시작: {f.name}")
        if args.dry_run:
            continue

        try:
            result = process_file(
                f=f,
                out_dir=results,
                whisper_model=whisper_model,
                language=language,
                gemini_key=gemini_key,
                gemini_model=gemini_model,
                stt_provider=stt_provider,
                gemini_stt_model=gemini_stt_model,
                denoise_enabled=denoise_enabled,
                denoise_level=denoise_level,
            )
            if tg_token and tg_chat_id:
                msg = (
                    f"[Voice Journal]\n파일: {result.file_name}\n\n"
                    f"요약:\n{result.summary}\n\n"
                    f"액션 개수: {len(result.actions)}"
                )
                send_telegram(tg_token, tg_chat_id, msg)

            target = processed / f.name
            shutil.move(str(f), str(target))
            print(f"  -> 완료: {target}")
        except Exception as e:
            print(f"  !! 실패: {f.name} ({e})")


if __name__ == "__main__":
    main()
