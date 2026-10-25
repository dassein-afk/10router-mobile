# OTA 工具链（组件级热更新 · 服务端 + 参考客户端）

本目录是 10Router 安卓版「组件级 OTA 热更新」的**服务端工具**与**参考客户端**，
落地自 `_internal/ota-design.md`（设计文档）。策略：不重新发整包 APK，只把两个上游
（10router 网关 / creditDaddy 额度管家）的新载荷换进去，靠一份清单 + 多镜像 + sha256 校验。

> 状态：**POC 已实测通过**（13/13 断言），但线上载荷为合成占位包；真实启用前须换成构建流水线产物。
> 本目录**未推送**到公有仓 `dassein-afk/10router-mobile`（按"OTA 内部实现不公开"纪律，推前需拍板）。

## 1. 设计要点（与初稿的实测修正）
- 条件请求：**Caddy 返回弱 ETag（`W/`），`If-None-Match` 不 304**；改用 **`If-Modified-Since`/`Last-Modified`**，实测 304 生效。
- 续传：Caddy 原生支持 **Range 206**，断点续传可行。
- 校验：**sha256 必校验**（CAS 思想，镜像不可信），防坏包/中间人。
- 防无限重置：FSM + 重试预算 + 熔断 + 单飞锁 + 版本单调守卫（拒绝降级）。

## 2. publish-ota.sh —— 清单生成器
登记一份"已打好补丁的成品载荷"到 `updates.json`，并（可选）发布到站点。

```bash
bash tools/ota/publish-ota.sh \
  -c creditDaddy -v 1.6.1 -f /path/to/creditDaddy-1.6.1.zip \
  -u "https://das-sein.top/10router/ota/payloads/creditDaddy-1.6.1.zip" \
  -u "https://ghfast.top/https://github.com/dassein-afk/10router-mobile/releases/download/ota/creditDaddy-1.6.1.zip" \
  -n "由 build-cd.sh 预烘焙产出" \
  --landing /opt/apps/sites/landing/10router      # 复制到站点 + 校验线上 200
```
硬校验：`component` 仅 `[a-zA-Z0-9_-]`；`version` 必须 semver 且**严格高于**已登记版本（防降级）；
每个 `mirror` 必须 `https://`；`size`/`sha256` 默认自动算（可显式覆盖）。

## 3. ota-check.js —— 参考客户端 / 一致性验证器
零依赖（仅 Node 内置 `fetch`），把设计里的 FSM 落地成可运行代码，并用**真实线上端点**跑通成对断言。

```bash
node tools/ota/ota-check.js --component creditDaddy --installed 1.5.9 --dir /tmp/ota-work
```
覆盖：304 短路、清单解析、版本守卫、测速选源、Range 断点续传、sha256 校验、原子替换，
以及负向（降级/同版本被拒、sha 篡改被拒、真实 404 poison pill）。

**这份 JS 即 Android 壳下载器模块的"逻辑规格"**——移植到 Java/Kotlin 时按上游语言直译即可，
壳内 `act('restart')` 现有可用，无需新增重启逻辑。

## 4. 线上端点
- 清单：`https://das-sein.top/10router/ota/updates.json`
- 载荷：`https://das-sein.top/10router/ota/payloads/<file>.zip`

## 5. 真正启用前 checklist
- [ ] 用 `build-cd.sh` / `build-android.sh` 产出**真实预烘焙包**（补丁已在服务端重放）替换 POC 占位包
- [ ] 把 `10router` 组件也登记进 `updates.json`
- [ ] 移植 `ota-check.js` 逻辑到壳（Java/Kotlin）+ 走远程构建机编译验证（本目录未碰壳/构建链）
- [ ] 可选：清单签名（防投毒）、灰度 `rollout`、差量 bsdiff
- [ ] 推 `tools/ota/` 到公有仓（需用户拍板）
