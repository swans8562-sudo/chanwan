const SW_VER = 'chanwan-sw-prime4-v32';
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

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  let url;
  try { url = new URL(e.request.url); } catch (_) { return; }
  // NEVER intercept external APIs (Supabase, Gemini, EMQX, ntfy, Google Translate, fonts)
  if (url.origin !== self.location.origin) return;

  const isNav = e.request.mode === 'navigate' || url.pathname.endsWith('/') || url.pathname.endsWith('/index.html');
  const forceFresh = isNav && url.searchParams.has('v');

  // Explicit ?v= update URL on HTML navigation ONLY: Network-First
  if (forceFresh) {
    e.respondWith(
      fetch(e.request, { cache: 'no-cache' })
        .then((res) => {
          if (res && res.ok && isHtmlResponse(res)) {
            const copy = res.clone();
            caches.open(SW_VER).then((c) => {
              c.put('./index.html', copy.clone()).catch(() => {});
              c.put('./', copy).catch(() => {});
            }).catch(() => {});
          }
          return res;
        })
        .catch(() => caches.match('./index.html').then((r) => r || caches.match(e.request)))
    );
    return;
  }

  // Non-navigation static assets (manifest_clean.json?v=22, icon-192.png?v=21, etc.): cache by exact request URL ONLY!
  if (!isNav) {
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
    return;
  }

  // Normal HTML navigation launch: Stale-While-Revalidate with strict HTML content-type check!
  e.respondWith(
    caches.open(SW_VER).then((cache) =>
      cache.match('./index.html').then((cached) => {
        const safeCached = (cached && isHtmlResponse(cached)) ? cached : null;
        const netPromise = fetch(e.request, { cache: 'no-cache' })
          .then((res) => {
            if (res && res.ok && isHtmlResponse(res)) {
              cache.put('./index.html', res.clone()).catch(() => {});
              cache.put('./', res.clone()).catch(() => {});
            }
            return res;
          })
          .catch(() => safeCached);
        if (safeCached) {
          e.waitUntil(netPromise.catch(() => {}));
          return safeCached;
        }
        return netPromise;
      })
    )
  );
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const data = (e.notification && e.notification.data) || {};
  const targetUrl = data.url || './';
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) {
          client.postMessage({ type: 'NOTIF_CLICK', tab: data.tab || 'translator', sub: data.sub || '' });
          return client.focus();
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(targetUrl);
    })
  );
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
