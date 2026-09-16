// Every htmx request that changes state carries the CSRF token as a header (B35).
// Forms also include it as a hidden field, so they keep working without JavaScript.
document.addEventListener('htmx:configRequest', (event) => {
    const token = document.querySelector('meta[name="csrf-token"]');
    const header = document.querySelector('meta[name="csrf-header"]');
    if (token && header) {
        event.detail.headers[header.content] = token.content;
    }
});
