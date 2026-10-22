#!/usr/bin/env node
/* passTunnel 回归测试 —— 断言"藏隧道时绝不误伤邻居元素"。
 * 为什么存在：旧实现把三行共用容器/整张卡/整页 div 当成"一行"藏掉，
 * 曾出现「点隧道设置后同页面其它元素一起消失」。
 *
 * 关键设计：**从仓库 app/ui/panel-fix.js 里自动抽取真实实现**（不是手抄副本），
 * 所以改了补丁层再跑这个测试，测的就是改后的代码。
 *
 * 用法： node tools/tests/tunnel-regress.js      （需要 jsdom，构建机 tenrouter-src/node_modules 里有）
 * 退出码 0 = 6 个场景全部符合预期。
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const SRC = fs.readFileSync(path.join(__dirname, '../../app/ui/panel-fix.js'), 'utf8');
function grab(a, b) { const i = SRC.indexOf(a), j = SRC.indexOf(b, i); if (i < 0 || j < 0) throw new Error('抽取失败: ' + a); return SRC.slice(i, j); }
const IMPL = [
  grab('  function isTunnelScope() {', '  var ICON_NAMES ='),
  grab('  var ICON_NAMES =', '  function hideTunnelRow(el) {'),
  grab('  function hideTunnelRow(el) {', '  function passTunnel() {'),
  grab('  function passTunnel() {', '  /* CD 加载浮层兜底'),
].join('\n');

const CASES = {
  '① Endpoint 三行': `<div id="card" class="rounded-xl border p-6">
    <h2 class="text-lg font-semibold">API Endpoint</h2>
    <div id="rows" class="flex flex-col gap-2">
      <div id="a" class="flex items-center gap-2"><span>Local</span><input value="http://127.0.0.1:20128/v1"><button><span class="material-symbols-outlined">content_copy</span></button></div>
      <div id="b" class="flex items-center gap-2"><span>Tunnel</span><input value="https://x.trycloudflare.com/v1"><button><span class="material-symbols-outlined">content_copy</span></button></div>
      <div id="c" class="flex items-center gap-2"><span>Tailscale</span><input value="https://y.ts.net/v1"><button><span class="material-symbols-outlined">content_copy</span></button></div>
    </div></div>`,
  '② 侧栏导航（3 项含 Tunnel）': `<nav id="side" class="flex flex-col gap-1">
      <a id="n1" class="flex items-center gap-3">仪表盘</a>
      <a id="n2" class="flex items-center gap-3">Tunnel</a>
      <a id="n3" class="flex items-center gap-3">设置</a>
    </nav>`,
  '③ 设置页隧道标题卡 + 另一张正常卡': `<div id="page">
      <div id="cardT" class="rounded-xl border p-6">
        <h2 id="hdr">Cloudflare Tunnel</h2>
        <p id="tp">允许通过隧道访问仪表盘</p>
        <button id="tbtn">Enable Tunnel</button>
      </div>
      <div id="cardOther" class="rounded-xl border p-6">
        <h2>API 密钥</h2><p id="keepme">这是另一张卡的正常内容</p>
      </div>
    </div>`,
  '④ 隧道关闭态（裸 Enable 按钮）': `<div id="card" class="rounded-xl border p-6">
      <h2>API Endpoint</h2>
      <div id="rows" class="flex flex-col gap-2">
        <div id="a" class="flex items-center gap-2"><span>Local</span><input value="http://127.0.0.1:20128/v1"><button><span class="material-symbols-outlined">content_copy</span></button></div>
        <div id="b" class="flex items-center gap-2"><span>Tunnel</span><button id="en">Enable</button></div>
      </div></div>`,
  '⑤ 普通段落含 Tailscale 说明': `<div id="page">
      <div id="card" class="rounded-xl border p-6">
        <h2 id="hdr">网络出口</h2>
        <p id="keep1">出站流量可走 Tailscale 或直连</p>
        <p id="keep2">这是一段完全无关的正常说明文字</p>
        <button id="keepbtn">保存</button>
      </div></div>`,
  '⑥ 侧栏 5 项（超过 children≤5 护栏）': `<nav id="side" class="flex flex-col">
      <a id="m1">仪表盘</a><a id="m2">供应商</a><a id="m3">Tunnel</a><a id="m4">设置</a><a id="m5">日志</a><a id="m6">关于</a>
    </nav>`,
};

const EXPECT = {
  '① Endpoint 三行': { keep: ['card','rows','a'], hide: ['b'] },
  '② 侧栏导航（3 项含 Tunnel）': { keep: ['side','n1','n3'], hide: ['n2'] },
  '③ 设置页隧道标题卡 + 另一张正常卡': { keep: ['page','cardOther','keepme'], hide: ['hdr'] },
  '④ 隧道关闭态（裸 Enable 按钮）': { keep: ['card','rows','a'], hide: ['b'] },
  '⑤ 普通段落含 Tailscale 说明': { keep: ['page','card','hdr','keep1','keep2','keepbtn'], hide: [] },
  '⑥ 侧栏 5 项（超过 children≤5 护栏）': { keep: ['side','m1','m2','m4','m5','m6'], hide: ['m3'] },
};

let totalFail = 0;
for (const [name, html] of Object.entries(CASES)) {
  const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>');
  const document = dom.window.document;
  const location = { pathname: '/dashboard/endpoint', search: '' };
  const stats = { tunnel: 0, reveal: 0 };
    eval(IMPL);   // 注入真实实现
  function hideEl(el) {
    if (!el || el.getAttribute('data-pf-tunnel')) return;
    el.setAttribute('data-pf-tunnel', '1');
    try { el.style.setProperty('display', 'none', 'important'); stats.tunnel++; } catch (e) {}
  }

  passTunnel();
  const visible = el => {
    let n = el;
    while (n && n !== document.documentElement) {
      if (n.style && n.style.display === 'none') return false;
      n = n.parentElement;
    }
    return true;
  };
  const exp = EXPECT[name];
  let bad = [];
  for (const id of exp.keep) {
    const el = document.getElementById(id);
    if (!el || !visible(el)) bad.push('丢了 #' + id);
  }
  for (const id of exp.hide) {
    const el = document.getElementById(id);
    if (el && visible(el)) bad.push('该藏没藏 #' + id);
  }
  const hidden = [...document.querySelectorAll('[data-pf-tunnel]')].map(
    e => '#' + (e.id || e.tagName.toLowerCase()));
  console.log('\n===== ' + name + ' =====');
  console.log('  藏掉:', hidden.join(', ') || '（无）');
  if (bad.length) { totalFail++; console.log('  ❌ ' + bad.join('，')); }
  else console.log('  ✅ 符合预期');
}
console.log('\n==== 总计 ' + totalFail + ' 个场景不符 ====');
process.exit(totalFail ? 1 : 0);
