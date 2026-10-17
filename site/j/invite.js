(function () {
  var code = decodeURIComponent((location.hash || "").replace(/^#/, ""));
  var box = document.getElementById("code");
  var copy = document.getElementById("copy");
  var copied = document.getElementById("copied");

  if (!code) {
    box.textContent = "No invite code found in this link. Ask your friend to send it again.";
    return;
  }

  box.textContent = code;
  copy.hidden = false;
  copy.addEventListener("click", function () {
    var done = function () { copied.hidden = false; };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(code).then(done, function () { box.focus(); });
    } else {
      var range = document.createRange();
      range.selectNodeContents(box);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });
})();
