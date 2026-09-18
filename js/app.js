/* ================================================================
   Journal — app.js
   One entry per day. Local-first, with Google sign-in and
   Firestore sync. Entries are keyed by date (YYYY-MM-DD).

   Pure logic (dates, formatting, normalize, merge rules, tombstones,
   streaks, moods, prompts) lives in js/core.js, which loads first and
   is unit-tested under node --test.
================================================================ */

'use strict';

/* ─── Firebase ────────────────────────────────────────────────── */

const FB_CONFIG = {
  apiKey:            'AIzaSyBpUUVpBIsuKAx1Tw-cnN4ItXho7IqbMMQ',
  authDomain:        'checkcheck-3d35f.firebaseapp.com',
  projectId:         'checkcheck-3d35f',
  storageBucket:     'checkcheck-3d35f.firebasestorage.app',
  messagingSenderId: '744363444071',
  appId:             '1:744363444071:web:5e72bf03a2771ae83c91c2',
};
firebase.initializeApp(FB_CONFIG);
const fbAuth  = firebase.auth();
const fbStore = firebase.firestore();
let   fbUser  = null;
let   syncState = 'offline';

// A fresh collection: older builds left differently-shaped docs in
// `journalEntries`, and reading those produced bad dates.
const COLLECTION = 'journalDays';
const col = () => fbStore.collection('users').doc(fbUser.uid).collection(COLLECTION);

function setSync(s) {
  syncState = s;
  const dot = $('sync-dot');
  if (dot) dot.className = 'dot-live' + (s === 'syncing' ? ' sync' : s === 'offline' ? ' off' : '');
  const lbl = $('sync-label');
  if (lbl) lbl.textContent = s === 'syncing' ? 'Syncing…'
    : s === 'offline' ? 'Offline — saved on this device' : 'Synced across your devices';
}

async function pushDay(e) {
  if (!fbUser || !e) return;
  setSync('syncing');
  try { await col().doc(e.date).set(e); setSync('synced'); }
  catch (err) { console.warn('save failed', err); setSync('offline'); }
}
// Hard delete — ONLY purgeTrash() may call this. Every user-facing delete
// is a tombstone so it can be undone and so other devices learn about it
// instead of resurrecting the entry (see pullAll's pushback).
async function deleteDay(key) {
  if (!fbUser) return;
  try { await col().doc(key).delete(); } catch (err) { console.warn('delete failed', err); }
}

/* ─── Soft delete / trash ─────────────────────────────────────────
   "Delete" marks an entry rather than removing it, so an accidental
   delete — the worst kind of journaling accident — can be undone: right
   away from the toast, or later from Settings → Recently deleted. Only
   purgeTrash() (run once per boot) does the real, permanent removal, and
   only once TRASH_DAYS has passed. The pure transforms live in core.js. ── */

function softDeleteEntry(key) {
  const e = DB.entries[key];
  if (!e || e.deleted) return;
  DB.entries[key] = applyTombstone(e, Date.now());
  saveLocal();
  pushDay(DB.entries[key]);
}

function restoreEntry(key) {
  const e = DB.entries[key];
  if (!e || !e.deleted) return;
  DB.entries[key] = restoreFromTombstone(e, Date.now());
  saveLocal();
  pushDay(DB.entries[key]);
}

function trashedEntries() {
  return Object.values(DB.entries)
    .filter(e => isKey(e.date) && e.deleted)
    .sort((a, b) => (b.deletedAt || 0) - (a.deletedAt || 0));
}

async function purgeTrash() {
  const now = Date.now();
  const gone = Object.keys(DB.entries).filter(k => isPurged(DB.entries[k], now));
  if (!gone.length) return;
  // Remove from Firestore FIRST. If a network delete fails, the local
  // tombstone survives and the purge is retried on the next boot; deleting
  // locally first would resurrect the entry on the next sync, because
  // pullAll() pushes back anything local that's missing remotely.
  for (const k of gone) await deleteDay(k);
  gone.forEach(k => delete DB.entries[k]);
  saveLocal();
}

async function pullAll() {
  if (!fbUser) return;
  setSync('syncing');
  try {
    const snap = await col().get();
    const remote = {};
    snap.forEach(d => {
      const clean = cleanIncoming(d.id, d.data());
      if (clean) remote[clean.date] = clean;
    });

    // Merge remote into local — newer updatedAt wins (mergeRemoteEntry in
    // core.js, so the rule is unit-tested).
    for (const key of Object.keys(remote)) {
      DB.entries[key] = mergeRemoteEntry(DB.entries[key], remote[key]);
    }
    saveLocal();

    // Push back anything local that's newer than, or missing from, the
    // remote copy. Without this, an offline edit — or any push that failed
    // and left syncState as 'offline' — sits on this device forever and
    // Firestore (and every other device) never learns about it. Tombstoned
    // entries push back too: that's how a delete propagates instead of the
    // entry resurrecting on the next sync.
    const pushes = [];
    for (const key of Object.keys(DB.entries)) {
      const local = DB.entries[key];
      if (needsPush(local, remote[key])) pushes.push(pushDay(local));
    }
    await Promise.all(pushes);

    setSync('synced');
  } catch (err) { console.warn('sync failed', err); setSync('offline'); }
}

/* ─── HTML sanitizing ───────────────────────────────────────────
   Imported backups — and, defense-in-depth, anything read back from
   Firestore — pass through here before reaching innerHTML. Allow-list
   only the markup the writer itself produces; dangerous elements are
   dropped with their contents, unknown ones are unwrapped so their
   text survives. Attributes never survive. ─────────────────────── */
const SANITIZE_ALLOW = { P: 1, H2: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, BR: 1 };
const SANITIZE_DROP = { SCRIPT: 1, STYLE: 1, IFRAME: 1, OBJECT: 1, EMBED: 1, LINK: 1, META: 1, SVG: 1, MATH: 1, FORM: 1, INPUT: 1, BUTTON: 1, SELECT: 1, TEXTAREA: 1, VIDEO: 1, AUDIO: 1, IMG: 1 };

function sanitizeHtml(html) {
  if (!html) return '';
  let parsed;
  try {
    parsed = new DOMParser().parseFromString('<div>' + html + '</div>', 'text/html');
  } catch { return ''; }
  const root = parsed.body.firstElementChild;
  if (!root) return '';
  const walk = el => {
    for (const child of Array.from(el.children)) {
      const tag = child.tagName;
      if (SANITIZE_DROP[tag]) { child.remove(); continue; }
      // The allow-listed markup needs no attributes; none survive.
      Array.from(child.attributes).forEach(a => child.removeAttribute(a.name));
      walk(child);               // sanitize descendants BEFORE unwrapping
      if (!SANITIZE_ALLOW[tag]) {
        while (child.firstChild) el.insertBefore(child.firstChild, child);
        child.remove();
      }
    }
  };
  walk(root);
  return root.innerHTML;
}

// normalize() from core.js, plus the HTML pass. e.plain is always
// inserted through esc() in templates, so sanitizing the html field
// is sufficient.
function cleanIncoming(key, raw) {
  const e = normalize(key, raw);
  if (e) e.html = sanitizeHtml(e.html);
  return e;
}

/* ─── Utils (DOM-only; pure ones live in core.js) ─────────────── */

const $ = id => document.getElementById(id);

function toast(msg, action) {
  const t = $('toast');
  t.innerHTML = esc(msg) + (action
    ? ` <button type="button" class="toast-action" id="toast-action">${esc(action.label)}</button>`
    : '');
  t.classList.add('show');
  clearTimeout(toast._t);
  if (action) {
    const btn = $('toast-action');
    if (btn) btn.onclick = () => {
      t.classList.remove('show');
      clearTimeout(toast._t);
      action.onClick();
    };
  }
  toast._t = setTimeout(() => t.classList.remove('show'), action ? 5000 : 1900);
}

/* ─── Moods ───────────────────────────────────────────────────── */

// Every mood colour goes through here so the dark steps are never forgotten.
function moodColor(v) {
  const m = mood(v);
  if (!m) return 'currentColor';
  return document.body.classList.contains('dark') ? m.d : m.c;
}

/* ─── Storage ─────────────────────────────────────────────────── */

const LS = { theme: 'jr3_theme', prefs: 'jr3_prefs', pin: 'jr3_pin' };
const DB = { entries: {}, prefs: { hidePrompt: false } };

// Entry storage is namespaced per account (jr3_entries_<uid>). Without
// this, a sign-out whose reload got interrupted would leave the previous
// account's entries in a shared key for the next account on this browser.
const entriesKey = () => fbUser ? 'jr3_entries_' + fbUser.uid : 'jr3_entries';

function loadEntries() {
  const key = entriesKey();
  // One-time migration: entries written before per-account namespacing live
  // under the shared 'jr3_entries' key. Adopt them into this account's
  // namespaced key the first time that key is empty, then drop the old copy.
  if (fbUser) {
    const legacy = localStorage.getItem('jr3_entries');
    if (legacy && !localStorage.getItem(key)) {
      localStorage.setItem(key, legacy);
    }
    if (legacy) localStorage.removeItem('jr3_entries');
  }
  let raw = {};
  try { raw = JSON.parse(localStorage.getItem(key) || '{}'); } catch { raw = {}; }
  DB.entries = {};
  Object.keys(raw).forEach(k => {
    const clean = cleanIncoming(k, raw[k]);
    if (clean) DB.entries[clean.date] = clean;
  });
}
function loadPrefs() {
  try {
    const p = JSON.parse(localStorage.getItem(LS.prefs) || 'null');
    if (p) DB.prefs = Object.assign(DB.prefs, p);
  } catch {}
}
function loadLocal() { loadEntries(); loadPrefs(); }

function saveLocal() {
  try {
    localStorage.setItem(entriesKey(), JSON.stringify(DB.entries));
  } catch (err) {
    console.warn('local save failed', err);
    toast('Could not save on this device — storage may be full. Try removing a photo.');
  }
}
function savePrefs() { localStorage.setItem(LS.prefs, JSON.stringify(DB.prefs)); }

