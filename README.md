# MikeOS Location (mikeos-location)

The MikeOS **system GNSS provider** (`com.mikeos.location`, label **"MikeOS Location"**) — a headless,
always-on native Kotlin foreground Service that OWNS the phone's GNSS request and continuously feeds
fresh fixes to the on-device daemon (`POST https://127.0.0.1:7743/api/location`).

Because the daemon is MikeOS's single location authority (`GET /api/location`), **every app gets a fresh
shared fix regardless of which app is foregrounded** — replacing the old fragile dependency on MikeGuide
being open/foregrounded.

- Primary path: the platform `LocationManager` (GPS + FUSED) — works **standalone on the de-Googled ROM**
  (Google Play Services optional).
- Adaptive cadence for battery (~2 s screen-on, ~30 s screen-off, always under the daemon's 3-min STALE
  threshold).
- Meant to ship as a **ROM priv-app** with location granted by default (see `CLAUDE.md`).
- **NOT** a MikeAgent (no heartbeat / hive / Agent Inspector). The only UI is a tiny status screen.

See `CLAUDE.md` for the design and the required follow-up (strip MikeGuide's provider role).
