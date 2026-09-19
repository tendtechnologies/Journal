/* ================================================================
   Journal — core.js
   Pure logic shared by the app and the unit tests: date keys,
   formatting, entry normalization, sync-merge rules, tombstones,
   streaks, moods, prompts.

   NO DOM, NO firebase, NO localStorage in this file — everything
   here must be loadable in plain Node so `node --test` can exercise
   it. Browser-only code lives in js/app.js.
================================================================ */

'use strict';

/* ─── Dates ───────────────────────────────────────────────────── */

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
// The regex alone isn't enough: V8's Date parser happily rolls invalid
// dates over ('2026-02-30' parses as March 2), so verify the components
// survive the round trip before accepting a key.
const isKey = k => {
  if (typeof k !== 'string' || !DATE_RE.test(k)) return false;
  const d = new Date(k + 'T00:00:00');
  if (isNaN(d.getTime())) return false;
  return d.getFullYear() === Number(k.slice(0, 4))
    && d.getMonth() === Number(k.slice(5, 7)) - 1
    && d.getDate() === Number(k.slice(8, 10));
};

function dkey(d) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
const todayKey = () => dkey(new Date());
const parseKey = k => new Date(k + 'T00:00:00');

function shiftKey(k, delta) {
  const d = parseKey(k);
  d.setDate(d.getDate() + delta);
  return dkey(d);
}
function daysBetween(a, b) { return Math.round((parseKey(a) - parseKey(b)) / 86400000); }

// Every formatter guards its input, so a malformed record can never
// render as "Invalid Date".
const fmt = {
  full:  k => isKey(k) ? parseKey(k).toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }) : '',
  med:   k => isKey(k) ? parseKey(k).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }) : '',
  short: k => isKey(k) ? parseKey(k).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '',
  rel: k => {
    if (!isKey(k)) return '';
    const diff = daysBetween(todayKey(), k);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Yesterday';
    if (diff === -1) return 'Tomorrow';
    if (diff < 0) return 'Upcoming';
    if (diff < 7) return diff + ' days ago';
    const w = Math.floor(diff / 7);
    if (w < 5) return w === 1 ? 'Last week' : w + ' weeks ago';
    const m = Math.floor(diff / 30);
    if (m < 12) return m === 1 ? 'Last month' : m + ' months ago';
    const y = Math.floor(diff / 365);
    return y === 1 ? 'Last year' : y + ' years ago';
  },
};

/* ─── Text ────────────────────────────────────────────────────── */

function esc(s) {
  if (s == null) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// Regex-based (not DOM) so it runs identically in Node and the browser.
// Block-level closers become paragraph breaks before tags are stripped.
function htmlToText(html) {
  if (!html) return '';
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|h[1-6]|li|blockquote)>/gi, '\n\n')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/\s+[ \t]/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function countWords(t) {
  const s = (t || '').trim();
  return s ? s.split(/\s+/).length : 0;
}

// Truncate on a word boundary rather than mid-word, with an ellipsis only
// when something was actually cut.
function truncateWords(text, max) {
  if (!text || text.length <= max) return text || '';
  const cut = text.slice(0, max);
  const lastSpace = cut.lastIndexOf(' ');
  return (lastSpace > max * 0.6 ? cut.slice(0, lastSpace) : cut).trimEnd() + '…';
}

/* ─── Entry shape ─────────────────────────────────────────────── */

// Coerce anything (older formats, partial records) into a valid entry,
// or return null when there's no usable date.
function normalize(key, raw) {
  const src = raw || {};
  const date = isKey(src.date) ? src.date : (isKey(key) ? key : null);
  if (!date) return null;

  let html = typeof src.html === 'string' ? src.html : '';
  if (!html && typeof src.text === 'string' && src.text.trim()) {
    html = src.text.split(/\n{2,}/).map(p => '<p>' + esc(p.trim()) + '</p>').join('');
  }
  const plain = typeof src.plain === 'string' && src.plain ? src.plain : htmlToText(html);
  const photos = Array.isArray(src.photos) ? src.photos.filter(p => typeof p === 'string')
    : (typeof src.photo === 'string' && src.photo ? [src.photo] : []);

  return {
    date,
    title: typeof src.title === 'string' ? src.title : '',
    html, plain,
    mood: [1, 2, 3, 4, 5].includes(Number(src.mood)) ? Number(src.mood) : null,
    tags: Array.isArray(src.tags) ? src.tags.filter(x => typeof x === 'string').slice(0, 20) : [],
    photos,
    favorite: !!src.favorite,
    words: Number(src.words) || countWords(plain),
    updatedAt: Number(src.updatedAt) || Number(src.timestamp) || Date.now(),
    // Soft-delete tombstone: kept (not removed) for TRASH_DAYS so "delete"
    // can be undone, then purged for real by purgeTrash().
    deleted: !!src.deleted,
    deletedAt: Number(src.deletedAt) || null,
  };
}