// Retire storage from earlier builds so nothing stale leaks through.
// ('jr3_entries' is deliberately NOT here — loadEntries() migrates it into
// the per-account namespaced key after sign-in.)
function retireLegacyKeys() {
  ['jr_entries', 'jr2_entries', 'jr2_journals', 'jr2_migrated', 'jr2_prefs', 'jr2_theme']
    .forEach(k => localStorage.removeItem(k));
}

/* ─── PIN lock ────────────────────────────────────────────────────
   A device-level lock screen, like a phone's PIN — not encryption.
   Entries stay wherever they already lived (localStorage / Firestore);
   the PIN just gates the UI. Only a salted hash is ever stored. ─── */

async function sha256Hex(str) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(str));
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
}
function randomHex(n) {
  const arr = new Uint8Array(n);
  crypto.getRandomValues(arr);
  return Array.from(arr).map(b => b.toString(16).padStart(2, '0')).join('');
}
// The PIN record lives on the account in Firestore, so the lock follows you to
// any browser or device you sign in from. localStorage is only an offline
// cache of that record — it is never the source of truth.
const secDoc = () => fbStore.collection('users').doc(fbUser.uid).collection('settings').doc('security');

function getPinRecord() {
  try { return JSON.parse(localStorage.getItem(LS.pin) || 'null'); } catch { return null; }
}
function cachePinRecord(rec) {
  if (rec) localStorage.setItem(LS.pin, JSON.stringify(rec));
  else localStorage.removeItem(LS.pin);
}
function hasPinLock() { return !!getPinRecord(); }

// Called once after sign-in, before the app is revealed. Returns true when the
// account's lock state is known for certain, false when we're working from cache.
async function pullPinRecord() {
  if (!fbUser) return false;
  try {
    const snap = await secDoc().get();
    if (snap.exists) {
      const d = snap.data() || {};
      // An explicit tombstone means the lock was deliberately turned off on
      // some device. Without it, a stale local cache would look identical to
      // "never synced" and this device would resurrect the old PIN.
      if (d.disabled) { cachePinRecord(null); return true; }
      if (d.salt && d.hash) { cachePinRecord({ salt: d.salt, hash: d.hash, length: d.length || 4 }); return true; }
    }
    // No record at all: either a new account, or a device-only PIN left by the
    // original build. Migrate that one up so the lock starts applying everywhere.
    const local = getPinRecord();
    if (local) { await secDoc().set(local); return true; }
    cachePinRecord(null);
    return true;
  } catch (err) {
    console.warn('could not read lock state', err);
    return false;   // offline: fall back to whatever this device has cached
  }
}

async function setPin(pin) {
  const salt = randomHex(16);
  const hash = await sha256Hex(salt + ':' + pin);
  const rec = { salt, hash, length: pin.length };
  await secDoc().set(rec);   // must reach the account, or the lock won't follow you
  cachePinRecord(rec);
}
async function verifyPinValue(pin) {
  const rec = getPinRecord();
  if (!rec) return true;
  return (await sha256Hex(rec.salt + ':' + pin)) === rec.hash;
}
async function clearPinLock() {
  // Tombstone rather than delete — see pullPinRecord.
  try { await secDoc().set({ disabled: true, updatedAt: Date.now() }); }
  catch (err) { console.warn('could not clear lock', err); }
  cachePinRecord(null);
}

let pinCtrl = null;

function renderPinDots(filled, total) {
  const n = total || Math.max(filled, 1);
  $('pin-dots').innerHTML = Array.from({ length: n })
    .map((_, i) => `<span class="pin-dot${i < filled ? ' filled' : ''}"></span>`).join('');
  // The dots themselves carry no text, so a visually-hidden live region is
  // what actually gets announced to screen readers as digits are entered.
  const progress = $('pin-progress');
  if (progress) progress.textContent = filled + ' of ' + n + ' digits entered';
}
function updatePinContinue() {
  const btn = $('pin-continue');
  if (btn.style.display !== 'none') btn.disabled = !(pinCtrl && pinCtrl.buffer.length >= 4);
}
function openPinGate(cfg) {
  pinCtrl = Object.assign({ buffer: '' }, cfg);
  $('pin-title').textContent = cfg.title || 'Enter PIN';
  $('pin-sub').textContent = cfg.sub || '';
  $('pin-error').textContent = '';
  $('pin-continue').style.display = cfg.requiredLen ? 'none' : 'inline-flex';
  $('pin-forgot').style.display = cfg.showForgot ? 'inline-flex' : 'none';
  $('pin-cancel').style.visibility = cfg.showCancel ? 'visible' : 'hidden';
  renderPinDots(0, cfg.requiredLen || null);
  updatePinContinue();
  $('pin-gate').classList.add('show');
}
function closePinGate() {
  $('pin-gate').classList.remove('show');
  pinCtrl = null;
}
function pinPressDigit(d) {
  if (!pinCtrl) return;
  const max = pinCtrl.requiredLen || 8;
  if (pinCtrl.buffer.length >= max) return;
  pinCtrl.buffer += d;
  renderPinDots(pinCtrl.buffer.length, pinCtrl.requiredLen || null);
  updatePinContinue();
  if (pinCtrl.requiredLen && pinCtrl.buffer.length === pinCtrl.requiredLen) submitPin();
}
function pinBackspace() {
  if (!pinCtrl || !pinCtrl.buffer.length) return;
  pinCtrl.buffer = pinCtrl.buffer.slice(0, -1);
  renderPinDots(pinCtrl.buffer.length, pinCtrl.requiredLen || null);
  updatePinContinue();
  $('pin-error').textContent = '';
}
async function submitPin() {
  if (!pinCtrl || !pinCtrl.buffer.length) return;
  const cfg = pinCtrl;
  const pin = cfg.buffer;
  $('pin-error').textContent = '';
  const result = await cfg.onSubmit(pin);
  if (result && result.ok === false) {
    $('pin-error').textContent = result.error || 'Incorrect PIN';
    const dots = $('pin-dots');
    dots.classList.remove('shake'); void dots.offsetWidth; dots.classList.add('shake');
    if (pinCtrl === cfg) {
      pinCtrl.buffer = '';
      renderPinDots(0, cfg.requiredLen || null);
      updatePinContinue();
    }
  }
}
function wirePinPad() {
  document.querySelectorAll('#pin-pad [data-k]').forEach(b => { b.onclick = () => pinPressDigit(b.dataset.k); });
  $('pin-back').onclick = pinBackspace;
  $('pin-continue').onclick = submitPin;
  $('pin-cancel').onclick = () => { const c = pinCtrl; closePinGate(); if (c && c.onCancel) c.onCancel(); };
  $('pin-forgot').onclick = () => { if (pinCtrl && pinCtrl.onForgot) pinCtrl.onForgot(); };
  document.addEventListener('keydown', ev => {
    if (!$('pin-gate').classList.contains('show')) return;
    if (/^[0-9]$/.test(ev.key)) pinPressDigit(ev.key);
    else if (ev.key === 'Backspace') pinBackspace();
    else if (ev.key === 'Enter') submitPin();
  });
}

function unlockFlow() {
  return new Promise(resolve => {
    const rec = getPinRecord();
    if (!rec) return resolve(true);
    openPinGate({
      title: 'Enter PIN',
      sub: 'Unlock your journal',
      requiredLen: rec.length,
      showForgot: true,
      onForgot: async () => {
        if (!confirm("Forgot your PIN? This removes the PIN lock from your account, on every device — your entries are safe and untouched. You can set a new PIN afterward in Settings.")) return;
        await clearPinLock();
        closePinGate();
        toast('PIN lock removed');
        resolve(true);
      },
      onSubmit: async pin => {
        if (await verifyPinValue(pin)) { closePinGate(); resolve(true); return { ok: true }; }
        return { ok: false, error: 'Incorrect PIN' };
      },
    });
  });
}
function createPinFlow() {
  return new Promise(resolve => {
    openPinGate({
      title: 'Create a PIN',
      sub: '4–8 digits',
      showCancel: true,
      onCancel: () => resolve(false),
      onSubmit: async pin => {
        if (pin.length < 4) return { ok: false, error: 'At least 4 digits' };
        const first = pin;
        openPinGate({
          title: 'Confirm PIN',
          sub: 'Enter it again',
          showCancel: true,
          onCancel: () => resolve(false),
          onSubmit: async pin2 => {
            if (pin2 !== first) return { ok: false, error: "PINs didn't match — try again" };
            // If this can't reach the account the lock would only exist on this
            // device — which is the bug we're fixing. Fail loudly instead.
            try { await setPin(pin2); }
            catch { return { ok: false, error: "Couldn't save to your account — check your connection" }; }
            closePinGate();
            toast('PIN lock on, across your devices');
            resolve(true);
          },
        });
        return { ok: true };
      },
    });
  });
}
function changePinFlow() {
  return new Promise(resolve => {
    const rec = getPinRecord();
    if (!rec) return createPinFlow().then(resolve);
    openPinGate({
      title: 'Current PIN',
      sub: 'Enter your current PIN',
      requiredLen: rec.length,
      showCancel: true,
      onCancel: () => resolve(false),
      onSubmit: async pin => {
        if (!(await verifyPinValue(pin))) return { ok: false, error: 'Incorrect PIN' };
        createPinFlow().then(resolve);
        return { ok: true };
      },
    });
  });
}
function disablePinFlow() {
  return new Promise(resolve => {
    const rec = getPinRecord();
    if (!rec) return resolve(true);
    openPinGate({
      title: 'Enter PIN',
      sub: 'Confirm to turn off PIN lock',
      requiredLen: rec.length,
      showCancel: true,
      onCancel: () => resolve(false),
      onSubmit: async pin => {
        if (!(await verifyPinValue(pin))) return { ok: false, error: 'Incorrect PIN' };
        await clearPinLock();
        closePinGate();
        toast('PIN lock turned off');
        resolve(true);
      },
    });
  });
}
async function lockAppNow() {
  if (!hasPinLock() || $('pin-gate').classList.contains('show')) return;
  $('app').classList.remove('show');
  await unlockFlow();
  $('app').classList.add('show');
  render();
}

