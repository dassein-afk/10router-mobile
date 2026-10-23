# 上游补丁说明 · UI 适配 1.0.0

APK 是 10Router 与 CreditDaddy 的一份副本。手机环境与桌面环境有差异，需要少量源码改动。
本文档逐条说明这些改动：改了什么、为什么改、改在哪。

`1.0.0` 是这套改动的内容集合版本号。上游升级时按本文档重新施加，不通过运行时注入。

## 为什么不运行时注入

早期做法是在 WebView 加载面板后注入 CSS 与脚本。这种方式有三个固有问题：

| 问题 | 表现 |
|---|---|
| 时序空窗 | 面板先渲染、补丁后到，界面跳动一次 |
| 静默失效 | 注入文件不在打包白名单里时，包能正常签名但适配整层不生效 |
| 定位不可靠 | 靠文本匹配 + 向上找容器定位元素，容易波及相邻内容 |

源码改动直接改具体组件，不扫描、不猜、不依赖时序，因此改为源码级。

---

## 一、10Router（`techysy/10router`）

补丁文件：`patches/04`、`patches/05`、`patches/06`
重放：`tools/upstream.sh apply <tag> <outdir>`（任一补丁打不上即非 0 退出）

### 1. 侧栏背景不透明

**文件**：`src/app/globals.css`
**位置**：`.bg-vibrancy { … }` 与 `.dark .bg-vibrancy { … }`

```css
/* 修改前 */
.bg-vibrancy {
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  background: rgba(255, 255, 255, 0.72);
}
.dark .bg-vibrancy { background: rgba(38, 38, 38, 0.72); }

/* 修改后 */
.bg-vibrancy {
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  background: var(--color-bg);
}
.dark .bg-vibrancy { background: var(--color-bg); }
```

**原因**：Android WebView 对 `backdrop-filter` 支持不稳定。模糊不生效时侧栏只剩 72% 透明底色，
与下方内容叠加后文字互相穿透。改用不透明主题色，深浅色主题自动跟随。

`.bg-vibrancy` 目前只在 `src/shared/components/Sidebar.js` 的 `<aside>` 上使用。
若上游将此工具类用于其它元素，需要重新评估。

### 2. 移除公网隧道与虚拟网入口

**文件**：`src/app/(dashboard)/dashboard/endpoint/EndpointPageClient.js`
**位置**：`{/* Cloudflare Tunnel */}` 与 `{/* Tailscale */}` 两段 JSX
（位于 `<EndpointRow label="Local">` 之后的 `<div className="flex flex-col gap-2">` 内）

整段删除，保留 `Local` 行与该容器。

**原因**：本 App 的远程访问方案是用户自有的 WireGuard 或局域网，不使用公网隧道服务。
面板中出现相关入口会引导用户开启本 App 不支持、且存在安全风险的通道。

删除后 `tunnelEnabled` / `tsEnabled` 恒为 false，其后的安全提示块条件不成立，无需额外处理。

### 3. 移除面板内更新入口

**文件**：`src/shared/components/Sidebar.js`
**位置**：`{updateInfo && ( … )}` 整块（含 `New version available` / `Update now` / `Download new APK`）

**原因**：APK 内嵌上游，版本随 App 一起升级。面板内的更新入口会引导用户执行 npm 安装命令，
而 App 环境里没有 npm，执行会中断网关进程。更新入口统一由 App 的「设置 → 更新」提供。

---

## 二、CreditDaddy（`techysy/CreditDaddy`）

补丁文件：`cd-patches/01-src_panel_html.patch`
CreditDaddy 只发布 npm 包，面板是单文件 `src/panel.html`，改动直接施加在该文件的 `<style>` 段上。

### 只有一处改动：`<style>` 里的手机适配规则

**不追加任何 `<script>`。** 这是硬性约束，`tools/build-cd.sh` 会用反向断言拦截（见下）。

