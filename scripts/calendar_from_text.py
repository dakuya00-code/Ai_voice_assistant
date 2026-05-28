#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import subprocess
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from dotenv import load_dotenv


@dataclass
class CandidateEvent:
    summary: str
    start: str
    end: str
    description: str
    source_file: str


def now_kst() -> datetime:
    return datetime.now(timezone(timedelta(hours=9)))


def normalize_iso(value: str) -> str:
    # allow '2026-05-28 14:00' style
    v = value.strip().replace(" ", "T")
    dt = datetime.fromisoformat(v)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone(timedelta(hours=9)))
    return dt.isoformat()


def parse_with_openai(text: str, source_file: str) -> list[CandidateEvent]:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    model = os.getenv("OPENAI_MODEL", "gpt-4o-mini").strip()
    if not api_key:
        return []

    from openai import OpenAI

    prompt = (
        "당신은 일정 추출기입니다. 한국어 문장에서 캘린더 일정을 JSON 배열로 추출하세요.\n"
        "출력은 JSON 배열만. 항목 스키마:\n"
        "[{\"summary\": str, \"start\": \"YYYY-MM-DDTHH:MM:SS+09:00\", \"end\": \"YYYY-MM-DDTHH:MM:SS+09:00\", \"description\": str}]\n"
        "규칙: 시간이 없으면 start를 현재시각+1시간으로 두고 end는 +30분.\n"
        "일정이 명확하지 않으면 빈 배열 []"
    )

    client = OpenAI(api_key=api_key)
    resp = client.chat.completions.create(
        model=model,
        temperature=0,
        messages=[
            {"role": "system", "content": prompt},
            {"role": "user", "content": text},
        ],
    )
    raw = (resp.choices[0].message.content or "").strip()
    data = json.loads(raw)
    if not isinstance(data, list):
        return []

    out: list[CandidateEvent] = []
    for item in data:
        try:
            summary = str(item.get("summary", "")).strip()
            start = normalize_iso(str(item.get("start", "")).strip())
            end = normalize_iso(str(item.get("end", "")).strip())
            description = str(item.get("description", "")).strip()
            if summary:
                out.append(CandidateEvent(summary, start, end, description, source_file))
        except Exception:
            continue
    return out


def parse_fallback(text: str, source_file: str) -> list[CandidateEvent]:
    # minimal fallback: create 1 tentative event if key words exist
    keys = ["미팅", "회의", "콜", "약속", "일정"]
    if not any(k in text for k in keys):
        return []
    start = (now_kst() + timedelta(hours=1)).replace(second=0, microsecond=0)
    end = start + timedelta(minutes=30)
    summary = "업무 대화에서 추출된 일정(검토 필요)"
    return [
        CandidateEvent(
            summary=summary,
            start=start.isoformat(),
            end=end.isoformat(),
            description=text[:1000],
            source_file=source_file,
        )
    ]


def google_api_script() -> str:
    hermes_home = os.getenv("HERMES_HOME", str(Path.home() / ".hermes"))
    return str(Path(hermes_home) / "skills" / "productivity" / "google-workspace" / "scripts" / "google_api.py")


def create_calendar_event(event: CandidateEvent) -> dict[str, Any]:
    script = google_api_script()
    cmd = [
        "python",
        script,
        "calendar",
        "create",
        "--summary",
        event.summary,
        "--start",
        event.start,
        "--end",
        event.end,
    ]
    if event.description:
        cmd += ["--description", event.description]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip() or p.stdout.strip() or "calendar create failed")
    return json.loads(p.stdout.strip() or "{}")


def file_fingerprint(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def main() -> None:
    load_dotenv()
    parser = argparse.ArgumentParser(description="Create Google Calendar events from mobile analysis text files")
    parser.add_argument("--text-root", default="/workspace/server_data/text_results")
    parser.add_argument("--state-file", default="/workspace/server_data/calendar_state.json")
    parser.add_argument("--log-dir", default="/workspace/server_data/calendar_logs")
    parser.add_argument("--apply", action="store_true", help="actually create events in Google Calendar")
    args = parser.parse_args()

    text_root = Path(args.text_root)
    log_dir = Path(args.log_dir)
    state_file = Path(args.state_file)
    log_dir.mkdir(parents=True, exist_ok=True)
    state_file.parent.mkdir(parents=True, exist_ok=True)

    state: dict[str, Any] = {"processed": {}}
    if state_file.exists():
        try:
            state = json.loads(state_file.read_text(encoding="utf-8"))
        except Exception:
            pass
    processed: dict[str, Any] = state.setdefault("processed", {})

    files = sorted(text_root.rglob("*.txt"))
    print(f"[calendar] text_files={len(files)} apply={args.apply}")

    run_log: list[dict[str, Any]] = []

    for f in files:
        fp = file_fingerprint(f)
        if processed.get(str(f)) == fp:
            continue

        text = f.read_text(encoding="utf-8").strip()
        events = parse_with_openai(text, str(f))
        if not events:
            events = parse_fallback(text, str(f))

        if not events:
            run_log.append({"file": str(f), "status": "no_events"})
            processed[str(f)] = fp
            continue

        for ev in events:
            item: dict[str, Any] = {
                "file": str(f),
                "summary": ev.summary,
                "start": ev.start,
                "end": ev.end,
                "status": "planned" if not args.apply else "pending_create",
            }
            if args.apply:
                try:
                    created = create_calendar_event(ev)
                    item["status"] = "created"
                    item["event"] = created
                except Exception as e:
                    item["status"] = "failed"
                    item["error"] = str(e)
            run_log.append(item)

        processed[str(f)] = fp

    state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = log_dir / f"calendar_run_{stamp}.json"
    log_path.write_text(json.dumps(run_log, ensure_ascii=False, indent=2), encoding="utf-8")

    created = sum(1 for x in run_log if x.get("status") == "created")
    planned = sum(1 for x in run_log if x.get("status") == "planned")
    failed = sum(1 for x in run_log if x.get("status") == "failed")
    print(f"[calendar] planned={planned} created={created} failed={failed} log={log_path}")


if __name__ == "__main__":
    main()
