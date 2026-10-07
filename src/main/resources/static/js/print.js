// "Print" buttons (data-print, rendered hidden): shown only when JavaScript runs, they open the browser's print dialog.
// Without JavaScript the browser's own Print command prints the same page (see the print styles in app.css).
document.querySelectorAll('[data-print]').forEach(function (button) {
	button.hidden = false;
	button.addEventListener('click', function () {
		window.print();
	});
});