```css
/* 媒体元素不超过容器 */
img, svg, video, canvas, iframe { max-width: 100%; }
img, video { height: auto; }

/* 长字符串（密钥 / URL / 模型名）可断行 */
code, pre, kbd, samp, .mono { overflow-wrap: anywhere; }

/* 根节点不横向撑破 */
html, body { max-width: 100%; overflow-x: hidden; }

/* 顶部标签栏：可滑 + 让"还能滑"一眼可见（不依赖 JS 加类） */
.nav {
  -webkit-overflow-scrolling: touch;
  scrollbar-width: thin;
  overscroll-behavior-x: contain;
  -webkit-mask-image: linear-gradient(90deg,#000 0,#000 calc(100% - 18px),rgba(0,0,0,.45) 100%);
          mask-image: linear-gradient(90deg,#000 0,#000 calc(100% - 18px),rgba(0,0,0,.45) 100%);
}
.nav::-webkit-scrollbar { height: 3px; display: block; }
.nav::-webkit-scrollbar-thumb { background: rgba(128,128,128,.55); border-radius: 99px; }

/* 结构性截断改可滚；弹性子项不被内容撑破 */
.list, .cards, .grid, .row, table { max-width: 100%; }
.row > *, .flex > * { min-width: 0; }
.desc, .note, .sub, .hint, p, small { overflow-wrap: anywhere; word-break: break-word; }

@media (max-width: 560px) {
  input, select, textarea { font-size: max(16px, 1em); }
  [role="dialog"], .modal, .mask > .box { max-width: 94vw; max-height: 88vh; overflow: auto; }
}

/* 面板设置浮层：锚点从 28px 宽的齿轮按钮上移到整行。
   原 right:0 相对齿轮，而齿轮右侧还有九宫格按钮，360px 视口下 left=-26，左列被裁。 */
.head-right { position: relative; }
.head-right .head-setpop { position: static; }
```

### 早年那套运行时脚本为什么全部废止

早期版本在 `</body>` 前插入一个 `<script>`，用 11 个 `pass*` 函数扫 DOM 量宽度再改。它被废止的理由，每一条都在真机上复现过：

| 旧做法 | 换成什么 |
|---|---|
| `passTabHint` 给标签条加类做可滑提示 | CSS 的 `mask-image` + `::-webkit-scrollbar`，不依赖类 |
| `passUnclip` 把 `nowrap` 改成换行 | `overflow-wrap: anywhere` 写在容器上 |
| `passReveal` 截断改可滚 | `[role=dialog],.modal{overflow:auto}` |
| `passWidth` / `passWrap` 事后抠宽度 | `min-width:0` + `max-width:100%` |
| `passContrast` 逐元素改色 | 删除。上游自带主题变量与 `prefers-color-scheme` |
| `passStack` 栅格收单列 | 上游自带 `@media (max-width:560px){.list{grid-template-columns:1fr}}` |

废止的三条根本原因：

1. **扫漏就错乱**：`document.querySelectorAll('body *')` 遍历所有层级，父容器的 `textContent` 天然包含子元素文本，命中一次就能改坏整块布局。
2. **慢一拍**：页面已经画出来才动手，用户能看见跳变；`MutationObserver` 还会被不可见 WebView 节流。
3. **提示靠类生效**：给标签条加类再靠类里的 `mask` 出提示——`mask` 在 WebView 里常失效，结果滑到末尾没有任何提示，看起来就是一片空白。

### 构建闸门（`tools/build-cd.sh`）

```bash
# 正断言：纯 CSS 适配层必须在
grep -q "手机适配 —— 纯 CSS" src/panel.html || exit 6
grep -q "mask-image: linear-gradient" src/panel.html || exit 6

# 反断言：绝不允许运行时注入脚本
grep -q "panelFix\|pf-tabscroll\|原 panel-fix.js" src/panel.html && exit 10
NSCRIPT=$(grep -c "<script" src/panel.html); [ "$NSCRIPT" -le 1 ] || exit 11
```

上游 `panel.html` 自带 1 个业务 `<script>`，出现第 2 个就是又塞了适配脚本。

> 闸门查整个文件，**注释里写出 `pf-tabscroll` 这类类名也会被拦**——这是故意的，防止后来者照注释恢复旧做法。写注释用「运行时加类」，不写类名。

---

## 三、上游升级流程

```bash
# 1. 10Router：重放补丁，冲突即停
tools/upstream.sh apply v<新tag> /tmp/x

# 2. CreditDaddy：重放面板补丁
tools/build-cd.sh <新版本>
#    若冲突，按本文档「二」手工改一遍后重新生成：
#    diff -u <原panel.html> <改后panel.html> > cd-patches/01-src_panel_html.patch

# 3. 打包
tools/build-android.sh v<新tag> <cd版本>
```

`tools/build-android.sh` 在任一环节失败时退出，不产出不完整的包。

---

## 四、历史备份

`backup/panel-fix-1.0.0.{js,css}` 是改造前的运行时注入版本，仅作规则出处参考，不参与构建。

## 五、回归测试

`tools/tests/tunnel-regress.js` 针对"隐藏元素时不能波及相邻内容"这一判据编写（6 个场景，
每个场景都有"必须隐藏"与"必须保留"两组断言）。补丁改为源码级后，它测试的对象已不存在，
但其中记录的定位原则仍适用于 `panel.html` 里任何"隐藏元素"的改动。
