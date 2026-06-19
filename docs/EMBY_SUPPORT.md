# Emby Server Support

> **Status: disabled by default.** Official releases of Stonecrusher Media are Jellyfin-only.
> Emby integration remains in the repository for optional local builds but is not tested
> or supported in default builds.

Emby support can be re-enabled for development by setting `moonfin.emby.enabled=true` in
[`gradle.properties`](../gradle.properties). That switches the build from lightweight stub
modules to the full `server/emby` and `playback/emby` implementations.

## Supported Versions (when enabled)

| Emby Version | Status |
|--------------|--------|
| 4.9.x | Previously tested, recommended when experimenting locally |
| 4.8.x | Supported in code, not validated in CI |
| < 4.8.0.0 | Not supported |

## Server Detection

When Emby support is enabled, the app detects Jellyfin vs Emby by querying
`/System/Info/Public`. The detected server type is stored with the server entry.

When Emby support is **disabled** (the default), Emby servers are rejected during setup
and existing Emby server entries are treated as unsupported.

## Feature Compatibility Matrix

This matrix describes behavior when `moonfin.emby.enabled=true`. With the default
disabled build, only the Jellyfin column applies.

| Feature | Jellyfin | Emby | Notes |
|---------|----------|------|-------|
| **Authentication** | | | |
| Username/Password login | Yes | Yes | Identical |
| QuickConnect (QR code) | Yes | No | Different Emby API, not yet implemented |
| Public user list | Yes | Yes | Identical |
| Token auto-login | Yes | Yes | Identical |
| Emby Connect | No | No | Cloud account linking, not yet implemented |
| **Browsing** | | | |
| Library sections | Yes | Yes | Identical |
| Item details | Yes | Yes | Identical |
| Search | Yes | Yes | Identical |
| Favorites | Yes | Yes | Identical |
| Played/Unplayed | Yes | Yes | Identical |
| Resume items | Yes | Yes | Identical |
| Next Up | Yes | Yes | Identical |
| Similar items | Yes | Yes | Identical |
| Latest media | Yes | Yes | Identical |
| Display preferences | Yes | Yes | Cached in-memory (5 min TTL) |
| **Playback** | | | |
| Video direct play | Yes | Yes | Identical |
| Video transcode (HLS) | Yes | Yes | Identical |
| Audio direct play | Yes | Yes | Identical |
| Audio transcode | Yes | Yes | Identical |
| Subtitles (external) | Yes | Yes | Identical |
| Subtitles (burn-in) | Yes | Yes | Identical |
| Device profile negotiation | Yes | Yes | Same model, different construction |
| Playback progress reporting | Yes | Yes | Identical |
| Chapter navigation | Yes | Yes | Identical |
| Intro/credits skip | Yes | No | Jellyfin Media Segments, not available in Emby |
| Trickplay (seek preview) | Yes | No | Different format (Jellyfin: trickplay, Emby: BIF). BIF not yet implemented |
| Lyrics | Yes | No | Jellyfin-only API |
| **Live TV** | | | |
| Channel list | Yes | Yes | Identical |
| Program guide (EPG) | Yes | Yes | Identical |
| Live TV playback | Yes | Yes | Identical |
| DVR recordings | Yes | Yes | Identical |
| Timer management | Yes | Yes | Identical |
| **Real-Time** | | | |
| WebSocket events | Yes | Yes | Native OkHttp WebSocket for Emby |
| Remote control | Yes | Yes | Similar message types |
| Library change notifications | Yes | Yes | Similar message types |
| SyncPlay (group watch) | Yes | No | Emby uses incompatible "Party" protocol |
| **Other** | | | |
| Multi-server | Yes | Yes* | *Only when Emby support is enabled at build time |
| Theme music | Yes | Yes | Identical |
| Client log upload | Yes | No | Jellyfin-only |
| Home screen channels | Yes | Yes | Uses common item abstraction |
| Screensaver | Yes | Yes | Uses common image abstraction |
| External player | Yes | Yes | Stream URL adapts per server type |
| Seerr | Yes | No | Requires Jellyfin server for auth |

## Enabling Emby for Local Development

1. Set `moonfin.emby.enabled=true` in `gradle.properties`
2. Sync Gradle and rebuild
3. Connect to an Emby Server 4.8.0.0 or newer

The full implementation lives in `server/emby/` and `playback/emby/`. With the flag set to
`false`, Gradle redirects those module paths to no-op stubs in `server/emby-stub/` and
`playback/emby-stub/`.

## Feature Gating

When connected to an Emby server (enabled builds only), Jellyfin-only features are hidden
automatically: SyncPlay, media segment skip, lyrics, trickplay thumbnails, and client log
upload.

## Known Limitations

- **Disabled by default** — not included in official release builds
- **Emby Connect** (cloud account linking) is not yet supported
- **BIF trickplay** (seek preview thumbnails) uses a different format than Jellyfin
- **QuickConnect** on Emby uses a different API than Jellyfin
- **SyncPlay** is Jellyfin-only; Emby's "Watch Party" uses an incompatible protocol
- **Seerr** integration requires a Jellyfin server for authentication
