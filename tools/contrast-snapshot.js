(function () {
  var x = document.createElement('canvas').getContext('2d');
  function toRGBA(c) {
    try {
      x.fillStyle = '#000';
      x.fillStyle = c;
      var s = x.fillStyle;
      if (s.charAt(0) === '#') {
        var h = s.slice(1);
        if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
        return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1];
      }
      var m = s.match(/[\d.]+/g);
      if (!m || m.length < 3) return null;
      return [+m[0], +m[1], +m[2], m.length > 3 ? +m[3] : 1];
    } catch (e) { return null; }
  }
  function lum(c) {
    if (!c) return null;
    var v = [c[0], c[1], c[2]].map(function (y) {
      y = y / 255;
      return y <= 0.03928 ? y / 12.92 : Math.pow((y + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
  }
  function cr(a, b) {
    var L1 = lum(a), L2 = lum(b);
    if (L1 == null || L2 == null) return 99;
    return (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
  }
  function effBg(el) {
    var st = [], n = el;
    while (n && n !== document.documentElement) {
      var c = toRGBA(getComputedStyle(n).backgroundColor);
      if (c && c[3] > 0) st.push(c);
      n = n.parentElement;
    }
    st.push(toRGBA(getComputedStyle(document.documentElement).backgroundColor) || [10, 10, 10, 1]);
    st.push([255, 255, 255, 1]);
    var o = [0, 0, 0, 1];
    for (var i = st.length - 1; i >= 0; i--) {
      var ci = st[i], a = ci[3];
      o[0] = ci[0] * a + o[0] * (1 - a);
      o[1] = ci[1] * a + o[1] * (1 - a);
      o[2] = ci[2] * a + o[2] * (1 - a);
    }
    return o;
  }
  function isVis(el) {
    var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.display !== 'none'
      && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.05;
  }
  function bgStr(c) { return 'rgb(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ')'; }

  var low = 0, worst = 99, sample = null, clips = 0, clipSample = null;
  var all = document.querySelectorAll('body *');
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (!isVis(el)) continue;
    var cs = getComputedStyle(el);
    if (/material|symbol|icon/i.test(cs.fontFamily || '')) continue;

    var txt = el.children.length ? '' : (el.textContent || '').trim();
    if (txt) {
      var fg = toRGBA(cs.color);
      if (fg) {
        var bg = effBg(el), al = fg[3];
        var fe = [fg[0] * al + bg[0] * (1 - al), fg[1] * al + bg[1] * (1 - al), fg[2] * al + bg[2] * (1 - al), 1];
        var rc = cr(fe, bg);
        if (rc < 4.5) {
          low++;
          if (rc < worst) {
            worst = +rc.toFixed(2);
            sample = { t: txt.slice(0, 14), color: cs.color, bg: bgStr(bg), cls: String(el.className || '').slice(0, 18) };
          }
        }
      }
    }
    var r2 = el.getBoundingClientRect();
    if (cs.overflowX === 'hidden' && el.scrollWidth > el.clientWidth + 4 && el.clientWidth > 60
        && cs.whiteSpace !== 'nowrap' && cs.textOverflow !== 'ellipsis') {
      clips++;
      if (!clipSample) clipSample = { cls: String(el.className || '').slice(0, 18), need: el.scrollWidth, got: el.clientWidth };
    }
  }
  return JSON.stringify({ low: low, worst: worst === 99 ? null : worst, sample: sample,
                          clips: clips, clipSample: clipSample, runs: window.__panelFixRuns || 0 });
})()
