# 10Router Android

把 [10Router](https://github.com/techysy/10router)（本地 AI 路由网关）和
[CreditDaddy](https://github.com/techysy/CreditDaddy)（额度管家）连同 Node 运行时一起装进一个 APK。

安装即用：不需要 Termux，不需要 root，不需要额外下载运行时。

## 功能

- **网关** — 本地运行的 AI 路由网关，统一管理多个模型供应商，为客户端提供 OpenAI 兼容接口
- **额度管家** — 多账号额度管理与每日签到，额度自动同步给网关
- **网页版** — 在电脑或平板的浏览器里管理网关和额度
- **后台守护** — 开机自启、进程监督、锁屏保活

## 安装

1. 从 [Releases](../../releases) 下载最新 `10router-*.apk`
2. 允许安装未知来源应用（自签名包，系统会提示"仍要安装"）
3. 打开 App，等待首次初始化完成（约 1–2 分钟）
4. 在网关面板设置访问密码
5. 客户端 API Base 填 `http://127.0.0.1:20128/v1`

**要求**：arm64 设备，Android 7.0 及以上。数据保存在应用私有目录，卸载后自动清除。

## 构建

在 x86_64 Linux 上（Ubuntu 22.04+ 已验证）：

```bash
# 依赖
apt install -y openjdk-17-jdk-headless binutils zip unzip python3-pil
# Android SDK：build-tools;34.0.0 与 platforms;android-34

# 构建（10Router 上游 tag + CreditDaddy 版本）
tools/build-android.sh v1.6.0 1.6.0
```

脚本依次完成：重放上游补丁 → 重建 CreditDaddy 载荷 → Next 构建 → 组装 standalone →
产物断言 → 签名。任一环节失败即退出，不会产出不完整的包。

### 上游补丁

APK 是这两个上游项目的一份副本。手机环境与桌面环境有差异，需要少量源码改动：

| 路径 | 内容 |
|---|---|
| `patches/` | 10Router 源码改动（运行环境守卫、更新渠道、界面适配） |
| `cd-patches/` | CreditDaddy 面板改动（显示适配） |
| `patches/UI-PATCHES-1.0.0.md` | 逐条说明改了什么、为什么改 |

补丁由 `tools/upstream.sh` 管理：`apply` 重放、`make-patches` 生成、`assert` 校验产物。
上游升级时若补丁冲突会直接失败，需要手工 rebase 后重新生成。

## 项目结构

```
app/                    APK 外壳（纯 Java，不依赖 Gradle）
  src/top/dassein/tenrouter/
    MainActivity.java       主界面与面板 WebView
    GatewayService.java     前台服务：启动并守护网关进程
    KeepAlive.java          保活策略、状态查询
    ShellWeb.java           内置 HTTP 服务：网页版壳页面
  ui/                   壳页面（原生 HTML/CSS/JS，无框架）
  res/                  应用图标

patches/                10Router 源码补丁
cd-patches/             CreditDaddy 面板补丁
backup/                 历史补丁备份（不参与构建）

tools/
  build-android.sh        一键构建
  build-cd.sh             单独重建 CreditDaddy 载荷
  upstream.sh             补丁管理
  remote-build.sh         构建机上的实际流程

build/                  免 Gradle 打包流水线（aapt2 + d8 + apksigner）
```

## 技术说明

- **运行时**：Termux aarch64 二进制包（bionic 原生 ELF，`PT_INTERP=/system/bin/linker64`）。
  Node 官方 arm64 包是 glibc 的，Android 上无法执行。
- **targetSdk 28**：Android 10+ 的 SELinux 禁止执行 data 目录下的文件，targetSdk≤28 豁免。
- **启动入口是 `custom-server.js`**，不是 standalone 的 `server.js`。
  前者会给请求标记进程级本机身份，WebView 通过 127.0.0.1 访问时才被识别为本机。
- **x64 原生模块（`.node`）全部剔除**：10Router 在 Node≥24 下自动使用内置 `node:sqlite`。
- **符号链接在打包前解引用**：`java.util.zip` 无法还原 symlink。

## 上游与许可

本仓库是**打包与集成工程**，把下面几个开源项目装进一个 APK：

| 内含软件 | 项目 | 许可 |
|---|---|---|
| 10Router（网关） | <https://github.com/techysy/10router> | MIT · © 2024-2026 decolua, techysy and contributors |
| CreditDaddy（额度管家） | <https://github.com/techysy/CreditDaddy> | MIT · © 2026 CreditDaddy contributors |
| Node.js 运行时 | <https://github.com/termux/termux-packages> | 各包自有许可 |

本仓库自身的打包外壳与构建流水线采用 **MIT**，见 [LICENSE](LICENSE)。
两份上游许可全文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，
App 内「设置 → 关于 → 开源许可与鸣谢」可展开查看同样内容。

Next 的 `.next/standalone` 产物会剥掉上游 `LICENSE`，而分发 APK 需随副本附带版权与许可声明，
因此构建链会把上游 `LICENSE` 补回 `app/` 并在出包后断言其存在。

「10Router」与「CreditDaddy」为上游项目名称，本工程仅在说明集成了哪个上游软件时使用。

## 反馈

问题与建议请开 [Issue](../../issues)。
