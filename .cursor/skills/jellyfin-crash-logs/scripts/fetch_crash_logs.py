#!/usr/bin/env python3
"""Fetch and track Moonfin Android TV crash logs from Jellyfin."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parents[1]
ENV_FILE = SKILL_DIR / ".env"
MANIFEST_FILE = SKILL_DIR / "state" / "manifest.json"
CRASH_LOGS_DIR = SKILL_DIR / "crash-logs"

CLIENT_UPLOAD_PREFIX = "upload_Moonfin Android TV"
CRASH_REPORT_MARKER = "type: crash_report"
SKIP_STATUSES = frozenset({"remediated", "skipped"})


def load_env() -> dict[str, str]:
    if not ENV_FILE.is_file():
        sys.exit(f"Missing {ENV_FILE}. Copy config.example.env to .env and set credentials.")

    env: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")

    missing = [key for key in ("JELLYFIN_URL", "JELLYFIN_API_KEY") if not env.get(key)]
    if missing:
        sys.exit(f"Missing required keys in {ENV_FILE}: {', '.join(missing)}")

    env["JELLYFIN_URL"] = env["JELLYFIN_URL"].rstrip("/")
    return env


def api_request(env: dict[str, str], path: str, query: dict[str, str] | None = None) -> bytes:
    url = env["JELLYFIN_URL"] + path
    if query:
        url += "?" + urllib.parse.urlencode(query)

    request = urllib.request.Request(
        url,
        headers={"Authorization": f'MediaBrowser Token="{env["JELLYFIN_API_KEY"]}"'},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        sys.exit(f"HTTP {exc.code} for {url}: {body or exc.reason}")


def load_manifest() -> dict:
    if not MANIFEST_FILE.is_file():
        return {"version": 1, "logs": {}}

    data = json.loads(MANIFEST_FILE.read_text(encoding="utf-8"))
    data.setdefault("version", 1)
    data.setdefault("logs", {})
    return data


def save_manifest(manifest: dict) -> None:
    MANIFEST_FILE.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_FILE.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def is_crash_upload(name: str) -> bool:
    return name.startswith(CLIENT_UPLOAD_PREFIX)


def is_crash_report(content: str) -> bool:
    return CRASH_REPORT_MARKER in content


def parse_frontmatter(content: str) -> dict[str, str]:
    match = re.match(r"^---\r?\n(.*?)\r?\n---", content, re.DOTALL)
    if not match:
        return {}

    fields: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key.strip()] = value.strip()
    return fields


def parse_exception_summary(content: str) -> str | None:
    match = re.search(r"\*\*\*Stack Trace\*\*\*:\s*\n```(?:log)?\s*\n(.*?)\n```", content, re.DOTALL)
    if not match:
        return None

    for line in match.group(1).splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return None


def list_server_logs(env: dict[str, str]) -> list[dict]:
    payload = json.loads(api_request(env, "/System/Logs").decode("utf-8"))
    return [entry for entry in payload if is_crash_upload(entry.get("Name", ""))]


def should_skip(name: str, manifest: dict) -> bool:
    entry = manifest["logs"].get(name)
    return bool(entry and entry.get("status") in SKIP_STATUSES)


def download_log(env: dict[str, str], name: str) -> str:
    body = api_request(env, "/System/Logs/Log", {"name": name})
    return body.decode("utf-8")


def update_entry_metadata(entry: dict, content: str, path: Path) -> None:
    entry["local_path"] = str(path.relative_to(SKILL_DIR))
    entry["size"] = path.stat().st_size

    frontmatter = parse_frontmatter(content)
    if frontmatter.get("client_version"):
        entry["client_version"] = frontmatter["client_version"]
    summary = parse_exception_summary(content)
    if summary:
        entry["exception_summary"] = summary


def ensure_downloaded(
    env: dict[str, str],
    manifest: dict,
    name: str,
    server_item: dict | None = None,
) -> tuple[Path, str, bool]:
    """Return path, content, and whether the file was newly downloaded."""
    CRASH_LOGS_DIR.mkdir(parents=True, exist_ok=True)
    path = CRASH_LOGS_DIR / name
    newly_downloaded = not path.is_file()

    if path.is_file():
        content = path.read_text(encoding="utf-8")
    else:
        content = download_log(env, name)
        path.write_text(content, encoding="utf-8")

    entry = manifest["logs"].setdefault(name, {})
    if entry.get("status") not in SKIP_STATUSES:
        entry["status"] = entry.get("status") or "downloaded"
    if newly_downloaded or "downloaded_at" not in entry:
        entry["downloaded_at"] = utc_now()

    if server_item:
        entry["server_date_created"] = server_item.get("DateCreated")
        entry["server_date_modified"] = server_item.get("DateModified")

    update_entry_metadata(entry, content, path)
    return path, content, newly_downloaded


def cmd_fetch(env: dict[str, str]) -> int:
    manifest = load_manifest()
    server_logs = list_server_logs(env)

    skipped_count = 0
    newly_downloaded = 0

    for item in server_logs:
        name = item["Name"]
        if should_skip(name, manifest):
            skipped_count += 1
            continue

        _, content, is_new = ensure_downloaded(env, manifest, name, item)
        if is_new:
            newly_downloaded += 1

        if not is_crash_report(content):
            entry = manifest["logs"][name]
            entry["status"] = "skipped"
            entry["notes"] = "Not a crash_report document"
            entry["skipped_at"] = utc_now()

    save_manifest(manifest)

    pending = [
        (name, entry)
        for name, entry in manifest["logs"].items()
        if entry.get("status") == "downloaded"
    ]

    print(f"Server crash uploads found: {len(server_logs)}")
    print(f"Skipped (remediated/skipped): {skipped_count}")
    print(f"Newly downloaded: {newly_downloaded}")
    print(f"Pending triage: {len(pending)}")
    print()

    for name, entry in sorted(pending, key=lambda pair: pair[1].get("downloaded_at", ""), reverse=True):
        version = entry.get("client_version", "?")
        summary = entry.get("exception_summary", "unknown exception")
        print(f"- {name}")
        print(f"  version: {version}")
        print(f"  exception: {summary}")

    return 0


def cmd_list(args: argparse.Namespace) -> int:
    manifest = load_manifest()
    entries = list(manifest["logs"].items())

    if args.pending:
        entries = [(name, entry) for name, entry in entries if entry.get("status") == "downloaded"]

    if not entries:
        print("No matching log entries.")
        return 0

    for name, entry in sorted(entries, key=lambda pair: pair[1].get("downloaded_at", ""), reverse=True):
        status = entry.get("status", "unknown")
        version = entry.get("client_version", "?")
        summary = entry.get("exception_summary", "")
        notes = entry.get("notes", "")
        print(f"[{status}] {name}")
        print(f"  version: {version}")
        if summary:
            print(f"  exception: {summary}")
        if notes:
            print(f"  notes: {notes}")
        if entry.get("remediated_at"):
            print(f"  remediated_at: {entry['remediated_at']}")
    return 0


def cmd_mark(env: dict[str, str], name: str, status: str, notes: str | None) -> int:
    manifest = load_manifest()
    ensure_downloaded(env, manifest, name)

    entry = manifest["logs"].setdefault(name, {})
    entry["status"] = status
    if notes:
        entry["notes"] = notes
    timestamp = utc_now()
    if status == "remediated":
        entry["remediated_at"] = timestamp
    elif status == "skipped":
        entry["skipped_at"] = timestamp

    save_manifest(manifest)
    print(f"Marked {name} as {status}.")
    return 0


def cmd_show(env: dict[str, str], name: str) -> int:
    manifest = load_manifest()
    path, content, _ = ensure_downloaded(env, manifest, name)
    save_manifest(manifest)
    print(content)
    print(f"\n--- saved to {path} ---", file=sys.stderr)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("fetch", help="Download new crash logs from Jellyfin")

    list_parser = subparsers.add_parser("list", help="List tracked crash logs")
    list_parser.add_argument("--pending", action="store_true", help="Only show downloaded, unremediated logs")

    remediated_parser = subparsers.add_parser("mark-remediated", help="Mark a log as remediated")
    remediated_parser.add_argument("name", help="Jellyfin log filename")
    remediated_parser.add_argument("notes", nargs="*", help="Optional notes about the fix")

    skipped_parser = subparsers.add_parser("mark-skipped", help="Mark a log as reviewed/skipped")
    skipped_parser.add_argument("name", help="Jellyfin log filename")
    skipped_parser.add_argument("notes", nargs="*", help="Optional notes")

    show_parser = subparsers.add_parser("show", help="Print a crash log")
    show_parser.add_argument("name", help="Jellyfin log filename")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    env = load_env()

    if args.command == "fetch":
        return cmd_fetch(env)
    if args.command == "list":
        return cmd_list(args)
    if args.command == "mark-remediated":
        return cmd_mark(env, args.name, "remediated", " ".join(args.notes).strip() or None)
    if args.command == "mark-skipped":
        return cmd_mark(env, args.name, "skipped", " ".join(args.notes).strip() or None)
    if args.command == "show":
        return cmd_show(env, args.name)

    parser.error(f"Unknown command: {args.command}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
