package top.dassein.tenrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 10Router 安卓壳。
 *
 * 结构：FrameLayout 两层 —— 壳 UI（assets/ui，file://）+ 面板层（真实面板，顶层加载）。
 *
 * 面板为什么必须"顶层加载"而不是塞进 iframe：
 *   父页面是 file:// 时，iframe 内对 http://127.0.0.1 的请求算**跨站**，
 *   而网关的会话 Cookie 是 SameSite=Lax → 跨站不发送 → 登录 POST 成功但下一页又变未登录，
 *   用户看到的就是"密码输对了也进不去"。顶层加载后是同一站点，Cookie 正常。
 *
 * 交互设计上有一条硬约束（以前踩过的坑）：**任何需要用户操作的东西都不能放在启动页**。
 * 启动页只用于"首次解包"这一件真的需要等待的事，且不承载任何操作。
 */
public class MainActivity extends Activity {

    /** 载荷版本：bootstrap.zip 内容变了就 +1，否则老用户不会重新解包
     *  （已由 KeepAlive.payloadFingerprint 取代，仅作兼容保留：指纹随内容自动变，
     *   不需要人记得改常量 —— 曾因为要手工 +1 而漏更新，用户直接问"这个真的是更新了 10R 吗？"）*/
    static final int BOOT_VERSION = 4;

    /** 额度管家（CreditDaddy）在本机的数据目录与端口 */
    static final String CD_HOME = "home/.creditdaddy";
    static final int CD_PORT = 47860;

    private static final int REQ_VPN = 0x7A1;
    private static final int REQ_OVERLAY = 0x7A2;   // 触摸屏蔽覆盖层权限

    private FrameLayout rootView;
    private FrameLayout content;                 // 内容区（壳与面板叠放），上方留出底部导航
    private WebView web;                         // 壳 UI
    private WebView panelWeb;                    // 真实面板（网关 / 额度管家）
    private View panelVeil;                      // 面板加载遮罩：补丁生效前不露出上游裸页
    private int veilToken = 0;                   // 兜底揭罩的代次（新一次加载要让上一次的计时器作废）
    private LinearLayout[] navItems;             // 底部导航四项（原生常驻）
    private TextView[] navIcons, navLabels;
    private View[] navIndicators;                // 选中态顶部指示条（与壳同源）
    private String currentTab = "home";
    private int navHeightPx;                   // 导航实测高度（最低 dp(52)，含系统 inset 内边距）
    private int lastNavH = -1;                 // 上一次同步给内容区的导航高度
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean panelOpen = false;
    private volatile boolean extracting = false;
    private int extractFails = 0;
    private ShellWeb shellWeb;                   // 网页版 == APK：把壳原样搬到浏览器（:20130）

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        rootView = new FrameLayout(this);

        content = new FrameLayout(this);

