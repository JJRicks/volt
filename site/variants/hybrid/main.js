/* Volt homepage, hybrid variant. No dependencies and no network requests. */
(() => {
  'use strict';
  // Marks JavaScript as available before first paint; nothing on the page is hidden without it.
  document.documentElement.classList.add('js');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const activity = (() => {
    const listeners = new Set();
    let shown = true;
    const active = () => shown && !document.hidden && document.hasFocus();
    const update = () => listeners.forEach(listener => listener());
    document.addEventListener('visibilitychange', update);
    window.addEventListener('focus', update);
    window.addEventListener('blur', update);
    window.addEventListener('pagehide', () => { shown = false; update(); });
    window.addEventListener('pageshow', () => { shown = true; update(); });
    return { active, listen: listener => listeners.add(listener) };
  })();

  const init = () => { nav(); currentSection(); lockBar(); route(); field(); openLinked(); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();

  function nav() {
    const toggle = document.getElementById('nav-toggle');
    const links = document.getElementById('nav-links');
    if (!toggle || !links) return;
    const setOpen = open => { toggle.setAttribute('aria-expanded', String(open)); links.classList.toggle('open', open); };
    toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
    links.addEventListener('click', event => { if (event.target.closest('a')) setOpen(false); });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && links.classList.contains('open')) { setOpen(false); toggle.focus(); }
    });
  }

  // The app's selected-tab dot: a 5px red dot under the section in view, gliding between links.
  function currentSection() {
    const links = document.getElementById('nav-links');
    const dot = document.getElementById('nav-dot');
    if (!links || !dot || !('IntersectionObserver' in window)) return;
    const anchors = [...links.querySelectorAll('a[href^="#"]')];
    const sections = anchors.map(a => document.getElementById(a.getAttribute('href').slice(1))).filter(Boolean);
    const visible = new Map();
    const place = () => {
      let best = null;
      sections.forEach(section => { if (visible.get(section) && (!best || visible.get(section) > visible.get(best))) best = section; });
      anchors.forEach(a => {
        const on = best && a.getAttribute('href') === '#' + best.id;
        a.classList.toggle('is-current', Boolean(on));
        if (on) a.setAttribute('aria-current', 'location'); else a.removeAttribute('aria-current');
        if (on) dot.style.setProperty('--dot-x', `${(a.offsetLeft + a.offsetWidth / 2 - 2.5).toFixed(1)}px`);
      });
      dot.classList.toggle('is-on', Boolean(best));
    };
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => visible.set(entry.target, entry.isIntersecting ? entry.intersectionRatio : 0));
      place();
    }, { rootMargin: '-35% 0px -45% 0px', threshold: [0, 0.25, 0.5, 0.75, 1] });
    sections.forEach(section => observer.observe(section));
    window.addEventListener('resize', place, { passive: true });
  }

  // A working demo of the app's Lock bar: slide or tap to lock, slide back to unlock. It sends nothing anywhere.
  function lockBar() {
    const bar = document.getElementById('lockbar');
    const thumb = document.getElementById('lock-thumb');
    const label = document.getElementById('lock-label');
    if (!bar || !thumb || !label) return;
    bar.removeAttribute('aria-hidden');
    bar.setAttribute('role', 'switch');
    bar.setAttribute('tabindex', '0');
    bar.setAttribute('aria-label', 'Lock bar demo');
    let locked = false;
    const travel = () => Math.max(0, bar.clientWidth - thumb.offsetWidth - 10);
    const measure = () => bar.style.setProperty('--travel', `${travel()}px`);
    const set = value => {
      locked = value;
      bar.classList.toggle('is-locked', locked);
      bar.setAttribute('aria-checked', String(locked));
      label.textContent = locked ? 'Locked' : 'Unlocked';
    };
    measure();
    set(false);
    window.addEventListener('resize', measure, { passive: true });

    let drag = null;
    bar.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      measure();
      drag = { x: event.clientX, moved: false, id: event.pointerId };
      bar.setPointerCapture(event.pointerId);
    });
    bar.addEventListener('pointermove', event => {
      if (!drag || event.pointerId !== drag.id) return;
      const dx = event.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) < 4) return;
      drag.moved = true;
      bar.classList.add('is-dragging');
      const max = travel();
      const at = Math.min(max, Math.max(0, (locked ? max : 0) + dx));
      drag.at = at / (max || 1);
      thumb.style.transform = `translateX(${at}px)`;
    });
    const end = event => {
      if (!drag || event.pointerId !== drag.id) return;
      const { moved, at } = drag;
      drag = null;
      bar.classList.remove('is-dragging');
      thumb.style.removeProperty('transform');
      if (!moved) set(!locked);
      else if (locked ? at < 0.5 : at > 0.5) set(!locked);
    };
    bar.addEventListener('pointerup', end);
    bar.addEventListener('pointercancel', end);
    bar.addEventListener('keydown', event => {
      if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); set(!locked); }
      else if (event.key === 'ArrowRight' && !locked) set(true);
      else if (event.key === 'ArrowLeft' && locked) set(false);
    });
  }

  // The ride map demo: a dot travels the sample route while the readings follow it.
  function route() {
    const card = document.querySelector('.route-card');
    const path = document.getElementById('route-path');
    const dot = document.getElementById('route-dot');
    const power = document.getElementById('ro-power');
    const speed = document.getElementById('ro-speed');
    const grade = document.getElementById('ro-grade');
    if (!card || !path || !dot || !power || !speed || !grade || typeof path.getTotalLength !== 'function') return;
    const total = path.getTotalLength();
    let readoutAt = -Infinity;
    const place = (fraction, now, force = false) => {
      const point = path.getPointAtLength(fraction * total);
      dot.setAttribute('cx', point.x.toFixed(1));
      dot.setAttribute('cy', point.y.toFixed(1));
      // The dot stays smooth; readable numbers need only a few updates per second.
      if (!force && now - readoutAt < 160) return;
      readoutAt = now;
      const ahead = path.getPointAtLength(Math.min(total, fraction * total + 6));
      const slope = Math.max(-12, Math.min(12, ((point.y - ahead.y) / 6) * 9));
      const watts = Math.round(Math.max(60, 380 + slope * 95 + Math.sin(fraction * 18) * 60));
      power.textContent = watts.toLocaleString('en-US');
      speed.textContent = (16.4 - slope * 0.35 + Math.cos(fraction * 11) * 0.8).toFixed(1);
      grade.textContent = `${slope >= 0 ? '+' : ''}${slope.toFixed(0)}`;
    };
    let frameId = 0, last = 0, elapsed = 0, inView = !('IntersectionObserver' in window);
    const eligible = () => activity.active() && inView && !reduceMotion.matches;
    const loop = now => {
      frameId = 0;
      if (!eligible()) { last = 0; return; }
      if (last) elapsed += now - last;
      last = now;
      const cycle = (elapsed / 10000) % 2;
      place(cycle < 1 ? cycle : 2 - cycle, now);
      frameId = requestAnimationFrame(loop);
    };
    const update = () => {
      if (!eligible()) {
        cancelAnimationFrame(frameId); frameId = 0; last = 0;
        if (reduceMotion.matches) place(0.62, performance.now(), true);
      } else if (!frameId) frameId = requestAnimationFrame(loop);
    };
    activity.listen(update);
    reduceMotion.addEventListener('change', update);
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(entries => { inView = entries.some(entry => entry.isIntersecting); update(); }).observe(card);
    }
    place(0.62, performance.now(), true);
    update();
  }

  // Opens an FAQ answer when a link (or the address bar) points at it. Without JavaScript the link still scrolls to it.
  function openLinked() {
    const open = hash => {
      let id = null;
      try { id = hash.length > 1 ? decodeURIComponent(hash.slice(1)) : null; } catch (error) { return; }
      const target = id ? document.getElementById(id) : null;
      if (target && target.tagName === 'DETAILS') target.open = true;
    };
    open(location.hash);
    window.addEventListener('hashchange', () => open(location.hash));
    document.addEventListener('click', event => {
      const link = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null;
      if (link) open(link.getAttribute('href'));
    });
  }

  /* The charged-particle field with lightning arcs, kept from today's page and calmed to the app's palette:
     the same density and links in Volt red, slower drift, slightly rarer arcs. Capped at 60 fps and paused when
     the page is hidden, unfocused or motion is reduced (a still frame stays). */
  function field() {
    const canvas = document.getElementById('field');
    const context = canvas && canvas.getContext ? canvas.getContext('2d') : null;
    if (!context) return;
    let width = 0, height = 0, ratio = 1, particles = [], arcs = [], frameId = 0, last = 0, nextArc = 0;
    let frameDue = 0, pausedAt = 0;
    const frameInterval = 1000 / 60;
    const eligible = () => activity.active() && !reduceMotion.matches;
    const pointer = { x: -1e4, y: -1e4, seen: 0 };
    const sprite = document.createElement('canvas');
    sprite.width = sprite.height = 64;
    const spriteContext = sprite.getContext('2d');
    const glow = spriteContext.createRadialGradient(32, 32, 0, 32, 32, 32);
    glow.addColorStop(0, 'rgba(255, 214, 208, 1)');
    glow.addColorStop(0.2, 'rgba(227, 43, 47, .85)');
    glow.addColorStop(0.55, 'rgba(227, 43, 47, .2)');
    glow.addColorStop(1, 'rgba(227, 43, 47, 0)');
    spriteContext.fillStyle = glow;
    spriteContext.fillRect(0, 0, 64, 64);

    const spawn = anywhere => ({
      x: Math.random() * width,
      y: anywhere ? Math.random() * height : height + 12,
      vx: (Math.random() - 0.5) * 0.08,
      vy: -(0.05 + Math.random() * 0.18),
      size: 5 + Math.random() * 10,
      alpha: 0.3 + Math.random() * 0.5,
      phase: Math.random() * Math.PI * 2,
    });
    const resize = () => {
      ratio = Math.min(window.devicePixelRatio || 1, 1.75);
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      const count = Math.round(Math.min(88, Math.max(34, (width * height) / 16000)));
      particles = Array.from({ length: count }, () => spawn(true));
    };
    const bolt = (x1, y1, x2, y2) => {
      let points = [[x1, y1], [x2, y2]];
      let spread = Math.hypot(x2 - x1, y2 - y1) * 0.3;
      for (let depth = 0; depth < 6; depth++) {
        const next = [points[0]];
        for (let i = 1; i < points.length; i++) {
          const [ax, ay] = points[i - 1], [bx, by] = points[i];
          const normalX = -(by - ay), normalY = bx - ax, length = Math.hypot(normalX, normalY) || 1;
          const offset = (Math.random() - 0.5) * spread;
          next.push([(ax + bx) / 2 + (normalX / length) * offset, (ay + by) / 2 + (normalY / length) * offset], [bx, by]);
        }
        points = next;
        spread *= 0.55;
      }
      return points;
    };
    const strike = (from, to, life = 340) => {
      const path = new Path2D();
      bolt(from.x, from.y, to.x, to.y).forEach(([x, y], index) => (index ? path.lineTo(x, y) : path.moveTo(x, y)));
      arcs.push({ path, born: performance.now(), life });
    };
    const nearest = (x, y, limit) => particles
      .map(particle => ({ particle, distance: Math.hypot(particle.x - x, particle.y - y) }))
      .filter(entry => entry.distance < limit)
      .sort((a, b) => a.distance - b.distance);

    const draw = (now, still = false) => {
      frameId = 0;
      if (!still && !reduceMotion.matches && !activity.active()) return;
      if (still && pausedAt) now = pausedAt;
      if (!still && !reduceMotion.matches && frameDue && now + 0.2 < frameDue) {
        frameId = requestAnimationFrame(draw); return;
      }
      frameDue = now + frameInterval - (frameDue ? Math.max(0, now - frameDue) % frameInterval : 0);
      const delta = still ? 0 : Math.min(48, now - (last || now));
      last = now;
      context.clearRect(0, 0, width, height);
      context.globalCompositeOperation = 'lighter';
      const pointerFresh = now - pointer.seen < 2500;
      for (const particle of particles) {
        particle.phase += delta * 0.0009;
        particle.x += (particle.vx + Math.sin(particle.phase) * 0.04) * delta * 0.06;
        particle.y += particle.vy * delta * 0.06;
        if (pointerFresh) {
          const dx = pointer.x - particle.x, dy = pointer.y - particle.y, distance = Math.hypot(dx, dy);
          if (distance < 160 && distance > 1) { particle.x += (dx / distance) * 0.2 * delta * 0.06; particle.y += (dy / distance) * 0.2 * delta * 0.06; }
        }
        if (particle.y < -20 || particle.x < -30 || particle.x > width + 30) Object.assign(particle, spawn(false));
      }
      context.lineWidth = 1;
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const a = particles[i], b = particles[j], dx = a.x - b.x, dy = a.y - b.y;
          const squared = dx * dx + dy * dy;
          if (squared > 14400) continue;
          const distance = Math.sqrt(squared);
          context.strokeStyle = `rgba(227, 43, 47, ${((1 - distance / 120) * 0.22).toFixed(3)})`;
          context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); context.stroke();
        }
      }
      for (const particle of particles) {
        context.globalAlpha = particle.alpha * (0.75 + Math.sin(particle.phase * 2) * 0.25);
        context.drawImage(sprite, particle.x - particle.size / 2, particle.y - particle.size / 2, particle.size, particle.size);
      }
      context.globalAlpha = 1;
      if (!still && !reduceMotion.matches && now > nextArc && particles.length > 1) {
        nextArc = now + 1800 + Math.random() * 2600;
        const origin = pointerFresh ? { x: pointer.x, y: pointer.y } : particles[Math.floor(Math.random() * particles.length)];
        const targets = nearest(origin.x, origin.y, 280).filter(entry => entry.distance > 40);
        if (targets.length) strike(origin, targets[Math.floor(Math.random() * Math.min(3, targets.length))].particle);
      }
      arcs = arcs.filter(arc => now - arc.born < arc.life);
      for (const arc of arcs) {
        const fade = 1 - (now - arc.born) / arc.life;
        context.shadowColor = 'rgba(227, 43, 47, .8)';
        context.shadowBlur = 14;
        context.strokeStyle = `rgba(227, 43, 47, ${(0.5 * fade).toFixed(3)})`;
        context.lineWidth = 4;
        context.stroke(arc.path);
        context.shadowBlur = 4;
        context.strokeStyle = `rgba(244, 245, 240, ${(0.9 * fade).toFixed(3)})`;
        context.lineWidth = 1.1;
        context.stroke(arc.path);
        context.shadowBlur = 0;
      }
      context.globalCompositeOperation = 'source-over';
      if (eligible()) frameId = requestAnimationFrame(draw);
    };

    resize();
    window.addEventListener('resize', () => {
      resize();
      if (!eligible()) { last = 0; draw(performance.now(), true); }
    }, { passive: true });
    window.addEventListener('pointermove', event => { pointer.x = event.clientX; pointer.y = event.clientY; pointer.seen = performance.now(); }, { passive: true });
    const update = () => {
      const now = performance.now();
      if (!eligible()) {
        cancelAnimationFrame(frameId); frameId = 0; last = 0; frameDue = 0;
        if (!pausedAt) pausedAt = now;
        pointer.seen = -Infinity;
        if (reduceMotion.matches) arcs = [];
        if (!document.hidden) draw(now, true);
      } else if (!frameId) {
        if (pausedAt) {
          const gap = now - pausedAt;
          arcs.forEach(arc => { arc.born += gap; }); nextArc += gap;
        }
        pausedAt = 0; last = 0; frameDue = 0;
        frameId = requestAnimationFrame(draw);
      }
    };
    activity.listen(update);
    reduceMotion.addEventListener('change', update);
    update();
  }
})();
