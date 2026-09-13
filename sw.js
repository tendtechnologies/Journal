/* Journal — Service Worker v10 */
const CACHE = 'journal-v10';
const PRECACHE = [
  './index.html',
  './css/app.css?v=10',
  './js/app.js?v=10',
  './manifest.json',
  './assets/icon-192.png',
  './assets/icon-512.png',
  './assets/icon-maskable-512.png',
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
