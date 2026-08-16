const CACHE_NAME = 'obey-me-v3.1';
const ASSETS_TO_CACHE = [
    '/style.css?v=3.1',
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&family=Outfit:wght@700;800&display=swap',
    'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css'
];

// Install Event - Pre-cache Static Assets
self.addEventListener('install', event => {
    self.skipWaiting();
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            return cache.addAll(ASSETS_TO_CACHE).catch(err => console.log('[SW] Cache add warning:', err));
        })
    );
});

// Activate Event - Instantly purge all old caches
self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys().then(keys => {
            return Promise.all(
                keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
            );
        }).then(() => self.clients.claim())
    );
});

// Network-First strategy for style.css & HTML files
self.addEventListener('fetch', event => {
    const req = event.request;
    if (req.method !== 'GET') return;

    if (req.url.includes('style.css')) {
        event.respondWith(
            fetch(req).then(networkResponse => {
                if (networkResponse && networkResponse.status === 200) {
                    const clone = networkResponse.clone();
                    caches.open(CACHE_NAME).then(cache => cache.put(req, clone));
                }
                return networkResponse;
            }).catch(() => caches.match(req))
        );
        return;
    }
});
