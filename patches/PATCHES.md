# 上游源码补丁清单（10Router）

补丁集由 `tools/upstream.sh` 管理：`make-patches` 生成、`apply` 重放、`assert` 校验。
**任一补丁打不上就非 0 退出，绝不静默出包。**

> UI/手机适配那条线单独看 `UI-PATCHES-1.0.0.md`（补丁版本 1.0.0，已冻结）。

## 当前 6 个补丁（针对 10Router v1.6.0）

| # | 文件 | 改什么 | 类别 |
|---|---|---|---|
| 01 | `custom-server.js` | node 父进程守卫（`ANDROID_PARENT_PID`） | 运行环境 |
| 02 | `src/app/api/version/route.js` | android 渠道返回 APK 下载链接 | 更新渠道 |
| 03 | `src/app/api/version/update/route.js` | 拒绝 npm 自更新（APK 安装方式） | 更新渠道 |
| 04 | `src/app/(dashboard)/dashboard/endpoint/EndpointPageClient.js` | **删公网隧道 / Tailscale 两段 JSX** | 产品规则 |
| 05 | `src/app/globals.css` | `.bg-vibrancy` 改不透明（侧栏穿透修复） | 手机适配 |
| 06 | `src/shared/components/Sidebar.js` | 删 `updateInfo` 更新块 | 产品规则 |

04 / 05 / 06 是「手机适配补丁 1.0.0」的源码级落地，施工图与理由见 `UI-PATCHES-1.0.0.md`。

---

# 下游补丁（10Router 上游 → 我们的安卓包）

上游 `techysy/10router` 一直在发版，我们的改动不能跟着源码树走，必须**固化成补丁**。

## 我们保留的改动（4 个文件）

> **v1.11.16 起只保留「更新渠道」这一件事。**
> 10Router 面板只保留侧栏不透明背景一条 CSS 改动（`aside` 背景），其余排版类改动一律不做，
> 以避免与上游自身布局冲突。
> 撤掉的是普适排版/兜底类补丁（日志换行、tooltip 浮层、窄屏 CSS 兜底、中文默认、
> 顶栏搜索框收窄）—— 它们不是"侧栏颜色"，且部分在桌面宽度下改变上游既有表现。
> 保留的 4 个全是**更新渠道**相关：App 里没有 npm 环境，面板内的自更新会杀网关进程、
> 版本不变，必须由上游源码层改掉。

| 补丁 | 改了什么 |
|---|---|
| `01` custom-server.js | `ANDROID_PARENT_PID`：App 进程死了 node 自杀，不占着 20128 |
| `02` api/version/route.js | 新增 `android` 安装渠道：更新提示给 APK 直链而不是 npm |
| `03` api/version/update/route.js | `android` 渠道**硬拒** npm 自更新（403） |
| `04` shared/components/Sidebar.js | 更新提示条支持 android 渠道（「下载新版 APK」） |

**侧栏颜色不在这个补丁集里** —— 它在运行时补丁层 `app/ui/panel-fix.css`
（`aside[class*="bg-vibrancy"]` 不透明背景），不修改上游源码。

Java 壳（`app/src/top/dassein/tenrouter/*`、`AndroidManifest.xml`）是**我们自己的代码**，不在上游树里，不受上游更新影响。

## 三层防护

1. **构建期**：`tools/build-android.sh <ref>` 先用 `tools/upstream.sh apply` 把上游 + 全部补丁重放到干净目录；**任一补丁打不上就退出**，绝不"少打一个也照样出包"。
2. **产物校验**：构建机在打包前跑 `tools/upstream.sh assert <payload>`，检查 `.patchset.json`、android 更新守卫、APK 链接 —— 缺任何一项直接失败（实测：对旧 payload 报缺失并拒绝发版）。
3. **运行期自愈**：payload 里带 `.patchset.json`；壳启动时若发现它消失（被外部更新/覆盖），就删掉首次解包标记 → 下次打开 App 从 APK 内置 `assets/bootstrap.zip` 重新解包，自动回到补丁版。

## 日常操作

```bash
# 上游发新版 → 重建（补丁自动重放 + 校验 + 签名）
tools/build-android.sh v1.5.1

# 只有我们改了东西 → 先更新下游树，再重生成补丁
#   $DOWNSTREAM 默认 ~/10r-src（我们改过的上游源码树）
tools/upstream.sh make-patches v1.5.0     # 重新生成 patches/*.patch
tools/upstream.sh list v1.5.0             # 看改了哪些文件
tools/upstream.sh apply v1.5.0 /tmp/x     # 演练重放，与下游树比对应为零差异
```

`patches/PINS` 记录当前补丁集对应的上游 ref 与生成时间。

## 上游改到同一个文件了怎么办

`apply` 会打印哪个补丁 FAIL 并退出。处理：

1. 看 `patches/NN-*.patch` 确认我们的意图；
2. 把新版源码放进 `$DOWNSTREAM`（`tools/upstream.sh fetch <ref>` 后拷过去），**手工把我们的改动重新应用到新版上**；
3. `tools/upstream.sh make-patches <ref>` 重新生成补丁；
4. 再走一次 `apply` 演练，零差异才构建。

**别用 `patch --fuzz` 硬上**：模糊匹配可能在语义漂移的地方打进去，静默出个半坏的包，比缺功能更难查。

## 已知边界

- 上游的 npm 自更新对我们是无效操作（会杀网关进程 + 把包装进 Termux prefix），所以 Android 渠道直接拒掉，改成下载新 APK。
- 桌面壳的 `shell-update` 路由只在 `INSTALL_CHANNEL=desktop` 生效，与我们无关。
- 补丁重放用的是 `git apply`（严格模式，不带 fuzz），上游源码里一行上下文变了就会失败——这是刻意的。
- 补丁文件按文件名升序应用，重编号时保持这个顺序。
