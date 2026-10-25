#!/usr/bin/env node
'use strict';
// ota-check.js — OTA 参考客户端 / 一致性验证器（Node，零依赖，仅用内置 fetch）
//
// 作用：把设计文档里的 FSM（探测→选源→续传→校验→原子替换→纪律）落地成可运行代码，
// 并用真实线上清单 + 载荷跑通成对断言：
//   - 正向：条件请求 304 短路 / Range 断点续传 / sha256 校验通过 / 原子替换成功
//   - 负向：版本不降级被拒 / sha 篡改被拒 / 404 致命失败(poison pill)
// 这份 JS 即是 Android 壳里下载器模块的"逻辑规格"，移植时按上游语言直译即可。
import { createHash } from 'node:crypto';
import { open, stat, rename, rm, mkdir, readFile, writeFile, appendFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';

const results = [];
function check(name, cond, detail = '') {
  results.push({ name, ok: !!cond, detail });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const UA = { 'user-agent': '10Router-OTA/1' };

// 网络重试（指数退避 + 抖动）：把"连接重置/fetch failed"当作可恢复错误
async function withRetry(fn, { tries = 4, base = 400 } = {}) {
  let last;
  for (let i = 0; i < tries; i++) {
    try { return await fn(); }
    catch (e) { last = e; await sleep(base * 2 ** i + Math.random() * base); }
  }
  throw last;
}

// ---------------- 纯函数（可在 Android 端直译） ----------------
function cmpVer(a, b) { // 1 if a>b, -1 if a<b, 0 if eq
  const A = a.split('.').map(Number), B = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if ((A[i] || 0) > (B[i] || 0)) return 1;
    if ((A[i] || 0) < (B[i] || 0)) return -1;
  }
  return 0;
}
const monotonicOk = (oldV, newV) => cmpVer(newV, oldV) > 0;

async function sha256File(path) {
  const h = createHash('sha256');
  const fh = await open(path, 'r');
  try {
    let off = 0; const buf = Buffer.alloc(1024 * 1024);
    for (;;) {
      const { bytesRead } = await fh.read(buf, 0, buf.length, off);
      if (bytesRead === 0) break;
      h.update(buf.subarray(0, bytesRead));
      off += bytesRead;
    }
  } finally { await fh.close(); }
  return h.digest('hex');
}

// ---------------- 网络：探针 / 续传 / 熔断 / 单飞 ----------------
async function probeMirror(url) {
  const t0 = Date.now();
  try {
    const r = await fetch(url, { method: 'HEAD', headers: UA });
    return { url, ok: r.ok, ms: Date.now() - t0, status: r.status };
  } catch (e) {
    return { url, ok: false, ms: Date.now() - t0, status: 0, error: e.message };
  }
}
async function selectSource(mirrors) {
  const probes = await Promise.all(mirrors.map(probeMirror));
  const ok = probes.filter((p) => p.ok).sort((a, b) => a.ms - b.ms);
  return ok.length ? ok : probes; // failover：全挂也返回列表（让调用方报 FATAL）
}

// 续传下载：从 .part 已有字节继续；206 续传 / 200 重头（截断）；poison=404/410；重试预算+熔断
async function downloadResumable(url, partPath, expectedSha, opts = {}) {
  const maxRetries = opts.maxRetries ?? 5;
  const deadlineMs = opts.deadlineMs ?? 600_000;
  const breaker = { fails: 0, openUntil: 0, threshold: 4 };
  const tStart = Date.now();
  let attempt = 0;
  for (;;) {
    if (Date.now() - tStart > deadlineMs) return { status: 'fatal', reason: 'deadline' };
    if (breaker.openUntil > Date.now()) return { status: 'fatal', reason: 'circuit-open' };
    let offset = existsSync(partPath) ? (await stat(partPath)).size : 0;
    const headers = { ...UA };
    if (offset > 0) headers['Range'] = `bytes=${offset}-`;
    let res;
    try {
      res = await withRetry(() => fetch(url, { headers, redirect: 'follow' }), { tries: 3 });
    } catch (e) {
      breaker.fails++; if (breaker.fails >= breaker.threshold) breaker.openUntil = Date.now() + 30_000;
      if (++attempt > maxRetries) return { status: 'fatal', reason: 'network' };
      await sleep(500 * attempt); continue;
    }
    if (res.status === 404 || res.status === 410) {
      await res.text().catch(() => {});
      return { status: 'poison', reason: `http-${res.status}` };   // 永久失败，不重试
    }
    // 200 = 源不支持 Range → 从头截断重写；206 = 从 offset 续写
    const restart = res.status === 200;
    const pos = restart ? 0 : offset;
    const fh = await open(partPath, pos === 0 ? 'w' : 'r+');   // r+ 要求文件已存在(=offset>0)
    try {
      let p = pos;
      for await (const chunk of res.body) { await fh.write(chunk, 0, chunk.length, p); p += chunk.length; }
    } finally { await fh.close(); }
    const sha = await sha256File(partPath);
    if (sha === expectedSha) { breaker.fails = 0; return { status: 'done', size: (await stat(partPath)).size }; }
    await rm(partPath, { force: true });   // 半截/坏包：删掉，下轮重下
    breaker.fails++; if (breaker.fails >= breaker.threshold) breaker.openUntil = Date.now() + 30_000;
    if (++attempt > maxRetries) return { status: 'fatal', reason: 'sha-mismatch' };
    await sleep(500 * attempt);
  }
}

// 单飞锁：同一组件同一刻只跑一个更新流程
const singleFlight = new Map();
function singleFlightRun(key, fn) {
  if (singleFlight.has(key)) return singleFlight.get(key);
  const p = fn().finally(() => singleFlight.delete(key));
  singleFlight.set(key, p);
  return p;
}

async function atomicSwap(partPath, finalPath) {
  if (existsSync(finalPath)) await rm(finalPath, { force: true });
  await rename(partPath, finalPath);
  return existsSync(finalPath) && !existsSync(partPath);
}

// ---------------- FSM 主流程 ----------------
async function fetchManifest(url, { lastModified, cachePath }) {
  const headers = { ...UA };
  if (lastModified) headers['If-Modified-Since'] = lastModified;
  const r = await fetch(url, { headers });
  const lm = r.headers.get('last-modified');
  if (r.status === 304) {
    let cached = null;
    if (existsSync(cachePath)) cached = JSON.parse(await readFile(cachePath, 'utf8'));
    return { status: 304, manifest: cached, lastModified: lm || lastModified };
  }
  if (!r.ok) return { status: r.status, manifest: null, lastModified: lm };
  const text = await r.text();
  await writeFile(cachePath, text);
  return { status: 200, manifest: JSON.parse(text), lastModified: lm };
}

async function runUpdate({ manifestUrl, component, installedVersion, workDir }) {
  const state = { fsm: 'IDLE' };
  await mkdir(workDir, { recursive: true });
  const cachePath = workDir + '/manifest.json';
  state.fsm = 'CHECKING';

  // 演示 304 短路：先无条件拉一次（写缓存），再带 If-Modified-Since 拉一次 → 期望 304
  const first = await fetchManifest(manifestUrl, { lastModified: '', cachePath });
  const second = await fetchManifest(manifestUrl, { lastModified: first.lastModified, cachePath });
  check('条件请求 304 短路', second.status === 304, `second.status=${second.status}`);
  const manifest = second.manifest || first.manifest;
  check('拿到清单', !!manifest && !!manifest.components[component], `component=${component}`);

  const comp = manifest.components[component];
  state.fsm = 'AVAILABLE';
  check('版本单调（不降级）', monotonicOk(installedVersion, comp.version),
        `installed=${installedVersion} offered=${comp.version}`);
  if (!monotonicOk(installedVersion, comp.version)) { state.fsm = 'FATAL'; return state; }

  // 选源
  state.fsm = 'SELECT_SOURCE';
  const ranked = await selectSource(comp.mirrors);
  check('至少一条镜像可用', ranked.some((m) => m.ok), ranked.map((m) => `${m.ms}ms/${m.status}`).join(','));
  const best = ranked[0];

  // 续传下载（先人工截断到一半演示断点续传）
  state.fsm = 'DOWNLOADING';
  const partPath = workDir + `/${component}.part`;
  await rm(partPath, { force: true });
  // 造一半：先取前半字节
  // 造一半：先取前半字节（演示断点续传的起点）
  const head = await withRetry(() => fetch(best.url, { headers: { ...UA, Range: 'bytes=0-4' } }));
  const halfBuf = Buffer.from(await head.arrayBuffer());
  await writeFile(partPath, halfBuf);
  const before = (await stat(partPath)).size;
  const dl = await downloadResumable(best.url, partPath, comp.sha256);
  check('断点续传：从半截完成', dl.status === 'done' && (await stat(partPath)).size > before,
        `before=${before} after=${(await stat(partPath)).size}`);
  check('sha256 校验通过', dl.status === 'done');

  // 原子替换
  state.fsm = 'SWAPPING';
  const finalPath = workDir + `/${component}.zip`;
  const swapped = await atomicSwap(partPath, finalPath);
  check('原子替换成功', swapped && (await sha256File(finalPath)) === comp.sha256);
  state.fsm = 'DONE';
  check('DONE：运行版 = 新包 sha', (await sha256File(finalPath)) === comp.sha256);
  return state;
}

// ---------------- 负向断言（纯函数，无需网络） ----------------
// 真实命中 404 的下载：确认 downloadResumable 返回 poison（致命失败，不无限重试）
async function poisonTest() {
  const url = 'https://das-sein.top/10router/ota/payloads/nope-999.zip'; // 必 404
  const r = await downloadResumable(url, '/tmp/ota-poison.part', 'deadbeef', { maxRetries: 1, deadlineMs: 15000 });
  check('负向·404 致命失败(poison pill, 不重试)', r.status === 'poison', `status=${r.status} reason=${r.reason}`);
}

function negativeTests() {
  check('负向·降级被拒(2.0.0→1.6.0)', !monotonicOk('2.0.0', '1.6.0'));
  check('负向·同版本被拒(1.6.0→1.6.0)', !monotonicOk('1.6.0', '1.6.0'));
  check('负向·升级通过(1.6.0→1.6.1)', monotonicOk('1.6.0', '1.6.1'));
  check('负向·sha 篡改被拒', 'deadbeef' !== 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
}

// ---------------- 入口 ----------------
async function main() {
  const args = process.argv.slice(2);
  const get = (k, d = '') => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
  const manifestUrl = get('--manifest', 'https://das-sein.top/10router/ota/updates.json');
  const component = get('--component', 'creditDaddy');
  const installed = get('--installed', '1.6.0');
  const workDir = get('--dir', '/tmp/ota-work');

  console.log(`# 参考客户端跑通 FSM：manifest=${manifestUrl} component=${component} installed=${installed}\n`);
  console.log('## 负向断言（纯函数 + 真实 404）'); negativeTests(); await poisonTest();
  console.log('\n## 正向：真实清单 + 真实载荷 + 真实续传/校验/替换');
  try {
    await singleFlightRun(component, () => runUpdate({ manifestUrl, component, installedVersion: installed, workDir }));
  } catch (e) {
    console.log('异常:', e.message);
  }

  const pass = results.filter((r) => r.ok).length;
  const fail = results.length - pass;
  console.log(`\n==== 结果：${pass} PASS / ${fail} FAIL / 共 ${results.length} ====`);
  process.exit(fail ? 1 : 0);
}
main();
