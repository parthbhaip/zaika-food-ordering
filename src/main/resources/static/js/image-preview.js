// Preview a chosen photo before it is saved: <input type="file" data-image-preview="imgId"> shows the file in
// <img id="imgId"> and unhides <... id="imgId-note">. Without JavaScript the page shows the saved photo only.
// The preview is a data: URL (CSP img-src allows 'self' and data:).
document.querySelectorAll('input[type="file"][data-image-preview]').forEach(function (input) {
  var img = document.getElementById(input.dataset.imagePreview);
  if (!img) {
    return;
  }
  var note = document.getElementById(img.id + '-note');
  var saved = img.src;
  input.addEventListener('change', function () {
    var file = input.files && input.files[0];
    var usable = file && /^image\/(jpeg|png|webp)$/.test(file.type);
    if (note) {
      note.hidden = !usable;
    }
    if (!usable) {
      img.src = saved;
      return;
    }
    var reader = new FileReader();
    reader.addEventListener('load', function () {
      img.src = reader.result;
    });
    reader.readAsDataURL(file);
  });
});
