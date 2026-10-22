/* web-shim.js —— 网页版（浏览器）壳桥，MPA 整页跳转方案。
 * 砍掉了 v1.11.x 的三样卡顿根源：①同步 XHR 心跳（改异步 fetch）②iframe 套壳（改整页跳转）③blur 叠加。
 * 契约：status() 仍同步返回缓存（app.js 直接 JSON.parse），但刷新走异步 fetch 不阻塞主线程；
 *       act() 异步 fire-and-forget（app.js 不关心返回值）。 */
(function () {
  var BASE = window.__WEB_BASE || '';
  window.__web = true;

  var last = '{}';
  function refreshState() {
    fetch(BASE + '/api/state')
      .then(function (r) { return r.ok ? r.text() : null; })
      .then(function (t) {
        if (t) {
          last = t;
          if (window.__uiRefresh) { try { window.__uiRefresh(); } catch (e) {} }
        }
      })
      .catch(function () {});
  }
  refreshState();
  setInterval(refreshState, 6000);   // 异步 fetch，不阻塞主线程

  // 底部导航 = 4 个整页跳转链接。无 iframe、无 blur、无同步心跳。
  function buildWebNav() {
    if (document.querySelector('nav.webnav')) return;
    var nav = document.createElement('nav');
    nav.className = 'webnav';
    nav.setAttribute('role', 'tablist');
    var H = location.hostname;
    var P = window.__WEB_PORTS || { gw: 20128, cd: 47860 };
    // ❗不反代面板：面板用自己的端口直连（Next.js 的 /_next/ /api/ 才天然正确）。
    var GW = 'http://' + H + ':' + P.gw;
    var CD = 'http://' + H + ':' + P.cd;
    var SHELL = 'http://' + H + ':' + location.port;
    window.__PANEL_URLS = { gw: GW + '/dashboard', cd: CD + '/' };
    var curKey = (location.pathname.indexOf('/set') >= 0) ? 'set' : 'home';
    var tabs = [
      { k: 'home',   label: '首页', icon: '⌂', href: SHELL + '/' },
      { k: 'gw',     label: '网关', icon: '◉', href: GW + '/dashboard' },
      { k: 'credit', label: '额度', icon: '◈', href: CD + '/' },
      { k: 'set',    label: '设置', icon: '⚙', href: SHELL + '/set' }
    ];
    tabs.forEach(function (t) {
      var a = document.createElement('a');
      a.className = 'wtab' + (t.k === curKey ? ' on' : '');
      a.setAttribute('role', 'tab');
      a.href = t.href;
      a.innerHTML = '<i>' + t.icon + '</i><span>' + t.label + '</span>';
      nav.appendChild(a);
    });
    document.body.appendChild(nav);
    // /set 路由：加载后切到设置 section
    if (curKey === 'set' && window.tab) window.tab('set');
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', buildWebNav);
  else buildWebNav();

  window.Native = {
    status: function () { return last; },   // 同步契约不变：返回缓存
    act: function (name) {
      return fetch(BASE + '/api/act', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name })
      }).then(function () { refreshState(); }).catch(function () {});
    },
    claim: function () { refreshState(); },
    scheme: function () { return ''; },
    copy: function (t) { try { navigator.clipboard.writeText(t); } catch (e) {} },
    openBrowser: function (u) { window.open(u, '_blank'); },
    // MPA：不再用 iframe 套壳，面板靠导航条整页跳转（首页「打开网关面板」按钮也走这里）
    openPanel: function (which) {
      var u = (window.__PANEL_URLS || {})[which === 'credit' ? 'cd' : 'gw'];
      if (u) location.href = u;
    },
    closePanel: function () {},
    patchApplied: function () {}
  };
})();