const allEntries = () => Object.values(DB.entries).filter(e => isKey(e.date) && !e.deleted);
const sortedDesc = list => list.slice().sort((a, b) => a.date < b.date ? 1 : -1);

function allTags() {
  const c = {};
  allEntries().forEach(e => (e.tags || []).forEach(t => { c[t] = (c[t] || 0) + 1; }));
  return Object.keys(c).sort((a, b) => c[b] - c[a]).map(t => ({ tag: t, n: c[t] }));
}

const streaks = () => computeStreaks(allEntries().map(e => e.date), todayKey());

/* ─── State ───────────────────────────────────────────────────── */

const state = {
  view: 'write',
  date: todayKey(),   // the day being written
  draft: null,        // working copy for that day
  search: '',
  tagFilter: null,
  favFilter: false,
  calMonth: new Date().getMonth(),
  calYear: new Date().getFullYear(),
};

/* ─── Render dispatch ─────────────────────────────────────────── */

const content = () => $('content');

function render() {
  document.querySelectorAll('.tab, .bn-item').forEach(b =>
    b.classList.toggle('active', b.dataset.view === state.view));
  $('streak-count').textContent = streaks().current;

  if (state.view === 'write')    renderWrite();
  if (state.view === 'entries')  renderEntries();
  if (state.view === 'calendar') renderCalendar();
  if (state.view === 'insights') renderInsights();
  if (state.view === 'settings') renderSettings();
}

function go(view) {
  if (state.view === 'write' && view !== 'write') commitDraft();
  state.view = view;
  window.scrollTo(0, 0);
  render();
}

// Open a specific day in the writing view
function openDay(key) {
  if (!isKey(key)) return;
  if (state.view === 'write') commitDraft();
  state.date = key;
  state.view = 'write';
  window.scrollTo(0, 0);
  render();
}

// The stored entry for this day, ignoring tombstones: opening a trashed
// day shows a blank writer, and writing into it restores the entry.
function liveEntry(key) {
  const e = DB.entries[key];
  return e && !e.deleted ? e : null;
}

/* ─── Write view ──────────────────────────────────────────────── */

let saveTimer = null;

function renderWrite() {
  const key = state.date;
  const existing = liveEntry(key);
  state.draft = existing ? JSON.parse(JSON.stringify(existing)) : blankEntry(key);
  const e = state.draft;

  const isToday  = key === todayKey();
  const isFuture = daysBetween(todayKey(), key) < 0;
  const showPrompt = !DB.prefs.hidePrompt && !e.plain && !e.title;

  content().innerHTML = `
    <div class="day-nav">
      <button class="icon-btn" id="day-prev" aria-label="Previous day">
        <svg viewBox="0 0 24 24" class="ic"><path d="M15 18l-6-6 6-6"/></svg>
      </button>
      <button class="day-current" id="day-pick">
        <span class="day-main">${esc(fmt.full(key))}</span>
        <span class="day-sub">${esc(fmt.rel(key))}${existing ? ' · saved' : ''}</span>
      </button>
      <button class="icon-btn" id="day-next" aria-label="Next day" ${isToday ? 'disabled' : ''}>
        <svg viewBox="0 0 24 24" class="ic"><path d="M9 18l6-6-6-6"/></svg>
      </button>
    </div>

    ${!isToday ? `<button class="jump-today" id="jump-today">
      <svg viewBox="0 0 24 24" class="ic"><path d="M3 12h18M12 3v18"/></svg> Back to today
    </button>` : ''}

    ${isFuture ? `<div class="notice">You're looking at a future date. Entries are meant for days that have happened.</div>` : `

    <div class="card writer">
      ${showPrompt ? `<div class="prompt-strip" id="prompt-strip">
        <svg viewBox="0 0 24 24" class="ic"><path d="M12 3a6 6 0 0 0-4 10.5c.6.6 1 1.3 1 2.1V17h6v-1.4c0-.8.4-1.5 1-2.1A6 6 0 0 0 12 3z"/><path d="M10 21h4"/></svg>
        <span id="prompt-text">${esc(promptFor(key))}</span>
        <button class="icon-btn xs" id="prompt-hide" aria-label="Hide prompt">
          <svg viewBox="0 0 24 24" class="ic"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>` : ''}

      <div class="mood-strip" id="mood-strip"></div>

      <input type="text" id="w-title" class="w-title" placeholder="Title (optional)" value="${esc(e.title)}" />

      <div class="format-bar" id="format-bar">
        <button data-cmd="bold" title="Bold"><b>B</b></button>
        <button data-cmd="italic" title="Italic"><i>I</i></button>
        <button data-cmd="underline" title="Underline"><u>U</u></button>
        <span class="fb-div"></span>
        <button data-block="h2" title="Heading">H</button>
        <button data-cmd="insertUnorderedList" title="List">
          <svg viewBox="0 0 24 24" class="ic"><path d="M8 6h13M8 12h13M8 18h13"/><circle cx="3.5" cy="6" r="1.2" fill="currentColor" stroke="none"/><circle cx="3.5" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="3.5" cy="18" r="1.2" fill="currentColor" stroke="none"/></svg>
        </button>
        <button data-block="blockquote" title="Quote">
          <svg viewBox="0 0 24 24" class="ic"><path d="M6 17h3l2-4V7H5v6h3zM15 17h3l2-4V7h-6v6h3z"/></svg>
        </button>
        <span class="fb-div"></span>
        <button id="photo-btn" title="Add photos">
          <svg viewBox="0 0 24 24" class="ic"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>
        </button>
        <input type="file" id="photo-input" accept="image/*" multiple hidden />
        <span class="save-hint" id="save-hint"></span>
      </div>

      <div id="w-body" class="w-body" contenteditable="true" role="textbox" aria-multiline="true" aria-label="Journal entry text" data-placeholder="Start writing…">${e.html || ''}</div>

      <div class="photo-grid" id="photo-grid"></div>

      <div class="writer-foot">
        <div class="tag-list" id="w-tags"></div>
        <input type="text" id="tag-input" class="tag-input" placeholder="Add tag…" list="tag-suggest" />
        <datalist id="tag-suggest"></datalist>
        <div class="foot-right">
          <span class="word-count" id="word-count">0 words</span>
          <button class="icon-btn ${e.favorite ? 'on' : ''}" id="w-star" aria-label="Favorite">
            <svg viewBox="0 0 24 24" class="ic"><path d="M12 17.75l-6.17 3.24 1.18-6.87-5-4.86 6.9-1L12 2l3.09 6.26 6.9 1-5 4.86 1.18 6.87z"/></svg>
          </button>
          ${existing ? `<button class="icon-btn danger" id="w-delete" aria-label="Delete entry">
            <svg viewBox="0 0 24 24" class="ic"><path d="M3 6h18"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4h6v2"/></svg>
          </button>` : ''}
        </div>
      </div>
    </div>

    ${onThisDay(key)}
    `}`;

  if (isFuture) { wireDayNav(); return; }

  renderMoods();
  renderTags();
  renderPhotos();
  updateWords();
  $('tag-suggest').innerHTML = allTags().map(t => `<option value="${esc(t.tag)}">`).join('');

  wireDayNav();
  wireWriter();
}

function onThisDay(key) {
  if (!isKey(key)) return '';
  const d = parseKey(key);
  const hits = [];
  for (let y = 1; y <= 6; y++) {
    const k = dkey(new Date(d.getFullYear() - y, d.getMonth(), d.getDate()));
    const e = liveEntry(k);
    if (e) hits.push({ k, e, y });
  }
  if (!hits.length) return '';
  return `<div class="card">
    <div class="card-title">On this day</div>
    ${hits.map(h => `<div class="otd" data-open="${h.k}" role="button" tabindex="0" aria-label="${h.y} year${h.y > 1 ? 's' : ''} ago, ${esc(h.e.title || h.e.plain || 'no text')}">
      <div class="otd-head">
        ${h.e.mood ? moodSvg(h.e.mood, 15) : ''}
        <span>${h.y} year${h.y > 1 ? 's' : ''} ago</span>
      </div>
      <div class="otd-text">${esc(h.e.title || h.e.plain || 'No text')}</div>
    </div>`).join('')}
  </div>`;
}

function wireDayNav() {
  $('day-prev').onclick = () => openDay(shiftKey(state.date, -1));
  const next = $('day-next');
  if (next && !next.disabled) next.onclick = () => openDay(shiftKey(state.date, 1));
  const jt = $('jump-today');
  if (jt) jt.onclick = () => openDay(todayKey());
  $('day-pick').onclick = openDatePicker;
  wireOpenTargets();
}

function wireOpenTargets() {
  document.querySelectorAll('[data-open]').forEach(b => {
    const open = () => openDay(b.dataset.open);
    b.onclick = open;
    b.onkeydown = ev => {
      if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); }
    };
  });
}

function openDatePicker() {
  $('modal-title').textContent = 'Jump to a date';
  $('modal-body').innerHTML = `
    <div class="field">
      <label>Date</label>
      <input type="date" class="input" id="d-pick" value="${state.date}" max="${todayKey()}">
    </div>
    <div class="quick-dates">
      <button data-jump="0">Today</button>
      <button data-jump="-1">Yesterday</button>
      <button data-jump="-7">A week ago</button>
    </div>
    <div style="display:flex;gap:8px;margin-top:16px">
      <button class="btn-primary" id="d-ok" style="flex:1">Go</button>
      <button class="btn-secondary" id="d-cancel">Cancel</button>
    </div>`;
  openModal();
  $('d-cancel').onclick = closeModal;
  document.querySelectorAll('[data-jump]').forEach(b => {
    b.onclick = () => { closeModal(); openDay(shiftKey(todayKey(), Number(b.dataset.jump))); };
  });
  $('d-ok').onclick = () => {
    const v = $('d-pick').value;
    closeModal();
    if (isKey(v)) openDay(v);
    else toast('Pick a valid date');
  };
}

