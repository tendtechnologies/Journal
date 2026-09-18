# SCHEMA.md — Journal data contract

The one document every client conforms to. Three readers/writers share the
Firestore documents at `users/{uid}/journalDays/{yyyy-MM-dd}` (web PWA,
Android, iOS eventually) — when they disagree about a field, *this file is
the arbiter*, not whatever another client happens to do today.

Firestore security rules: a user can only read/write their own subtree
(`firestore.rules` in this repo).

## Document: `users/{uid}/journalDays/{date}`

One document per day. Document ID is the date key, `yyyy-MM-dd`, always
zero-padded, always a real calendar date.

| Field | Type | Who writes | Who reads | Notes |
|---|---|---|---|---|
| `date` | string | every client | every client | Same value as the doc ID. Fallback when reading: parse the doc ID; drop the doc if neither parses. |
| `title` | string | every client | every client | Plain text, one line. |
| `html` | string | web (rich text), Android (only when the body text changed) | every client | Rich-text body. Android preserves a browser-written `html` **verbatim** — it never regenerates HTML unless the text actually changed, or it would silently flatten formatting. Sanitized on the web before rendering (allow-list: `p, h2, ul, ol, li, blockquote, b, i, u, br` — no attributes survive). |
| `plain` | string | every client | every client | Flattened text of `html`. Readers that don't render HTML display this. Derived on read when absent. |
| `mood` | number (1–5) or null | web (1–5 picker), Android (mirrors `pleasantness`) | every client | The legacy 1–5 scale, kept as the *lingua franca*: Android's two-axis pleasantness is rounded into this on every write so the browser needs no migration. Out-of-range values are ignored on read. |
| `pleasantness` | number (1–5) or null | Android | Android | Two-axis mood, axis 1. The web doesn't render this axis; it falls back to `mood`. |
| `energy` | number (1–5) or null | **nobody, going forward** | Android (read-only, legacy entries) | **Decision (plan item 21): dead code.** Entries from the old two-axis pad still carry it and Android still displays it for those days, but no client writes new values and iOS must not inherit the ambiguity. A migration would delete the field from old docs; until one is scheduled, treat it as read-only legacy. |
| `place` | string or null | Android | Android (display + search) | Free text. Not surfaced on the web yet; the web preserves it (normalize keeps unknown fields' neighbors intact) but a web *rewrite* of the doc drops it — same caveat as any field a client doesn't model. The web reading `place` for search is a known parity gap (plan item 30). |
| `tags` | array\<string\> | every client | every client | Max 20, each ≤ 24 chars, no `#` prefix. |
| `photos` | array\<string\> | web | every client | Each item is either a Firebase Storage **download URL** (`https://firebasestorage.googleapis.com/…`, preferred) or a legacy base64 `data:` URL. Path convention for new uploads: `users/{uid}/journalDays/{date}/{random}.jpg`. Max 10 per entry. Android renders both shapes read-only. |
| `favorite` | boolean | every client | every client | |
| `words` | number | every client | every client | Word count of `plain`. Derived on read when absent/zero. |
| `updatedAt` | number (ms epoch) | every client | every client | **The conflict-resolution field.** Newer wins; tie goes to the remote copy. Every write bumps it. |
| `deleted` | boolean | every client | every client | Soft-delete tombstone. `false`/absent = live. Never hard-delete a day doc except in the purge (below). |
| `deletedAt` | number (ms epoch) or null | every client | every client | When the tombstone was set. Entries older than 30 days past this are purged. |

## Sync rules (unit-tested in `js/core.js`, `tests/unit/core.test.mjs`)

1. **Merge (pull):** for each remote doc, `mergeRemoteEntry(local, remote)` —
   newer `updatedAt` wins; a tie goes remote. Applied on web (`pullAll`) and
   Android (`loadEverything`).
2. **Pushback:** a local entry that is missing remotely or strictly newer
   than the remote copy is pushed (`needsPush`). This is what keeps offline
   edits from stranding on one device. Tombstoned entries push back too —
   that is how a delete propagates.
3. **Never hard-delete except in the purge.** Deleting = `deleted: true` +
   `deletedAt: now` + fresh `updatedAt`. Purge (web: `purgeTrash()` on boot;
   Android: not yet implemented) hard-deletes only docs whose `deletedAt` is
   more than 30 days old, and deletes **remotely first** — a local-first
   delete would resurrect the doc on the next sync.
4. **Import** applies the same updatedAt-wins rule; an older backup entry
   never overwrites a newer edit (reported as "skipped N").

## Related documents

- `users/{uid}/settings/security` — the PIN-lock record
  (`{salt, hash, length}`, or `{disabled: true}` as a tombstone). Written by
  the web only; Android reads it to gate its own lock screen. Same
  tombstone discipline: clearing the lock writes `disabled`, never deletes.

## Field-preservation rule for new clients

`set()` (not merge) is the write shape, so a client writes exactly the
fields it models. **A client must model (and round-trip) every field above,
even ones it doesn't render** — dropping `deleted`/`deletedAt` or
`place`/`energy` on rewrite is a data-corruption bug, not a style choice.
This is exactly the bug class `EntryRoundTripTest` guards on Android.
