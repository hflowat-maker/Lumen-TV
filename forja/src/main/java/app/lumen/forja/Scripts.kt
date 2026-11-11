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

    /** Met en pause la vidéo (quand on quitte le lecteur avec le bouton Accueil). */
    const val PAUSE_ALL = "document.querySelectorAll('video').forEach(function(v){ try { v.pause(); } catch(e){} });"
}