/* ─── Writer pieces ───────────────────────────────────────────── */

function renderMoods() {
  const e = state.draft;
  $('mood-strip').innerHTML = MOODS.map(m => `
    <button class="mood-btn${e.mood === m.v ? ' on' : ''}" data-m="${m.v}" aria-pressed="${e.mood === m.v}" style="${e.mood === m.v ? 'color:' + moodColor(m.v) : ''}">
      ${moodSvg(m.v, 23)}<span>${m.l}</span>
    </button>`).join('');
  document.querySelectorAll('#mood-strip .mood-btn').forEach(b => {
    b.onclick = () => {
      const v = Number(b.dataset.m);
      e.mood = e.mood === v ? null : v;
      renderMoods();
      scheduleSave();
    };
  });
}

function renderTags() {
  const e = state.draft;
  $('w-tags').innerHTML = (e.tags || []).map(t =>
    `<span class="tag-pill">#${esc(t)}<button data-rm="${esc(t)}" aria-label="Remove">✕</button></span>`).join('');
  document.querySelectorAll('#w-tags [data-rm]').forEach(b => {
    b.onclick = () => { e.tags = e.tags.filter(t => t !== b.dataset.rm); renderTags(); scheduleSave(); };
  });
}

function renderPhotos() {
  const e = state.draft;
  $('photo-grid').innerHTML = (e.photos || []).map((p, i) => `
    <div class="photo-item">
      <img src="${esc(p)}" data-lb="${i}" alt="">
      <button class="photo-del" data-rmp="${i}" aria-label="Remove photo">
        <svg viewBox="0 0 24 24" class="ic"><path d="M18 6L6 18M6 6l12 12"/></svg>
      </button>
    </div>`).join('');
  document.querySelectorAll('#photo-grid [data-rmp]').forEach(b => {
    b.onclick = () => { e.photos.splice(Number(b.dataset.rmp), 1); renderPhotos(); scheduleSave(); };
  });
  document.querySelectorAll('#photo-grid [data-lb]').forEach(img => {
    img.onclick = () => { $('lightbox-img').src = img.src; $('lightbox').classList.add('open'); };
  });
}

function updateWords() {
  const n = countWords(htmlToText($('w-body').innerHTML));
  $('word-count').textContent = n + (n === 1 ? ' word' : ' words');
}

function readDraft() {
  const e = state.draft;
  if (!e) return;
  const titleEl = $('w-title'), bodyEl = $('w-body');
  if (!titleEl || !bodyEl) return;
  e.title = titleEl.value.trim();
  e.html  = bodyEl.innerHTML;
  e.plain = htmlToText(e.html);
  e.words = countWords(e.plain);
  e.updatedAt = Date.now();
}

// opts.localOnly: skip the Firestore push. Used on page unload, where an
// async write is a lost race anyway — localStorage is written synchronously
// by saveLocal(), and the next boot's pullAll() pushback carries it up.
// Returns {deleted:true} when it moved a stored entry to the trash, or the
// pushDay promise for the normal save path (so callers can await the push).
function commitDraft(opts) {
  const e = state.draft;
  if (!e || !isKey(e.date)) return null;
  readDraft();
  const stored = DB.entries[e.date];
  const had = !!(stored && !stored.deleted);

  if (isEmpty(e)) {
    if (had) {
      // Clearing an existing entry is almost always an accident (a stray
      // select-all + delete), which is exactly what the trash exists for:
      // tombstone it — undoable from the toast, restorable for 30 days —
      // and never hard-delete here. Only purgeTrash() removes documents.
      clearTimeout(saveTimer);
      DB.entries[e.date] = applyTombstone(stored, Date.now());
      saveLocal();
      const push = (opts && opts.localOnly) ? null : pushDay(DB.entries[e.date]);
      state.draft = blankEntry(e.date);
      toast('Entry moved to trash', { label: 'Undo', onClick: () => {
        restoreEntry(e.date);
        if (state.date === e.date) {
          state.draft = JSON.parse(JSON.stringify(DB.entries[e.date]));
        }
        render();
      } });
      return push ? push.then(() => ({ deleted: true })) : { deleted: true };
    }
    return null;
  }
  const finished = JSON.parse(JSON.stringify(e));
  delete finished.deleted;      // writing into a trashed day restores it
  delete finished.deletedAt;
  DB.entries[e.date] = finished;
  saveLocal();
  if (opts && opts.localOnly) return null;
  return pushDay(finished);
}

function scheduleSave() {
  clearTimeout(saveTimer);
  const hint = $('save-hint');
  if (hint) hint.textContent = 'Saving…';
  saveTimer = setTimeout(() => {
    const result = commitDraft();
    // The hint must reflect the FIRESTORE result, not just the local write:
    // commitDraft returns the pushDay promise, so by the time it settles
    // syncState says truthfully whether the push made it.
    const done = () => {
      const h = $('save-hint');
      if (h) {
        h.textContent = syncState === 'offline' ? 'Saved on this device' : 'Saved';
        setTimeout(() => { if ($('save-hint')) $('save-hint').textContent = ''; }, 1300);
      }
      $('streak-count').textContent = streaks().current;
    };
    if (result && typeof result.then === 'function') result.then(done, done);
    else if (!result || !result.deleted) done();
    else $('streak-count').textContent = streaks().current;
  }, 900);
}

