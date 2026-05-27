#!/usr/bin/env python3
import argparse
import json
import os
import shutil
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


def transcribe_with_whisper(audio_path: Path, model_name: str, language: str) -> str:
    import whisper  # lazy import

    model = whisper.load_model(model_name)
    result = model.transcribe(str(audio_path), language=language)
    return (result.get("text") or "").strip()


def summarize_with_openai(transcript: str, model: str, api_key: str) -> tuple[str, list[dict[str, Any]]]:
    from openai import OpenAI

    client = OpenAI(api_key=api_key)
    prompt = (
        "당신은 음성 저널 분석기입니다. 한국어로 답하세요.\n"
        "1) transcript를 5줄 이내 핵심요약\n"
        "2) 실행 액션 TODO를 JSON 배열로 추출\n"
        "JSON 스키마: [{\"title\": str, \"due\": str|null, \"priority\": \"high\"|\"medium\"|\"low\"}]\n"
        "가능한 날짜/시간 표현은 ISO-like(예: 2026-05-27 14:00)로 정규화 시도.\n"
        "반드시 아래 형식으로만 출력:\n"
        "SUMMARY:\n<요약>\n\nACTIONS_JSON:\n<json>"
    )

    resp = client.chat.completions.create(
        model=model,
        temperature=0.2,
        messages=[
            {"role": "system", "content": prompt},
            {"role": "user", "content": transcript},
        ],
    )
    content = resp.choices[0].message.content or ""
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
    openai_key: str | None,
    openai_model: str,
) -> JournalResult:
    transcript = transcribe_with_whisper(f, whisper_model, language)
    if openai_key:
        summary, actions = summarize_with_openai(transcript, openai_model, openai_key)
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
    language = os.getenv("VOICE_JOURNAL_LANGUAGE", "ko")
    max_files = int(os.getenv("VOICE_JOURNAL_MAX_FILES", "10"))
    openai_key = os.getenv("OPENAI_API_KEY")
    openai_model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

    tg_token = os.getenv("TELEGRAM_BOT_TOKEN")
    tg_chat_id = os.getenv("TELEGRAM_CHAT_ID")

    files = [p for p in inbox.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTS][:max_files]
    print(f"[voice-journal] inbox={inbox} files={len(files)} dry_run={args.dry_run}")

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
                openai_key=openai_key,
                openai_model=openai_model,
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
