const SW_VER = 'chanwan-sw-prime4-v64';
const PRECACHE_URLS = ['./', './index.html', './manifest_clean.json?v=22', './icon-192.png?v=21', './badge-96.png?v=23'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(SW_VER)
      .then((cache) => cache.addAll(PRECACHE_URLS).catch(() => {}))
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

function isHtmlResponse(res) {
  try {
    const ct = (res && res.headers && res.headers.get('content-type')) || '';
    return ct.toLowerCase().includes('text/html');
  } catch (_) { return false; }
}

function fetchHtmlWithTimeout(req, ms) {
  return new Promise((resolve, reject) => {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const t = setTimeout(() => {
      if (ctrl) { try { ctrl.abort(); } catch (_) {} }
      reject(new Error('nav_timeout'));
    }, ms);
    fetch(req, { cache: 'no-store', signal: ctrl ? ctrl.signal : undefined })
      .then((res) => { clearTimeout(t); resolve(res); })
      .catch((err) => { clearTimeout(t); reject(err); });
  });
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  let url;
  try { url = new URL(e.request.url); } catch (_) { return; }
  if (url.origin !== self.location.origin) return;

  const isNav = e.request.mode === 'navigate' || url.pathname.endsWith('/') || url.pathname.endsWith('/index.html');

  if (isNav) {
    e.respondWith(
      fetchHtmlWithTimeout(e.request, 3200)
        .then((res) => {
          if (res && res.ok && isHtmlResponse(res)) {
            const copy1 = res.clone();
            const copy2 = res.clone();
            caches.open(SW_VER).then((c) => {
              c.put('./index.html', copy1).catch(() => {});
              c.put('./', copy2).catch(() => {});
            }).catch(() => {});
            return res;
          }
          throw new Error('bad_html');
        })
        .catch(() =>
          caches.open(SW_VER).then((c) =>
            c.match('./index.html').then((r) => r || c.match('./') || caches.match(e.request))
          )
        )
    );
    return;
  }

  e.respondWith(
    caches.open(SW_VER).then((cache) =>
      cache.match(e.request).then((cached) => {
        const net = fetch(e.request).then((res) => {
          if (res && res.ok) cache.put(e.request, res.clone()).catch(() => {});
          return res;
        }).catch(() => cached);
        if (cached) { e.waitUntil(net.catch(() => {})); return cached; }
        return net;
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
