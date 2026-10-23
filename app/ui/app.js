/* 10Router 壳 UI 逻辑。
   与原生通过 window.Native 桥接（无 Native 时进 mock，便于在桌面浏览器做窄屏与对比度实测）。

   为什么不用 iframe 装面板：iframe 里对 127.0.0.1 的请求在父页面是 file:// 时算跨站，
   而网关的会话 Cookie 是 SameSite=Lax —— 跨站不发送 → 表现是"密码输对了也进不去"。
   所以 网关/额度 两个标签交给原生做顶层加载（Native.openPanel），Cookie 变成第一方。 */
(function () {
  var hasNative = !!window.Native;

  /* ---------- 主题：跟随系统，不替两个面板做决定 ---------- */
  function applyScheme(v) {
    if (v === 'light' || v === 'dark') document.documentElement.setAttribute('data-scheme', v);
  }
  try { if (hasNative && window.Native.scheme) applyScheme(window.Native.scheme()); } catch (e) {}
  window.onScheme = applyScheme;              // 原生在系统主题切换时回调
  if (!hasNative && window.matchMedia) {
    var mq = matchMedia('(prefers-color-scheme: light)');
    applyScheme(mq.matches ? 'light' : 'dark');
    mq.addEventListener && mq.addEventListener('change', function (e) {
      applyScheme(e.matches ? 'light' : 'dark');
    });
  }

  function $(id) { return document.getElementById(id); }

  /* ---------- 反馈基元 ---------- */
  function toast(m, cls) {
    var t = $('toast');
    t.textContent = m;
    t.className = 'toast on' + (cls ? ' ' + cls : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { t.className = 'toast' + (cls ? ' ' + cls : ''); }, 2600);
  }
  window.toast = toast;   // Java 用 js("toast(...)") 调的是全局名。此前 toast 只存在于 IIFE 内部，
                          // 原生那 9 处提示（重启请求/补丁进行中/日志失败…）在真机上全部静默失败 —— 这是
                          // "点了不知道点没点上"的直接原因之一。必须显式挂到 window。
  function tone(m) {      // 结果消息自动分色：失败红、成功绿
    return /失败|不能|拒绝|不符|错误|未安装|打不开|不可用/.test(m) ? 'bad'
         : (/已|成功|最新|完成|可用/.test(m) ? 'ok' : '');
  }
  function setHTML(el, html) {   // 内容没变就不重建 DOM：2.5s 定时刷新不再吞掉按钮的进行中/瞬时状态，
    if (el && el.innerHTML !== html) el.innerHTML = html;   // 也避免"重建瞬间点按钮"被吞
  }
  function nowHM() {
    var d = new Date(), z = function (n) { return (n < 10 ? '0' : '') + n; };
    return z(d.getHours()) + ':' + z(d.getMinutes());
  }

  /* ---------- 标签 ----------
     底部导航已由原生常驻绘制（面板页也保留），这里只负责切换内容区。 */
  window.tab = function (name) {
    var secs = document.querySelectorAll('main section');
    for (var i = 0; i < secs.length; i++) secs[i].classList.remove('on');
    var sec = $('s-' + name);
    if (sec) sec.classList.add('on');
    $('main').scrollTop = 0;
  };

  /** 打开真实面板：原生顶层加载，直进面板，没有中转页 */
  window.openPanel = function (which) {
    if (!hasNative) { toast('演示模式：打开 ' + which + ' 面板'); return; }
    try { window.Native.openPanel(which === 'credit' ? 'credit' : 'gateway'); }
    catch (e) { toast('打开失败：' + e); }
  };

  /* ---------- 按钮状态机：按下 = 立刻有可见反馈 ----------
     用户痛点（原话）："有一些按钮点了，你都不知道点没点上（复制按钮），
     更没有更新（打补丁的时候不知道在没在打）"。
     规则：每个动作按钮按下即转圈+文案改写+防重复；原生结果回执即解锁；
     没回执的动作最迟按超时恢复。ms 依据：fetchResilient 最坏 = 主站两试+镜像两试
     ×(connect 8s + read 15s) → 检查类给 95s 上限。 */
  var BUSY = {
    restart:      { t: '重启中…',       ms: 15000 },
    update:       { t: '检查版本中…', ms: 95000, rel: 'update', info: 0 },
    updateSw:     { t: '检查软件版本…', ms: 95000, rel: 'updateSw', info: 0 },
    logs:         { t: '打开日志…',   ms: 3000 },
    touchBlock:   { t: '切换中…',     ms: 4000, rel: 'touchBlock', info: 0 },
    lanToggle:    { t: '切换·重启中…', ms: 15000, rel: 'lan' },
    notify:       { t: '打开设置…',       ms: 3000 },
    battery:      { t: '查询中…',       ms: 3000 },
    anchor:       { t: '打开设置…',       ms: 3000 },
    autostart:    { t: '打开设置…',       ms: 3000 },
    vpn:          { t: '切换守候…',       ms: 4000 },
    anchor_auto:  { t: '切换中…',       ms: 4000 },
    anchor_vpn:   { t: '挂锚中…',     ms: 8000 },
        anchor_off:   { t: '解除守候…',       ms: 4000 },
    vpnSettings:  { t: '打开 VPN 设置…',  ms: 3000 },
    log:          { t: '读取中…',         ms: 3000 },
    cdlog:        { t: '读取中…',         ms: 3000 },
    diag:         { t: '打开分享…',     ms: 5000 },
    tunnelToggle: { t: '切换中…',         ms: 12000 },
    tunnelLog:    { t: '读取中…',         ms: 3000 },
    claim:        { t: '正在领取…',       ms: 30000, rel: 'claim' },
    screenOn:     { t: '切换常亮…',     ms: 3000, rel: 'screenOn' },
  };
  var busyRel = {};   // rel -> [解锁函数]：结果回执按 kind 批量解锁
  var copyFlash = { t: 0, a: '' };   // 复制按钮的“✓ 已复制”瞬时态（活在数据里，不怕 2.5s 刷新重建）
  var lastState = null;              // 上一次渲染用的状态 JSON，供即时局部重绘
  function setBusy(btn, cfg) {
    if (!btn || btn.disabled || btn.classList.contains('busy')) return null;
    var orig = btn.innerHTML;
    btn.classList.add('busy');
    btn.innerHTML = '<i class="spin"></i>' + cfg.t;
    var timer = setTimeout(stop, cfg.ms || 5000);
    function stop() {
      clearTimeout(timer);
      btn.classList.remove('busy');
      btn.innerHTML = orig;
    }
    return stop;
  }
  function releaseKind(kind) {
    var a = busyRel[kind] || [];
    for (var i = 0; i < a.length; i++) a[i]();
    busyRel[kind] = [];
  }

  /* ---------- 动作 ---------- */
  window.act = function (name, btn) {
    if (!hasNative) { toast('演示模式：' + name); return; }
    var cfg = BUSY[name];
    if (cfg && btn) {
      var stop = setBusy(btn, cfg);
      if (stop && cfg.rel) { (busyRel[cfg.rel] = busyRel[cfg.rel] || []).push(stop); }
    }
    try { window.Native.act(name); }
    catch (e) { toast('操作失败：' + e, 'bad'); if (cfg && cfg.rel) releaseKind(cfg.rel); }
  };

  window.claim = function () {
    var b = $('claim');
    if (!hasNative) { toast('演示模式：领取'); return; }
    var stop = setBusy(b, BUSY.claim);
    if (stop) { (busyRel.claim = busyRel.claim || []).push(stop); }
    try { window.Native.claim(); }
    catch (e) { toast('领取失败：' + e, 'bad'); releaseKind('claim'); }
  };

  window.onNativeResult = function (kind, msg) {
    if (kind === 'claim') releaseKind('claim');
    releaseKind(kind);
    if (msg) {
      toast(msg, tone(msg));
      if (kind === 'updateSw') { var e = $('swVerHint'); if (e) e.textContent = msg; }
    }
    refresh();
  };

  window.onBoot = function (pct, msg) {
    $('boot').classList.add('on');
    $('bootbar').style.width = (pct || 0) + '%';
    if (msg) $('bootmsg').textContent = msg;
    if (pct >= 100) setTimeout(function () { $('boot').classList.remove('on'); }, 350);
  };

  /* ---------- 渲染 ---------- */
  function dot(el, kind) { el.className = 'dot' + (kind ? ' ' + kind : ''); }

  function chip(ok, okText, badText) {
    return ok ? '<span class="chip ok">' + okText + '</span>'
              : '<span class="chip bad">' + badText + '</span>';
  }

  function checkRow(icon, title, sub, chipHtml, action, actionText) {
    var btn = action
      ? '<button class="btn sm ghost" onclick="act(\'' + action + '\',this)">' + (actionText || '去开启') + '</button>'
      : '';
    return '<div class="chk"><div class="ic">' + icon + '</div>' +
      '<div class="tx"><b>' + title + '</b><span>' + sub + '</span></div>' + chipHtml + btn + '</div>';
  }

  function guardRows(s, full) {
    return [
      // ❗已授权时也要给按钮：原来 action=null 会让按钮整个消失，只剩一个绿色标签，
      //   用户面前没有任何可点的东西，"关不掉"的感觉就是这么来的。
      //   这四项都是**系统权限**，Android 不允许 App 自己撤销（只有在系统设置里关）。
      //   所以已授权时按钮写"系统设置"，点进去由用户在系统里关，而不是假装我们能关。
      checkRow('🔔', '通知权限', '用于常驻通知，避免被系统回收',
        chip(s.notif, '已允许', '未允许'), 'notify', s.notif ? '系统设置' : '去开启'),
      checkRow('🔋', '电池优化豁免', '避免息屏后被系统冻结',
        chip(s.battery, '已豁免', '未豁免'), 'battery', s.battery ? '系统设置' : '去开启'),
      checkRow('🔗', '系统加固', '进程被结束后自动重连',
        chip(s.anchor, '已开启', '未开启'), 'anchor', s.anchor ? '系统设置' : '去开启'),
      // 自启动是"不占 VPN 通道"的保活里最关键的一环：厂商白名单默认全关，
      // 而且没有任何系统 API 能查状态，所以它永远是动作按钮，不是可自动判定的开关。
      checkRow('🚀', '自启动授权', '厂商的后台白名单，默认是关的；关着就会被清',
        '<span class="chip">需手动</span>', 'autostart', '去设置'),
      // 无障碍锚点：可选第四档。和 VPN 锚同级（都是系统级绑定），但不占 VPN 通道。
      // 必须把授权的真实含义写在副标题里——系统的授权弹窗会写"读取屏幕"，
      // 如果界面上不主动说明，用户看到系统文案会觉得我们在骗他授权。
      checkRow('♿', '无障碍锚点（可选·四档）',
        '系统级绑定，不占 VPN 通道；授权时系统会提示「读取屏幕」，本服务已关闭全部读取能力',
        s.a11y ? '<span class="chip ok">已开启</span>'
               : '<span class="chip">可选</span>',
        'a11y', s.a11y ? '系统设置' : '去开启'),
      anchorRow(s, full)
    ];
  }

  /** 守候锚点：只在 VPN 通道空闲时挂一条零路由隧道（不代理任何流量）。
   *  用户的梯子/Tailscale/WG 一占用，就自动让位，不会两条 VPN 互相顶。
   *  状态 chip 在右（与其他体检行一致），模式按钮在文字下方。
   *  边界说明：锚点只防"内存回收杀"，防不了激进 ROM 的"息屏冻结"；
   *  冻结→授权页按钮，无线电省电→已内置高性能锁（状态可见），绝对在线→服务器常亮。 */
  function anchorRow(s, full) {
    var cur = s.vpnAnchor ? '<span class="chip ok">VPN锚·通道空闲时</span>'
            : (s.anchorMode === 'auto' ? '<span class="chip ok">已让位·不与梯子抢</span>'
            : s.anchorMode === 'off' ? '<span class="chip bad">已关</span>'
            : '<span class="chip bad">未挂锚</span>');
    var why = (s.anchorWhy || '').split('|')[1] || '';
    var now = (why || '未挂锚（选「自动」即挂）') + ' · 无线电' + (s.radioFull ? '全速锁' : '省电');

    /* 首页版（full=false）：只给"当前状态 + 缺的一步"，一键补上即可。
       曾经的问题就在这里：首页"还缺 1 项"把整套控制台（1 个分段控件、
       2 个开关、2 个动作按钮、160 字说明书）整个搬上了首页，首页不再是首页。
       控制台只在设置页出现。 */
    if (!full) {
      return '<div class="chk"><div class="ic">🛡</div>' +
        '<div class="tx"><b>守候锚点</b><span>' + now + '</span></div>' + cur +
        (s.vpnAnchor ? ''
          : '<button class="btn sm ghost" onclick="act(\'anchor_auto\',this)">挂锚</button>') +
        '</div>';
    }

    /* 单选用分段控件（一组里选一个，选中用强调色）—— 形态上就和"独立开关"区分开 */
    function seg(m, label) {
      return '<button class="' + (s.anchorMode === m ? 'on' : '') +
             '" onclick="act(\'anchor_' + m + '\')">' + label + '</button>';
    }
    /* 开关用滑块状（独立开/关），和分段控件一眼区分 */
    function sw(on, actName, label) {
      return '<button class="sw' + (on ? ' on' : '') + '" role="switch" aria-checked="' + !!on +
             '" onclick="act(\'' + actName + '\')"><i></i>' + label + '</button>';
    }
    /* 三组分开：模式（单选）/ 保活（开关）/ 系统（动作）。
       形态即性质：分段控件=单选、滑块=开关、文字按钮=动作，不用读标签也知道能不能多选。
       说明书（为什么需要这些、锁屏后连不上怎么查）收进 details：需要的人点开看，
       不需要的人不被 160 字挡住视线 —— 但一句结论留在明面上，不做"藏起来骗人"。 */
    return '<div class="chk chk2"><div class="ic">🛡</div>' +
      '<div class="tx"><b>守候锚点</b><span>现状：' + now + '</span></div>' + cur +
      '<div class="ops-group"><span class="gl">锚点模式</span><div class="seg">' +
        seg('auto', '自动') + seg('vpn', 'VPN') + seg('off', '关') +
      '</div></div>' +
      /* 「服务器模式」原来是个凭空的分类名，实际上它只包含两件具体的事：
         屏幕不熄 + 禁止触摸。名字不对会让用户以为这里有一整套"服务器设置"，
         而点开只有两个开关。改成直说它是什么。
         另外删掉了「授权后台活动」和「防误触设置」两个按钮：
           · 授权后台活动 —— 与页面顶部「电池优化豁免」是同一个系统设置，重复入口；
           · 防误触设置 —— 跳的是系统显示/锁屏设置**首页**，那页上不一定有这一项，
             用户点进去只会一脸茫然（原话："我不知道要设置什么"）。而且它防的是
             "手机放口袋里误碰"，这件事下面的「禁触摸」开关已经完整覆盖。 */
      '<div class="ops-group"><span class="gl">插电当服务器时</span>' +
        '<div class="sw-row">' +
          sw(s.keepScreenOn, 'screenOn', (s.keepScreenMode === 2 ? '亮度0' : '常亮')) +
          sw(s.touchBlock, 'touchBlock', '禁触摸') +
        '</div>' +
        '<p class="opnote">常亮只在 App 前台且插电时生效，锁屏即失效。想让屏幕黑着又不会误碰，两个一起开。</p>' +
      '</div>' +
      '</div>';
  }

  /* 渲染远程访问行 —— 这里就是"访问地址"的正式代码来源（不依赖任何补丁层）。
     产品决定：只显示一个访问地址、不生成隧道行（远程只走自家 WireGuard）。
     历史：这段收敛曾经也写在 panel-fix.js 的 passTunnel.isHome 分支里给老 APK 兜底；
     现在补丁层已删除 shell 分支、且壳 WebView 不再被注入补丁，所以壳的显示完全由此处正式代码负责。 */
  function renderRemote(s) {
    var port = s.gwPort || 20128;
    var ip = (s.lan || '').split(',')[0];        // 老 App 会回逗号串，取第一个（单 IP 正式逻辑，非补丁）
    var on = !!(s.bindAll && ip);
    var addr = on ? 'http://' + ip + ':' + port + '/v1' : 'http://127.0.0.1:' + port + '/v1';
    var tip = on ? '局域网内平板/电脑把它填进 base_url 即可'
                 : '未开局域网访问，仅本机可用；要跨设备访问请点下方按钮开启';
    var copied = copyFlash.a === addr && (Date.now() - copyFlash.t) < 2200;   // 2.2 秒的“✓ 已复制”窗口
    var addrRow = '<div class="chk"><div class="ic">📶</div><div class="tx"><b>访问地址</b><span>'
      + addr + '　<span style="opacity:.65">' + tip + '</span></span></div>'
      + '<button class="btn sm ghost' + (copied ? ' done' : '') + '" onclick="copyAddr(\'' + addr + '\')">'
      + (copied ? '✓ 已复制' : '复制') + '</button></div>';

    /* 首页与设置曾经是"同一份完整卡片渲染两遍"（地址+复制+开关+两段说明 ×2）：
       信息重复、首页被说明书占住。现在按"首页办事、设置配全"分工：
       · 首页：地址 + 复制 + **开关常驻**。
         曾经的问题：这里只在"没开"时给按钮，开了就只剩地址 ——
         曾出现「首页开了局域网、回头找不到关的地方」，只能翻设置。
         规则：**就地能开的就该就地关**，按钮文字随状态变（允许局域网访问 ⇄ 关闭局域网访问），
         和设置页那一条同一条动作（lanToggle），不存在两处状态不同步。
       · 设置：完整（开关 + 边界说明）。 */
    var lanBtn = '<div class="row" style="margin-top:11px">'
      + '<button class="btn ghost" onclick="act(\'lanToggle\',this)">'
      + (on ? '关闭局域网访问' : '允许局域网访问') + '</button></div>';
    var home = addrRow + lanBtn;
    var full = addrRow
      + '<div class="row" style="margin-top:11px">'
      + '<button class="btn ghost" onclick="act(\'lanToggle\',this)">' + (on ? '关闭局域网访问' : '允许局域网访问') + '</button>'
      + '</div>'
      + '<div class="note">地址只在同一 Wi-Fi 内可访问。</div>';
    setHTML($('remote'), home);
    setHTML($('remote2'), full);
  }

  /* 设置页「面板地址」：网关/额度各自一个独立端口，本机地址 + 局域网地址两条。
     不给壳的浏览器版（20130 已删）：它与 App 重复，还要额外维护一套密钥和鉴权，
     而用户真正常用的是「在平板上开面板」这一件事。 */
  function renderWebEntry(s) {
    var box = $('webEntry');
    if (!box) return;
    var gwPort = s.gwPort || 20128, cdPort = s.cdPort || 47860;
    var ip = (s.lan || '').split(',')[0];
    var lanOn = !!s.bindAll && !!ip;

    /* 一块面板 = 两条地址。本机地址在手机上就能开；开了局域网访问后，
       下面再浮出一条局域网地址，给同一 Wi-Fi 的电脑/平板用（两个都保留）。 */
    function block(name, path, port) {
      var local = 'http://127.0.0.1:' + port + path;
      var h = '<div class="wp"><b>' + name + '</b><code>' + local + '</code>' +
        '<div class="wpops">' +
        '<button class="btn sm ghost" onclick="openUrl(\'' + local + '\')">打开</button>' +
        '<button class="btn sm ghost" onclick="copyAddr(\'' + local + '\')">复制</button>' +
        '</div>';
      if (lanOn) {
        var lan = 'http://' + ip + ':' + port + path;
        h += '<div class="wplan"><span class="lb">同 Wi-Fi</span><code>' + lan + '</code>' +
          '<button class="btn sm ghost" onclick="copyAddr(\'' + lan + '\')">复制</button></div>';
      }
      return h + '</div>';
    }

    box.innerHTML = block('网关面板', '/dashboard', gwPort) + block('额度面板', '/', cdPort);
  }

  function renderPorts(s) {
    renderWebEntry(s);
  }

  /* 首页「怎么接入」展开/收起：状态写在按钮文字里（不用猜再点一次会怎样） */
  window.toggleIntro = function () {
    var body = $('introBody'), btn = $('introBtn');
    if (!body || !btn) return;
    var open = !body.hasAttribute('hidden');
    if (open) { body.setAttribute('hidden', ''); btn.textContent = '查看接入方法'; btn.setAttribute('aria-expanded', 'false'); }
    else { body.removeAttribute('hidden'); btn.textContent = '收起'; btn.setAttribute('aria-expanded', 'true'); }
  };

  /* 设置页「网页端口」的两个「打开」：交给系统浏览器（壳的 openExternalBrowser）。
     老 WebView 打不开面板时，用户复制地址到谷歌浏览器；这里直接给一条更短的路。 */
  window.openUrl = function (u) {
    if (hasNative) { try { window.Native.openBrowser(u); } catch (e) { toast('打开失败：' + e, 'bad'); } }
    else { try { window.open(u, '_blank'); } catch (e) {} }
    toast('已在浏览器中打开', 'ok');
  };

  window.copyAddr = function (t) {
    copyFlash = { t: Date.now(), a: t };   // 按钮就地变“✓ 已复制”：点没点上，一眼可见
    if (hasNative) { try { window.Native.copy(t); } catch (e) {} }
    toast('已复制到剪贴板', 'ok');
    if (lastState) renderRemote(lastState);
    setTimeout(function () { if (lastState) renderRemote(lastState); }, 2400);
  };

  function fmtAge(sec) {
    if (sec === null || sec === undefined || sec < 0) return '—';
    if (sec < 60) return sec + 's';
    if (sec < 3600) return Math.floor(sec / 60) + 'm';
    return Math.floor(sec / 3600) + 'h';
  }

  function render(s) {
    lastState = s;
    dot($('gdot'), s.gw ? 'ok' : 'bad');
    $('gtitle').textContent = s.gw ? '网关运行中' : (s.booting ? '网关启动中…' : '网关未运行');
    $('gsub').textContent = '127.0.0.1:20128' + (s.gw ? ' · 可服务' : ' · 点下方按钮查看原因');

    var today = s.cdToday === undefined ? '' : (s.cdToday ? ' · 今日已领' : ' · 今日未领');
    dot($('cdot'), s.cd ? 'ok' : 'warn');
    $('ctitle').textContent = s.cd ? '额度管家已就绪' : '额度管家未运行';
    $('csub').textContent = s.cd
      ? ((s.cdAccounts ? s.cdAccounts + ' 个账号' : '还没有账号，去面板添加') + today)
      : '点「面板」看日志与原因';

    // 计数只算「必需项」：无障碍锚点是可选项，它没开不算"还缺"，
    // 否则首页会永远挂着一条红色缺失提示，用户会以为坏了。
    var okCount = (s.notif ? 1 : 0) + (s.battery ? 1 : 0) + (s.anchor ? 1 : 0) + ((s.vpnAnchor || s.anchorMode === 'auto') ? 1 : 0);
    $('hdot').className = 'dot ' + (okCount >= 4 ? 'ok' : (okCount >= 3 ? 'warn' : 'bad'));

    // 首页只报"缺什么"，不重复整张清单；也不放按钮（跳系统页的入口只在设置页）
    var REQUIRED = 5;   // 通知 / 电池 / 系统加固 / 自启动 / 守候锚点
    var missing = guardRows(s, false).filter(function (r) { return r.indexOf('chip ok') < 0; })
      .filter(function (r) { return r.indexOf('无障碍锚点') < 0; })
      // 剥掉整行里的按钮：首页只留文字摘要
      .map(function (r) { return r.replace(/<button[\s\S]*?<\/button>/g, ''); });
    setHTML($('guardSummary'), missing.length
      ? '<div style="font-size:13.5px;color:var(--warn);margin-bottom:2px">' + (REQUIRED - missing.length)
        + '/' + REQUIRED + ' 项已开，还缺 ' + missing.length + ' 项 · 在「设置」里逐项开启</div>' + missing.join('')
      : '<div style="font-size:13.5px;color:var(--ok)">' + REQUIRED + '/' + REQUIRED
        + ' 项必需项已开' + (s.a11y ? '，另开了无障碍锚点' : '') + '</div>');

    setHTML($('checks'), guardRows(s, true).join(''));
    $('st-hb').textContent = fmtAge(s.hbAge);
    $('st-boot').textContent = s.boots === undefined ? '—' : s.boots + ' 次';
    $('st-fz').textContent = s.freezeCount === undefined ? '—' : s.freezeCount + ' 次';
    if (s.ver) { $('ver').textContent = '版本 v' + s.ver; $('sub').textContent = '本地 AI 网关 · v' + s.ver; }
    renderRemote(s);
    renderPorts(s);
  }



  /* 浏览器实测用的演示数据（无 Native 时走这里） */
  var MOCK = { ver: '1.11.10', gw: true, booting: false, hbAge: 4, boots: 1, freezeCount: 0,
             notif: true, battery: true, anchor: true, vpnAnchor: false, anchorMode: 'auto', a11y: false,
             anchorWhy: 'none|Chrome 正占用 VPN 通道，已让位', extVpn: true,
             keepScreenOn: false, keepScreenMode: 0, touchBlock: false, radioFull: true,
             cd: true, cdAccounts: 2, cdToday: false,
             gwPort: 20128, cdPort: 47860, bindAll: true, lan: '192.168.1.23', vpn: '100.64.7.9',
             tunnelOn: true, tunnelUrl: 'https://demo-tunnel.trycloudflare.com' };

  function refresh() {
    if (!hasNative) { render(MOCK); return; }
    try { render(JSON.parse(window.Native.status())); }
    catch (e) { dot($('gdot'), 'bad'); $('gtitle').textContent = '状态读取失败'; $('gsub').textContent = String(e); }
  }


  refresh();
  setInterval(refresh, (typeof window.__WEB_BASE !== 'undefined') ? 8000 : 2500);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) refresh(); });
})();