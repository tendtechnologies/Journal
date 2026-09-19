/* Journal — Service Worker v13 */
const CACHE = 'journal-v13';
const PRECACHE = [
  './index.html',
  './css/app.css?v=13',
  './js/core.js?v=13',
  './js/app.js?v=13',
  './js/boot.js?v=13',
  './manifest.json',
  './assets/icon-192.png',
  './assets/icon-512.png',
  './assets/icon-maskable-512.png',
  './assets/icon-180.png',
  './assets/icon.svg',
];

self.addEventListener('install', e => {
  // Cache files individually so one bad URL can't fail the whole install
  // and leave the app wedged on an old worker.
  e.waitUntil(
    caches.open(CACHE)
      .then(c => Promise.all(PRECACHE.map(u => c.add(u).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const url = e.request.url;

  // Never intercept Firebase, Google auth, fonts, or other third-party requests
  if (
    url.includes('firebaseapp.com') ||
    url.includes('googleapis.com') ||
    url.includes('gstatic.com') ||
    url.includes('accounts.google.com') ||
    url.includes('firestore.googleapis.com') ||
    e.request.method !== 'GET'
  ) return;

  // Navigations: network-first so a fresh deploy shows up immediately,
  // falling back to the cached shell when offline.
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request).then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put('./index.html', copy));
        return res;
      }).catch(() => caches.match('./index.html'))
    );
    return;
  }

  // App shell: network-first, cache as offline fallback.
  e.respondWith(
    fetch(e.request).then(res => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy));
      }
      return res;
    }).catch(() => caches.match(e.request))
  );
});

/* ─── Daily reminder (plan item 24) ──────────────────────────────
   The page shares its reminder state through the Cache API (visible to
   both page and SW) because the SW can't read the page's localStorage.
   Periodic Background Sync fires on the browser's own schedule — this
   is "the next chance at/after the chosen time", not an exact alarm.
   The in-page check in app.js is the authoritative path. ───────── */

const REMINDER_META = 'journal-sw-meta';
const REMINDER_STATE_URL = '/reminder-state';

async function readReminderState() {
  try {
    const c = await caches.open(REMINDER_META);
    const res = await c.match(REMINDER_STATE_URL);
    return res ? await res.json() : null;
  } catch { return null; }
}

self.addEventListener('periodicsync', e => {
  if (e.tag !== 'daily-reminder') return;
  e.waitUntil((async () => {
    const st = await readReminderState();
    if (!st || !st.enabled || !st.time) return;
    const now = new Date();
    const today = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0')
      + '-' + String(now.getDate()).padStart(2, '0');
    // writtenToday is stamped by the page on every save; a stale false only
    // means "written elsewhere since the last visit" — one possibly extra
    // nudge, never a missed day.
    if (st.writtenToday === today || st.fired === today) return;
    const parts = st.time.split(':');
    if (now.getHours() * 60 + now.getMinutes() < Number(parts[0]) * 60 + Number(parts[1])) return;
    st.fired = today;
    try {
      const c = await caches.open(REMINDER_META);
      await c.put(REMINDER_STATE_URL, new Response(JSON.stringify(st)));
    } catch { /* best-effort */ }
    await self.registration.showNotification('Journal', {
      body: "You haven't written today — one line is enough.",
      icon: 'assets/icon-192.png',
      badge: 'assets/icon-192.png',
      tag: 'daily-reminder',
    });
  })());
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (wins.length) return wins[0].focus();
    return self.clients.openWindow('./');
  })());
});
