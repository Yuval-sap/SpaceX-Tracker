self.addEventListener('install', (e) => {
    self.skipWaiting();
});

// No 'fetch' handler: one that does nothing (as there was) only puts every request of the site through the
// worker for no gain - Chrome flags it as a no-op and skips it anyway. Installing the site as an app no longer
// needs one, and the launch reminders (registration.showNotification) need only the registered worker.