function wireWriter() {
  const body = $('w-body');

  $('w-title').addEventListener('input', scheduleSave);
  body.addEventListener('input', () => { updateWords(); scheduleSave(); });
  body.addEventListener('keyup', syncFormatBar);
  body.addEventListener('mouseup', syncFormatBar);
  body.addEventListener('paste', ev => {
    const cd = ev.clipboardData || window.clipboardData;
    let files = cd && cd.files ? Array.from(cd.files) : [];
    if (!files.length && cd && cd.items) {
      files = Array.from(cd.items)
        .filter(it => it.kind === 'file')
        .map(it => it.getAsFile())
        .filter(Boolean);
    }
    files = files.filter(f => f && f.type && f.type.startsWith('image/'));
    if (files.length) {
      // A pasted screenshot used to vanish silently — text/plain was the
      // only thing this handler ever read. Route it through the same photo
      // pipeline as the photo button rather than trying to inline it into
      // the contenteditable body, which the rest of the app doesn't expect.
      ev.preventDefault();
      addPhotos(files);
      return;
    }
    ev.preventDefault();
    const text = cd.getData('text/plain');
    document.execCommand('insertText', false, text);
  });

  document.querySelectorAll('#format-bar [data-cmd]').forEach(b => {
    b.onmousedown = ev => ev.preventDefault();
    b.onclick = () => { document.execCommand(b.dataset.cmd, false, null); body.focus(); syncFormatBar(); scheduleSave(); };
  });
  document.querySelectorAll('#format-bar [data-block]').forEach(b => {
    b.onmousedown = ev => ev.preventDefault();
    b.onclick = () => {
      const tag = b.dataset.block;
      let cur = '';
      try { cur = (document.queryCommandValue('formatBlock') || '').toLowerCase(); } catch {}
      document.execCommand('formatBlock', false, cur === tag ? 'p' : tag);
      body.focus(); scheduleSave();
    };
  });

  $('photo-btn').onclick = () => $('photo-input').click();
  $('photo-input').onchange = ev => { addPhotos(ev.target.files); ev.target.value = ''; };

  $('w-star').onclick = () => {
    state.draft.favorite = !state.draft.favorite;
    $('w-star').classList.toggle('on', state.draft.favorite);
    scheduleSave();
  };

  const del = $('w-delete');
  if (del) del.onclick = () => {
    if (!confirm('Delete this entry?')) return;
    clearTimeout(saveTimer);
    const key = state.date;
    softDeleteEntry(key);
    state.draft = blankEntry(key);
    toast('Entry deleted', { label: 'Undo', onClick: () => {
      restoreEntry(key);
      if (state.date === key) {
        state.draft = JSON.parse(JSON.stringify(DB.entries[key]));
      }
      render();
    } });
    render();
  };

  const ph = $('prompt-hide');
  if (ph) ph.onclick = () => {
    DB.prefs.hidePrompt = true; savePrefs();
    const s = $('prompt-strip'); if (s) s.remove();
  };

  const ti = $('tag-input');
  ti.addEventListener('keydown', ev => {
    if (ev.key === 'Enter' || ev.key === ',') {
      ev.preventDefault();
      const v = ti.value.trim().replace(/^#/, '').slice(0, 24);
      if (v && !state.draft.tags.includes(v)) { state.draft.tags.push(v); renderTags(); scheduleSave(); }
      ti.value = '';
    } else if (ev.key === 'Backspace' && !ti.value && state.draft.tags.length) {
      state.draft.tags.pop(); renderTags(); scheduleSave();
    }
  });
}

function syncFormatBar() {
  document.querySelectorAll('#format-bar [data-cmd]').forEach(b => {
    let on = false;
    try { on = document.queryCommandState(b.dataset.cmd); } catch {}
    b.classList.toggle('on', on);
  });
}

function compressImage(file) {
  const looksHeic = /image\/hei[cf]/i.test(file.type) || /\.(heic|heif)$/i.test(file.name || '');

  const sizedTo = (naturalW, naturalH) => {
    const max = 1400;
    let w = naturalW, h = naturalH;
    if (w > max || h > max) {
      if (w > h) { h = Math.round(h * max / w); w = max; }
      else { w = Math.round(w * max / h); h = max; }
    }
    return [w, h];
  };
  const draw = (source, w, h) => {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    c.getContext('2d').drawImage(source, 0, 0, w, h);
    let q = 0.8, out = c.toDataURL('image/jpeg', q);
    while (out.length > 320000 && q > 0.28) { q -= 0.1; out = c.toDataURL('image/jpeg', q); }
    return out;
  };

  return new Promise((resolve, reject) => {
    const fail = () => reject(new Error(looksHeic ? 'heic' : 'decode'));

    function legacyDecode() {
      const r = new FileReader();
      r.onload = ev => {
        const img = new Image();
        img.onload = () => { const [w, h] = sizedTo(img.width, img.height); resolve(draw(img, w, h)); };
        img.onerror = fail;
        img.src = ev.target.result;
      };
      r.onerror = fail;
      r.readAsDataURL(file);
    }

    // createImageBitmap with imageOrientation:'from-image' rotates/flips the
    // decoded pixels to match the file's EXIF orientation tag before
    // anything is drawn. The FileReader+Image path below doesn't apply EXIF
    // orientation on every browser, so a portrait phone photo could import
    // sideways — this is the fix, with that path kept only as a fallback for
    // browsers (Safari < 15) that don't support the option.
    if (window.createImageBitmap) {
      createImageBitmap(file, { imageOrientation: 'from-image' }).then(bmp => {
        const [w, h] = sizedTo(bmp.width, bmp.height);
        const out = draw(bmp, w, h);
        if (bmp.close) bmp.close();
        resolve(out);
      }).catch(legacyDecode);
    } else {
      legacyDecode();
    }
  });
}

/* ─── Photos: Firebase Storage ──────────────────────────────────
   A Firestore document caps at 1 MiB — ten ~320 KB base64 photos blow
   past that and pushes start failing. So each compressed photo is
   uploaded to Storage and the entry keeps only its download URL.
   Legacy entries still carrying base64 data URLs keep working (they
   render as-is); the limit now only bites on very old entries. ── */

async function uploadPhoto(date, dataUrl) {
  if (!fbUser || !(firebase.storage && firebase.storage())) throw new Error('storage unavailable');
  const name = randomHex(10) + '.jpg';
  const ref = firebase.storage().ref('users/' + fbUser.uid + '/journalDays/' + date + '/' + name);
  await ref.putString(dataUrl, 'data_url');
  return ref.getDownloadURL();
}

async function addPhotos(files) {
  const e = state.draft;
  if (!e) return;
  e.photos = e.photos || [];
  let keptInline = 0;
  for (const f of Array.from(files)) {
    if (e.photos.length >= 10) { toast('Up to 10 photos per entry'); break; }
    try {
      const dataUrl = await compressImage(f);
      let stored = dataUrl;
      try {
        stored = await uploadPhoto(e.date, dataUrl);
      } catch (err) {
        // Offline or a Storage hiccup: keep the compressed inline copy so the
        // photo isn't lost — but warn below, because an inline copy can push
        // the day past Firestore's 1 MiB document cap and stop that day syncing.
        console.warn('photo upload failed; keeping inline copy', err);
        keptInline++;
      }
      e.photos.push(stored);
    } catch (err) {
      toast(err && err.message === 'heic'
        ? "HEIC photos aren't supported yet — try converting to JPEG first"
        : 'Could not read that image');
    }
  }
  if (keptInline) toast("Couldn't upload " + (keptInline === 1 ? 'a photo' : keptInline + ' photos') + ' to cloud storage — kept inline; that day may not sync until the photo is removed');
  renderPhotos();
  scheduleSave();
}

/* ─── Entries list ────────────────────────────────────────────── */

function renderEntries() {
  const q = state.search.trim().toLowerCase();
  const anyFilter = state.tagFilter || state.favFilter || q;
  let list = sortedDesc(allEntries().filter(e => {
    if (state.tagFilter && !(e.tags || []).includes(state.tagFilter)) return false;
    if (state.favFilter && !e.favorite) return false;
    if (q) {
      const hay = ((e.title || '') + ' ' + (e.plain || '') + ' ' + moodLabel(e.mood) + ' ' + (e.tags || []).join(' ')).toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }));

  const tags = allTags().slice(0, 10);

  // Month headers make a long, plain-browse list scannable. Since `list` is
  // already newest-first and dates are unique, a header only needs to go up
  // whenever the month actually changes as we walk down it.
  let cardsHtml = '';
  let lastMonth = null;
  for (const e of list) {
    const label = parseKey(e.date).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
    if (label !== lastMonth) {
      cardsHtml += `<div class="month-head">${esc(label)}</div>`;
      lastMonth = label;
    }
    cardsHtml += entryCard(e);
  }

  content().innerHTML = `
    <div class="page-head">
      <div class="page-title">Entries</div>
      <div class="page-sub">${list.length} ${list.length === 1 ? 'entry' : 'entries'}${state.tagFilter ? ' tagged #' + esc(state.tagFilter) : ''}${state.favFilter ? ' · favorites' : ''}</div>
    </div>

    <div class="search-wrap">
      <svg viewBox="0 0 24 24" class="ic search-ic"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.1-4.1"/></svg>
      <input type="search" id="search-input" class="search-input" aria-label="Search your entries" placeholder="Search your entries…" value="${esc(state.search)}" />
      <button type="button" class="fav-filter-btn${state.favFilter ? ' on' : ''}" id="fav-filter" aria-pressed="${state.favFilter}" aria-label="Show favorites only" title="Show favorites only">
        <svg viewBox="0 0 24 24" class="ic"><path d="M12 17.75l-6.17 3.24 1.18-6.87-5-4.86 6.9-1L12 2l3.09 6.26 6.9 1-5 4.86 1.18 6.87z"/></svg>
      </button>
    </div>

    ${tags.length ? `<div class="tag-row">
      ${tags.map(t => `<button class="tag-chip${state.tagFilter === t.tag ? ' on' : ''}" data-tag="${esc(t.tag)}">#${esc(t.tag)} <span>${t.n}</span></button>`).join('')}
    </div>` : ''}

    ${list.length ? cardsHtml : `
      <div class="empty">
        <div class="empty-ic"><svg viewBox="0 0 24 24" class="ic" style="width:30px;height:30px"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg></div>
        <h3>${anyFilter ? 'Nothing matches' : 'No entries yet'}</h3>
        <p>${anyFilter ? 'Try a different search or clear the filter.' : 'Head to Write and put down a line about today.'}</p>
        ${anyFilter ? '' : '<button class="btn-primary" id="empty-write">Start writing</button>'}
      </div>`}`;

  const si = $('search-input');
  let t = null;
  si.addEventListener('input', () => {
    clearTimeout(t);
    t = setTimeout(() => { state.search = si.value; renderEntries(); si.focus(); }, 200);
  });

  document.querySelectorAll('[data-tag]').forEach(b => {
    b.onclick = () => { state.tagFilter = state.tagFilter === b.dataset.tag ? null : b.dataset.tag; renderEntries(); };
  });
  $('fav-filter').onclick = () => { state.favFilter = !state.favFilter; renderEntries(); };
  wireOpenTargets();
  const ew = $('empty-write');
  if (ew) ew.onclick = () => go('write');
}

function entryCard(e) {
  const m = mood(e.mood);
  const photos = e.photos || [];
  // A <div role="button"> rather than a <button> — buttons may only contain
  // phrasing content, and this card nests divs and images. wireOpenTargets()
  // gives it the same click/Enter/Space behaviour a real button would have.
  return `
    <div class="entry-card" data-open="${e.date}" role="button" tabindex="0" aria-label="${esc(fmt.full(e.date))}${e.title ? ', ' + esc(e.title) : ''}">
      <div class="ec-top">
        ${m ? moodSvg(m.v, 17) : ''}
        <span class="ec-date">${esc(fmt.med(e.date))}</span>
        <span class="ec-rel">${esc(fmt.rel(e.date))}</span>
        ${e.favorite ? '<span class="ec-star"><svg viewBox="0 0 24 24" class="ic"><path d="M12 17.75l-6.17 3.24 1.18-6.87-5-4.86 6.9-1L12 2l3.09 6.26 6.9 1-5 4.86 1.18 6.87z"/></svg></span>' : ''}
      </div>
      ${e.title ? `<div class="ec-title">${esc(e.title)}</div>` : ''}
      <div class="ec-preview">${e.plain ? esc(truncateWords(e.plain, 220)) : '<span style="color:var(--text-3)">No text</span>'}</div>
      ${photos.length ? `<div class="ec-thumbs">
        ${photos.slice(0, 3).map(p => `<img src="${esc(p)}" alt="">`).join('')}
        ${photos.length > 3 ? `<div class="ec-thumb-more">+${photos.length - 3}</div>` : ''}
      </div>` : ''}
      <div class="ec-foot">
        ${(e.tags || []).slice(0, 4).map(t => `<span class="ec-tag">#${esc(t)}</span>`).join('')}
        <span class="ec-meta">${e.words || 0} words</span>
      </div>
    </div>`;
}

/* ─── Calendar ────────────────────────────────────────────────── */

function renderCalendar() {
  const y = state.calYear, m = state.calMonth;
  const label = new Date(y, m, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const now = new Date();
  const isCur = m === now.getMonth() && y === now.getFullYear();
  const offset = new Date(y, m, 1).getDay();
  const days = new Date(y, m + 1, 0).getDate();

  let written = 0;
  for (let d = 1; d <= days; d++) {
    if (liveEntry(dkey(new Date(y, m, d)))) written++;
  }

  content().innerHTML = `
    <div class="page-head">
      <div class="page-title">Calendar</div>
      <div class="page-sub">${written} of ${days} days written · tap a day to open it</div>
    </div>
    <div class="card">
      <div class="month-nav">
        <button class="icon-btn" id="cal-prev" aria-label="Previous month"><svg viewBox="0 0 24 24" class="ic"><path d="M15 18l-6-6 6-6"/></svg></button>
        <span class="month-label">${label}</span>
        <button class="icon-btn" id="cal-next" aria-label="Next month" ${isCur ? 'disabled' : ''}><svg viewBox="0 0 24 24" class="ic"><path d="M9 18l6-6-6-6"/></svg></button>
      </div>
      <div class="cal-grid">
        ${['S','M','T','W','T','F','S'].map(d => `<div class="cal-hdr">${d}</div>`).join('')}
        ${Array.from({ length: offset }).map(() => '<div class="cal-cell blank"></div>').join('')}
        ${Array.from({ length: days }).map((_, i) => {
          const day = i + 1;
          const k = dkey(new Date(y, m, day));
          const e = liveEntry(k);
          const isToday = k === todayKey();
          const future = daysBetween(todayKey(), k) < 0;
          const mc = e && e.mood ? moodColor(e.mood) : null;
          return `<button class="cal-cell${e ? ' has' : ''}${isToday ? ' today' : ''}${future ? ' future' : ''}"
            data-open="${k}" ${future ? 'disabled' : ''}
            ${mc ? `style="background:${mc}24;border-color:${mc}66;color:var(--text-1)"` : ''}>
            <span>${day}</span>
            ${e ? (e.mood ? moodSvg(e.mood, 13, mc) : '<span class="cal-dot"></span>') : ''}
          </button>`;
        }).join('')}
      </div>
    </div>`;

  $('cal-prev').onclick = () => {
    if (state.calMonth === 0) { state.calMonth = 11; state.calYear--; } else state.calMonth--;
    renderCalendar();
  };
  const nx = $('cal-next');
  if (!isCur) nx.onclick = () => {
    if (state.calMonth === 11) { state.calMonth = 0; state.calYear++; } else state.calMonth++;
    renderCalendar();
  };
  document.querySelectorAll('.cal-cell[data-open]').forEach(b => {
    if (b.disabled) return;
    b.onclick = () => openDay(b.dataset.open);
  });
}

/* ─── Insights ────────────────────────────────────────────────── */

function renderInsights() {
  const list = allEntries();
  const { current, longest } = streaks();
  const words = list.reduce((s, e) => s + (e.words || 0), 0);

  const last30 = [];
  for (let i = 29; i >= 0; i--) last30.push(shiftKey(todayKey(), -i));
  const moodSeries = last30.map(k => {
    const e = liveEntry(k);
    return e ? e.mood : null;
  });
  const valid = moodSeries.filter(v => v != null);
  const avg = valid.length ? (valid.reduce((a, b) => a + b, 0) / valid.length).toFixed(1) : '—';

  // Trend: compare this 30-day window to the 30 days before it, so the
  // number means something instead of floating on its own.
  const prev30 = [];
  for (let i = 59; i >= 30; i--) prev30.push(shiftKey(todayKey(), -i));
  const prevValid = prev30.map(k => { const e = liveEntry(k); return e ? e.mood : null; }).filter(v => v != null);
  const prevAvg = prevValid.length ? prevValid.reduce((a, b) => a + b, 0) / prevValid.length : null;
  let trendHtml = '';
  if (valid.length && prevAvg != null) {
    const diff = parseFloat(avg) - prevAvg;
    trendHtml = Math.abs(diff) < 0.05
      ? '<div class="trend-note">steady vs prior 30d</div>'
      : `<div class="trend-note" style="color:${diff > 0 ? 'var(--green)' : 'var(--red)'}">${diff > 0 ? '+' : ''}${diff.toFixed(1)} vs prior 30d</div>`;
  }

  const dist = MOODS.map(m => ({ ...m, n: list.filter(e => e.mood === m.v).length }));
  const distMax = Math.max(1, ...dist.map(d => d.n));

  // Raw counts of "which days you write" are skewed by how many of each
  // weekday have actually occurred since the journal started (e.g. a
  // journal begun on a Monday racks up Mondays faster). Normalize to a
  // rate — the share of each weekday actually written on — for an
  // honest picture of the pattern.
  const firstKey = list.length ? list.reduce((min, e) => (e.date < min ? e.date : min), list[0].date) : todayKey();
  const occCount = [0, 0, 0, 0, 0, 0, 0];
  for (let k = firstKey; daysBetween(todayKey(), k) >= 0; k = shiftKey(k, 1)) occCount[parseKey(k).getDay()]++;
  const dow = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'].map((name, i) => {
    const n = list.filter(e => parseKey(e.date).getDay() === i).length;
    const occ = occCount[i] || 1;
    return { name, n, rate: n / occ };
  });
  const dowMax = Math.max(0.0001, ...dow.map(d => d.rate));

  const tags = allTags().slice(0, 8);
  const tagMax = Math.max(1, ...tags.map(t => t.n));

  content().innerHTML = `
    <div class="page-head">
      <div class="page-title">Insights</div>
      <div class="page-sub">Across ${list.length} ${list.length === 1 ? 'entry' : 'entries'}</div>
    </div>

    <div class="stat-grid">
      <div class="stat"><div class="stat-val" style="color:var(--accent)">${current}</div><div class="stat-lbl">Current streak</div></div>
      <div class="stat"><div class="stat-val">${longest}</div><div class="stat-lbl">Longest streak</div></div>
      <div class="stat"><div class="stat-val">${avg}</div><div class="stat-lbl">Avg mood (30d)</div>${trendHtml}</div>
      <div class="stat"><div class="stat-val">${list.length}</div><div class="stat-lbl">Days written</div></div>
      <div class="stat"><div class="stat-val">${words.toLocaleString()}</div><div class="stat-lbl">Total words</div></div>
      <div class="stat"><div class="stat-val">${list.length ? Math.round(words / list.length) : 0}</div><div class="stat-lbl">Words per entry</div></div>
    </div>

    <div class="card">
      <div class="card-title">Mood · last 30 days</div>
      ${valid.length ? '<canvas id="mood-chart"></canvas>'
        : '<p class="muted-note">Log a few moods and a trend line shows up here.</p>'}
    </div>

    <div class="card">
      <div class="card-title">How often each mood</div>
      ${dist.map(d => `<div class="bar-row">
        <span class="bar-lbl" style="display:flex;align-items:center;gap:7px">${moodSvg(d.v, 15)}${d.l}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${(d.n / distMax) * 100}%;background:${moodColor(d.v)}"></div></div>
        <span class="bar-num">${d.n}</span></div>`).join('')}
    </div>

    <div class="card">
      <div class="card-title">Which days you write</div>
      <p class="muted-note" style="margin-bottom:12px">Share of each weekday you've journaled on, since your first entry</p>
      ${dow.map(d => `<div class="bar-row">
        <span class="bar-lbl">${d.name}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${(d.rate / dowMax) * 100}%"></div></div>
        <span class="bar-num">${Math.round(d.rate * 100)}%</span></div>`).join('')}
    </div>

    ${tags.length ? `<div class="card">
      <div class="card-title">Most used tags</div>
      ${tags.map(t => `<div class="bar-row">
        <span class="bar-lbl">#${esc(t.tag)}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${(t.n / tagMax) * 100}%"></div></div>
        <span class="bar-num">${t.n}</span></div>`).join('')}
    </div>` : ''}

    <div class="card">
      <div class="card-title">Writing history</div>
      <div class="heat-wrap"><div class="heat-months" id="heatmap"></div></div>
      <div class="heat-legend"><span>No entry</span><span class="heat-cell" style="background:var(--surface-3)"></span>
        <span class="heat-cell" style="background:#6366F1"></span><span>Wrote</span></div>
    </div>`;

  if (valid.length) drawMoodChart(moodSeries, last30);
  buildHeatmap();
}

let moodChartVals = null;
let moodChartDates = null;

function drawMoodChart(vals, dates) {
  if (vals) moodChartVals = vals;
  if (dates) moodChartDates = dates;
  const series = moodChartVals;
  const canvas = $('mood-chart');
  if (!canvas || !series) return;

  // Measure the CSS box, then size the backing store in device pixels.
  // The CSS rule for #mood-chart keeps the displayed box at 100% width,
  // so a high-DPR screen can't stretch the element past its card.
  const w = canvas.clientWidth;
  const h = 150;
  if (!w) return;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);

  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const cs = getComputedStyle(document.body);
  const accent = (cs.getPropertyValue('--accent') || '#6366F1').trim();
  const grid   = (cs.getPropertyValue('--border-soft') || '#eee').trim();
  const label  = (cs.getPropertyValue('--text-3') || '#9B9CA8').trim();

  // Gutters: room on the left for mood labels, on the bottom for the date range.
  const padL = 44, padR = 12, padT = 12, padB = 22;
  const gw = Math.max(1, w - padL - padR);
  const gh = Math.max(1, h - padT - padB);
  const n = series.length;

  ctx.font = '600 10px ' + (cs.getPropertyValue('--font') || 'sans-serif').trim();
  ctx.textBaseline = 'middle';

  const rowLabel = { 5: 'Great', 3: 'Okay', 1: 'Rough' };
  ctx.lineWidth = 1;
  for (let i = 1; i <= 5; i++) {
    const y = padT + gh - ((i - 1) / 4) * gh;
    ctx.strokeStyle = grid;
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(padL + gw, y); ctx.stroke();
    if (rowLabel[i]) {
      ctx.fillStyle = label;
      ctx.textAlign = 'right';
      ctx.fillText(rowLabel[i], padL - 8, y);
    }
  }

  ctx.fillStyle = label;
  ctx.textAlign = 'left';
  ctx.fillText('30 days ago', padL, h - 6);
  ctx.textAlign = 'right';
  ctx.fillText('Today', padL + gw, h - 6);

  const pt = (v, i) => [padL + (n > 1 ? (i / (n - 1)) * gw : gw / 2), padT + gh - ((v - 1) / 4) * gh];

  ctx.beginPath();
  let started = false;
  series.forEach((v, i) => {
    if (v == null) { started = false; return; }
    const p = pt(v, i);
    if (!started) { ctx.moveTo(p[0], p[1]); started = true; } else ctx.lineTo(p[0], p[1]);
  });
  ctx.strokeStyle = accent; ctx.lineWidth = 2.4;
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  ctx.stroke();

  series.forEach((v, i) => {
    if (v == null) return;
    const p = pt(v, i);
    ctx.beginPath(); ctx.arc(p[0], p[1], 2.8, 0, Math.PI * 2);
    ctx.fillStyle = accent; ctx.fill();
  });

  canvas._chartGeom = { padL, gw, n, series, dates: moodChartDates };
  wireChartTooltip(canvas);
}

function wireChartTooltip(canvas) {
  if (canvas._tipWired) return;
  canvas._tipWired = true;

  const nearest = clientX => {
    const g = canvas._chartGeom;
    if (!g) return null;
    const rect = canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    let idx = g.n > 1 ? Math.round(((x - g.padL) / g.gw) * (g.n - 1)) : 0;
    idx = Math.max(0, Math.min(g.n - 1, idx));
    return { idx, rect };
  };
  const showFor = clientX => {
    const hit = nearest(clientX);
    if (!hit) return;
    const { idx, rect } = hit;
    const v = canvas._chartGeom.series[idx];
    if (v == null) { hideFloatingTip(); return; }
    const dates = canvas._chartGeom.dates;
    const dateLabel = dates && dates[idx] ? fmt.med(dates[idx]) : '';
    const m = mood(v);
    const x = rect.left + rect.width * (canvas._chartGeom.n > 1 ? idx / (canvas._chartGeom.n - 1) : 0.5);
    showFloatingTip(x, rect.top, dateLabel + (m ? ' · ' + m.l : ''));
  };

  canvas.addEventListener('mousemove', ev => showFor(ev.clientX));
  canvas.addEventListener('mouseleave', hideFloatingTip);
  canvas.addEventListener('touchstart', ev => {
    const t = ev.touches[0];
    if (t) showFor(t.clientX);
  }, { passive: true });
  canvas.addEventListener('touchmove', ev => {
    const t = ev.touches[0];
    if (t) showFor(t.clientX);
  }, { passive: true });
  canvas.addEventListener('touchend', () => {
    clearTimeout(showFloatingTip._hideT);
    showFloatingTip._hideT = setTimeout(hideFloatingTip, 1200);
  });
}

function buildHeatmap() {
  const wrap = $('heatmap');
  if (!wrap) return;
  const today = new Date();
  const start = new Date(today);
  start.setDate(start.getDate() - 300);
  start.setDate(start.getDate() - start.getDay());

  const months = [];
  let cursor = new Date(start), block = null;
  while (cursor <= today) {
    const mk = cursor.getFullYear() + '-' + cursor.getMonth();
    if (!block || block.k !== mk) {
      block = { k: mk, name: cursor.toLocaleDateString('en-US', { month: 'short' }), cells: [] };
      months.push(block);
    }
    for (let i = 0; i < 7; i++) { block.cells.push(new Date(cursor)); cursor.setDate(cursor.getDate() + 1); }
  }

  wrap.innerHTML = months.map(mo => `
    <div class="heat-month">
      <div class="heat-name">${mo.name}</div>
      <div class="heat-grid">
        ${mo.cells.map(d => {
          if (d > today) return '<div class="heat-cell" style="background:transparent"></div>';
          const k = dkey(d), e = liveEntry(k);
          const bg = e ? (e.mood ? moodColor(e.mood) : '#6366F1') : 'var(--surface-3)';
          const tip = esc(fmt.short(k)) + (e ? ' · wrote' : '');
          return `<div class="heat-cell" style="background:${bg}" title="${tip}" data-tip="${tip}"></div>`;
        }).join('')}
      </div>
    </div>`).join('');

  // title="" never fires on touch — tapping a cell shows the same text in a
  // floating tooltip instead, so the heatmap is legible on a phone too.
  wrap.querySelectorAll('.heat-cell[data-tip]').forEach(c => {
    c.addEventListener('click', () => {
      const r = c.getBoundingClientRect();
      showFloatingTip(r.left + r.width / 2, r.top, c.dataset.tip);
      clearTimeout(showFloatingTip._hideT);
      showFloatingTip._hideT = setTimeout(hideFloatingTip, 2200);
    });
  });
}

/* ─── Floating tooltip ────────────────────────────────────────────
   One shared tooltip element for anything that can't rely on a native
   title="" (which never fires on touch): heatmap cells (tap) and the
   mood chart (hover or drag). ─────────────────────────────────────── */

function showFloatingTip(x, y, text) {
  let tip = document.getElementById('float-tip');
  if (!tip) {
    tip = document.createElement('div');
    tip.id = 'float-tip';
    tip.className = 'float-tip';
    document.body.appendChild(tip);
  }
  tip.textContent = text;
  tip.style.left = x + 'px';
  tip.style.top = y + 'px';
  tip.classList.add('show');
}
function hideFloatingTip() {
  const tip = document.getElementById('float-tip');
  if (tip) tip.classList.remove('show');
}

/* ─── Settings ────────────────────────────────────────────────── */

function renderSettings() {
  const u = fbAuth.currentUser;
  const av = u && u.photoURL
    ? `<img src="${esc(u.photoURL)}" referrerpolicy="no-referrer" alt="">`
    : esc(((u && u.email) || 'U')[0].toUpperCase());

  content().innerHTML = `
    <div class="page-head"><div class="page-title">Settings</div></div>

    <div class="card">
      <div class="card-title">Account</div>
      <div class="acct">
        <div class="acct-av">${av}</div>
        <div style="min-width:0">
          <div class="acct-name">${esc((u && u.displayName) || 'You')}</div>
          <div class="acct-mail">${esc((u && u.email) || '')}</div>
        </div>
      </div>
      <div class="set-row">
        <div><div class="lbl">Sync</div><p><span class="dot-live" id="sync-dot"></span><span id="sync-label">Synced across your devices</span></p></div>
        <button class="btn-secondary" id="signout">Sign out</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Preferences</div>
      <div class="set-row">
        <div><div class="lbl">Writing prompts</div><p>Show a suggestion on blank days</p></div>
        <button class="btn-secondary" id="toggle-prompt">${DB.prefs.hidePrompt ? 'Off' : 'On'}</button>
      </div>
      <div class="set-row">
        <div><div class="lbl">Appearance</div><p>Auto follows this device's setting</p></div>
        <button class="btn-secondary" id="toggle-theme-2">${themeLabel()}</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Privacy</div>
      <div class="set-row">
        <div><div class="lbl">PIN lock</div><p>Require a PIN to open the journal, on every device you sign in from</p></div>
        <button class="btn-secondary" id="pin-toggle">${hasPinLock() ? 'On' : 'Off'}</button>
      </div>
      ${hasPinLock() ? `
      <div class="set-row">
        <div><div class="lbl">Change PIN</div><p>Update your PIN</p></div>
        <button class="btn-secondary" id="pin-change">Change</button>
      </div>
      <div class="set-row">
        <div><div class="lbl">Lock now</div><p>Require the PIN again immediately</p></div>
        <button class="btn-secondary" id="pin-lock-now">Lock</button>
      </div>` : ''}
    </div>

    <div class="card">
      <div class="card-title">Data</div>
      <div class="set-row">
        <div><div class="lbl">Export</div><p>Download every entry as a JSON backup</p></div>
        <button class="btn-secondary" id="export">Export</button>
      </div>
      <div class="set-row">
        <div><div class="lbl">Import</div><p>Restore from a backup file</p></div>
        <label class="btn-secondary" style="cursor:pointer">Import<input type="file" id="import" accept="application/json" hidden></label>
      </div>
      <div class="set-row">
        <div><div class="lbl">Delete everything</div><p>Kept for 30 days before it's gone for good — see Recently deleted below.</p></div>
        <button class="btn-ghost-danger" id="wipe">Delete</button>
      </div>
    </div>

    ${trashedEntries().length ? `<div class="card">
      <div class="card-title">Recently deleted</div>
      <p class="muted-note" style="margin-bottom:10px">Removed for good after ${TRASH_DAYS} days.</p>
      ${trashedEntries().map(e => `
      <div class="set-row">
        <div><div class="lbl">${esc(fmt.med(e.date))}</div><p>${esc(e.title || (e.plain ? e.plain.slice(0, 60) : 'No text'))}</p></div>
        <button class="btn-secondary" data-restore="${e.date}">Restore</button>
      </div>`).join('')}
    </div>` : ''}

    <p class="foot-note">Your entries are private to your account.</p>`;

  setSync(syncState);

  $('signout').onclick = () => fbAuth.signOut().then(() => location.reload());
  $('toggle-prompt').onclick = () => { DB.prefs.hidePrompt = !DB.prefs.hidePrompt; savePrefs(); renderSettings(); };
  $('toggle-theme-2').onclick = () => { cycleTheme(); renderSettings(); };

  $('pin-toggle').onclick = async () => {
    const ok = hasPinLock() ? await disablePinFlow() : await createPinFlow();
    if (ok) renderSettings();
  };
  const pinChange = $('pin-change');
  if (pinChange) pinChange.onclick = async () => { if (await changePinFlow()) renderSettings(); };
  const pinLockNow = $('pin-lock-now');
  if (pinLockNow) pinLockNow.onclick = () => lockAppNow();

  $('export').onclick = () => {
    const liveEntries = {};
    allEntries().forEach(e => { liveEntries[e.date] = e; });
    const payload = { version: 3, exportedAt: new Date().toISOString(), entries: liveEntries };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'journal-backup-' + todayKey() + '.json';
    a.click();
    URL.revokeObjectURL(a.href);
    toast('Backup downloaded');
  };

  $('import').onchange = ev => {
    const f = ev.target.files[0];
    if (!f) return;
    const r = new FileReader();
    r.onload = async x => {
      try {
        const data = JSON.parse(x.target.result);
        const incoming = data.entries || data;
        let imported = 0, skipped = 0;
        for (const k of Object.keys(incoming)) {
          const clean = cleanIncoming(k, incoming[k]);
          if (!clean) continue;
          // An old backup must never clobber a newer edit: only entries that
          // are unknown here or strictly newer than what we have are written.
          // (Same updatedAt-wins rule as pullAll's merge.)
          const existing = DB.entries[clean.date];
          if (existing && (existing.updatedAt || 0) > (clean.updatedAt || 0)) { skipped++; continue; }
          DB.entries[clean.date] = clean;
          await pushDay(clean);
          imported++;
        }
        saveLocal();
        toast('Imported ' + imported + (imported === 1 ? ' entry' : ' entries')
          + (skipped ? ' · skipped ' + skipped + ' (newer version kept)' : ''));
        render();
      } catch { toast('That file could not be read'); }
    };
    r.readAsText(f);
    ev.target.value = '';
  };

  $('wipe').onclick = async () => {
    if (!confirm("Delete every entry, everywhere? You'll get a chance to undo right after, and deleted entries are kept for 30 days before they're removed for good.")) return;
    const keys = Object.keys(DB.entries).filter(k => !DB.entries[k].deleted);
    const now = Date.now();
    keys.forEach(k => { DB.entries[k] = applyTombstone(DB.entries[k], now); });
    saveLocal();
    for (const k of keys) await pushDay(DB.entries[k]);
    state.date = todayKey();
    toast('All entries deleted', { label: 'Undo', onClick: () => {
      keys.forEach(restoreEntry);
      toast('Restored');
      render();
    } });
    render();
  };

  document.querySelectorAll('[data-restore]').forEach(b => {
    b.onclick = () => { restoreEntry(b.dataset.restore); renderSettings(); };
  });
}

/* ─── Modal / lightbox / theme ────────────────────────────────── */

let modalReturnFocus = null;

function openModal() {
  const bd = $('modal-backdrop');
  modalReturnFocus = document.activeElement;
  bd.classList.add('open');
  // Move focus into the dialog so Tab can't escape behind the modal.
  const m = $('modal');
  if (m && m.focus) m.focus();
}
function closeModal() {
  $('modal-backdrop').classList.remove('open');
  if (modalReturnFocus && modalReturnFocus.focus) {
    try { modalReturnFocus.focus(); } catch {}
  }
  modalReturnFocus = null;
}

// Keep Tab / Shift-Tab cycling inside the open modal.
document.addEventListener('keydown', ev => {
  if (ev.key !== 'Tab') return;
  if (!$('modal-backdrop').classList.contains('open')) return;
  const sel = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
  const focusables = Array.from($('modal').querySelectorAll(sel))
    .filter(el => !el.disabled && el.offsetParent !== null);
  if (!focusables.length) return;
  const first = focusables[0], last = focusables[focusables.length - 1];
  if (ev.shiftKey && document.activeElement === first) { ev.preventDefault(); last.focus(); }
  else if (!ev.shiftKey && document.activeElement === last) { ev.preventDefault(); first.focus(); }
});

const SUN = '<circle cx="12" cy="12" r="4.2"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>';
const MOON = '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>';

function applyTheme(dark) {
  document.body.classList.toggle('dark', dark);
  $('theme-icon').innerHTML = dark ? SUN : MOON;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', dark ? '#0E0F13' : '#6366F1');
}

// Three states: Auto (follow the device's prefers-color-scheme), Light, Dark.
function themePref() {
  const saved = localStorage.getItem(LS.theme);
  return saved === 'light' || saved === 'dark' ? saved : 'auto';
}
function themeLabel() {
  const p = themePref();
  return p === 'dark' ? 'Dark' : p === 'light' ? 'Light' : 'Auto';
}
function isDarkNow() {
  const p = themePref();
  return p === 'dark' ? true
    : p === 'light' ? false
    : !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}
function applyThemeNow() { applyTheme(isDarkNow()); }
function cycleTheme() {
  const order = ['auto', 'light', 'dark'];
  const next = order[(order.indexOf(themePref()) + 1) % order.length];
  localStorage.setItem(LS.theme, next);
  applyThemeNow();
  // Mood colours differ per theme and are baked into already-rendered markup
  // (inline styles, the canvas, the heatmap), so the view has to be redrawn —
  // a CSS variable swap can't reach them. Commit first: renderWrite() rebuilds
  // state.draft straight from DB.entries, which would otherwise discard
  // anything typed since the last autosave tick.
  if (state.view === 'write') commitDraft();
  if (typeof ready !== 'undefined' && ready) render();
}
function initTheme() {
  applyThemeNow();
  if (window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => { if (themePref() === 'auto') applyThemeNow(); };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
    else if (mq.addListener) mq.addListener(onChange);
  }
}

/* ─── Wiring ──────────────────────────────────────────────────── */

function wireChrome() {
  document.querySelectorAll('.tab, .bn-item').forEach(b => { b.onclick = () => go(b.dataset.view); });
  $('theme-toggle').onclick = cycleTheme;
  $('avatar-btn').onclick = () => go('settings');
  $('modal-close').onclick = closeModal;
  $('modal-backdrop').addEventListener('click', ev => { if (ev.target.id === 'modal-backdrop') closeModal(); });
  $('lightbox').onclick = () => $('lightbox').classList.remove('open');

  document.addEventListener('keydown', ev => {
    if (ev.key === 'Escape') {
      if ($('lightbox').classList.contains('open')) return $('lightbox').classList.remove('open');
      if ($('modal-backdrop').classList.contains('open')) return closeModal();
    }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName) || ev.target.isContentEditable;
    if (typing) return;
    if (state.view === 'write') {
      if (ev.key === 'ArrowLeft')  { ev.preventDefault(); openDay(shiftKey(state.date, -1)); }
      if (ev.key === 'ArrowRight' && state.date !== todayKey()) { ev.preventDefault(); openDay(shiftKey(state.date, 1)); }
    }
  });

  // On unload, only the synchronous local write is trustworthy — an async
  // Firestore push here is a race the browser is free to cancel. The next
  // boot's pullAll() pushback carries the change up instead.
  window.addEventListener('beforeunload', () => { if (state.view === 'write') commitDraft({ localOnly: true }); });

  // Two tabs on the same browser otherwise overwrite each other's edits to
  // the same day silently. The storage event fires in every OTHER tab when
  // one of them writes the entries store.
  let tabConflictAskedAt = 0;
  window.addEventListener('storage', ev => {
    if (!ev.key || ev.key.indexOf('jr3_entries') !== 0) return;
    if (state.view !== 'write') return;
    if (Date.now() - tabConflictAskedAt < 30000) return;
    let incoming = {};
    try { incoming = JSON.parse(ev.newValue || '{}'); } catch { return; }
    const theirs = incoming[state.date] || null;
    const mine = DB.entries[state.date] || null;
    if (JSON.stringify(theirs) === JSON.stringify(mine)) return;
    tabConflictAskedAt = Date.now();
    if (confirm('This entry changed in another tab. Replace what this tab has with that version?')) {
      loadLocal();
      render();
    }
  });

  // Canvas pixels don't reflow on their own — redraw when the box changes.
  let resizeTimer = null;
  window.addEventListener('resize', () => {
    if (state.view !== 'insights') return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => drawMoodChart(), 120);
  });

  const PIN_RELOCK_MS = 30000; // brief backgrounding (e.g. a notification) doesn't force re-entry
  let hiddenAt = null;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      if (state.view === 'write') commitDraft();
      hiddenAt = Date.now();
    } else if (document.visibilityState === 'visible') {
      if (hasPinLock() && hiddenAt && (Date.now() - hiddenAt) > PIN_RELOCK_MS && $('app').classList.contains('show')) {
        lockAppNow();
      }
      hiddenAt = null;
    }
  });
}

/* ─── Init ────────────────────────────────────────────────────── */

let ready = false;

function init() {
  initTheme();
  loadPrefs();
  retireLegacyKeys();

  const btn = $('google-signin-btn');
  const err = $('signin-error');

  btn.onclick = () => {
    btn.disabled = true;
    err.textContent = '';
    const provider = new firebase.auth.GoogleAuthProvider();
    fbAuth.signInWithPopup(provider).catch(e => {
      if (e.code === 'auth/popup-blocked' || e.code === 'auth/popup-closed-by-user') {
        fbAuth.signInWithRedirect(provider);
      } else { err.textContent = e.message || e.code; btn.disabled = false; }
    });
  };

  fbAuth.getRedirectResult().catch(e => {
    if (e && e.code !== 'auth/no-auth-event') err.textContent = e.message || e.code;
  });

  fbAuth.onAuthStateChanged(async user => {
    fbUser = user;
    if (!user) {
      DB.entries = {};
      $('boot').classList.add('hide');
      $('app').classList.remove('show');
      $('auth-gate').classList.add('show');
      btn.disabled = false;
      window.__journalReady = true;   // signed out is a valid state, not a failure
      return;
    }
    $('auth-gate').classList.remove('show');
    window.__journalReady = true;   // gate cleared; app is running

    // Load THIS account's entries (localStorage is namespaced per uid), so an
    // interrupted sign-out can never leak one account's days into the next.
    DB.entries = {};
    loadEntries();

    if (user.photoURL) $('avatar-btn').innerHTML = '<img src="' + user.photoURL + '" referrerpolicy="no-referrer" alt="">';
    else { const a = $('avatar-inner'); if (a) a.textContent = (user.email || 'U')[0].toUpperCase(); }

    // Read the account's lock state and clear the gate BEFORE fetching entries,
    // so nothing is pulled down or rendered behind the PIN screen.
    await pullPinRecord();

    $('boot').classList.add('hide');
    if (!ready) { ready = true; wireChrome(); wirePinPad(); }
    if (hasPinLock()) await unlockFlow();

    await pullAll();
    purgeTrash();

    $('app').classList.add('show');
    render();
    window.__journalReady = true;
  });
}

document.addEventListener('DOMContentLoaded', init);
