self.addEventListener('install', (e) => {
    self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
    // מאפשר לדפדפן לדעת שיש פה אפליקציית PWA
});