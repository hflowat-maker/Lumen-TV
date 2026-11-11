package app.lumen.forja

/** JavaScript injecté dans les pages de Forja (sans caractère dollar : chaînes brutes Kotlin). */
object Scripts {

    /**
     * Aides communes, injectées à chaque page (idempotent) :
     * - signale à l'app la lecture vidéo (écran toujours allumé, curseur masqué) ;
     * - signale quand un champ de saisie a le focus (la touche Entrée valide alors la recherche) ;
     * - __ftvScroll : fait défiler l'élément sous le curseur (rangées horizontales, carrousels, page) ;
     * - __ftvVideo : lecture / pause / avance rapide sur la vidéo de la page.
     */
    val CORE = """
(function(){
  if (window.__ftvCore) return; window.__ftvCore = 1;
  function bridge(){ return window.ForjaTV; }
  function anyPlaying(){
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++){ if (!vs[i].paused && !vs[i].ended) return true; }
    return false;
  }
  function report(){ var b = bridge(); if (b){ try { b.playing(anyPlaying()); } catch(e){} } }
  ['playing','play','pause','ended','emptied'].forEach(function(n){
    document.addEventListener(n, function(){ setTimeout(report, 60); }, true);
  });
  function editable(e){
    if (!e) return false;
    var t = (e.tagName || '').toLowerCase();
    if (t === 'textarea') return true;
    if (t === 'input'){
      var ty = (e.getAttribute('type') || 'text').toLowerCase();
      return ['text','search','email','password','tel','url','number'].indexOf(ty) >= 0;
    }
    return !!e.isContentEditable;
  }
  document.addEventListener('focusin', function(e){ var b = bridge(); if (b){ try { b.editing(editable(e.target)); } catch(x){} } }, true);
  document.addEventListener('focusout', function(){ var b = bridge(); if (b){ try { b.editing(false); } catch(x){} } }, true);

  function canScroll(el, axis, d){
    var s = getComputedStyle(el);
    if (axis === 'y'){
      if (!/(auto|scroll|overlay)/.test(s.overflowY) || el.scrollHeight <= el.clientHeight + 1) return false;
      return d > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0;
    }
    if (!/(auto|scroll|overlay)/.test(s.overflowX) || el.scrollWidth <= el.clientWidth + 1) return false;
    return d > 0 ? el.scrollLeft + el.clientWidth < el.scrollWidth - 1 : el.scrollLeft > 0;
  }
  var NEXT = '.swiper-button-next, .slick-next, .splide__arrow--next, .glide__arrow--right, .embla__next, .owl-next, [class*="next" i], [class*="arrow-right" i], [aria-label*="next" i], [aria-label*="suiv" i]';
  var PREV = '.swiper-button-prev, .slick-prev, .splide__arrow--prev, .glide__arrow--left, .embla__prev, .owl-prev, [class*="prev" i], [class*="arrow-left" i], [aria-label*="prev" i], [aria-label*="préc" i]';
  var CAROUSEL = /swiper|slick|carousel|slider|splide|glide|embla|keen|flicking|owl/i;
  var lastCarousel = 0;
  function carousel(el, d){
    var now = Date.now();
    if (now - lastCarousel < 450) return;
    var c = el;
    while (c && c !== document.body && c !== document.documentElement){
      if (c.swiper){ lastCarousel = now; if (d > 0) c.swiper.slideNext(); else c.swiper.slidePrev(); return; }
      var cls = typeof c.className === 'string' ? c.className : '';
      if (CAROUSEL.test(cls)){
        var scopes = [c, c.parentElement];
        for (var i = 0; i < scopes.length; i++){
          var btn = scopes[i] && scopes[i].querySelector(d > 0 ? NEXT : PREV);
          if (btn){ lastCarousel = now; btn.click(); return; }
        }
      }
      c = c.parentElement;
    }
  }
  function scrollAxis(x, y, axis, d, smooth){
    if (!d) return;
    var el = document.elementFromPoint(x, y);
    var t = el;
    while (t && t !== document.body && t !== document.documentElement){
      if (canScroll(t, axis, d)){
        t.scrollBy({ left: axis === 'x' ? d : 0, top: axis === 'y' ? d : 0, behavior: smooth ? 'smooth' : 'auto' });
        return;
      }
      t = t.parentElement;
    }
    if (axis === 'x'){ carousel(el, d); return; }
    window.scrollBy({ left: 0, top: d, behavior: smooth ? 'smooth' : 'auto' });
  }
  window.__ftvScroll = function(fx, fy, fdx, fdy, smooth){
    try {
      var x = fx * window.innerWidth, y = fy * window.innerHeight;
      if (fdy) scrollAxis(x, y, 'y', fdy * window.innerHeight, smooth);
      if (fdx) scrollAxis(x, y, 'x', fdx * window.innerWidth, smooth);
    } catch(e){}
  };

  function videos(){
    var list = Array.prototype.slice.call(document.querySelectorAll('video'));
    var fr = document.querySelectorAll('iframe');
    for (var i = 0; i < fr.length; i++){
      try { list = list.concat(Array.prototype.slice.call(fr[i].contentDocument.querySelectorAll('video'))); } catch(e){}
    }
    return list;
  }
  window.__ftvVideo = function(cmd, arg){
    var vs = videos();
    if (!vs.length) return 'none';
    var v = null;
    for (var i = 0; i < vs.length; i++){ if (!vs[i].paused && !vs[i].ended){ v = vs[i]; break; } }
    if (!v){
      vs.sort(function(a, b){ return b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight; });
      v = vs[0];
    }
    if (cmd === 'toggle') cmd = (v.paused || v.ended) ? 'play' : 'pause';
    if (cmd === 'play'){ var p = v.play(); if (p && p.catch) p.catch(function(){}); }
    else if (cmd === 'pause'){ v.pause(); }
    else if (cmd === 'seek'){
      var dur = isFinite(v.duration) ? v.duration : 1e9;
      v.currentTime = Math.max(0, Math.min(dur - 1, v.currentTime + arg));
    }
    return JSON.stringify({ cmd: cmd, t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0 });
  };
})();
"""

    /**
     * Force la largeur de mise en page du site (en pixels CSS) : 1280 donne un rendu
     * « ordinateur » lisible à 3 m sur une TV 1080p. Surveille la balise viewport,
     * que le site peut réécrire en changeant de page.
     */
    fun viewport(width: Int): String = """
(function(W){
  window.__ftvW = W;
  function fix(){
    var head = document.head; if (!head) return;
    var c = 'width=' + window.__ftvW;
    var ms = head.querySelectorAll('meta[name="viewport"]');
    if (!ms.length){
      var m = document.createElement('meta');
      m.setAttribute('name', 'viewport'); m.setAttribute('content', c); head.appendChild(m);
      return;
    }
    for (var i = 0; i < ms.length; i++){ if (ms[i].getAttribute('content') !== c) ms[i].setAttribute('content', c); }
  }
  if (!window.__ftvVp && document.head){
    window.__ftvVp = new MutationObserver(fix);
    window.__ftvVp.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
  }
  fix();
})(__W__);
""".replace("__W__", width.toString())

    /** Met en pause toutes les vidéos (quand on quitte l'app avec le bouton Accueil). */
    const val PAUSE_ALL = "document.querySelectorAll('video').forEach(function(v){ try { v.pause(); } catch(e){} });"
}