function blankEntry(date) {
  return { date, title: '', html: '', plain: '', mood: null, tags: [],
           photos: [], favorite: false, words: 0, updatedAt: Date.now() };
}
function isEmpty(e) {
  return !e || (!e.title && !e.plain && !(e.photos || []).length && !e.mood && !(e.tags || []).length);
}

/* ─── Soft delete / tombstones ────────────────────────────────────
   "Delete" marks an entry rather than removing it, so an accidental
   delete — the worst kind of journaling accident — can be undone.
   These are the pure transforms; app.js applies them to storage. ── */

const TRASH_DAYS = 30;

function applyTombstone(entry, now) {
  return Object.assign({}, entry, { deleted: true, deletedAt: now, updatedAt: now });
}
function restoreFromTombstone(entry, now) {
  return Object.assign({}, entry, { deleted: false, deletedAt: null, updatedAt: now });
}
function isPurged(entry, now) {
  return !!(entry && entry.deleted && (entry.deletedAt || 0) < now - TRASH_DAYS * 24 * 60 * 60 * 1000);
}

/* ─── Sync merge rules ────────────────────────────────────────────
   Both directions of the pullAll() merge, in testable form.
   Rule: newer updatedAt wins; a tie goes to the remote copy (it may
   carry fields this client doesn't know, and last-writer-wins on
   equal timestamps is arbitrary anyway). A local entry that is
   missing remotely, or strictly newer than the remote copy, must be
   pushed up — this is what keeps an offline edit from being stranded
   on one device forever. ───────────────────────────────────────── */

function mergeRemoteEntry(local, remote) {
  if (!local) return remote;
  if ((remote.updatedAt || 0) >= (local.updatedAt || 0)) return remote;
  return local;
}
function needsPush(local, remote) {
  return !remote || (local.updatedAt || 0) > (remote.updatedAt || 0);
}

/* ─── Streaks ─────────────────────────────────────────────────── */

function computeStreaks(dateKeys, today) {
  const days = new Set(Array.from(dateKeys).filter(isKey));
  if (!days.size) return { current: 0, longest: 0 };
  const sorted = Array.from(days).sort();
  let longest = 0, run = 0, prev = null;
  sorted.forEach(k => {
    run = prev && daysBetween(k, prev) === 1 ? run + 1 : 1;
    longest = Math.max(longest, run);
    prev = k;
  });
  let current = 0, cur = today;
  if (!days.has(cur)) cur = shiftKey(cur, -1);
  while (days.has(cur)) { current++; cur = shiftKey(cur, -1); }
  return { current, longest };
}

/* ─── Moods ───────────────────────────────────────────────────── */

// A diverging scale: two hues either side of a neutral midpoint, shared with
// the Android app so a day looks the same in both. The old ramp had Good and
// Great only ΔE 2.5 apart — visually one colour — and put amber as a third hue
// on one arm, which made it read as a traffic light rather than a scale.
// Dark mode uses its own steps (see moodColor in app.js): on a dark surface
// the extremes have to be the BRIGHTEST, so the arms run the other way.
const MOODS = [
  { v: 1, l: 'Rough', c: '#DC2626', d: '#F87171' },
  { v: 2, l: 'Low',   c: '#F87171', d: '#DC2626' },
  { v: 3, l: 'Okay',  c: '#475569', d: '#A8B0BD' },
  { v: 4, l: 'Good',  c: '#22D3EE', d: '#0891B2' },
  { v: 5, l: 'Great', c: '#0891B2', d: '#67E8F9' },
];
const mood = v => MOODS.find(m => m.v === v);
function moodLabel(v) { const m = mood(v); return m ? m.l : ''; }

const FACE = {
  1: '<path d="M7 8.7L10 10"/><path d="M17 8.7L14 10"/><circle cx="8.7" cy="11.3" r="1" fill="currentColor" stroke="none"/><circle cx="15.3" cy="11.3" r="1" fill="currentColor" stroke="none"/><path d="M8 17.5Q12 13.3 16 17.5"/>',
  2: '<circle cx="8.7" cy="10.8" r="1" fill="currentColor" stroke="none"/><circle cx="15.3" cy="10.8" r="1" fill="currentColor" stroke="none"/><path d="M8 16.2Q12 14.3 16 16.2"/>',
  3: '<circle cx="8.7" cy="10.8" r="1" fill="currentColor" stroke="none"/><circle cx="15.3" cy="10.8" r="1" fill="currentColor" stroke="none"/><path d="M8 15L16 15"/>',
  4: '<circle cx="8.7" cy="10.6" r="1" fill="currentColor" stroke="none"/><circle cx="15.3" cy="10.6" r="1" fill="currentColor" stroke="none"/><path d="M8 14Q12 16.6 16 14"/>',
  5: '<path d="M7.4 10.6Q8.7 9.2 10 10.6"/><path d="M14 10.6Q15.3 9.2 16.6 10.6"/><path d="M7.5 13.6Q12 18.6 16.5 13.6"/>',
};

