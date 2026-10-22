package top.dassein.tenrouter;

import android.webkit.JavascriptInterface;

/**
 * UI(HTML) 与原生之间的唯一桥。
 *
 * 注意：@JavascriptInterface 方法运行在 WebView 的 JS 线程，**不是** UI 线程，
 * 所以这里的每个方法都必须 post 回主线程，否则会崩（CalledFromWrongThreadException）。
 * 方法名与 ui/app.js 里 window.Native.* 一一对应，改名要两边同时改。
 */
public class NativeBridge {

    private final MainActivity a;

    NativeBridge(MainActivity activity) {
        this.a = activity;
    }

    /** 复制文本到系统剪贴板（file:// 页面里 navigator.clipboard 不可靠，走原生） */
    @JavascriptInterface
    public void copy(final String text) {
        a.runOnUiThread(() -> a.copyToClipboard(text));
    }

    /** 系统当前深浅色：壳据此切换自己主题（不干涉两个面板自己的选择） */
    @JavascriptInterface
    public String scheme() {
        return a.systemScheme();
    }

    /** 一次性返回当前全部状态（JSON 字符串）。UI 每 2.5s 轮询一次。 */
    @JavascriptInterface
    public String status() {
        return a.uiStatusJson();
    }

    /** 执行一个动作：battery / notify / anchor / autostart / vpn / restart / log / diag / update */
    @JavascriptInterface
    public void act(String name) {
        a.runOnUiThread(() -> a.uiAct(name));
    }

    /** 一键领取今日额度（原生侧发 HTTP，避免 file:// 页面的跨域限制） */
    @JavascriptInterface
    public void claim() {
        a.runOnUiThread(() -> a.uiClaim());
    }

    /** 打开真实面板：原生顶层加载（第一方上下文），网关的 SameSite=Lax 会话 Cookie 才会带上 */
    @JavascriptInterface
    public void openPanel(String which) {
        a.runOnUiThread(() -> a.openPanel(which));
    }

    /** 补丁层自报"手机适配已生效"：面板遮罩据此揭开。
     *  生命周期问题 —— 面板一 loadUrl 就可见，于是先看到上游裸页
     *  （SSR 还是英文），水合跳一次中文，补丁再改布局又跳一次。正确顺序是
     *  「注入完成 → 一次性显示最终态」。这个方法就是那句"注入完成了"。 */
    @JavascriptInterface
    public void patchApplied() {
        a.runOnUiThread(() -> a.hidePanelVeil("patch"));
    }

    /** 在系统浏览器（外部）打开一个 URL —— 低版本 WebView 兜底：面板交给 Chrome（内核新），完整界面照常可用。 */
    @JavascriptInterface
    public void openBrowser(final String url) {
        a.runOnUiThread(() -> a.openExternalBrowser(url));
    }
}
