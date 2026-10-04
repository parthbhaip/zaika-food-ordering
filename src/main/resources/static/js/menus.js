// Header menus are native <details data-menu> elements, so they open and close without JavaScript.
// This only adds the comforts: Escape closes an open menu (focus goes back to its toggle) and a click
// outside closes it.
document.addEventListener('keydown', function (event) {
  if (event.key !== 'Escape') {
    return;
  }
  document.querySelectorAll('details[data-menu][open]').forEach(function (menu) {
    menu.open = false;
    menu.querySelector('summary').focus();
  });
});

document.addEventListener('click', function (event) {
  document.querySelectorAll('details[data-menu][open]').forEach(function (menu) {
    if (!menu.contains(event.target)) {
      menu.open = false;
    }
  });
});

// Toast close buttons (fragments/flash): remove the toast. Delegated, so htmx out-of-band toasts work too.
document.addEventListener('click', function (event) {
  var close = event.target.closest('[data-toast-close]');
  if (close) {
    close.closest('.toast').remove();
  }
});
