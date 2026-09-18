// Unit tests for js/core.js — the pure sync/merge/tombstone/streak logic.
// These are the rules a third client (iOS) will port, so they get tested
// once, here, in plain Node.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);
const core = require(join(dirname(fileURLToPath(import.meta.url)), '../../js/core.js'));

const {
  isKey, dkey, shiftKey, daysBetween, fmt,
  htmlToText, countWords, truncateWords,
  normalize, blankEntry, isEmpty,
  TRASH_DAYS, applyTombstone, restoreFromTombstone, isPurged,
  mergeRemoteEntry, needsPush,
  computeStreaks, promptFor,
} = core;

/* ── Dates ─────────────────────────────────────────────────────── */

test('isKey accepts real dates and rejects junk', () => {
  assert.equal(isKey('2026-09-17'), true);
  assert.equal(isKey('2026-13-01'), false);  // no month 13
  assert.equal(isKey('2026-02-30'), false);  // invalid day rolls over → NaN
  assert.equal(isKey('09/17/2026'), false);
  assert.equal(isKey(null), false);
  assert.equal(isKey('2026-9-7'), false);    // zero-padded only
});

test('shiftKey and daysBetween agree', () => {
  assert.equal(shiftKey('2026-01-01', -1), '2025-12-31');
  assert.equal(shiftKey('2024-02-28', 1), '2024-02-29');  // leap day
  assert.equal(shiftKey('2025-02-28', 1), '2025-03-01');
  assert.equal(daysBetween('2026-09-17', '2026-09-10'), 7);
});

/* ── fmt.rel ───────────────────────────────────────────────────── */

test('fmt.rel buckets', () => {
  const today = core.todayKey();
  assert.equal(fmt.rel(today), 'Today');
  assert.equal(fmt.rel(shiftKey(today, -1)), 'Yesterday');
  assert.equal(fmt.rel(shiftKey(today, 1)), 'Tomorrow');
  assert.equal(fmt.rel(shiftKey(today, -5)), '5 days ago');
  assert.equal(fmt.rel(shiftKey(today, -14)), '2 weeks ago');
  assert.equal(fmt.rel('not-a-date'), '');
});

/* ── Text ──────────────────────────────────────────────────────── */

test('htmlToText flattens paragraphs without running them together', () => {
  assert.equal(htmlToText('<p>Hello</p><p>World</p>'), 'Hello\n\nWorld');
  assert.equal(htmlToText('<h2>Title</h2><ul><li>one</li><li>two</li></ul>'), 'Title\n\none\n\ntwo');
  assert.equal(htmlToText('a<br>b'), 'a\nb');
  assert.equal(htmlToText('&amp;&lt;&gt;&quot;'), '&<>"');
  assert.equal(htmlToText(''), '');
});

test('countWords and truncateWords', () => {
  assert.equal(countWords('one two  three'), 3);
  assert.equal(countWords(''), 0);
  const long = 'alpha bravo charlie delta echo foxtrot golf hotel india juliet';
  const cut = truncateWords(long, 30);
  assert.ok(cut.endsWith('…'));
  assert.ok(!cut.includes('juliet'));
});

/* ── normalize ─────────────────────────────────────────────────── */

test('normalize coerces legacy shapes', () => {
  const e = normalize('2026-09-17', { text: 'hello world' });
  assert.equal(e.date, '2026-09-17');
  assert.equal(e.html, '<p>hello world</p>');
  assert.equal(e.plain, 'hello world');
  assert.equal(e.words, 2);
  assert.equal(e.deleted, false);

  const fromPhoto = normalize('2026-09-17', { photo: 'data:image/jpeg;base64,AA' });
  assert.deepEqual(fromPhoto.photos, ['data:image/jpeg;base64,AA']);
});

test('normalize drops records without a usable date', () => {
  assert.equal(normalize('garbage', { title: 'x' }), null);
  assert.equal(normalize(null, { title: 'x' }), null);
});

test('normalize preserves tombstones', () => {
  const e = normalize('2026-09-17', { deleted: true, deletedAt: 123, updatedAt: 456 });
  assert.equal(e.deleted, true);
  assert.equal(e.deletedAt, 123);
});

test('normalize rejects out-of-range mood and junk photos', () => {
  const e = normalize('2026-09-17', { mood: 9, photos: ['ok', 42, null] });
  assert.equal(e.mood, null);
  assert.deepEqual(e.photos, ['ok']);
});

test('isEmpty treats mood/tags/photos as content', () => {
  assert.equal(isEmpty(blankEntry('2026-09-17')), true);
  assert.equal(isEmpty({ ...blankEntry('2026-09-17'), mood: 3 }), false);
  assert.equal(isEmpty({ ...blankEntry('2026-09-17'), tags: ['x'] }), false);
  assert.equal(isEmpty({ ...blankEntry('2026-09-17'), photos: ['p'] }), false);
});

