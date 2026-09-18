# Journal — Improvement Plan

> **Goal:** a Day One–class journal — quiet, fast, trustworthy, and polished —
> running on Web, Android, and eventually iOS, all over one shared data layer.
>
> Reviewed: web PWA (`index.html`, `js/app.js`, `js/boot.js`, `css/app.css`,
> `sw.js`) and the native Android app (`android/`, Kotlin + Compose).
> Smoke tests pass (`2 passed`). Findings below are from reading the code, not
> guesses — each bug cites the file and function.
>
> **Status: implementation session 1 (2026-09-17).** Part 1 (bugs 1–18),
> the photos-to-Storage move, unit tests, `SCHEMA.md`, and CI are **done**
> and verified (web smoke 2/2, Node unit 16/16, Android unit 17/17,
> `assembleDebug` compiles, cache-bust lockstep enforced). Remaining work is
> Part 2 leftovers, Part 3 polish, and Part 4 — listed per item below and
> summarized at the end.

---

## Part 1 — Bugs to fix

### 🔴 P0 — Data loss / sync corruption (fix first)

- [x] **1. Deleted entries resurrect across devices.**
  Every delete on every client is now a tombstone (`deleted: true`,
  `deletedAt`, fresh `updatedAt`), and tombstoned entries push back up in
  `pullAll()` so deletes propagate. The only remaining hard delete is inside
  `purgeTrash()`, which now also deletes **remotely first** (a local-first
  delete would resurrect the doc on the next sync). Android's
  `CloudSync.delete()` is documented purge-only.

- [x] **2. Android doesn't understand the trash.**
  `Entry.fromMap()` parses `deleted`/`deletedAt`, `toMap()` writes them back
  (tombstones survive Android rewrites), and `CloudSync.pullAll()` filters
  trashed days from every view. Covered by `EntryRoundTripTest`.

- [x] **3. Emptying an entry bypasses the trash.**
  Web `commitDraft()` and Android `commitNow()` both soft-delete when a draft
  becomes empty but a stored entry exists — with an undo toast/snackbar and
  the 30-day safety net.

- [x] **4. Import can clobber newer entries.**
  The web import handler applies updatedAt-wins: strictly-older entries are
  skipped and counted, and the toast reports "Imported X · skipped Y (newer
  version kept)".

- [x] **5. Photos will break sync at scale.**
  Web now uploads each compressed photo to Firebase Storage
  (`users/{uid}/journalDays/{date}/{rand}.jpg`) and stores the download URL;
  the Firestore doc stays small. Legacy base64 entries still render.
  Fallback on upload failure keeps the photo inline with a clear warning.
  Remaining: photo *add* on Android (viewing works), migrating old inline
  photos, and Storage cleanup on purge.

### 🟠 P1 — Broken or missing behavior

- [x] **6. Android has no delete UI at all.**
  Trash icon in the writer (shown once the day exists), wired to the
  soft-delete flow, with an Undo snackbar hosted in `AppScaffold`.

- [x] **7. "Saved" hint can lie.**
  `commitDraft()` returns the push promise and `scheduleSave()` awaits it
  before setting the hint, so `syncState === 'offline'` truthfully renders
  "Saved on this device".

- [x] **8. Stale draft on sign-out / account switch (web).**
  localStorage entries are namespaced per UID (`jr3_entries_<uid>`), reset on
  every auth-state change, and the legacy shared key is migrated on first
  sign-in. Theme/prefs stay device-level on purpose.

- [x] **9. `beforeunload` push is a lost race.**
  The unload path now commits `{ localOnly: true }` — only the synchronous
  localStorage write; the next boot's `pullAll()` pushback carries it up.

- [x] **10. Two tabs fight.**
  The web app listens to the `storage` event; when another tab changed the
  open day it offers "This entry changed in another tab. Replace…?" (throttled
  to one prompt per 30s, no prompt when nothing meaningful changed).

### 🟡 P2 — Polish & hardening

- [x] **11. Sanitize imported `html`.**
  `sanitizeHtml()` allow-lists exactly the writer's markup
  (`p, h2, ul, ol, li, blockquote, b/strong, i/em, u, br`), drops
  script/style/iframe/img/etc., unwraps unknown tags, strips all attributes —
  and runs on every incoming entry (localStorage, Firestore, import), not
  just imports. Photo URLs are `esc()`-ed in grids/thumbnails.

- [x] **12. CSP: add `frame-ancestors 'self'`.** Done, plus the
  `firebasestorage.googleapis.com` img-src/connect-src entries Storage needs.

- [x] **13. Focus trap + return-focus in the modal.**
  The modal is now `role="dialog" aria-modal="true"`, focus moves in on open,
  Tab cycles inside, and focus returns to the opener on close.

- [x] **14. Android release build is unminified.**
  `isMinifyEnabled = true` + `isShrinkResources = true` (+ proguard file),
  `versionCode` 1 → 2, `versionName` 1.1. Rule going forward: bump versionCode
  per release.

- [x] **15. Automate the cache-bust.**
  `scripts/check-version.mjs` fails CI if `?v=` strings, the sw.js cache
  name, and the PRECACHE list drift out of lockstep; enforced as the first
  step of the web CI job. `npm run bump-version` still does the bump.

- [x] **16. No unit tests.**
  Pure logic extracted to `js/core.js` (dates, `normalize`, merge rules,
  tombstones, `computeStreaks`, `fmt.rel`, prompts) with 16 Node tests in
  `tests/unit/core.test.mjs`. Android: `EntryRoundTripTest` (7 tests,
  incl. tombstone round-trips from web-shaped docs) and `InsightsTest`
  (10 tests: streaks, weekday rates, sleep-vs-mood thresholds).
  *Remaining:* none required by the plan; the sanitize allow-list and
  `commitDraft` flows would benefit from Playwright-level coverage next.

