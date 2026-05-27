#!/usr/bin/env python3
import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Voice Journal Pipeline")
    parser.add_argument("--inbox", default="data/inbox", help="음성 파일 입력 폴더")
    parser.add_argument("--processed", default="data/processed", help="처리 완료 폴더")
    parser.add_argument("--dry-run", action="store_true", help="실제 처리 없이 파일 탐색만 수행")
    args = parser.parse_args()

    inbox = Path(args.inbox)
    processed = Path(args.processed)
    inbox.mkdir(parents=True, exist_ok=True)
    processed.mkdir(parents=True, exist_ok=True)

    files = [p for p in inbox.iterdir() if p.is_file()]
    print(f"[voice-journal] inbox={inbox} files={len(files)} dry_run={args.dry_run}")

    for f in files:
        print(f"- 발견: {f.name}")
        if not args.dry_run:
            target = processed / f.name
            f.rename(target)
            print(f"  -> 이동: {target}")


if __name__ == "__main__":
    main()
