// Volt homepage, "clean" variant. No network. The sample Clean Home's top area follows a moment (the
// switch, a tap on a moment, or, on two panes, the moment the page pane is reading), the sample Lock bar
// slides, and the charging glow breathes only while it shows. Without JavaScript the Home rests on Parked.
'use strict';
document.documentElement.classList.add('js');

document.addEventListener('DOMContentLoaded', function () {
  var area = document.getElementById('top-area');
  var bar = document.getElementById('moment-switch');
  var texts = Array.prototype.slice.call(document.querySelectorAll('.m-text[data-moment]'));
  var buttons = bar ? Array.prototype.slice.call(bar.querySelectorAll('button[data-moment]')) : [];
  // Two panes with the Home held in view (styles.css: the sticky pane).
  var twoPanes = window.matchMedia('(min-width: 1024px) and (min-height: 760px)');

  // ----- Moments: one top area, one height; only what is in it changes (a 220 ms crossfade, in CSS). -----
  var current = 'parked';
  var show = function (moment) {
    if (!area || moment === current) return;
    current = moment;
    area.setAttribute('data-moment', moment);
    buttons.forEach(function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-moment') === moment ? 'true' : 'false'); });
    texts.forEach(function (t) { t.classList.toggle('on', t.getAttribute('data-moment') === moment); });
    breathe();
  };
  if (bar && area) {
    bar.hidden = false;
    buttons.forEach(function (b) { b.addEventListener('click', function () { show(b.getAttribute('data-moment')); }); });
  }
  texts.forEach(function (t) {
    if (t.getAttribute('data-moment') === current) t.classList.add('on');
    t.addEventListener('click', function () { show(t.getAttribute('data-moment')); });
  });
  // On two panes the Home follows the moment the reader is on: the one crossing the middle of the window.
  if (area && texts.length && 'IntersectionObserver' in window) {
    var reading = new IntersectionObserver(function (entries) {
      if (!twoPanes.matches) return;
      entries.forEach(function (e) { if (e.isIntersecting) show(e.target.getAttribute('data-moment')); });
    }, { rootMargin: '-45% 0px -50% 0px' });
    texts.forEach(function (t) { reading.observe(t); });
    // Back at the top, the Home is parked again.
    var hero = document.querySelector('.hero');
    if (hero) new IntersectionObserver(function (entries) {
      if (twoPanes.matches && entries[0].isIntersecting) show('parked');
    }, { rootMargin: '-45% 0px -50% 0px' }).observe(hero);
  }

  // ----- The charging glow: breathes while the charging view shows (CSS); paused in place, not reset, while the
  // Home is off screen or the page is hidden or unfocused, so it resumes mid-breath. Reduced motion: none (CSS). -----
  var inView = true, focused = document.hasFocus ? document.hasFocus() : true;
  function breathe() {
    if (!area) return;
    area.classList.toggle('paused', !(inView && focused && !document.hidden));
  }
  if (area && 'IntersectionObserver' in window) {
    new IntersectionObserver(function (entries) { inView = entries[0].isIntersecting; breathe(); }).observe(area);
  }
  document.addEventListener('visibilitychange', breathe);
  window.addEventListener('focus', function () { focused = true; breathe(); });
  window.addEventListener('blur', function () { focused = false; breathe(); });
  window.addEventListener('pagehide', function () { focused = false; breathe(); });

  // ----- Sample Lock bar: drag the thumb (or press it); past halfway it snaps over in 180 ms. -----
  var lock = document.getElementById('lock');
  var thumb = document.getElementById('lock-thumb');
  if (lock && thumb) {
    // Ships disabled, its hint hidden, so that without JavaScript it is a plain picture of the bar.
    thumb.disabled = false;
    var lockHint = document.getElementById('lock-hint');
    if (lockHint) lockHint.hidden = false;
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
    thumb.addEventListener('pointerup', function (e) {
      if (!dragging) return;
      dragging = false;
      lock.classList.remove('dragging');
      if (!moved) return; // a plain press is handled by click
      set(Math.max(0, Math.min(travel(), base + (e.clientX - startX))) > travel() / 2);
    });
    // A cancelled drag (a scroll took over) changes nothing: the thumb goes back where it was.
    thumb.addEventListener('pointercancel', function () {
      if (!dragging) return;
      dragging = false; lock.classList.remove('dragging'); place(locked() ? travel() : 0);
    });
    thumb.addEventListener('click', function (e) {
      if (moved) { moved = false; e.preventDefault(); return; }
      set(!locked());
    });
    window.addEventListener('resize', function () { place(locked() ? travel() : 0); });
  }
});
