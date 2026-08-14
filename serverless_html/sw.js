const CACHE_NAME = 'obey-me-v2';
const ASSETS_TO_CACHE = [
    '/style.css',
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&family=Outfit:wght@700;800&display=swap',
    'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css'
];

// Install Event - Pre-cache Static CSS & Fonts only (HTML pages are always network live)
self.addEventListener('install', event => {
    self.skipWaiting();
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            return cache.addAll(ASSETS_TO_CACHE).catch(err => console.log('[SW] Cache add warning:', err));
        })
    );
});

// Activate Event - Clean old caches
self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys().then(keys => {
            return Promise.all(
                keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
            );
        }).then(() => self.clients.claim())
    );
});

// Network-First for HTML/APIs, Cache-First for static assets
self.addEventListener('fetch', event => {
    const req = event.request;
    if (req.method !== 'GET') return;

    // Static CSS & Fonts: Cache First
    if (req.url.includes('style.css') || req.url.includes('fonts.googleapis') || req.url.includes('font-awesome')) {
        event.respondWith(
            caches.match(req).then(cached => cached || fetch(req))
        );
        return;
    }

    // All HTML & API requests: Network Direct (Never stall)
    return;
});
