/* ================================================================
   Journal — boot.js
   Self-healing recovery banner + service worker registration.
   Split out of index.html so the page can ship a CSP without
   'unsafe-inline' in script-src.
================================================================ */

// ── Self-healing: wipe caches + service worker, then reload ──
function hardReset() {
  var jobs = [];
  if ('caches' in window) {
    jobs.push(caches.keys().then(function (ks) {
      return Promise.all(ks.map(function (k) { return caches.delete(k); }));
    }));
  }
  if ('serviceWorker' in navigator) {
    jobs.push(navigator.serviceWorker.getRegistrations().then(function (rs) {
      return Promise.all(rs.map(function (r) { return r.unregister(); }));
    }));
  }
  return Promise.all(jobs)['catch'](function () {}).then(function () {
    window.location.replace(window.location.pathname + '?fresh=' + Date.now());
  });
}
document.getElementById('recover-btn').onclick = hardReset;

function showRecover(msg) {
  var el = document.getElementById('recover');
  if (!el) return;
  el.classList.add('show');
  var d = document.getElementById('recover-detail');
  if (d && msg) d.textContent = msg;
  var b = document.getElementById('boot');
  if (b) b.classList.add('hide');
}

// If a script error escapes before the app is running, offer the fix.
window.addEventListener('error', function (ev) {
  if (!window.__journalReady) {
    showRecover(ev && ev.message ? String(ev.message) : '');
  }
});

// Safety net: if nothing has rendered after 12s, surface the recovery card.
setTimeout(function () {
  var c = document.getElementById('content');
  var gateOpen = document.getElementById('auth-gate').classList.contains('show');
  if (!window.__journalReady && !gateOpen && (!c || !c.innerHTML.trim())) {
    showRecover('The app did not finish loading.');
  }
}, 12000);

if ('serviceWorker' in navigator) {
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('./sw.js').then(function (reg) {
      reg.update();
      document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') reg.update();
      });
    }).catch(function (e) { console.warn('SW reg failed:', e); });
    var refreshing = false;
    navigator.serviceWorker.addEventListener('controllerchange', function () {
      if (refreshing) return;
      refreshing = true;
      window.location.reload();
    });
  });
}
