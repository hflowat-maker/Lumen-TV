package app.lumen.forja

/** JavaScript injecté dans la page du lecteur officiel de Forja (chaînes brutes : aucun caractère dollar). */
object Scripts {

    /**
     * Pilotage du lecteur officiel :
     * - rapporte à l'app la position, la durée, la lecture et la présence d'une publicité ;
     * - signale la fin de l'épisode (épisode suivant) ;
     * - reprend à la position mémorisée une fois la vidéo prête (jamais pendant une publicité) ;
     * - __ftvCmd : lecture / pause / avance, refusés pendant une publicité.
     */
    const val PLAYER = """
(function(){
  if (window.__ftv) return; window.__ftv = 1;
  if (typeof window.__ftvStart !== 'number') window.__ftvStart = 0;
  var bornAt = Date.now();
  var unmuted = false, kicked = false, endedSent = false;
  function bridge(){ return window.ForjaTV; }
  function main(){
    var m = document.querySelector('video.eb-video');
    if (m) return m;
    var vs = Array.prototype.slice.call(document.querySelectorAll('video'));
    vs.sort(function(a, b){ return b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight; });
    return vs[0] || null;
  }
  function visible(el){
    if (!el || el.offsetWidth <= 0 || el.offsetHeight <= 0) return false;
    var st = getComputedStyle(el);
    return st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
  }
  function adActive(){
    var m = main();
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++){
      var v = vs[i];
      if (v !== m && !v.paused && !v.ended) return true;
    }
    var p = document.querySelector('.eb-player');
    if (p && /(^|\s|-)ads?(-|\s|\b)/.test(p.className)) return true;
    var c = document.querySelector('.eb-ads-container');
    if (visible(c)){
      var kids = c.querySelectorAll('*');
      for (var j = 0; j < kids.length; j++){ if (visible(kids[j])) return true; }
    }
    return false;
  }
  // Bandeau cookies de Forja : repéré pour que l'app propose « OK pour accepter » (jamais accepté tout seul).
  function cookieButton(){
    var els = document.querySelectorAll('button, a, [role="button"]');
    for (var i = 0; i < els.length; i++){
      var el = els[i];
      var t = (el.innerText || el.textContent || '').trim().toLowerCase();
      if (t !== 'accepter' && t !== 'accept' && t !== "j'accepte" && t !== 'قبول' && t !== 'موافق') continue;
      if (!visible(el)) continue;
      var box = el.parentElement, k = 0;
      while (box && k < 5){
        if (/cookie|كوكيز|ملفات تعريف/i.test(box.innerText || '')) return el;
        box = box.parentElement; k++;
      }
    }
    return null;
  }
  window.__ftvCookie = function(){ var c = cookieButton(); if (c){ c.click(); return 'ok'; } return 'none'; };

  function tick(){
    var m = main(); if (!m) return;
    var ad = adActive();
    var d = isFinite(m.duration) ? m.duration : 0;
    if (!ad && m.readyState >= 1 && d > 0){
      if (window.__ftvStart > 0){
        if (m.currentTime < window.__ftvStart - 3) { try { m.currentTime = Math.min(window.__ftvStart, d - 5); } catch(e){} }
        window.__ftvStart = 0;
      }
      if (!unmuted && !m.paused){ unmuted = true; if (m.muted) { m.muted = false; if (m.volume < 0.5) m.volume = 1; } }
      if (!kicked && m.paused && m.currentTime === 0 && m.readyState >= 2 && Date.now() - bornAt > 2000){
        kicked = true; var pr = m.play(); if (pr && pr.catch) pr.catch(function(){});
      }
    }
    if (!ad && m.ended && !endedSent){ endedSent = true; var b0 = bridge(); if (b0) { try { b0.ended(); } catch(e){} } }
    if (!m.ended) endedSent = false;
    var b = bridge();
    if (b){ try { b.state(m.currentTime || 0, d, !m.paused && !m.ended, ad, !!cookieButton()); } catch(e){} }
  }
  setInterval(tick, 500);
  document.addEventListener('ended', function(){ setTimeout(tick, 50); }, true);

  window.__ftvCmd = function(cmd, arg){
    var m = main();
    if (!m) return JSON.stringify({ ok: false, reason: 'none' });
    if (adActive()) return JSON.stringify({ ok: false, reason: 'ad' });
    if (cmd === 'toggle') cmd = (m.paused || m.ended) ? 'play' : 'pause';
    if (cmd === 'play'){ var p = m.play(); if (p && p.catch) p.catch(function(){}); }
    else if (cmd === 'pause'){ m.pause(); }
    else if (cmd === 'seek'){
      var dur = isFinite(m.duration) ? m.duration : 1e9;
      m.currentTime = Math.max(0, Math.min(dur - 1, m.currentTime + arg));
    }
    return JSON.stringify({ ok: true, cmd: cmd, t: m.currentTime || 0, d: isFinite(m.duration) ? m.duration : 0, paused: cmd === 'play' ? false : m.paused });
  };
})();
"""