        web = new WebView(this);
        tune(web);
        web.addJavascriptInterface(new NativeBridge(this), "Native");
        web.setWebViewClient(new Client());
        web.loadUrl("file:///android_asset/ui/index.html");
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));

        panelWeb = new WebView(this);
        tune(panelWeb);
        // ❗面板也必须挂桥：面板打不开时的"友好页"按钮（重试/看日志/重启）全靠它，
        // 上一版漏了这一句，导致那些按钮点了没反应（用户报"手段都用完了都不行"）。
        panelWeb.addJavascriptInterface(new NativeBridge(this), "Native");
        panelWeb.setWebViewClient(new Client());
        panelWeb.setVisibility(View.GONE);
        // ❗不要给面板强制暗色：两个上游项目都自带深浅两套主题（默认跟随系统），
        // 之前用 setForceDark 把它们全压成暗色，等于替上游做了它自己的决定。现在完全交给面板自己。
        content.addView(panelWeb, new FrameLayout.LayoutParams(-1, -1));

        // 遮罩必须是 content 的最后一个孩子：FrameLayout 后加者在上，才能盖住面板。
        panelVeil = buildPanelVeil();
        panelVeil.setVisibility(View.GONE);
        content.addView(panelVeil, new FrameLayout.LayoutParams(-1, -1));

        // ❗底部导航高度：以前写死 dp(52)，而内容实际需要 指示条2 + 上边距6 + 图标~28 +
        // 文字~16 + 下边距4 ≈ 56dp —— ROM 字体放大或厂商字体度量略大时，**最后一行文字被直接切掉**
        // （曾出现"导航栏文字被截断"）。改成"最低 52dp、按内容自动长高"，
        // 并把内容区的 bottomMargin 跟着实测高度走，两者永远一致。
        navHeightPx = dp(52);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        cp.bottomMargin = navHeightPx;
        View nav = buildNav();
        nav.setMinimumHeight(navHeightPx);
        rootView.addView(content, cp);
        rootView.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));   // -2 = WRAP_CONTENT
        setContentView(rootView);
        syncNavHeight(nav);
        // 手势导航条/三键导航条占的那几十像素也要让出来：有 bottom inset 就给导航加内边距并重测高度。
        // ❗监听挂在 rootView 上而不是 nav 上：targetSdk 28 下 decor 会把 inset 消耗掉，
        // 挂在子 View 上可能一次都不回调（那样这几十像素就永远压在文字上）。
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = navBottomInset(insets);
            int side = Math.max(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetRight());
            nav.setPadding(side, 0, side, bottom);
            syncNavHeight(nav);
            return insets;
        });
        applyNavTheme();   // 首次进入即按系统主题初始化导航底色 + 高亮当前 tab（home），不依赖首次点击
        KeepAlive.scheduleJob(this);
        KeepAlive.schedulePing(this);
        KeepAlive.watchNetwork(this);
        applyKeepScreenOn();
        // ⚠️ 触摸屏蔽**不自动恢复**：自动恢复会让用户在重启后被锁死，
        //    只能靠重启手机甚至安全模式脱困。改为「用户手动开启才生效」。
        askNotificationPermission();
        ensureBatteryExempt();
        new Thread(this::prepare).start();
        // 网页版：随「局域网访问」开关一起绑定 0.0.0.0/127.0.0.1（默认不开 LAN 时仅本机可访问）
        shellWeb = new ShellWeb(this, GatewayService.PORT, GatewayService.CD_PORT);
        shellWeb.start(KeepAlive.bindAll(this));
    }

    private void tune(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // ❗移动端关键：默认 Android WebView 布局视口是 980px，会无视页面里的
        // viewport meta（width=device-width），于是面板按桌面布局渲染（1120px .wrap
        // 等），在手机上直接溢出、一半在屏幕外。这两句让 meta 生效，布局视口=设备宽。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        w.setBackgroundColor(0xFF0A0A0A);
    }

    /** 底部导航：原生绘制，常驻显示（含面板页），不再有"顶部凭空多出一条栏"。
     *  视觉与壳 HTML 同源：背景取壳 --card 90% 透明（毛玻璃质感由半透明+系统合成近似），
     *  选中态用壳强调色 --acc(#F0EBE1) 画顶部 2px 指示条 + 图标/文字提亮，未选为 --dim2(#8C8C8C)。
     *  深浅色跟随系统（systemScheme），与壳 CSS 的 data-scheme 保持一致。 */
    private View buildNav() {
        boolean dark = "dark".equals(systemScheme());
        // 壳 --card 暗 #141414 / 亮 #ffffff，这里取 90% 不透明近似 CSS color-mix(card 90%, transparent)
        int card = dark ? 0xE6141414 : 0xE6FFFFFF;
        int line = dark ? 0xFF272727 : 0xFFE5E5E5;
        int dim  = dark ? 0xFF8C8C8C : 0xFF5F6368;   // 壳 --dim2
        int acc  = dark ? 0xFFF0EBE1 : 0xFF1F1F1F;   // 壳 --acc

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(card);
        // ❗col 的高度是 WRAP_CONTENT（见 onCreate）：里面的 bar 必须自己包内容，
        // 用"高度 0 + 权重 1"在包内容父容器下会被量成 0 高 —— 导航整条消失。
        col.setGravity(Gravity.CENTER);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        final String[] keys  = {"home", "gateway", "credit", "set"};
        final String[] icons = {"\u2302", "\u25a4", "\u25a6", "\u2699"};
        final String[] names = {"首页", "网关", "额度", "设置"};
        navItems = new LinearLayout[keys.length];
        navIcons = new TextView[keys.length];
        navLabels = new TextView[keys.length];
        navIndicators = new View[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(4));

            TextView ic = new TextView(this);
            ic.setText(icons[i]);
            ic.setTextSize(21);
            ic.setGravity(Gravity.CENTER);

            // 顶部 2px 选中指示条（与壳 nav button.on::before 同源），默认隐藏
            View ind = new View(this);
            ind.setBackgroundColor(acc);
            ind.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(2));
            ilp.topMargin = dp(0);
            item.addView(ind, ilp);

            TextView lb = new TextView(this);
            lb.setText(names[i]);
            lb.setTextSize(11.5f);
            lb.setGravity(Gravity.CENTER);

            item.addView(ic);
            item.addView(lb);
            item.setOnClickListener(v -> selectTab(k));
            bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
            navItems[i] = item; navIcons[i] = ic; navLabels[i] = lb; navIndicators[i] = ind;
        }
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));   // -2 = WRAP_CONTENT：随内容长高
        return col;
    }

    /** 切换标签： 首页/设置 → 壳； 网关/额度 → 真实面板（顶层加载） */
    void selectTab(String name) {
        currentTab = name;
        boolean isPanel = "gateway".equals(name) || "credit".equals(name);
        if (isPanel) {
            web.setVisibility(View.GONE);
            panelWeb.setVisibility(View.VISIBLE);
            panelOpen = true;
            openPanel(name);
        } else {
            panelOpen = false;
            hidePanelVeil("离开面板");
            panelWeb.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            js("tab('" + name + "')");
        }
        updateNavSelection();
    }

    private void updateNavSelection() {
        if (navIcons == null) return;
        boolean dark = "dark".equals(systemScheme());
        int dim = dark ? 0xFF8C8C8C : 0xFF5F6368;
        int acc = dark ? 0xFFF0EBE1 : 0xFF1F1F1F;
        String[] keys = {"home", "gateway", "credit", "set"};
        for (int i = 0; i < keys.length; i++) {
            boolean on = keys[i].equals(currentTab);
            int color = on ? acc : dim;
            navIcons[i].setTextColor(color);
            navLabels[i].setTextColor(color);
            navIndicators[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** 面板加载遮罩。
     *  为什么要它（生命周期处理）：点「打开网关面板」后 WebView 立刻可见，
     *  于是用户依次看到 ①空白 ②上游 SSR 的**英文**裸页（上游 lang 恒为 en）③水合后跳成中文
     *  ④手机适配补丁改完布局又跳一次 —— 四次可见的形态变化。正确顺序是
     *  「加载 + 注入 → 一次性显示最终态」，所以加载期间用不透明遮罩盖住，收到补丁的
     *  patchApplied 回调才揭开。
     *  ❗绝不用 setVisibility(GONE) 藏 WebView 本体：Chromium 对不可见 WebView 会节流渲染与
     *    定时器，补丁里的 MutationObserver 可能根本跑不完 —— 遮罩是盖在它上面，不是把它藏起来。
     *  ❗任何遮罩都必须自带逃生口：2600ms 无条件揭开，
     *    补丁没开、注入失败、页面卡住时都必须让用户看到东西。 */
    private View buildPanelVeil() {
        boolean dark = "dark".equals(systemScheme());
        FrameLayout box = new FrameLayout(this);
        // 底色跟上游两个面板自己的 theme-color 一致（暗 #0a0a0a / 亮 #FDFAF6），揭开时不会闪一块异色
        box.setBackgroundColor(dark ? 0xFF0A0A0A : 0xFFFDFAF6);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminate(true);
        TextView t = new TextView(this);
        t.setText("正在准备手机适配界面…");
        t.setTextSize(13f);
        t.setTextColor(dark ? 0xFFB8B8B8 : 0xFF5A5A5A);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(12);
        col.addView(pb, new LinearLayout.LayoutParams(-2, -2));
        col.addView(t, tp);
        box.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return box;
    }

    /** 一次面板加载开始：盖上遮罩 + 立兜底计时器（veilToken 让上一次的计时器作废，不会误揭新页面） */
    private void armPanelVeil() {
        if (panelVeil == null) return;
        // 补丁已改为源码级（1.0.0）：没有"注入完成"这个事件可等，遮罩一律不挂
        panelVeil.setVisibility(View.VISIBLE);
        final int tok = ++veilToken;
        ui.postDelayed(() -> { if (tok == veilToken) hidePanelVeil("兜底超时"); }, 2600);
    }

    /** 揭开遮罩。source 进日志，便于事后判断到底是"补丁报的"还是"超时兜的" */
    void hidePanelVeil(String source) {
        veilToken++;
        if (panelVeil != null && panelVeil.getVisibility() != View.GONE) {
            panelVeil.setVisibility(View.GONE);
            KeepAlive.logLine(this, "[veil] 面板揭罩：" + source);
        }
    }

    private class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
            if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                    || url.startsWith("file://")) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            return true;
        }

        // 面板是"为电脑做的"，手机适配补丁要尽早注入（可见前 + 加载后 + 路由变化后由补丁自身重跑）。
        // ❗只注入面板 WebView：壳页是我们自己写的，上游补丁的选择器撞进壳曾把 .app
        // column 容器加 wrap、main 折成第二列（表现为左死区、右侧被裁切）。
        @Override public void onPageCommitVisible(WebView v, String url) {
            // 手机适配已在源码级（补丁 1.0.0）：面板自己就是适配过的，不再注入、不再等遮罩
            if (v == panelWeb) hidePanelVeil("源码级补丁");
        }

        @Override public void onPageFinished(WebView v, String url) {
            // 面板打不开时，把日志尾直接铺到友好页上：用户不需要再点任何按钮
            if (url != null && url.contains("offline.html")) {
                // 友好页/老内核提示页是我们自己写的静态页，不需要适配补丁，直接显示
                if (v == panelWeb) hidePanelVeil("自家友好页");
            }
            if (url != null && url.contains("offline.html")) {
                boolean cd = url.contains("p=credit");
                String f = cd ? "cd.log" : "gateway.log";
                String t = tailLog(f, 14);
                // 载荷自检：缺什么文件一目了然，不用再靠一轮轮下载去猜
                String reason = KeepAlive.payloadReport(MainActivity.this)
                        + (cd ? "" : "");
                try {
                    v.evaluateJavascript("window.setReason && window.setReason("
                            + org.json.JSONObject.quote(reason) + ");"
                            + "window.setLog && window.setLog(" + org.json.JSONObject.quote(t) + ")", null);
                } catch (Throwable ignored) {}
            }
        }
    }

    /** 返回当前 WebView 内核的 Chromium 主版本号；无法检测返回 0。
     *  Android 8 (EMUI 8) 通常锁在 64-70；上游 10R Dashboard 需要 ≥105 才能解析 @container。
     *  从 UA 字符串解析 Chrome/xxx——WebViewPackageInfo 在编译路径里找不到，UA 是可靠替代。 */
    int getWebViewVersion() { return getWebViewVersion(panelWeb); }
    int getWebViewVersion(WebView w) {
        try {
            String ua = w.getSettings().getUserAgentString();
            return parseIntVersion(ua);
        } catch (Exception e) { return 0; }
    }

    private static int parseIntVersion(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 打开真实面板：先探活，起不来就给自家的友好页（绝不把浏览器原始报错甩给用户） */
    void openPanel(final String which) {
        final boolean gw = !"credit".equals(which);
        final String url = gw ? "http://127.0.0.1:" + GatewayService.PORT + "/dashboard"
                              : "http://127.0.0.1:" + CD_PORT + "/";
        final int wv = getWebViewVersion();
        // 旧内核渲染不了面板（上游 CSS 需 Chromium ≥111，低于它整块样式表被丢弃）。
        // 交给系统浏览器：Chrome 内核新，完整界面照常可用。
        if (wv > 0 && wv < 111) {
            openExternalBrowser(url);
            return;
        }
        armPanelVeil();                    // 入口就盖住：底部导航、壳里按钮、面板重开都走这里
        panelWeb.loadUrl("about:blank");   // 先清屏：否则会先闪一下上一个面板
        new Thread(() -> {
            String reason = null;
            if (!gw) {
                if (!KeepAlive.ensureCdPayload(this)) {
                    reason = "额度管家载荷未能就位。\n\n" + KeepAlive.payloadReport(this);
                }
            }
            final boolean up = probe(url);
            final String why = reason;
            ui.post(() -> {
                if (up) {
                    // 兜底计时从这里重算：探活也可能要时间，从"点按钮"起算会在慢机器上提前揭开、露出骨架屏
                    armPanelVeil();
                    panelWeb.loadUrl(url);
                    /* 友好兜底：面板进程在、网页也加载了，但若页面长时间仍无内容
                     * （上游面板自身崩溃/白屏），把原生日志铺上换页，不让用户面对空白桌面页。
                     *
                     * 原判定 5 秒 + (文字<120 且 元素<60) 过于激进 —— Next.js SPA
                     * 首屏要 fetch 数据，5 秒内 body 可能只有骨架，被误判成"空白"→ 切到离线页，
                     * 而网关其实健康（health ok）。改成 15 秒 + 更严判定（文字<60 且 元素<40 且
                     * document 已加载完），并排除登录页。 */
                    ui.postDelayed(() -> {
                        try {
                            panelWeb.evaluateJavascript(
                                "(function(){try{"
                                + "var b=document.body;if(!b){window.__panelBlank=true;return;}"
                                + "var t=(b.innerText||'').replace(/\\s+/g,'').length;"
                                + "var n=b.querySelectorAll('*').length;"
                                + "if(/password|登录|login/i.test(b.innerText||'')){window.__panelBlank=false;return;}"
                                + "if(t<60&&n<40&&document.readyState==='complete'){window.__panelBlank=true;return;}"
                                + "window.__panelBlank=false;}catch(e){}})();"
                                + "window.__panelBlank===true && window.Native && window.Native.act('panelBlank');",
                                null);
                        } catch (Throwable ignored) {}
                    }, 15000);
                } else {
                    panelWeb.loadUrl("file:///android_asset/ui/offline.html?p=" + (gw ? "gateway" : "credit"));
                    if (why != null) injectReason(why);
                }
            });
        }).start();
    }

    /** 把原生侧算出的原因写进"面板打不开"页（页面加载完再注入，避免被覆盖） */
    private void injectReason(final String text) {
        ui.postDelayed(() -> {
            try {
                panelWeb.evaluateJavascript("window.setReason && window.setReason("
                        + org.json.JSONObject.quote(text) + ")", null);
            } catch (Throwable ignored) {}
        }, 400);
    }

    void closePanel() { selectTab("home"); }

    /** 可达性探测：3 次、每次 700ms（进程刚起时给一点时间） */
    private boolean probe(String url) {
        for (int i = 0; i < 3; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(700);
                c.setReadTimeout(1200);
                c.setInstanceFollowRedirects(false);
                int rc = c.getResponseCode();
                c.disconnect();
                if (rc > 0) return true;
            } catch (Exception ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        }
        return false;
    }

    /** 系统当前深浅色（供壳切换自身主题；不干涉两个面板） */
    String systemScheme() {
        try {
            int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        } catch (Exception e) {
            return "dark";
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        js("window.onScheme && window.onScheme('" + systemScheme() + "')");
        applyNavTheme();
    }

    /** 导航实测高度 → 内容区 bottomMargin 跟随；否则内容会被压在导航下（或导航下留空缝）。
     *  用 post 到布局完成后再量：首帧前 getHeight()=0，量不到真实高度。 */
    private void syncNavHeight(final View nav) {
        if (nav == null) return;
        nav.post(() -> {
            int h = nav.getHeight();
            if (h <= 0 || h == lastNavH) return;
            lastNavH = h;
            navHeightPx = h;
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) content.getLayoutParams();
            cp.bottomMargin = h;
            content.setLayoutParams(cp);
        });
    }

    /** 底部系统栏（三键导航条 / 手势条）占的高度。API 30+ 走 systemBars，以下用兼容字段。 */
    private static int navBottomInset(android.view.WindowInsets insets) {
        if (insets == null) return 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** 系统深浅色切换时刷新原生导航的背景与配色（壳 HTML 端由 CSS 自动跟随，原生端需手动） */
    private void applyNavTheme() {
        if (navItems == null) return;
        boolean dark = "dark".equals(systemScheme());
        int card = dark ? 0xE6141414 : 0xE6FFFFFF;
        View navCol = rootView.getChildAt(rootView.getChildCount() - 1);
        if (navCol != null) navCol.setBackgroundColor(card);
        updateNavSelection();
    }

    /** 交给 UI 的复制能力 */
    void copyToClipboard(final String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("10Router", text));
            // 成功提示交给壳 JS 的"✓ 已复制"按钮态 + 绿 toast：双通道不重复刷屏
        } catch (Exception e) {
            js("toast('复制失败', 'bad')");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 启动准备 ----------------

    private void prepare() {
        try {
            // 闸门 = 载荷指纹（内容变了才重解包），不是人工 +1 的常量。
            // 旧标记 .ready_vN 直接弃用：老用户装上新包会按指纹重解包一次（1–2 分钟，仅一次），
            // 换来的是"装上的包 = 跑起来的 10R"，不再出现新包跑旧载荷。
            File marker = new File(getFilesDir(), ".ready_" + KeepAlive.payloadFingerprint(this, "bootstrap.zip"));
            if (!marker.exists()) {
                KeepAlive.logLine(this, "[shell] 载荷指纹 " + marker.getName() + "，开始解包");
                extract();
                marker.createNewFile();
            }
            startService(new Intent(this, GatewayService.class));   // 同时会带起额度管家
            js("onBoot(100,'运行环境就绪')");
            js("refresh()");
        } catch (Throwable t) {
            js("toast('初始化失败：" + esc(String.valueOf(t)) + "')");
        }
    }

    /** 首次解包。只在真的是首次时出现，且页面上只有一个进度条。 */
    private void extract() throws Exception {
        extracting = true;
        File base = getFilesDir();
        long total = 0;
        try {
            AssetFileDescriptor fd = getAssets().openFd("bootstrap.zip");
            total = fd.getLength();
            fd.close();
        } catch (Exception ignored) {}
        final long totalBytes = total;

        js("onBoot(1,'首次启动：正在准备运行环境，约 1–2 分钟，请留在本页')");
        CountingInputStream cin = new CountingInputStream(getAssets().open("bootstrap.zip"));
        ZipInputStream zin = new ZipInputStream(cin);
        ZipEntry e;
        byte[] buf = new byte[1 << 16];
        long lastUi = 0;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("..") || name.startsWith("/")) { zin.closeEntry(); continue; }
            File out = new File(base, name);
            if (e.isDirectory()) { out.mkdirs(); zin.closeEntry(); continue; }
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            // 逐条容错：单个文件失败不能让整包中断——上一版一旦中断，
            // 排在最末尾的组件就永久缺失，而前面的组件看着完全正常（真凶）
            try {
                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                if (name.startsWith("pfx/usr/bin/")) out.setExecutable(true, false);
            } catch (Exception we) {
                extractFails++;
                KeepAlive.logLine(this, "[shell] 解包跳过 " + name + "：" + we);
            }
            zin.closeEntry();
            long now = cin.count();
            if (now - lastUi > 3_000_000L && totalBytes > 0) {
                lastUi = now;
                int pct = (int) (now * 100 / totalBytes);
                js("onBoot(" + Math.min(pct, 99) + ",'首次启动：正在准备运行环境 " + pct + "%')");
            }
        }
        zin.close();
        // 关键文件校验：缺任何一个都不算完成（不置 ready 标记，下次启动会重来）
        boolean ok = criticalPayloadPresent();
        KeepAlive.logLine(this, "[shell] 解包结束：跳过 " + extractFails + " 项，关键文件齐全=" + ok);
        extracting = false;
        if (!ok) throw new IOException("关键文件缺失，解包未完成");
    }

    /** 三个关键文件都在，才算载荷可用 */
    private boolean criticalPayloadPresent() {
        File b = getFilesDir();
        return new File(b, "pfx/usr/bin/node").exists()
                && new File(b, "app/custom-server.js").exists()
                && new File(b, KeepAlive.CD_ENTRY).exists();
    }

    private static class CountingInputStream extends FilterInputStream {
        private long n = 0;
        CountingInputStream(InputStream in) { super(in); }
        long count() { return n; }
        @Override public int read() throws IOException {
            int c = super.read(); if (c >= 0) n++; return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len); if (r > 0) n += r; return r;
        }
    }

    // ---------------- 状态 ----------------

    /** 给 UI 的完整状态快照（JS 每 2.5s 拉一次，也会在回到前台时拉一次） */
    /** @param withSecret 调用方是否已通过鉴权；未鉴权时不回传网页密钥（否则局域网里
     *  任何设备 GET /api/state 就能拿到密钥、反过来绕过鉴权）。本机（loopback）视为已鉴权。 */
    String uiStatusJson() { return uiStatusJson(true); }

    String uiStatusJson(boolean withSecret) {
        JSONObject o = new JSONObject();
        try {
            o.put("ver", versionName());
            o.put("booting", extracting);
            o.put("gw", KeepAlive.isUp());
            o.put("gwPort", GatewayService.PORT);
            o.put("hbAge", KeepAlive.heartbeatAgeSec(this));
            o.put("boots", KeepAlive.bootCountOf(this));
            o.put("freezeCount", KeepAlive.freezeStats(this)[0]);
            o.put("notif", KeepAlive.notifEnabled(this));
            o.put("battery", KeepAlive.isIgnoringBattery(this));
            o.put("anchor", KeepAlive.anchorEnabled(this));
            // 注意："vpn" 这个键留给 100.x 网段字符串（下面远程段），锚点状态用独立键，别再撞车
            o.put("vpnAnchor", VpnKeepAliveService.active);
            o.put("audioAnchor", AudioAnchorService.active);
            o.put("anchorMode", KeepAlive.anchorMode(this));
            o.put("anchorWhy", KeepAlive.anchorState(this));
            o.put("extVpn", KeepAlive.externalVpn(this));
            o.put("keepScreenOn", KeepAlive.keepScreenOn(this));
            o.put("keepScreenMode", KeepAlive.keepScreenMode(this));   // 0关 1全亮 2亮度0
            o.put("touchBlock", KeepAlive.touchBlockEnabled(this));
            o.put("radioFull", GatewayService.wifiLockHeld);
            // 远程访问：局域网/虚拟网地址 + 公网隧道状态
            String[] ips = KeepAlive.lanAddresses().split("\\|", -1);
            o.put("lan", ips.length > 0 ? ips[0] : "");
            o.put("vpn", ips.length > 1 ? ips[1] : "");
            o.put("bindAll", KeepAlive.bindAll(this));
            o.put("gwPort", GatewayService.PORT);
            o.put("cdPort", GatewayService.CD_PORT);   // 设置页「网页端口」用
            o.put("tunnelOn", KeepAlive.tunnelEnabled(this));
            o.put("tunnelUrl", KeepAlive.tunnelUrl(this));

            // 额度管家：不去调它的 HTTP 接口（要面板密钥），直接读它自己的数据文件更稳
            o.put("cd", cdRunning());
            JSONArray acc = readJsonArray(new File(cdHome(this), "accounts.json"));
            if (acc != null) {
                o.put("cdAccounts", acc.length());
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                boolean done = false;
                for (int i = 0; i < acc.length(); i++) {
                    JSONObject a = acc.optJSONObject(i);
                    String lc = a == null ? "" : a.optString("lastCheckin", "");
                    if (lc.startsWith(today)) { done = true; break; }
                }
                o.put("cdToday", done);
            }
            // 网页版 == APK：浏览器入口（:20130）。免密钥，本机浏览器直接点开即可。
            o.put("webPort", ShellWeb.PORT);
            o.put("webOn", shellWeb != null && shellWeb.isRunning());
            o.put("webToken", withSecret && shellWeb != null ? shellWeb.getToken() : "");
        } catch (Exception ignored) {}
        return o.toString();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { return ""; }
    }

    static File cdHome(Activity a) {
        return new File(a.getFilesDir(), CD_HOME);
    }

    /** 额度管家是否在线：读它自己写的 daemon.json 里的 pid，比调接口轻且不需要密钥 */
    private boolean cdRunning() {
        try {
            JSONObject d = new JSONObject(readText(new File(cdHome(this), "daemon.json")));
            long pid = d.optLong("pid", -1);
            return pid > 0 && new File("/proc/" + pid).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONArray readJsonArray(File f) {
        try { return new JSONArray(readText(f)); } catch (Exception e) { return null; }
    }

    private static String readText(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ---------------- 动作 ----------------

    void uiAct(String name) {
        if (name == null) return;
        switch (name) {
            case "notify":    askNotificationPermission(); break;
            case "battery":   ensureBatteryExempt(); break;
            case "anchor":    KeepAlive.openAnchorSettings(this); break;
            case "autostart": KeepAlive.openAutostartSettings(this); break;
            case "vpn":         cycleAnchor(); break;
            case "anchor_auto":  setAnchor("auto"); break;
            case "anchor_vpn":   setAnchor("vpn"); break;
            case "anchor_audio": setAnchor("audio"); break;
            case "anchor_off":   setAnchor("off"); break;
            case "vpnSettings": openVpnSettings(); break;
            case "screenOn":  toggleScreenOn(); break;
            case "bgact":     bgActivitySettings(); break;
            case "mistouch":  openMistouchSettings(); break;
            case "touchBlock": toggleTouchBlock(); break;
            case "panelBlank": panelBlankToOffline(); break;
            case "restart":   restartGateway(); break;
            case "log":       showLog("gateway.log", "网关日志（尾部）"); break;
            case "cdlog":     showLog("cd.log", "额度管家日志（尾部）"); break;
            case "logs":      showLogPicker(); break;
            case "lanToggle":  toggleLan(); break;
            case "tunnelToggle": toggleTunnel(); break;
            case "tunnelLog":  showLog("tunnel.log", "隧道日志（尾部）"); break;
            case "diag":      KeepAlive.shareReport(this); break;
            case "update":    openUpdate(); break;
            case "updateSw":  openUpdate(); break;
            case "webReset":  if (shellWeb != null) shellWeb.rotateToken(); break;   // 局域网旧地址失效
            default: break;
        }
    }

    /** 手机适配补丁开关：关掉即完全按上游原样显示，便于判断问题是否由补丁引起 */
    /** 服务器常亮模式：窗口旗标即时生效 + 持久化（下次启动自动恢复）。诚实文案已进 UI。 */
    private void toggleScreenOn() {
        int m = KeepAlive.keepScreenMode(this);
        int next = m >= 2 ? 0 : m + 1;   // 0(关) → 1(全亮) → 2(亮度0) → 0
        KeepAlive.setKeepScreenMode(this, next);
        applyKeepScreenOn();
        String t = next == 0 ? "已关：屏幕会正常熄灭（靠前台服务+锚点保活；手动锁屏则触控禁用、不误碰）"
                : next == 1 ? "全亮已开（屏幕不自动熄灭；放口袋会误碰）"
                : "亮度0已开：屏幕黑但【触控仍生效】——只防冻结，不防误碰；要防误碰请手动锁屏或开系统「防误触保护」";
        js("onNativeResult('screenOn','" + t + "')");
    }

    private void applyKeepScreenOn() {
        try {
            int mode = KeepAlive.keepScreenMode(this);
            android.view.WindowManager.LayoutParams p = getWindow().getAttributes();
            if (mode > 0) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                // 亮度0：内容不可见（等同黑屏）→ 放口袋不误碰；系统仍认为亮着 → 防 ROM 冻结
                p.screenBrightness = (mode == 2) ? 0.0f : -1.0f;   // -1 = BRIGHTNESS_OVERRIDE_NONE
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                p.screenBrightness = -1.0f;
            }
            getWindow().setAttributes(p);
        } catch (Exception ignored) {}
    }

    /** 直达系统「防误触保护 / 口袋模式」设置页。
     *  真相：亮度0 只关背光，触控层照样工作（StackOverflow 权威确认）；真正防误触只有两条路——
     *  ① 屏幕真正关闭（手动锁屏，触控由锁屏界面接管/禁用）② 系统级防误触保护（距离传感器检测遮挡）。
     *  各厂商路径不同，逐个试；都打不开就提示用户手动找。 */
    private void openMistouchSettings() {
        String[] acts = {
            "android.settings.DISPLAY_SETTINGS",           // 三星：显示 → 防误触保护
            "com.android.settings.Settings$LockScreenSettingsActivity",  // 小米：锁屏 → 口袋模式
            "com.oneplus.settings.SettingsActivity",        // 一加：便捷工具 → 口袋模式
            "android.settings.SETTINGS"
        };
        for (String a : acts) {
            try { startActivity(new Intent(a)); js("toast('找「防误触保护 / 口袋模式 / 防误触」并开启（靠距离传感器检测口袋）')"); return; }
            catch (Exception ignored) {}
        }
        js("toast('请手动到 设置→显示 里找「防误触保护 / 口袋模式」开启','bad')");
    }

    /** 直达「后台活动/省电」授权页（与冻结通知同一链路，保证入口一致） */
    private void bgActivitySettings() {
        try {
            startActivity(KeepAlive.backgroundRunIntent(this));
        } catch (Exception e) {
            js("toast('打不开授权页：请手动到 设置→应用→10Router→电池 里放行','bad')");
        }
    }

    /** 允许局域网/虚拟网访问：切换网关监听地址（需要重启网关生效） */
    /** 面板进程在、网页也"加载完"了，但 15 秒后页面仍无内容（上游白屏/崩溃）：
     *  原样递给用户一个无声的空桌面页毫无意义——切到离线友好页并带上原生日志。
     *  注意措辞：网关日志显示进程健康（health ok），所以是"页面没渲染出来"，不是"进程没起"。 */
    void panelBlankToOffline() {
        String tab = currentTab;
        boolean gw = !"credit".equals(tab);
        String f = gw ? "gateway.log" : "cd.log";
        String t = tailLog(f, 14);
        final String log = t;
        panelWeb.loadUrl("file:///android_asset/ui/offline.html?p=" + (gw ? "gateway" : "credit"));
        ui.postDelayed(() -> {
            try {
                panelWeb.evaluateJavascript("window.setReason && window.setReason("
                        + JSONObject.quote("面板页面加载后仍无内容（不是进程没起——网关日志显示健康）。下为原生日志尾部：")
                        + ");window.setLog && window.setLog(" + JSONObject.quote(log) + ")", null);
            } catch (Throwable ignored) {}
        }, 400);
    }

    private void toggleLan() {
        boolean on = KeepAlive.bindAll(this);
        KeepAlive.setBindAll(this, !on);
        if (shellWeb != null) shellWeb.start(!on);   // 网页版跟着 LAN 开关一起重绑
        try {
            Intent i = new Intent(this, GatewayService.class);
            i.setAction(GatewayService.ACTION_RESTART);
            startService(i);
        } catch (Exception ignored) {}
        js("onNativeResult('lan','局域网访问已" + (on ? "关闭" : "开启") + "，网关正在重启')");
    }

    /**
     * 公网隧道开关（替代 Tailscale Funnel）。
     * 开启前先做安全检查：接口必须已启用鉴权（否则等于把接口公开给全网）。
     * 隧道只暴露 /v1，管理面板不暴露 —— 这是刻意的设计。
     */
    private void toggleTunnel() {
        boolean on = KeepAlive.tunnelEnabled(this);
        if (on) {
            KeepAlive.setTunnelEnabled(this, false);
            try {
                Intent i = new Intent(this, GatewayService.class);
                i.setAction(GatewayService.ACTION_TUNNEL);
                startService(i);
            } catch (Exception ignored) {}
            js("onNativeResult('tunnel','公网隧道已关闭')");
            return;
        }
        new Thread(() -> {
            String block = tunnelPrecheck();
            final String b2 = block;
            ui.post(() -> {
                if (b2 != null) { js("onNativeResult('tunnel','" + esc(b2) + "')"); return; }
                KeepAlive.setTunnelEnabled(MainActivity.this, true);
                try {
                    Intent i = new Intent(MainActivity.this, GatewayService.class);
                    i.setAction(GatewayService.ACTION_TUNNEL);
                    startService(i);
                } catch (Exception ignored) {}
                js("onNativeResult('tunnel','正在建立公网隧道，约 10 秒后显示地址')");
            });
        }).start();
    }

    /** 返回非 null 表示不允许开启（并说明原因）；null 表示通过 */
    private String tunnelPrecheck() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + GatewayService.PORT + "/v1/models").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(5000);
            int rc = c.getResponseCode();
            c.disconnect();
            if (rc == 200) return "接口没有鉴权，已拒绝开启：请先在网关面板设置访问密钥";
            return null;
        } catch (Exception e) {
            return "网关未就绪，稍后再试";
        }
    }

    /** 补丁热更新：只拉 ~20KB 的补丁文件，不需要重装 App（闸 5） */
    /** 一键回滚到随包内置补丁（闸 6 的回退通道） */
    private void restartGateway() {
        try {
            Intent i = new Intent(this, GatewayService.class);
            i.setAction(GatewayService.ACTION_RESTART);
            startService(i);
            js("toast('已请求重启网关')");
        } catch (Exception e) {
            js("toast('重启失败：" + esc(String.valueOf(e)) + "')");
        }
    }

    /** 日志选择器：网关 / 额度管家。两个日志入口合并成一个，减少按钮数量。 */
    private void showLogPicker() {
        try {
            new AlertDialog.Builder(this)
                .setTitle("查看日志")
                .setItems(new String[]{"网关日志", "额度管家日志"},
                    (d, w) -> showLog(w == 0 ? "gateway.log" : "cd.log",
                                      w == 0 ? "网关日志（尾部）" : "额度管家日志（尾部）"))
                .show();
        } catch (Exception e) {
            showLog("gateway.log", "网关日志（尾部）");
        }
    }

    private void showLog(String file, String title) {
        final String text = tailLog(file, 200);
        ui.post(() -> {
            try {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(text);
                tv.setTextSize(10.5f);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                tv.setPadding(28, 28, 28, 28);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .show();
            } catch (Throwable ignored) {}
        });
    }

    /**
     * 检查更新：先取线上版本清单，与本机 versionCode 比 —— **禁止降级**是硬约束。
     *
     * 旧实现是无条件打开下载页，而页面上主按钮指向的是老版本；本机版本比它新的人点下去
     * 只会得到"应用未安装"（系统拒绝降级），用户看到的却是"你让我下载了一个装不上的包"。
     * 现在：只有线上更新才给入口，且入口带上本机 vc，页面按 vc 决定该给你哪个包。
     */
    private void openUpdate() {
        js("toast('正在检查版本…')");
        new Thread(() -> {
            String msg; String url = null;
            int mine = 0; String myName = "?";
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                mine = pi.versionCode; myName = pi.versionName;
            } catch (Exception ignored) {}
            try {
                org.json.JSONObject o = new org.json.JSONObject(
                        KeepAlive.fetchPair(GatewayService.UPDATE_MANIFEST, KeepAlive.MIRROR_MANIFEST, 64 * 1024));
                int rv = o.optInt("versionCode", 0);
                String rn = o.optString("versionName", "?");
                if (rv <= 0) throw new Exception("清单缺版本号");
                if (rv > mine) {
                    msg = "有新版本 " + rn + "（本机 " + myName + "）";
                    url = GatewayService.APK_URL + "?vc=" + mine;
                } else if (rv == mine) {
                    msg = "已是最新（" + myName + "）";
                } else {
                    msg = "线上 " + rn + " 比本机 " + myName + " 低 —— 不给降级";
                }
            } catch (Exception e) {
                msg = "检查失败（网络或清单不可用）";
            }
            final String m = msg, u = url;
            runOnUiThread(() -> {
                // 走 onNativeResult：壳按钮的"进行中"态由此解锁，消息按成败分色（裸 toast 不会解锁按钮）
                js("onNativeResult('update','" + esc(m) + "')");
                if (u != null) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
                    catch (Exception e) { js("toast('打不开更新页', 'bad')"); }
                }
            });
        }).start();
    }

    /** 版本比较：1.2.3 > 1.2.2 返回 1，相等 0，小于 -1。 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int va = parseInt(pa[i]), vb = parseInt(pb[i]);
            if (va > vb) return 1;
            if (va < vb) return -1;
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    /** 从 npm registry 获取最新版本号；失败返回 null。 */
    private static String fetchNpmLatest(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONObject(sb.toString()).optString("version", null);
        } catch (Exception e) { return null; }
    }

    /** 读文件全部字节（Java 8 兼容）。 */
    private static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 打开外部浏览器访问给定 URL（低版本 WebView 兜底：跳系统浏览器看项目总览网页）。 */
    void openExternalBrowser(String url) {
        try {
            if (url == null || url.isEmpty()) url = "https://das-sein.top/10router/";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            js("toast('无法打开浏览器，请手动访问 das-sein.top/10router/','bad')");
        }
    }

    /** 打开外部 URL（面板低版本兜底：交给系统浏览器）。 */
    private void openUrl() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/chrome/")));
        } catch (Exception e) {
            js("toast('无法打开链接，请手动搜索安装 Chrome','bad')");
        }
    }

    // ===================== 运行时真更新（10R / CD）=====================
    // 用户要的是"点击就更新"，不是"检查后提示去装 APK"。10R/CD 都内嵌在 APK 里，
    // 但构建时会把它们的运行时单独发布成 payload 包，App 下载后替换 files/app|cd 即可。
    // 安全线（用户硬规则：改动前必须有"不坏"的方法）：
    //   ① 下载到 cache，不碰现有目录 ② 解压到临时目录 ③ 校验 package.json
    //   ④ 停服务 ⑤ 旧目录 rename 成 .bak ⑥ 新目录 rename 上位 ⑦ 起服务 ⑧ 任一步失败即回滚

    private String readRuntimeVersion(boolean is10r) {
        try {
            File f = new File(new File(getFilesDir(), is10r ? "app" : "cd"), "package.json");
            if (!f.exists()) return "0.0.0";
            return new JSONObject(new String(readAllBytes(f))).optString("version", "0.0.0");
        } catch (Exception e) { return "0.0.0"; }
    }

    private void downloadFile(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { c.disconnect(); }
    }

    /** 下载小文本文件（版本清单用，几十字节）。 */
    private static String downloadText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    private void unzip(File zip, File dest) throws IOException {
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("无法创建解压目录");
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(new FileInputStream(zip)))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n; while ((n = z.read(buf)) > 0) fo.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }


    /** 切换守候锚点模式：auto=VPN 优先、被占自动让位音频 | vpn=强制 VPN 锚 | audio=只用音频锚 | off=关。
     *  互切本身由 KeepAlive.evaluateAnchor 裁决，这里只负责"存意愿 + 补授权 + 立刻重估"。 */
    private void setAnchor(String mode) {
        KeepAlive.setAnchorMode(this, mode);
        if (mode.equals("vpn") || mode.equals("auto")) {
            try {
                if (!VpnKeepAliveService.active && VpnService.prepare(this) != null) {
                    js("toast('用 VPN 锚需授权一次（只驻留、不接管流量）；拒绝也行，会自动改挂音频锚')");
                    startActivityForResult(VpnService.prepare(this), REQ_VPN);
                }
            } catch (Exception e) {
                KeepAlive.logLine(this, "[anchor] VPN 授权异常: " + e);
            }
        }
        KeepAlive.evaluateAnchor(this, true);
        js("toast('守候模式 → " + mode + "，锚点已在调整')");
    }

    /** 旧链接兼容：一个按钮开/关（默认给最省心的 auto） */
    private void cycleAnchor() {
        setAnchor(KeepAlive.anchorMode(this).equals("off") ? "auto" : "off");
    }

    private void openVpnSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_VPN_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            js("toast('VPN 列表里点 10Router 旁齿轮 → 开「始终开启」，断线系统自己拉回')");
        } catch (Exception e) {
            js("toast('请手动进入 设置 → 网络 → VPN')");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            js(res == RESULT_OK ? "toast('已授权：切回 VPN 锚（第 4 档）')"
                                : "toast('已取消 VPN 授权：继续用音频锚，不占你的 VPN 通道')");
            KeepAlive.evaluateAnchor(this, true);
        } else if (req == REQ_OVERLAY) {
            // 用户从悬浮窗设置回来：再查一次，成了就自动开启屏蔽
            if (KeepAlive.canDrawOverlays(this)) {
                KeepAlive.setTouchBlock(this, true);
                js("onNativeResult('touchBlock','悬浮窗权限已给：触摸屏蔽已开')");
            } else {
                js("toast('悬浮窗权限没给：覆盖层画不出来，触摸屏蔽无效','bad')");
            }
        }
    }

    /** 触摸屏蔽开关：全屏覆盖层吃掉所有 MotionEvent。
     *  亮度0 只关背光、电容触控层照样工作（会误触）——所以「屏幕看不见 + 禁止触摸」要两者一起开。 */
    private void toggleTouchBlock() {
        if (KeepAlive.touchBlockEnabled(this)) {
            KeepAlive.setTouchBlock(this, false);
            js("onNativeResult('touchBlock','触摸屏蔽已关')");
            return;
        }
        if (!KeepAlive.canDrawOverlays(this)) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
                js("toast('要「禁止触摸」需给一次悬浮窗权限（画一层看不见的网挡住误触）')");
            } catch (Exception e) {
                js("toast('打不开悬浮窗授权页，请手动到 设置→应用→10Router→显示在其他应用上层','bad')");
            }
            return;
        }
        KeepAlive.setTouchBlock(this, true);
        js("onNativeResult('touchBlock','触摸屏蔽已开：屏幕看不见 + 点击/滑动全部无效')");
    }

    /** 一键领取今日额度：原生侧发 HTTP，避开 file:// 页面的跨域限制 */
    void uiClaim() {
        new Thread(() -> {
            String msg;
            try {
                JSONObject st = new JSONObject(readText(new File(cdHome(this), "settings.json")));
                String key = st.optString("panelKey", "");
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + CD_PORT + "/api/checkin").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.setReadTimeout(60000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                if (!key.isEmpty()) c.setRequestProperty("x-qd-key", key);
                c.getOutputStream().write("{}".getBytes("UTF-8"));
                int rc = c.getResponseCode();
                String body = readBody(c);
                c.disconnect();
                msg = rc == 200 ? (body.contains("\"error\"") ? "返回异常，请到额度面板查看" : "领取完成")
                                : (rc == 401 || rc == 403 ? "额度管家要求验证，请打开额度面板"
                                                          : "领取失败（HTTP " + rc + "）");
            } catch (Exception e) {
                msg = "领取失败：额度管家未就绪";
            }
            final String m = msg;
            ui.post(() -> js("onNativeResult('claim','" + esc(m) + "')"));
        }).start();
    }

    private static String readBody(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ---------------- 系统权限 ----------------

    void ensureBatteryExempt() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    void askNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Exception e) {
            KeepAlive.logLine(this, "[shell] 请求通知权限失败: " + e);
        }
    }

    // ---------------- 杂项 ----------------

    private String tailLog(String name, int lines) {
        try {
            File f = new File(getFilesDir(), name);
            if (!f.exists()) return "(暂无日志)";
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            ArrayDeque<String> dq = new ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) { dq.addLast(line); if (dq.size() > lines) dq.pollFirst(); }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (String l : dq) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(读日志失败 " + e + ")";
        }
    }

    private void js(final String code) {
        ui.post(() -> {
            try { web.evaluateJavascript(code, null); } catch (Exception ignored) {}
        });
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) { closePanel(); return; }
        super.onBackPressed();
    }
}
