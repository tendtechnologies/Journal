# Journal — Android

A native Kotlin/Compose client for the same journal the web app writes. Built to
match WeightTracker's conventions so the two apps stay one codebase in spirit:
same indigo, same typography scale, same `AppPalette`-over-Material3 approach,
same hand-drawn icons instead of a material-icons dependency, dynamic colour
deliberately off.

## Before it will build

**You must register this app in Firebase and drop in `app/google-services.json`.**
The `com.google.gms.google-services` plugin fails the build outright when that
file is missing — this is the one step that can't be done from outside the
console.

1. Firebase console → the existing **checkcheck-3d35f** project → Add app → Android
2. Package name: `com.avi.journal`
3. Add your debug SHA-1 (`./gradlew signingReport`) — Credential Manager sign-in
   needs it, or Google sign-in fails at runtime with a bare `16:` error
4. Download `google-services.json` into `app/`

No Firestore rules change is needed. The existing rules in the Checkcheck repo
use `match /users/{userId}/{document=**}`, a recursive wildcard scoped to the
owner, which already covers everything this app reads and writes.

## Shared data model

Both clients read and write the *same* documents at
`users/{uid}/journalDays/{yyyy-MM-dd}`. Fields are defined in `data/Models.kt`.

Mood is the one place they differ. The web app stores a single `mood` in 1..5;
this app stores two axes, `pleasantness` and `energy`. Rather than change the
web app in the same breath, **pleasantness is mirrored into `mood` on every
write**, so:

- the browser keeps working unchanged and simply doesn't show the energy axis
- this app falls back to `mood` when it opens a day the browser wrote
- nothing is lost in either direction

`html` and `photos` are preserved verbatim unless the text is edited here.
Opening a browser-written entry on the phone and closing it again must not
silently flatten its formatting.

## Health Connect

Reads **steps** and **sleep**, both optional; every screen works without them.
Nothing is ever written back, and health data is never copied to Firestore — it
already syncs to a new phone through Health Connect, so mirroring it onto a
server would duplicate health measurements for no gain. (Same reasoning
WeightTracker's `CloudSync` gives for leaving weights out.)

Two details worth not undoing:

- `READ_HEALTH_DATA_HISTORY` is requested. Without it Health Connect silently
  truncates any range beyond 30 days, so year-long correlations would be
  computed from a month of data with no indication anything was missing.
- **Sleep is attributed to the day you woke up**, not the day you lay down. A
  night that starts at 23:40 belongs to the next morning's mood. Bucketing by
  start time would shift every sleep/mood correlation by a day for anyone who
  goes to bed before midnight.

## Location

Not part of Health Connect — it has no general location type. Coarse location
comes from Play services' fused provider, requested only when the writer taps
"use my location" on an entry. Read once per tap, no background updates, and
**only the place name is stored** — no coordinates, so the journal never
accumulates a location history.

## Correlations

`data/Insights.kt` refuses to report anything below `MIN_SAMPLE` paired days,
and describes a gap smaller than 0.25 on a 5-point scale as "no clear
difference" rather than dressing up noise as a finding. Steps are split at the
person's own median rather than a round 10,000. The UI says how many more days
are needed instead of showing an empty card.

## Known gaps

- Photos are preserved but not viewable or addable here (web only).
- The calendar view from the web app isn't ported yet.
- Tags render in a single `Row` and will clip past ~4 on a narrow screen; wants
  a flow layout.
- No widget yet — WeightTracker's is a good template if you want one.
- PIN can only be *verified* here, not set or cleared; the browser owns the
  format so there's a single source of truth.
