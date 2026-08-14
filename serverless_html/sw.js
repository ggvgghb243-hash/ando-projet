const CACHE_NAME = 'obey-me-v1';
const ASSETS_TO_CACHE = [
    '/',
    '/index.html',
    '/control.html',
    '/login.html',
    '/admin.html',
    '/style.css',
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&family=Outfit:wght@700;800&display=swap',
    'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css'
];

// Install Event - Pre-cache Static Assets
self.addEventListener('install', event => {
    self.skipWaiting();
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            console.log('[SW] Pre-caching static assets');
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

// Fetch Event - Stale-While-Revalidate for ultra-fast instant loads
self.addEventListener('fetch', event => {
    const req = event.request;
    // Only cache GET requests, skip firebase RTDB & auth dispatches
    if (req.method !== 'GET' || req.url.includes('firebasedatabase.app') || req.url.includes('googleapis.com/identitytoolkit') || req.url.includes('api.github.com')) {
        return;
    }

    event.respondWith(
        caches.match(req).then(cachedResponse => {
            const fetchPromise = fetch(req).then(networkResponse => {
                if (networkResponse && networkResponse.status === 200) {
                    const responseClone = networkResponse.clone();
                    caches.open(CACHE_NAME).then(cache => cache.put(req, responseClone));
                }
                return networkResponse;
            }).catch(() => cachedResponse);

            return cachedResponse || fetchPromise;
        })
    );
});
