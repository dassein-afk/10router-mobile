# 第三方开源声明 / Third-Party Notices

本工程（`dassein-afk/10router-mobile`）是**打包与集成工程**：它把下面两个独立的开源项目
连同 Node 运行时一起打进一个 Android APK。本工程自身的许可见 [`LICENSE`](LICENSE)（MIT）。

下面列出随 APK 分发的所有第三方软件及其许可，原文照录（未作任何删改）。

---

## 1. 10Router — 本地 AI 路由网关与仪表盘

- 项目：<https://github.com/techysy/10router>
- 许可：**MIT License**
- 在本产品中的角色：APK 内的网关本体（后台服务 `127.0.0.1:20128`，App 中的「网关」页）
- 署名文件随包位置：`assets/bootstrap.zip` 内的 `app/LICENSE`

```text
MIT License

Copyright (c) 2024-2026 decolua, techysy and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. CreditDaddy — 额度管家

- 项目：<https://github.com/techysy/CreditDaddy>
- 许可：**MIT License**
- 在本产品中的角色：APK 内的额度管家（后台服务 `127.0.0.1:47860`，App 中的「额度」页）
- 署名文件随包位置：`assets/cd.zip` 内的 `cd/LICENSE`

```text
MIT License

Copyright (c) 2026 CreditDaddy contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. 运行时与依赖

APK 内置一个 Termux（aarch64/bionic）**Node.js 运行时**，它由下列 Debian 风格二进制包
（Termux 仓库）解包而来，各自遵循其上游许可（多为 Apache-2.0 / BSD / MIT 及其组合）：

- `nodejs`、`libicu`、`openssl`、`libsqlite`、`c-ares`、`libuv`、`libnghttp2`、
  `ca-certificates`、`libssl`（及各自的传递依赖，由 `build/resolve_deps.py` 闭包解析）

这些包的许可声明可在 Termux 包仓库查询：<https://github.com/termux/termux-packages>。
它们只作为**独立的运行时**存在，本工程不修改、不链接其源码。

10Router 自身还依赖大量 npm 第三方包（随 `.next/standalone` 随包分发），
每个包各自的许可在其 `node_modules/<包>/LICENSE*` 中；可通过解包
`assets/bootstrap.zip` 查看。

---

## 4. 商标与名称

「10Router」是上游项目 techysy 的名称；「CreditDaddy」是上游项目 techysy 的名称。
本工程仅在说明"内含哪个上游软件"时使用这两个名称，不主张任何商标权利。
