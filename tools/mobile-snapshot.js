/* 手机端结构快照（真机保真参数下采集）
   用途：作为回归门禁的输入。对每个"页面 × 宽度 × 主题"采集：
     · metrics        四类可见问题的计数（越界/裁切/小热区/小字）
     · mainWidth      主内容列宽度（最重要：内容被挤窄说明布局被破坏）
     · pageHeight     文档总高（异常膨胀说明内容被拆行）
     · keyVisible     关键元素是否仍在视口内可见
     · overList       越界元素的标签（用于定位）
   设计原则：只统计"视口内可见"的元素；排除图标字体；排除脱离文档流的元素参与越界判定。
*/
(function snapshot() {
  var vw = innerWidth, vh = innerHeight;
  function vis(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < vh;
  }
  function isIcon(cs) {
    var f = (cs.fontFamily || '').toLowerCase();
    return f.indexOf('material') >= 0 || f.indexOf('symbol') >= 0 || f.indexOf('icon') >= 0;
  }
  function inScrollerX(el) {
    var p = el.parentElement;
    while (p && p !== document.body) {
      var c = getComputedStyle(p);
      if (c.overflowX === 'auto' || c.overflowX === 'scroll') return true;
      p = p.parentElement;
    }
    return false;
  }
  var over = 0, overScroller = 0, overList = [], clip = 0, tiny = 0, small = 0, largest = 0, contentH = 0;
  for (var i = 0; i < document.querySelectorAll('body *').length; i++) {
    var el = document.querySelectorAll('body *')[i];
    if (!vis(el)) continue;
    var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    var txt = (el.textContent || '').trim();
    // 越界（脱离文档流的浮层不计）
    if (cs.position !== 'fixed' && cs.position !== 'absolute' && Math.round(r.right - vw) > 2) {
      if (inScrollerX(el)) { overScroller++; } else { over++; }
      if (!inScrollerX(el) && overList.length < 6) {
        var cls = String(el.className || '').split(/\s+/).filter(Boolean).slice(0, 2).join('.');
        overList.push(el.tagName.toLowerCase() + (cls ? '.' + cls : '') + ':' + Math.round(r.width));
      }
    }
    if (el.children.length === 0 && txt && !isIcon(cs) && el.scrollWidth > el.clientWidth + 2) clip++;
    if ((el.tagName === 'BUTTON' || (el.tagName === 'A' && el.getAttribute('href'))) && r.height < 34) tiny++;
    var fs = parseFloat(cs.fontSize);
    if (fs && fs < 11.5 && txt && el.children.length === 0 && !isIcon(cs)) small++;
    // 内部滚动区的内容高：h-screen 布局的页面靠它才能发现"内容被拆行撑高"
    if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > contentH) {
      contentH = el.scrollHeight;
    }
    // 主内容列：视口内最宽且高度最大的流式容器
    if (r.height > vh * 0.4 && r.width > largest && cs.position !== 'fixed') {
      largest = r.width;
    }
  }
  var nav = document.querySelector('nav, header, [role="navigation"]');
  var btn = document.querySelector('button, a[href]');
  return JSON.stringify({
    vw: vw, vh: vh,
    metrics: { over: over, overScroller: overScroller, clip: clip, tiny: tiny, small: small },
    overList: overList,
    mainWidth: Math.round(largest),
    pageHeight: Math.round(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)),
    contentHeight: Math.round(contentH),
    keyVisible: {
      nav: !!nav && nav.getBoundingClientRect().height > 0,
      btn: !!btn && btn.getBoundingClientRect().height > 0
    },
    patch: window.__panelFixStats || null
  });
})()
