// Show/hide buttons for password fields: <button data-password-toggle="inputId" hidden>. The button stays hidden
// without JavaScript, so the field is a plain password input then.
document.querySelectorAll('[data-password-toggle]').forEach(function (button) {
  var input = document.getElementById(button.dataset.passwordToggle);
  var label = button.querySelector('[data-password-toggle-label]');
  if (!input || !label) {
    return;
  }
  button.hidden = false;
  button.addEventListener('click', function () {
    var show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    label.textContent = show ? 'Hide' : 'Show';
  });
});
