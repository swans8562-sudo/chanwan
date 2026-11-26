const SW_VER = 'chanwan-sw-prime4-v17';
self.addEventListener('install', (e) => { self.skipWaiting(); });
self.addEventListener('activate', (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
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
          if (Array.isArray(list)) list.forEach(n => { try { n.close(); } catch (_) {} });
        }
      } catch (_) {}
    })());
  }
});