    /**
     * Navigation aux flèches entre les boutons du lecteur officiel (sans pointeur) :
     * repère les boutons visibles, entoure celui qui est sélectionné d'un cadre doré
     * et renvoie sa position pour que l'app « appuie » dessus avec OK.
     */
    const val NAV = """
(function(){
  if (window.__ftvNav) return;
  var ring = null, cur = null;
  var SEL = 'button, a[href], [role="button"], [role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"], [role="option"], [role="tab"], [tabindex]';
  function vis(el){
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    if (r.left < -4 || r.top < -4 || r.right > window.innerWidth + 4 || r.bottom > window.innerHeight + 4) return false;
    for (var a = el; a && a !== document.documentElement; a = a.parentElement){
      var st = getComputedStyle(a);
      if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) < 0.05) return false;
    }
    // Pas caché derrière autre chose (ex. : pied de page du site sous la vidéo).
    var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return !!hit && (hit === el || el.contains(hit));
  }
  function label(el){
    return (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 40);
  }
  function bad(el){
    if (el.tagName === 'VIDEO' || el.tagName === 'HTML' || el.tagName === 'BODY') return true;
    var cls = typeof el.className === 'string' ? el.className : '';
    if (/picture|pip|live-sync/i.test(label(el) + ' ' + cls)) return true;
    var role = el.getAttribute('role');
    if (role === 'slider' || role === 'progressbar' || el.type === 'range') return true;
    // Liens vers d'autres pages (pied de page, conditions…) et affichage du temps : ignorés.
    if (el.tagName === 'A'){ var h = el.getAttribute('href') || ''; if (h && h.charAt(0) !== '#' && h.indexOf('javascript') !== 0) return true; }
    if (/^\d{1,2}:\d{2}/.test(label(el))) return true;
    if (el.getAttribute('tabindex') === '-1' && el.tagName !== 'BUTTON' && !role) return true;
    var r = el.getBoundingClientRect();
    return r.width * r.height > window.innerWidth * window.innerHeight * 0.3;
  }
  function inside(el, list){
    for (var a = el.parentElement; a; a = a.parentElement){ if (list.indexOf(a) >= 0) return true; }
    return false;
  }
  function candidates(){
    var t1 = [];
    var nodes = document.querySelectorAll(SEL);
    for (var i = 0; i < nodes.length; i++){ if (vis(nodes[i]) && !bad(nodes[i])) t1.push(nodes[i]); }
    t1 = t1.filter(function(el){ return !inside(el, t1); });
    var out = t1.map(function(el){ return { el: el, label: label(el) }; });
    var all = document.querySelectorAll('div, span, li, p, img');
    for (var j = 0; j < all.length; j++){
      var el = all[j];
      if (getComputedStyle(el).cursor !== 'pointer') continue;
      if (el.parentElement && getComputedStyle(el.parentElement).cursor === 'pointer') continue;
      if (!vis(el) || bad(el) || inside(el, t1) || el.querySelector(SEL)) continue;
      out.push({ el: el, label: label(el) });
    }
    // Publicité : le bouton « Ignorer » est dans le cadre de la régie, en bas à droite.
    var fr = document.querySelector('.eb-ads-container iframe, iframe[src*="imasdk"]');
    if (fr && vis(fr)){
      var R = fr.getBoundingClientRect();
      out.push({ el: null, label: 'Ignorer la publicité', r: { left: R.right - R.width * 0.2, top: R.bottom - R.height * 0.2, width: R.width * 0.16, height: R.height * 0.1 } });
    }
    return out;
  }
  function rectOf(t){ return t.el ? t.el.getBoundingClientRect() : t.r; }
  function center(r){ return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }
  function same(a, b){ return !!(a && b && (a.el ? a.el === b.el : (!b.el && a.label === b.label))); }
  function preferred(list){
    for (var i = 0; i < list.length; i++){ if (/accepter|accept/i.test(list[i].label)) return list[i]; }
    for (var k = 0; k < list.length; k++){ if (['pause', 'play', 'lecture'].indexOf(list[k].label.toLowerCase()) >= 0) return list[k]; }
    var best = null, bd = 1e9;
    list.forEach(function(t){
      var c = center(rectOf(t));
      var d = Math.hypot(c.x - window.innerWidth / 2, c.y - window.innerHeight * 0.55);
      if (d < bd){ bd = d; best = t; }
    });
    return best;
  }
  function nearest(list, dir){
    var c = center(rectOf(cur)), best = null, bs = 1e9;
    list.forEach(function(t){
      if (same(t, cur)) return;
      var p = center(rectOf(t)), dx = p.x - c.x, dy = p.y - c.y, prim, sec;
      if (dir === 'left'){ if (dx >= -4) return; prim = -dx; sec = Math.abs(dy); }
      else if (dir === 'right'){ if (dx <= 4) return; prim = dx; sec = Math.abs(dy); }
      else if (dir === 'up'){ if (dy >= -4) return; prim = -dy; sec = Math.abs(dx); }
      else { if (dy <= 4) return; prim = dy; sec = Math.abs(dx); }
      var sc = prim + sec * 2.5;
      if (sc < bs){ bs = sc; best = t; }
    });
    return best;
  }
  function draw(){
    if (!ring){
      ring = document.createElement('div');
      ring.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:3px solid #C9A45C;border-radius:10px;' +
        'box-shadow:0 0 0 3px rgba(0,0,0,.5),0 0 18px rgba(201,164,92,.7);transition:all .12s ease-out;display:none;';
      document.documentElement.appendChild(ring);
    }
    if (!cur){ ring.style.display = 'none'; return; }
    var r = rectOf(cur);
    ring.style.display = 'block';
    ring.style.left = (r.left - 5) + 'px';
    ring.style.top = (r.top - 5) + 'px';
    ring.style.width = (r.width + 10) + 'px';
    ring.style.height = (r.height + 10) + 'px';
  }
  function result(){
    if (!cur) return JSON.stringify({ ok: false });
    var c = center(rectOf(cur));
    return JSON.stringify({ ok: true, label: cur.label, fx: c.x / window.innerWidth, fy: c.y / window.innerHeight });
  }
  function wake(){
    var p = document.querySelector('.eb-player');
    if (!p) return;
    var r = p.getBoundingClientRect();
    var o = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    // Le lecteur de Forja affiche ses commandes sur « pointermove ».
    try { p.dispatchEvent(new PointerEvent('pointermove', o)); } catch(e){}
    try { p.dispatchEvent(new MouseEvent('mousemove', o)); } catch(e){}
  }
  function pick(list){ return list.length ? preferred(list) : null; }
  window.__ftvNav = {
    start: function(){ wake(); cur = pick(candidates()); draw(); return result(); },
    move: function(dir){
      wake();
      var list = candidates();
      if (!cur || !list.some(function(t){ return same(t, cur); })) cur = pick(list);
      else { var n = nearest(list, dir); if (n) cur = n; }
      draw();
      return result();
    },
    refresh: function(){
      wake();
      var list = candidates(), keep = null;
      if (cur){
        list.forEach(function(t){ if (same(t, cur)) keep = t; });
        if (!keep){
          var c = center(rectOf(cur)), bd = 1e9;
          list.forEach(function(t){ var p = center(rectOf(t)); var d = Math.hypot(p.x - c.x, p.y - c.y); if (d < bd){ bd = d; keep = t; } });
          if (bd > 60) keep = null;
        }
      }
      cur = keep || pick(list);
      draw();
      return result();
    },
    stop: function(){ cur = null; draw(); return 'ok'; },
    wake: wake
  };
})();
"""

    /** Met en pause la vidéo (quand on quitte le lecteur avec le bouton Accueil). */
    const val PAUSE_ALL = "document.querySelectorAll('video').forEach(function(v){ try { v.pause(); } catch(e){} });"
}
