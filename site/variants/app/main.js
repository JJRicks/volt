// Volt homepage, "app" variant. No network, no loops: a night-vision toggle, the sample Lock bar and the
// phone dock's position dot. Without JavaScript the page reads the same; the moon button stays hidden.
'use strict';
document.documentElement.classList.add('js');

document.addEventListener('DOMContentLoaded', function () {
  var root = document.documentElement;

  // Night vision: the app's red-on-black ColorMatrix, as tokens (styles.css html.nv) plus an SVG filter on images.
  var moon = document.getElementById('nv-toggle');
  if (moon) {
    moon.addEventListener('click', function () {
      var on = !root.classList.contains('nv');
      root.classList.toggle('nv', on);
      moon.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }

  // Sample Lock bar: drag the thumb (or press it) to lock; release past halfway snaps over in 180 ms.
  var lock = document.getElementById('lock');
  var thumb = document.getElementById('lock-thumb');
  if (lock && thumb) {
    var INSET = 5;
    var travel = function () { return lock.clientWidth - thumb.offsetWidth - INSET * 2; };
    var locked = function () { return lock.getAttribute('data-state') === 'locked'; };
    var place = function (x) { thumb.style.transform = 'translateX(' + x + 'px)'; };
    var set = function (isLocked) {
      lock.setAttribute('data-state', isLocked ? 'locked' : 'unlocked');
      thumb.setAttribute('aria-pressed', isLocked ? 'true' : 'false');
      place(isLocked ? travel() : 0);
    };
    var startX = 0, base = 0, moved = false, dragging = false;
    thumb.addEventListener('pointerdown', function (e) {
      dragging = true; moved = false; startX = e.clientX; base = locked() ? travel() : 0;
      lock.classList.add('dragging');
      thumb.setPointerCapture(e.pointerId);
    });
    thumb.addEventListener('pointermove', function (e) {
      if (!dragging) return;
      var dx = e.clientX - startX;
      if (Math.abs(dx) > 4) moved = true;
      place(Math.max(0, Math.min(travel(), base + dx)));
    });
    var end = function (e) {
      if (!dragging) return;
      dragging = false;
      lock.classList.remove('dragging');
      if (!moved) return; // a plain press is handled by click
      var x = Math.max(0, Math.min(travel(), base + (e.clientX - startX)));
      set(x > travel() / 2);
    };
    thumb.addEventListener('pointerup', end);
    thumb.addEventListener('pointercancel', function () { dragging = false; lock.classList.remove('dragging'); set(locked()); });
    thumb.addEventListener('click', function (e) {
      if (moved) { moved = false; e.preventDefault(); return; }
      set(!locked());
    });
    window.addEventListener('resize', function () { place(locked() ? travel() : 0); });
  }

  // Phone dock: a 5px dot under the section you're in, gliding over 260 ms (Ui.floatingNav).
  var dock = document.querySelector('.dock');
  var dot = document.querySelector('.dock-dot');
  // The dock joins once the riding instrument's controls scroll away, so it never covers the first readout.
  var controls = document.querySelector('.ride-controls');
  if (dock && controls) {
    var pending = false;
    var check = function () { pending = false; dock.classList.toggle('show', controls.getBoundingClientRect().bottom < 0); };
    window.addEventListener('scroll', function () { if (!pending) { pending = true; requestAnimationFrame(check); } }, { passive: true });
    check();
  } else if (dock) {
    dock.classList.add('show');
  }
  if (dock && dot && 'IntersectionObserver' in window) {
    var links = {};
    dock.querySelectorAll('a[data-spy]').forEach(function (a) { links[a.getAttribute('data-spy')] = a; });
    var map = { top: 'top', features: 'features', rides: 'features', screens: 'features', models: 'features', setup: 'setup', privacy: 'setup', faq: 'faq', get: 'faq' };
    var show = function (key) {
      var a = links[key];
      Object.keys(links).forEach(function (k) { links[k].classList.toggle('on', k === key); });
      if (!a) return;
      var x = a.offsetLeft + a.offsetWidth / 2 - 2.5;
      dot.style.transform = 'translateX(' + x + 'px)';
      dot.classList.add('on');
    };
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) { if (en.isIntersecting) show(map[en.target.id]); });
    }, { rootMargin: '-45% 0px -50% 0px' });
    Object.keys(map).forEach(function (id) { var el = document.getElementById(id); if (el) io.observe(el); });
  }
});
