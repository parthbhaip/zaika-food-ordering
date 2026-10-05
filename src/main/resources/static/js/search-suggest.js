// Header search suggestions (fragments/header, [data-suggest]). htmx loads the options into the listbox; this file
// adds the combobox keys: Up/Down move through them, Enter opens the chosen one, Escape or a click outside closes.
document.querySelectorAll('[data-suggest]').forEach(function (box) {
  var input = box.querySelector('[role="combobox"]');
  var list = document.getElementById(input.getAttribute('aria-controls'));
  var active = -1;

  function options() {
    return list.querySelectorAll('[role="option"]');
  }

  function choose(index) {
    var all = options();
    active = index;
    all.forEach(function (option, i) { option.setAttribute('aria-selected', String(i === index)); });
    if (index >= 0) {
      input.setAttribute('aria-activedescendant', all[index].id);
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  }

  function show(open) {
    open = open && options().length > 0;
    list.hidden = !open;
    input.setAttribute('aria-expanded', String(open));
    if (!open) { choose(-1); }
  }

  box.addEventListener('htmx:afterSettle', function () { choose(-1); show(document.activeElement === input); });
  input.addEventListener('focus', function () { show(true); });
  document.addEventListener('click', function (event) { if (!box.contains(event.target)) { show(false); } });
  box.addEventListener('focusout', function (event) {
    if (event.relatedTarget && !box.contains(event.relatedTarget)) { show(false); }
  });

  input.addEventListener('keydown', function (event) {
    var count = options().length;
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && count > 0) {
      event.preventDefault();
      show(true);
      var step = event.key === 'ArrowDown' ? 1 : -1;
      choose(active < 0 ? (step > 0 ? 0 : count - 1) : (active + step + count) % count);
    } else if (event.key === 'Enter' && active >= 0 && !list.hidden) {
      event.preventDefault();
      options()[active].click();
    } else if (event.key === 'Escape' && !list.hidden) {
      event.preventDefault();
      show(false);
    }
  });
});
