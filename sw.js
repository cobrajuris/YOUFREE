/* ═══════════════════════════════════════════════════════════
   YouFree – sw.js (Service Worker)
   PWA: cache estratégico + suporte offline
   ═══════════════════════════════════════════════════════════ */

const CACHE_NAME = 'youfree-v1.2';
const CACHE_URLS = [
  '/YOUFREE/',
  '/YOUFREE/index.html',
  '/YOUFREE/style.css',
  '/YOUFREE/script.js',
  '/YOUFREE/manifest.json',
  'https://fonts.googleapis.com/css2?family=Syne:wght@400;600;700;800&family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,400;0,9..40,500;0,9..40,600;1,9..40,300&display=swap',
];

/* ── INSTALL: pré-cache dos assets principais ── */
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      console.log('[YouFree SW] Cacheando assets principais...');
      return cache.addAll(CACHE_URLS).catch(err => {
        console.warn('[YouFree SW] Alguns assets falharam no cache:', err);
      });
    }).then(() => self.skipWaiting())
  );
});

/* ── ACTIVATE: limpa caches antigos ── */
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(cacheNames =>
      Promise.all(
        cacheNames
          .filter(name => name !== CACHE_NAME)
          .map(name => {
            console.log('[YouFree SW] Removendo cache antigo:', name);
            return caches.delete(name);
          })
      )
    ).then(() => self.clients.claim())
  );
});

/* ── FETCH: Network-first com fallback para cache ── */
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Ignora requests de extensões e externos não-críticos
  if (request.method !== 'GET') return;
  if (url.protocol === 'chrome-extension:') return;

  // Para requests ao YouTube/APIs externas: network-only
  if (url.hostname.includes('youtube') ||
      url.hostname.includes('googleapis') ||
      url.hostname.includes('unsplash') ||
      url.hostname.includes('ytdl')) {
    return;
  }

  event.respondWith(
    fetch(request)
      .then(networkResponse => {
        // Salva no cache se for um asset do YouFree
        if (networkResponse.ok && url.hostname === self.location.hostname) {
          const responseClone = networkResponse.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(request, responseClone));
        }
        return networkResponse;
      })
      .catch(() => {
        // Fallback: tenta retornar do cache
        return caches.match(request).then(cached => {
          if (cached) return cached;
          // Fallback final: retorna index.html para SPA
          if (request.headers.get('accept').includes('text/html')) {
            return caches.match('/YOUFREE/index.html');
          }
        });
      })
  );
});

/* ── BACKGROUND SYNC (preparado para futuro) ── */
self.addEventListener('sync', event => {
  if (event.tag === 'sync-playlists') {
    console.log('[YouFree SW] Background sync de playlists');
  }
});

/* ── PUSH NOTIFICATIONS (preparado para futuro) ── */
self.addEventListener('push', event => {
  const data = event.data ? event.data.json() : { title: 'YouFree', body: 'Nova notificação!' };
  event.waitUntil(
    self.registration.showNotification(data.title || 'YouFree', {
      body: data.body || 'Confira as novidades!',
      icon: '/YOUFREE/manifest.json',
      badge: '/YOUFREE/manifest.json',
      tag: 'youfree-notification',
      renotify: true,
    })
  );
});
