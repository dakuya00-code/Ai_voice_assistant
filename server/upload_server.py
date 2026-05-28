#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

app = FastAPI(title="voice-journal-upload")

STORAGE_ROOT = Path(os.getenv("VOICE_JOURNAL_STORAGE_ROOT", "/workspace/server_data")).resolve()
RECORDINGS_DIR = STORAGE_ROOT / "recordings"
METADATA_DIR = STORAGE_ROOT / "metadata"

PROJECT_ROOT = Path(__file__).resolve().parents[1]
ANALYSIS_INBOX = PROJECT_ROOT / "data" / "inbox"
ANALYSIS_PROCESSED = PROJECT_ROOT / "data" / "processed"
ANALYSIS_RESULTS = PROJECT_ROOT / "data" / "results"
ANALYSIS_SCRIPT = PROJECT_ROOT / "scripts" / "voice_journal.py"

ENABLE_SERVER_ANALYSIS = os.getenv("VOICE_JOURNAL_ENABLE_SERVER_ANALYSIS", "1") == "1"

_analysis_lock = threading.Lock()
_analysis_running = False


@app.on_event("startup")
def _startup() -> None:
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    ANALYSIS_INBOX.mkdir(parents=True, exist_ok=True)
    ANALYSIS_PROCESSED.mkdir(parents=True, exist_ok=True)
    ANALYSIS_RESULTS.mkdir(parents=True, exist_ok=True)


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "storage_root": str(STORAGE_ROOT),
        "recordings_dir": str(RECORDINGS_DIR),
        "analysis_enabled": ENABLE_SERVER_ANALYSIS,
        "analysis_script": str(ANALYSIS_SCRIPT),
    }


def _run_analysis_once() -> None:
    global _analysis_running
    with _analysis_lock:
        if _analysis_running:
            return
        _analysis_running = True

    try:
        if not ANALYSIS_SCRIPT.exists():
            return
        cmd = [
            "python3",
            str(ANALYSIS_SCRIPT),
            "--inbox",
            str(ANALYSIS_INBOX),
            "--processed",
            str(ANALYSIS_PROCESSED),
            "--results",
            str(ANALYSIS_RESULTS),
        ]
        subprocess.run(cmd, check=False)
    finally:
        with _analysis_lock:
            _analysis_running = False


@app.post("/api/upload-text")
async def upload_text(
    session_id: str = Form(...),
    source_file: str = Form(""),
    analyzed_text: str = Form(...),
):
    safe_session = "".join(c for c in session_id if c.isalnum() or c in "-_") or "default"
    session_dir = STORAGE_ROOT / "text_results" / safe_session
    session_dir.mkdir(parents=True, exist_ok=True)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    text_name = f"analysis_{ts}.txt"
    text_path = session_dir / text_name
    text_path.write_text(analyzed_text, encoding="utf-8")

    meta = {
        "session_id": safe_session,
        "source_file": source_file,
        "text_path": str(text_path),
        "created_at": datetime.now().isoformat(),
        "analysis_mode": "mobile_text",
    }
    meta_path = METADATA_DIR / f"{text_path.stem}.json"
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    return JSONResponse(
        {
            "status": "ok",
            "analysis_mode": "mobile_text",
            "text_saved_path": str(text_path),
        }
    )


@app.post("/api/upload")
async def upload(
    session_id: str = Form(...),
    chunk_index: int = Form(0),
    duration_seconds: int = Form(0),
    started_at: str = Form(""),
    recording: UploadFile = File(...),
):
    safe_session = "".join(c for c in session_id if c.isalnum() or c in "-_") or "default"
    session_dir = RECORDINGS_DIR / safe_session
    session_dir.mkdir(parents=True, exist_ok=True)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"chunk_{chunk_index:04d}_{ts}_{recording.filename or 'audio.m4a'}"
    audio_path = session_dir / filename

    with audio_path.open("wb") as f:
        shutil.copyfileobj(recording.file, f)

    meta = {
        "session_id": safe_session,
        "chunk_index": chunk_index,
        "duration_seconds": duration_seconds,
        "started_at": started_at,
        "saved_path": str(audio_path),
    }

    meta_path = METADATA_DIR / f"{audio_path.stem}.json"
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    analysis_enqueued = False
    analysis_error = ""
    if ENABLE_SERVER_ANALYSIS:
        try:
            inbox_name = f"{safe_session}_{audio_path.name}"
            inbox_audio_path = ANALYSIS_INBOX / inbox_name
            shutil.copy2(audio_path, inbox_audio_path)

            inbox_meta_path = ANALYSIS_INBOX / f"{Path(inbox_name).stem}.json"
            inbox_meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

            threading.Thread(target=_run_analysis_once, daemon=True).start()
            analysis_enqueued = True
        except Exception as e:
            analysis_error = str(e)

    return JSONResponse(
        {
            "saved_path": str(audio_path),
            "transcript_path": "",
            "audio_deleted": False,
            "audio_delete_error": "",
            "analysis_mode": "server",
            "analysis_enqueued": analysis_enqueued,
            "analysis_error": analysis_error,
        }
    )
