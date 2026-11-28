const SW_VER = 'chanwan-sw-prime4-v84';
const PRECACHE_URLS = [
  './',
  './index.html',
  './manifest_clean.json?v=22',
  './icon-192.png?v=21',
  './badge-96.png?v=23'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(SW_VER)
      .then((cache) => Promise.allSettled(PRECACHE_URLS.map((u) => cache.add(u))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SW_VER).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

async function fetchCompleteHtmlWithTimeout(req, ms) {
  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = setTimeout(() => {
    if (ctrl) { try { ctrl.abort(); } catch (_) {} }
  }, ms);
  try {
    const res = await fetch(req, { cache: 'no-store', signal: ctrl ? ctrl.signal : undefined });
    if (!res || !res.ok) throw new Error('bad_status');
    const text = await res.text();
    clearTimeout(timer);
    if (!text || !text.includes('</html>')) throw new Error('incomplete_html');
    return new Response(text, {
      status: 200,
      headers: { 'Content-Type': 'text/html; charset=utf-8' }
    });
  } catch (err) {
    clearTimeout(timer);
    throw err;
  }
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  let url;
  try { url = new URL(e.request.url); } catch (_) { return; }
  if (url.origin !== self.location.origin) return;
  if (url.pathname.endsWith('.apk')) return;

  const isNav = e.request.mode === 'navigate' || url.pathname.endsWith('/') || url.pathname.endsWith('/index.html');

  if (isNav) {
    e.respondWith((async () => {
      const cache = await caches.open(SW_VER);
      const cached = (await cache.match('./index.html')) || (await cache.match('./'));
      const netPromise = fetchCompleteHtmlWithTimeout(e.request, cached ? 5000 : 12000)
        .then((freshRes) => {
          cache.put('./index.html', freshRes.clone()).catch(() => {});
          cache.put('./', freshRes.clone()).catch(() => {});
          return freshRes;
        });

      if (cached) {
        e.waitUntil(netPromise.catch(() => {}));
        return cached;
      }
      return netPromise;
    })());
    return;
  }

  e.respondWith(
    caches.open(SW_VER).then((cache) =>
      cache.match(e.request).then((cached) => {
        if (cached) {
          e.waitUntil(
            fetch(e.request).then((res) => {
              if (res && res.ok) cache.put(e.request, res.clone()).catch(() => {});
            }).catch(() => {})
          );
          return cached;
        }
        return fetch(e.request).then((res) => {
          if (res && res.ok) cache.put(e.request, res.clone()).catch(() => {});
          return res;
        });
      })
    )
  );
});

self.addEventListener('notificationclick', (e) => {
  const clickedTitle = (e.notification && e.notification.title) || '';
  const clickedBody = (e.notification && e.notification.body) || '';
  e.notification.close();
  const data = (e.notification && e.notification.data) || {};
  const tab = data.tab || 'translator';
  const base = data.url || './';
  const targetUrl = base.includes('?') ? base : `${base}?tab=${encodeURIComponent(tab)}`;
  e.waitUntil((async () => {
    try {
      if (self.registration && self.registration.getNotifications) {
        const all = await self.registration.getNotifications();
        if (Array.isArray(all)) all.forEach((n) => { try { n.close(); } catch (_) {} });
      }
      if (self.registration && typeof self.registration.setAppBadge === 'function') await self.registration.setAppBadge(0);
      if (self.registration && typeof self.registration.clearAppBadge === 'function') await self.registration.clearAppBadge();
    } catch (_) {}
    const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of clientList) {
      if ('focus' in client) {
        client.postMessage({ type: 'NOTIF_CLICK', tab, sub: data.sub || '', title: clickedTitle, body: clickedBody });
        return client.focus();
      }
    }
    if (self.clients.openWindow) return self.clients.openWindow(targetUrl);
  })());
});

self.addEventListener('message', (e) => {
  if (e && e.data && e.data.type === 'CLEAR_APP_BADGE') {
    e.waitUntil((async () => {
      try {
        if (self.registration && typeof self.registration.setAppBadge === 'function') await self.registration.setAppBadge(0);
        if (self.registration && typeof self.registration.clearAppBadge === 'function') await self.registration.clearAppBadge();
        if (typeof navigator !== 'undefined' && typeof navigator.clearAppBadge === 'function') await navigator.clearAppBadge();
        if (self.registration && self.registration.getNotifications) {
          const list = await self.registration.getNotifications();
          if (Array.isArray(list)) list.forEach((n) => { try { n.close(); } catch (_) {} });
        }
      } catch (_) {}
    })());
  }
});
