---
name: jellyfin-crash-logs
description: Fetches Moonfin Android TV crash reports from a Jellyfin server via the System/Logs API, downloads new reports, and tracks remediated logs to avoid re-processing. Use when triaging app crashes, investigating telemetry uploads, or when the user mentions Jellyfin crash logs, ACRA reports, or stonecrusher crash triage.
---

# Jellyfin Crash Log Retrieval

Fetch crash reports uploaded by Moonfin Android TV to Jellyfin and track which ones have already been handled.

## Setup

1. Copy `config.example.env` to `.env` in this skill directory (`.cursor/skills/jellyfin-crash-logs/.env`).
2. Set `JELLYFIN_URL` and `JELLYFIN_API_KEY`. Requires an admin API key with access to `GET /System/Logs`.

`.env` is gitignored. Never commit API keys.

## Quick start

From the repository root:

```bash
python .cursor/skills/jellyfin-crash-logs/scripts/fetch_crash_logs.py fetch
```

This lists server logs, filters Moonfin Android TV crash uploads, skips entries already recorded in `state/manifest.json`, downloads new reports to `crash-logs/`, and updates the manifest.

## Commands

| Command | Purpose |
|---------|---------|
| `fetch` | Download new, unprocessed crash logs |
| `list` | Show manifest entries and status |
| `list --pending` | Show downloaded logs not yet remediated |
| `mark-remediated NAME [NOTES...]` | Mark a log as fixed/handled |
| `mark-skipped NAME [NOTES...]` | Mark as reviewed but intentionally not fixing |
| `show NAME` | Print a downloaded log or fetch it if missing |

`NAME` is the Jellyfin log filename (e.g. `upload_Moonfin Android TV_1.8.2_20260618235100_abc123.log`).

## Workflow

```
Task Progress:
- [ ] Run `fetch` to pull new crash reports
- [ ] For each pending log, read stack trace and device info
- [ ] Investigate and fix the crash in code
- [ ] Run `mark-remediated NAME "brief fix description"`
- [ ] On next run, `fetch` skips remediated and skipped logs
```

## Crash report format

Reports are markdown with YAML frontmatter uploaded via Jellyfin `POST /ClientLog/Document`:

- Server filename pattern: `upload_Moonfin Android TV_{version}_{timestamp}_{uuid}.log`
- Frontmatter includes `type: crash_report`, `client_version`, stack trace, logcat, device info

Filter both by filename prefix and `type: crash_report` in content.

## Manifest states

| Status | Meaning |
|--------|---------|
| `downloaded` | Fetched locally, not yet triaged |
| `remediated` | Bug fixed or resolved; skip on future runs |
| `skipped` | Reviewed, no action needed; skip on future runs |

Manifest path: `state/manifest.json`. Downloaded logs: `crash-logs/`. Both are gitignored.

## Triage output

After `fetch`, summarize for the user:

1. Count of newly downloaded logs
2. For each pending log: filename, client version, exception type/message (first line of stack trace), device model
3. Remind how to mark remediated when done

## API reference

- `GET {JELLYFIN_URL}/System/Logs` — list log files
- `GET {JELLYFIN_URL}/System/Logs/Log?name={filename}` — download log body
- Authorization header: `MediaBrowser Token="{JELLYFIN_API_KEY}"`

Only process logs matching `upload_Moonfin Android TV` with `type: crash_report`. Ignore server logs (`log_*.log`), FFmpeg logs, and other client uploads.
