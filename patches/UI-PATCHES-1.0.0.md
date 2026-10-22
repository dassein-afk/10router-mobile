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
CreditDaddy 只发布 npm 包，面板是单文件 `src/panel.html`，改动直接施加在该文件上。

### 1. 显示适配样式

追加到 `panel.html` 的 `<style>` 段末尾：

```css
/* 媒体元素不超过容器 */
img, svg, video, canvas, iframe { max-width: 100%; }
img, video { height: auto; }

/* 长字符串（密钥 / URL / 模型名）可断行，避免撑破容器 */
code, pre, kbd, samp { overflow-wrap: anywhere; }

body { max-width: 100%; }

@media (max-width: 560px) {
  /* 输入框字号小于 16px 时，聚焦会触发系统缩放整页 */
  input, select, textarea { font-size: max(16px, 1em); }
  /* 全屏弹窗宽度自适应，两侧各留 3% */
  [role="dialog"] { max-width: 94%; max-height: 88%; overflow: auto; }
}

/* 头部右侧浮层锚点从「28px 宽的齿轮按钮」上移到「整行」。
   原样式中浮层 right:0 相对齿轮，而齿轮右侧还有九宫格按钮，
   360px 视口下浮层 left = -26，左列内容被裁切。 */
.head-right { position: relative; }
.head-right .head-setpop { position: static; }
```

### 2. 布局适配脚本

追加到 `panel.html` 的 `</body>` 前，用 `<script>` 包裹。

脚本处理窄屏下的容器级排版，**不改字号、不改控件尺寸、不改列数**：

| 函数 | 作用 |
|---|---|
| `passStack` | 栅格在窄屏下放不下时收成单列 |
| `passWidth` | 元素宽于视口时约束到 100% |
| `passWrap` | 一行放不下时允许换行 |
| `passReveal` | 容器裁掉内容时改为可滚动 |
| `passUnclip` | 文字被裁切时允许换行 |
| `passScroll` | 宽表格包一层横向滚动容器 |
| `passContrast` | 文字与背景对比度不足时调整颜色（不改字号） |
| `passCdLoading` | 加载浮层长时间未消失时移除 |
| `passTabHint` | 横向标签条增加可滑动提示 |
| `passLangFlag` | 语言按钮图标与界面语言一致 |
| `passPitch` | 竖排菜单项间距补到可点范围（不改控件本身） |

**定位原则**（改动这些函数时必须遵守）：

- 只在叶子节点上做文本匹配，父容器不参与判定
- 文案一律全等匹配，不用"包含"（包含会把子元素文本算进父容器）
- 从命中元素扩到"整行"时，必须校验该层没有其它实质文本内容
- 兜底只隐藏命中的那个元素，不隐藏容器

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
