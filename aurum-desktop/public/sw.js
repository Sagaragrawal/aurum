const cacheName = 'aurum-shell-v537';
const shell = ['/', '/index.html', '/styles.css?v=525', '/app.js?v=537', '/manifest.webmanifest'];
self.addEventListener('install', (event) => event.waitUntil(caches.open(cacheName).then((cache) => cache.addAll(shell)).then(() => self.skipWaiting())));
self.addEventListener('activate', (event) => event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== cacheName).map((key) => caches.delete(key)))).then(() => self.clients.claim())));
self.addEventListener('fetch', (event) => { if (event.request.url.includes('/api/')) return; event.respondWith(fetch(event.request).then(async (response) => { if (response.ok && event.request.method === 'GET') { const cache = await caches.open(cacheName); await cache.put(event.request, response.clone()); } return response; }).catch(() => caches.match(event.request))); });