function moodSvg(v, size, color) {
  const m = mood(v);
  // moodColor (theme-aware) lives in app.js; fall back to the light step in
  // contexts — like Node tests — where there is no DOM theme to consult.
  const themed = typeof moodColor === 'function' ? moodColor(m ? m.v : null) : null;
  const c = color || themed || (m ? m.c : 'currentColor');
  return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" style="display:block;flex-shrink:0;color:' + c + '">'
    + '<circle cx="12" cy="12" r="9"/>' + (FACE[v] || '') + '</svg>';
}

/* ─── Prompts ─────────────────────────────────────────────────── */

const PROMPTS = [
  "What's one thing that made you smile today?",
  "What's weighing on your mind right now?",
  "Describe a small win from today.",
  "What are you grateful for in this moment?",
  "What would make tomorrow feel like a good day?",
  "Write about a conversation that stuck with you.",
  "What's something you're avoiding, and why?",
  "What did you learn about yourself this week?",
  "Describe your energy today in three words, then explain.",
  "What's one thing you'd tell your morning self?",
  "What are you looking forward to?",
  "What's a fear you can name out loud right now?",
  "Who or what supported you today?",
  "What did you do today that felt like 'you'?",
  "What's a thought you keep circling back to?",
  "If today had a title, what would it be?",
  "What's something you need to let go of?",
  "Describe a moment of calm from today.",
  "What's a boundary you held, or wish you'd held?",
  "What's one thing your body is telling you right now?",
  "What surprised you today?",
  "What's something you're proud of, even if small?",
  "Write a note to yourself one year from now.",
  "What pattern have you noticed in yourself lately?",
  "What does rest look like for you right now?",
  "What's a question you don't have the answer to yet?",
  "Who do you want to reach out to, and why haven't you?",
  "What's one thing you could simplify?",
  "Describe today using the weather as a metaphor.",
  "What did you do for someone else today?",
];
function promptFor(k) {
  let h = 0;
  for (let i = 0; i < k.length; i++) h = (h * 31 + k.charCodeAt(i)) >>> 0;
  return PROMPTS[h % PROMPTS.length];
}

/* ─── First-run onboarding ────────────────────────────────────────
   Which onboarding experience a boot should show. 'welcome': never
   seen the app on this device — lead with what it is, then hand off
   to sign-in. 'first-entry': signed in on a brand-new account — land
   on Write with a prompt at the ready. 'done': nothing to show.    */

function onboardingStep({ onboarded, signedIn, hasEntries }) {
  if (onboarded) return 'done';
  if (!signedIn) return 'welcome';
  return hasEntries ? 'done' : 'first-entry';
}

/* ─── Daily reminder ──────────────────────────────────────────────
   Serverless due-check shared by the page (which fires when the app
   is opened or becomes visible) and the best-effort Periodic Background
   Sync path in the service worker. Exact clock-time delivery needs a
   push server (FCM); without one, "due" means "the next check at/after
   the chosen time". lastFiredKey/today are date keys (yyyy-mm-dd). ── */

function reminderDue({ enabled, time }, hasEntryToday, now, lastFiredKey, today) {
  if (!enabled || typeof time !== 'string' || !/^\d{1,2}:\d{2}$/.test(time)) return false;
  if (hasEntryToday) return false;
  if (lastFiredKey === today) return false;
  const parts = time.split(':');
  const target = Number(parts[0]) * 60 + Number(parts[1]);
  return now.getHours() * 60 + now.getMinutes() >= target;
}

/* ─── Node export (the browser ignores this) ──────────────────── */

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    isKey, dkey, todayKey, parseKey, shiftKey, daysBetween, fmt,
    esc, htmlToText, countWords, truncateWords,
    normalize, blankEntry, isEmpty,
    TRASH_DAYS, applyTombstone, restoreFromTombstone, isPurged,
    mergeRemoteEntry, needsPush,
    computeStreaks,
    MOODS, mood, moodLabel, FACE, PROMPTS, promptFor,
    onboardingStep, reminderDue,
  };
}
