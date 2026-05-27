#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

app = FastAPI(title="voice-journal-upload")

STORAGE_ROOT = Path(os.getenv("VOICE_JOURNAL_STORAGE_ROOT", "./server_data")).resolve()
RECORDINGS_DIR = STORAGE_ROOT / "recordings"
METADATA_DIR = STORAGE_ROOT / "metadata"


@app.on_event("startup")
def _startup() -> None:
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
    METADATA_DIR.mkdir(parents=True, exist_ok=True)


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "storage_root": str(STORAGE_ROOT),
        "recordings_dir": str(RECORDINGS_DIR),
    }


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

    meta_path = METADATA_DIR / f"{audio_path.stem}.json"
    meta_path.write_text(
        (
            "{\n"
            f"  \"session_id\": \"{safe_session}\",\n"
            f"  \"chunk_index\": {chunk_index},\n"
            f"  \"duration_seconds\": {duration_seconds},\n"
            f"  \"started_at\": \"{started_at}\",\n"
            f"  \"saved_path\": \"{audio_path}\"\n"
            "}\n"
        ),
        encoding="utf-8",
    )

    return JSONResponse(
        {
            "saved_path": str(audio_path),
            "transcript_path": "",
            "audio_deleted": False,
            "audio_delete_error": "",
        }
    )
