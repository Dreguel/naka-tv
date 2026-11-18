/* Naka TV – navigation télécommande pour WebView (injecté par l'appli) */
(function () {
  'use strict';
  if (window.__ntv) return;
  var W = window, D = document;
  var NAT = W.NakaTVNative || null;

  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,iframe,video[controls],' +
    '[tabindex]:not([tabindex="-1"]),[onclick],[role=button],[role=link],[role=tab],[role=menuitem],' +
    '[role=option],[role=checkbox],[role=switch],[role=slider],[contenteditable=""],[contenteditable=true],label[for]';
  var LOOSE = 'div,li,span,img,article,figure,section,p,h1,h2,h3,h4,h5,h6,svg,td,tr';
  var FOCUSABLE = 'a[href],button,input,select,textarea,summary,iframe,[tabindex],[contenteditable]';

  var cache = [], dirty = true, builtAt = 0, cur = null, cursorMode = false;
  var ringEl = null, lastIframe = false, lastBackModal = null, rafPending = false;

  function connected(el) { return !!el && (el.isConnected !== undefined ? el.isConnected : D.documentElement.contains(el)); }
  function matches(el, s) { try { return (el.matches || el.webkitMatchesSelector).call(el, s); } catch (e) { return false; } }

  function isTextInput(el) {
    if (!el || !el.tagName) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    return ['text', 'search', 'email', 'url', 'tel', 'password', 'number'].indexOf(t) >= 0;
  }

  function visibleStyle(el) {
    if (el.checkVisibility) {
      try { return el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, opacityProperty: true, visibilityProperty: true }); } catch (e) {}
    }
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
  }

  function addStyle() {
    if (D.getElementById('__ntv_css')) return;
    var s = D.createElement('style');
    s.id = '__ntv_css';
    s.textContent = '[data-ntv]:focus{outline:none!important}*{-webkit-tap-highlight-color:transparent!important}';
    (D.head || D.documentElement).appendChild(s);
  }

  /* ---------- liste des éléments cliquables ---------- */
  function build() {
    var seen = [], list = [], i, el;
    var exp = D.querySelectorAll(SEL);
    for (i = 0; i < exp.length; i++) { list.push(exp[i]); exp[i].__ntvC = 1; seen.push(exp[i]); }
    var loose = D.querySelectorAll(LOOSE);
    if (loose.length < 4000) {
      for (i = 0; i < loose.length; i++) {
        el = loose[i];
        if (el.__ntvC) continue;
        var cs;
        try { cs = getComputedStyle(el); } catch (e) { continue; }
        if (cs.cursor !== 'pointer') continue;
        var p = el.parentElement;
        if (p && getComputedStyle(p).cursor === 'pointer') continue; // garder le plus externe
        if (p && p.closest && p.closest(SEL)) continue;
        list.push(el);
      }
    }
    for (i = 0; i < seen.length; i++) seen[i].__ntvC = 0;
    cache = list; dirty = false; builtAt = Date.now();
  }

  function findModal() {
    var ms = D.querySelectorAll('dialog[open],[aria-modal="true"]');
    for (var i = ms.length - 1; i >= 0; i--) {
      var r = ms[i].getBoundingClientRect();
      if (r.width > 20 && r.height > 20 && visibleStyle(ms[i])) return ms[i];
    }
    return null;
  }

  function hitOk(el, x, y) {
    var h = D.elementFromPoint(x, y);
    return !h || h === el || el.contains(h) || h.contains(el);
  }

  function occluded(el, r, vw, vh) {
    var x = Math.min(Math.max((r.left + r.right) / 2, 1), vw - 2);
    var y = Math.min(Math.max((r.top + r.bottom) / 2, 1), vh - 2);
    if (hitOk(el, x, y)) return false;
    x = Math.min(Math.max(r.left + Math.min(12, r.width / 2), 1), vw - 2);
    y = Math.min(Math.max(r.top + Math.min(12, r.height / 2), 1), vh - 2);
    return !hitOk(el, x, y);
  }

  function clipped(el, r) {
    var p = el.parentElement, n = 0;
    while (p && p !== D.body && p !== D.documentElement && n < 12) {
      var cs = getComputedStyle(p);
      if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
        if (!/(auto|scroll)/.test(cs.overflowX + ' ' + cs.overflowY)) {
          var pr = p.getBoundingClientRect();
          if (r.right <= pr.left + 1 || r.left >= pr.right - 1 || r.bottom <= pr.top + 1 || r.top >= pr.bottom - 1) return true;
        }
      }
      p = p.parentElement; n++;
    }
    return false;
  }

  function collect(useModal) {
    if (dirty || Date.now() - builtAt > 2500) build();
    var vw = W.innerWidth, vh = W.innerHeight, out = [];
    var modal = useModal ? findModal() : null;
    for (var i = 0; i < cache.length; i++) {
      var el = cache[i];
      if (!connected(el)) continue;
      if (modal && !modal.contains(el)) continue;
      if (el.disabled) continue;
      if (el.closest && el.closest('[aria-hidden="true"],[inert]')) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) continue;
      if (r.bottom < -vh * 1.5 || r.top > vh * 2.5 || r.right < -vw || r.left > vw * 2) continue;
      if (!visibleStyle(el)) continue;
      var inView = r.top >= 0 && r.left >= 0 && r.bottom <= vh && r.right <= vw;
      var partly = r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw;
      if (partly && occluded(el, r, vw, vh)) continue;
      if (!inView && clipped(el, r)) continue;
      out.push({ el: el, r: r });
    }
    if (!out.length && modal) return collect(false);
    return out;
  }

  /* ---------- choix du prochain élément ---------- */
  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1, b1) - Math.min(a2, b2)); }

  function pick(dir, list, from) {
    var best = null, bs = Infinity;
    var fcx = (from.left + from.right) / 2, fcy = (from.top + from.bottom) / 2;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], r = c.r;
      if (c.el === cur || c.el.contains(cur) || cur.contains(c.el)) continue;
      var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2, p, o, s;
      if (dir === 'right') {
        if (cx <= fcx + 1 || r.left <= from.left + 1) continue;
        p = Math.max(0, r.left - from.right); o = gap(r.top, r.bottom, from.top, from.bottom);
        if (o > Math.min(r.height, from.height) * 0.5) continue;
        s = p + o * 3 + Math.abs(cy - fcy) * 0.05;
      } else if (dir === 'left') {
        if (cx >= fcx - 1 || r.right >= from.right - 1) continue;
        p = Math.max(0, from.left - r.right); o = gap(r.top, r.bottom, from.top, from.bottom);
        if (o > Math.min(r.height, from.height) * 0.5) continue;
        s = p + o * 3 + Math.abs(cy - fcy) * 0.05;
      } else if (dir === 'down') {
        if (cy <= fcy + 1 || r.top <= from.top + 1) continue;
        p = Math.max(0, r.top - from.bottom); o = gap(r.left, r.right, from.left, from.right);
        s = p + o * 3 + Math.abs(cx - fcx) * 0.05;
      } else {
        if (cy >= fcy - 1 || r.bottom >= from.bottom - 1) continue;
        p = Math.max(0, from.top - r.bottom); o = gap(r.left, r.right, from.left, from.right);
        s = p + o * 3 + Math.abs(cx - fcx) * 0.05;
      }
      if (s < bs) { bs = s; best = c; }
    }
    return best;
  }

  function initial(list) {
    var vh = W.innerHeight, best = null, bs = Infinity;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].r;
      if (r.bottom <= 0 || r.top >= vh) continue;
      var s = Math.max(r.top, 0) + r.left * 0.3;
      if (s < bs) { bs = s; best = list[i]; }
    }
    return best || list[0] || null;
  }

  function inViewport(el) {
    var r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < W.innerHeight && r.right > 0 && r.left < W.innerWidth;
  }

  function move(dir) {
    if (!D.body) return false;
    var list = collect(true), vh = W.innerHeight;
    if (cur && (!connected(cur) || !inViewport(cur))) cur = null;
    var next;
    if (!cur) {
      next = initial(list);
    } else {
      var from = cur.getBoundingClientRect();
      next = pick(dir, list, from);
      // gros bloc de texte sans lien : on fait défiler d'abord
      if (next && dir === 'down' && next.r.top > vh && next.r.top - from.bottom > vh * 0.5) { W.scrollBy(0, vh * 0.45); ring(false); return true; }
      if (next && dir === 'up' && next.r.bottom < 0 && from.top - next.r.bottom > vh * 0.5) { W.scrollBy(0, -vh * 0.45); ring(false); return true; }
    }
    if (next) { focusEl(next.el); return true; }
    if (dir === 'down') W.scrollBy(0, vh * 0.45);
    else if (dir === 'up') W.scrollBy(0, -vh * 0.45);
    ring(false);
    return false;
  }

  function focusEl(el) {
    cur = el;
    if (el.tagName === 'IFRAME') {
      // ne jamais donner le focus à une iframe (sinon la télécommande reste bloquée dedans)
      try { if (D.activeElement && D.activeElement !== D.body) D.activeElement.blur(); } catch (e) {}
    } else {
      try {
        if (!matches(el, FOCUSABLE)) { el.setAttribute('tabindex', '-1'); el.setAttribute('data-ntv', ''); }
        el.focus({ preventScroll: true });
      } catch (e) { try { el.focus(); } catch (e2) {} }
      cur = el;
    }
    ensureVisible(el);
    ring(true);
  }

  function ensureVisible(el) {
    var r = el.getBoundingClientRect(), vh = W.innerHeight, vw = W.innerWidth, m = Math.min(80, vh * 0.1);
    if (r.top >= m && r.bottom <= vh - m && r.left >= 0 && r.right <= vw) return;
    try { el.scrollIntoView({ block: r.height > vh - 2 * m ? 'start' : 'center', inline: 'nearest' }); }
    catch (e) { try { el.scrollIntoView(r.top < 0); } catch (e2) {} }
  }

  /* ---------- cadre de sélection ---------- */
  function ring(animate) {
    if (!D.documentElement) return;
    if (!ringEl) {
      ringEl = D.createElement('div');
      ringEl.id = '__ntv_ring';
      ringEl.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;box-sizing:border-box;' +
        'border:4px solid #FFC400;border-radius:10px;display:none;left:0;top:0;width:0;height:0;' +
        'box-shadow:0 0 0 3px rgba(0,0,0,.6),0 0 26px rgba(255,196,0,.75);';
    }
    if (!connected(ringEl)) D.documentElement.appendChild(ringEl);
    if (cur && !connected(cur)) cur = null;
    if (!cur || cursorMode) { ringEl.style.display = 'none'; return; }
    var r = cur.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) { ringEl.style.display = 'none'; return; }
    var pad = 5;
    ringEl.style.transition = animate ? 'left .12s ease-out,top .12s ease-out,width .12s ease-out,height .12s ease-out' : 'none';
    ringEl.style.left = (r.left - pad) + 'px';
    ringEl.style.top = (r.top - pad) + 'px';
    ringEl.style.width = (r.width + pad * 2) + 'px';
    ringEl.style.height = (r.height + pad * 2) + 'px';
    ringEl.style.display = 'block';
  }
  function schedule() {
    if (rafPending) return;
    rafPending = true;
    (W.requestAnimationFrame || setTimeout)(function () { rafPending = false; ring(false); });
  }

  /* ---------- clic ---------- */
  function synthClick(el) {
    var r = el.getBoundingClientRect();
    var o = { bubbles: true, cancelable: true, view: W, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
    try { el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ pointerType: 'mouse', isPrimary: true }, o))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', Object.assign({ pointerType: 'mouse', isPrimary: true }, o))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
    el.click();
  }

  function activate(t) {
    var r = t.getBoundingClientRect(), vw = W.innerWidth, vh = W.innerHeight;
    var x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    if (x > 0 && y > 0 && x < vw && y < vh && NAT && NAT.tap) {
      var h = D.elementFromPoint(x, y);
      if (h && (h === t || t.contains(h))) {
        try { NAT.tap(x, y, vw); } catch (e) { synthClick(t); }
        if (t.tagName === 'IFRAME') setTimeout(notifyIframe, 300);
        return;
      }
    }
    if (t.tagName === 'IFRAME') return;
    synthClick(t);
  }

  function caretCanMove(a, dir) {
    try {
      var s = a.selectionStart, len = (a.value || '').length;
      if (s === null || s === undefined) return false;
      return dir === 'left' ? s > 0 : s < len;
    } catch (e) { return false; }
  }

  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Up: 'up', Down: 'down', Left: 'left', Right: 'right' };
  var CODES = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };

  function onKey(e) {
    if (e.defaultPrevented || cursorMode) return;
    var dir = DIRS[e.key] || CODES[e.keyCode];
    var a = D.activeElement;
    if (dir) {
      if (isTextInput(a) && (dir === 'left' || dir === 'right') && caretCanMove(a, dir)) return;
      if (a && a.tagName === 'INPUT' && a.type === 'range' && (dir === 'left' || dir === 'right')) return;
      e.preventDefault();
      move(dir);
      return;
    }
    if (e.key === 'Enter' || e.keyCode === 13) {
      if (e.repeat) { e.preventDefault(); return; }
      if (isTextInput(a)) {
        if (a.__ntvTyped) return; // déjà tapé du texte : on laisse valider
        e.preventDefault();
        activate(a);
        if (NAT && NAT.showKeyboard) try { NAT.showKeyboard(); } catch (err) {}
        return;
      }
      var t = (cur && connected(cur)) ? cur : (a && a !== D.body && a !== D.documentElement ? a : null);
      e.preventDefault();
      if (!t) { move('down'); return; }
      if (t.tagName === 'VIDEO') { if (t.paused) { var pr = t.play(); if (pr && pr.catch) pr.catch(function () {}); } else t.pause(); return; }
      activate(t);
    }
  }

  /* ---------- retour ---------- */
  function back() {
    try {
      var fe = D.fullscreenElement || D.webkitFullscreenElement;
      if (fe) { (D.exitFullscreen || D.webkitExitFullscreen).call(D); return true; }
    } catch (e) {}
    var modal = findModal();
    var t = D.activeElement || D.body;
    var prevented = false;
    try {
      var ev = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
      Object.defineProperty(ev, 'keyCode', { get: function () { return 27; } });
      Object.defineProperty(ev, 'which', { get: function () { return 27; } });
      prevented = !t.dispatchEvent(ev);
      var up = new KeyboardEvent('keyup', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
      t.dispatchEvent(up);
    } catch (e) {}
    if (prevented) { lastBackModal = null; return true; }
    if (modal) {
      if (modal === lastBackModal) { lastBackModal = null; return false; } // impossible à fermer : on recule
      lastBackModal = modal;
      try {
        var c = modal.querySelector('[aria-label*="close" i],[aria-label*="fermer" i],[data-dismiss],[data-bs-dismiss],.close,[class*="close"]');
        if (c) c.click();
      } catch (e) {}
      return true;
    }
    lastBackModal = null;
    return false;
  }

  /* ---------- vidéo ---------- */
  function allVideos() {
    var v = [].slice.call(D.querySelectorAll('video'));
    var fs = D.querySelectorAll('iframe');
    for (var i = 0; i < fs.length; i++) {
      try { var d = fs[i].contentDocument; if (d) v = v.concat([].slice.call(d.querySelectorAll('video'))); } catch (e) {}
    }
    return v;
  }
  function mainVideo() {
    var vs = allVideos(), best = null, bs = -Infinity, fe = D.fullscreenElement || D.webkitFullscreenElement;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect(), s = r.width * r.height;
      if (!v.paused) s += 1e9;
      if (fe && (fe === v || fe.contains(v))) s += 2e9;
      if (!v.currentSrc && v.readyState === 0) s -= 5e8;
      if (s > bs) { bs = s; best = v; }
    }
    return best;
  }
  function media(a, n) {
    if (a === 'pauseAll') { var all = allVideos(); for (var i = 0; i < all.length; i++) try { all[i].pause(); } catch (e) {} return { ok: true }; }
    var v = mainVideo();
    if (!v) return { ok: false };
    try {
      if (a === 'toggle') { if (v.paused || v.ended) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause(); }
      else if (a === 'play') { var p2 = v.play(); if (p2 && p2.catch) p2.catch(function () {}); }
      else if (a === 'pause') v.pause();
      else if (a === 'seek') {
        var d = v.duration;
        if (isFinite(d) && d > 0) v.currentTime = Math.min(Math.max(0, v.currentTime + n), d - 0.5);
        else if (v.seekable && v.seekable.length) {
          var st = v.seekable.start(0), en = v.seekable.end(v.seekable.length - 1);
          v.currentTime = Math.min(Math.max(v.currentTime + n, st), en);
        } else return { ok: false };
      }
    } catch (e) { return { ok: false }; }
    return { ok: true, paused: v.paused, t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0 };
  }

  /* ---------- mode pointeur ---------- */
  function scrollAt(x, y, dx, dy, viewW) {
    var k = viewW > 0 ? W.innerWidth / viewW : 1;
    var ax = dx * k, ay = dy * k;
    var el = D.elementFromPoint(x * k, y * k);
    while (el && el !== D.body && el !== D.documentElement) {
      var cs = getComputedStyle(el);
      var canY = ay && /(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 1;
      var canX = ax && /(auto|scroll)/.test(cs.overflowX) && el.scrollWidth > el.clientWidth + 1;
      if (canY || canX) {
        var bt = el.scrollTop, bl = el.scrollLeft;
        el.scrollTop += canY ? ay : 0; el.scrollLeft += canX ? ax : 0;
        if (el.scrollTop !== bt || el.scrollLeft !== bl) return true;
      }
      el = el.parentElement;
    }
    W.scrollBy(ax, ay);
    return true;
  }

  function setCursor(m) { cursorMode = !!m; ring(false); }

  /* ---------- iframes ---------- */
  function notifyIframe() {
    var f = !!(D.activeElement && D.activeElement.tagName === 'IFRAME');
    if (f !== lastIframe) {
      lastIframe = f;
      if (NAT && NAT.iframeFocus) try { NAT.iframeFocus(f); } catch (e) {}
    }
  }
  function escapeIframe(dir) {
    var a = D.activeElement;
    if (a && a.tagName === 'IFRAME') {
      cur = a;
      try { a.blur(); } catch (e) {}
      try { W.focus(); } catch (e) {}
    }
    notifyIframe();
    move(dir);
  }

  /* ---------- branchements ---------- */
  function init() {
    addStyle();
    W.addEventListener('keydown', onKey, false);
    D.addEventListener('focusin', function (e) {
      var t = e.target;
      if (!t || t === D.body || t === D.documentElement || t === W) return;
      if (isTextInput(t)) t.__ntvTyped = false;
      cur = t; ring(true);
    }, true);
    D.addEventListener('input', function (e) { if (e.target) e.target.__ntvTyped = true; }, true);
    D.addEventListener('scroll', schedule, true);
    W.addEventListener('resize', function () { dirty = true; schedule(); });
    W.addEventListener('blur', function () { setTimeout(notifyIframe, 0); });
    W.addEventListener('focus', function () { setTimeout(notifyIframe, 0); });
    setInterval(function () { notifyIframe(); if (cur) ring(false); }, 500);
    try {
      new MutationObserver(function () { dirty = true; }).observe(D.documentElement, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ['hidden', 'disabled', 'href', 'role', 'aria-hidden', 'aria-modal', 'open', 'inert']
      });
    } catch (e) {}
  }

  W.__ntv = { move: move, back: back, media: media, setCursor: setCursor, scrollAt: scrollAt, escapeIframe: escapeIframe };
  if (D.documentElement) init(); else D.addEventListener('DOMContentLoaded', init);
})();
