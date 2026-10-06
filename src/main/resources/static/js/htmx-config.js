// htmx settings for Zaika. Loaded (deferred) right after htmx, so it runs before htmx initialises.
// - Indicator styles come from app.css; htmx must not inject a <style> of its own.
// - No eval and no inline <script> execution: the CSP (script-src 'self') forbids both anyway.
// - Every htmx request carries the CSRF token from the <meta> tags in layouts/base.html.
htmx.config.includeIndicatorStyles = false;
htmx.config.allowEval = false;
htmx.config.allowScriptTags = false;

document.addEventListener('htmx:configRequest', function (event) {
  var token = document.querySelector('meta[name="_csrf"]');
  var header = document.querySelector('meta[name="_csrf_header"]');
  if (token && header) {
    event.detail.headers[header.content] = token.content;
  }
});

// htmx swaps nothing for an error status. An error answer that names its own target (HX-Retarget: the error toast
// of common.HtmxErrors) is shown anyway, so a failed click never goes silent.
document.addEventListener('htmx:beforeSwap', function (event) {
  if (event.detail.isError && event.detail.xhr.getResponseHeader('HX-Retarget')) {
    event.detail.shouldSwap = true;
  }
});