- [x] **17. Commit Firestore security rules to the repo.**
  `firestore.rules` committed (`users/{uid}` subtree readable/writable only
  by that uid). Deploy step is documented in the file; wiring a
  `firebase deploy --only firestore:rules` CI job needs a Firebase service
  account secret in repo settings — owner action.

- [x] **18. CI for Android.**
  New `android` CI job runs `./gradlew :app:assembleDebug :app:testDebugUnitTest`
  (the conditional google-services plugin means no json is needed in CI).

---

## Part 2 — Feature parity gap (Web vs Android)

The two clients already drift. Before iOS joins, close this gap or the schema
drift compounds:

| Feature | Web | Android |
|---|---|---|
| Rich text (bold/heading/quote) | ✅ | ❌ plain text only (HTML preserved) |
| Photos (add/view) | ✅ | 🟡 view only (add remains) |
| Trash / undo delete | ✅ | ✅ (this session) |
| Calendar view | ✅ | ❌ |
| Writing prompts | ✅ | ❌ |
| "On this day" | ✅ | ❌ |
| Export / import | ✅ | ❌ |
| Set/change PIN | ✅ | ❌ read-only (deliberate) |
| Place lookup | ❌ | ✅ |
| Health Connect insights | ❌ | ✅ |
| Mood chart + heatmap | ✅ | ❌ bars only |
| Theme options | ✅ Auto/Light/Dark | Auto/Light/Dark |

- [x] **19.** `SCHEMA.md` written — the contract for
  `users/{uid}/journalDays/{date}`: every field, type, who writes it, who
  reads it (incl. `deleted`, `deletedAt`, `pleasantness`, `energy`, `place`),
  the sync/merge/purge rules, and the field-preservation rule for new
  clients.

- [ ] **20. Bring Android up: photos view/add (after Storage move), trash,
  calendar, prompts, export.** **Done: trash + photo viewing.** Remaining,
  in the plan's order: photo add on Android, calendar view, writing prompts,
  export.

- [x] **21. Decide the fate of `energy`.** Decided and documented in
  `SCHEMA.md`: `energy` is legacy/read-only — Android still displays it on
  old two-axis entries, no client writes new values, iOS must not inherit it.

- [x] **22. Unify theme options (Auto on web).** Web now cycles
  Auto → Light → Dark; Auto follows `prefers-color-scheme` live (media-query
  listener) and remains the default.

---

## Part 3 — Day One–level polish (the "modern & well-polished" bar)

- [ ] **23. Onboarding.** First run still drops you at a blank page. Three
  quiet screens: what it is → sign in → write your first line (prompt
  pre-filled).
- [ ] **24. Daily reminder.** Local notification on Android; web
  `Notification` API. One setting: "Remind me at 21:00". Biggest streak
  driver.
- [ ] **25. End-to-end encryption.** Decide before iOS: derive a key from a
  passphrase (or wrap a random key with the PIN), encrypt entry bodies
  client-side before Firestore.
- [ ] **26. Editor upgrade.** Replace deprecated `document.execCommand` with
  a maintained editor (TipTap/ProseMirror keeps the no-framework build
  viable). Android stays plain-text.
- [ ] **27. Sign in with Apple.** Required by App Store rules once
  third-party sign-in is offered; add to web so iOS inherits it.
- [ ] **28. Entry page, not just edit.** Reading mode (calm,
  typography-first, full-width photos) separated from writing — both
  clients.
- [ ] **29. Motion & feel.** View transitions between days, skeleton states
  instead of the full-screen spinner, haptics on the PIN pad. Keep
  `prefers-reduced-motion` honored.
- [ ] **30. Search upgrade.** 🟡 Web search now also matches the mood label
  (e.g. "rough"); match highlighting remains, and web still has no `place`
  data to search — surfacing place on the web would close that half.

---

## Part 4 — iOS readiness (do these before writing Swift)

- [x] **31. Photos in Firebase Storage** (bug 5) — done on the web; Android
  add and old-photo migration remain.
- [x] **32. `SCHEMA.md` written and enforced** (step 19).
- [ ] **33. Sign in with Apple live on web** (step 27) — not started.
- [ ] **34. E2EE decision made** (step 25) — not started; the crypto
  decision is the one most worth making before iOS.
- [x] **35. Sync behavior extracted into tests.** Merge rules, tombstone
  semantics, and the purge boundary are documented in `SCHEMA.md` and
  unit-tested in `tests/unit/core.test.mjs` — portable to Swift almost
  mechanically.
- [ ] **36. Choose the iOS stack.** Recommendation stands: native SwiftUI.
- [ ] **37. Register the iOS app in the same Firebase project** and reuse
  `users/{uid}/journalDays` unchanged.

---

## Suggested order — updated

1. ~~Week 1: P0 bugs 1–4 + schema doc.~~ **Done.**
2. ~~Week 2: Photos → Firebase Storage (bug 5) + Android delete/trash UI.~~
   **Done (Android photo *add* still open).**
3. ~~Week 3: P1/P2 hardening + unit tests.~~ **Done.**
4. **Next session:** Android parity remainder (step 20) → onboarding (23) →
   reminders (24) → E2EE decision (25) → Sign in with Apple (27) → editor
   (26) → read mode (28) → motion (29).
5. **Then:** iOS (SwiftUI), born conforming to `SCHEMA.md`.

---

*Original review: 2026-09-17. Session 1 implemented 2026-09-17: web smoke
2/2, Node unit 16/16, Android unit 17/17, `assembleDebug` + release config
compile. Verification commands: `npm test`, `node scripts/check-version.mjs`,
`cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.*