/* ── Tombstones ────────────────────────────────────────────────── */

test('tombstone round trip: delete, purge boundary, restore', () => {
  const now = 1_726_588_800_000;
  const entry = { ...blankEntry('2026-09-17'), title: 'x', updatedAt: now - 1000 };

  const trashed = applyTombstone(entry, now);
  assert.equal(trashed.deleted, true);
  assert.equal(trashed.deletedAt, now);
  assert.equal(trashed.updatedAt, now);
  assert.equal(entry.deleted, undefined);   // original untouched

  assert.equal(isPurged(trashed, now), false);
  // Purge keeps tombstones for TRASH_DAYS, then drops them once they are
  // STRICTLY older than the window (isPurged compares deletedAt < cutoff).
  const cutoff = now + TRASH_DAYS * 24 * 60 * 60 * 1000;
  assert.equal(isPurged(trashed, cutoff), false);       // exactly 30 days → kept
  assert.equal(isPurged(trashed, cutoff + 1), true);    // a moment past → purged
  assert.equal(isPurged(entry, cutoff + 1), false);     // live entries never purge

  const restored = restoreFromTombstone(trashed, now + 5);
  assert.equal(restored.deleted, false);
  assert.equal(restored.deletedAt, null);
  assert.equal(restored.updatedAt, now + 5);
});

/* ── Sync merge rules ────────────────────────────────────────────
   These mirror pullAll() on the web, loadEverything on Android, and are
   the rules iOS must port. Newer updatedAt wins; a tie goes remote. */

test('mergeRemoteEntry: newer remote wins, newer local kept, tie goes remote', () => {
  const local = { date: '2026-09-17', title: 'local', updatedAt: 100 };
  const remoteNewer = { date: '2026-09-17', title: 'remote', updatedAt: 200 };
  const remoteOlder = { date: '2026-09-17', title: 'remote', updatedAt: 50 };
  const remoteTie = { date: '2026-09-17', title: 'remote', updatedAt: 100 };

  assert.equal(mergeRemoteEntry(local, remoteNewer).title, 'remote');
  assert.equal(mergeRemoteEntry(local, remoteOlder).title, 'local');
  assert.equal(mergeRemoteEntry(local, remoteTie).title, 'remote');
  // No local copy at all → adopt the remote one.
  assert.equal(mergeRemoteEntry(null, remoteOlder).title, 'remote');
  // A remote tombstone beats a stale live copy.
  const remoteTombstone = { ...remoteNewer, deleted: true };
  assert.equal(mergeRemoteEntry(local, remoteTombstone).deleted, true);
});

test('needsPush: missing remotely or strictly newer locally', () => {
  const local = { date: '2026-09-17', updatedAt: 100 };
  assert.equal(needsPush(local, null), true);
  assert.equal(needsPush(local, { updatedAt: 50 }), true);
  assert.equal(needsPush(local, { updatedAt: 100 }), false);  // tie: remote already has it
  assert.equal(needsPush(local, { updatedAt: 200 }), false);
});

/* ── Streaks ───────────────────────────────────────────────────── */

test('computeStreaks: empty, current-with-today, yesterday grace, gaps', () => {
  const today = '2026-09-17';
  assert.deepEqual(computeStreaks([], today), { current: 0, longest: 0 });

  const run = ['2026-09-15', '2026-09-16', '2026-09-17'];
  assert.deepEqual(computeStreaks(run, today), { current: 3, longest: 3 });

  // Today not written yet: the streak survives until today ends.
  const throughYesterday = ['2026-09-15', '2026-09-16'];
  assert.deepEqual(computeStreaks(throughYesterday, today), { current: 2, longest: 2 });

  const withGap = ['2026-09-10', '2026-09-11', '2026-09-17'];
  assert.deepEqual(computeStreaks(withGap, today), { current: 1, longest: 2 });
});

test('computeStreaks ignores junk keys and duplicates', () => {
  const today = '2026-09-17';
  const messy = ['2026-09-17', '2026-09-17', 'garbage', '2026-09-16', null];
  assert.deepEqual(computeStreaks(messy, today), { current: 2, longest: 2 });
});

/* ── Prompts ───────────────────────────────────────────────────── */

test('promptFor is deterministic and within range', () => {
  const a = promptFor('2026-09-17');
  assert.equal(a, promptFor('2026-09-17'));
  assert.ok(core.PROMPTS.includes(a));
  assert.notEqual(promptFor('2026-09-17'), promptFor('2026-09-18'));
});
