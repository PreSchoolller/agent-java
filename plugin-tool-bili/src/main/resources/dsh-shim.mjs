#!/usr/bin/env node
// ============================================================================
// dsh-shim —— 伪 DSH 宿主：让 dsh-bili-whale 的宿主侧（lib/）跑在 agent-java 上
// ============================================================================
// 原理：bili-whale 宿主侧只通过 ctx 依赖三个 DSH 服务，逐个核查后——
//   · sessionController（create/prompt）是唯一参与自治链路的（生物钟/监听投递全走它）
//   · webServer / connection 只服务设置页的 HTTP 路由，自治链路完全不经过
// 本 shim 伪造一个 cordis 风格 ctx：
//   · sessionController → 转调 agent-java 的回环 API（plugin-tool-bili 的 AgentApiServer）
//   · webServer / connection → 无害空壳（注册过的路由永远不会有请求进来）
// 然后原样加载插件 lib/index.js 并 apply(ctx)——原版代码零修改，
// 人设注入/日记恢复/生物钟/监听全部是原版逻辑。
//
// 本文件不手动运行：它打包在 plugin-tool-bili 的资源里，由 whale 工具（WhaleHost）
// 解压到 ~/.agentjava/shim/ 后拉起——普通会话里对模型说"启动她"即可。
//
// 环境变量（WhaleHost 注入）：
//   AGENT_JAVA_API   agent-java API 地址（默认 http://127.0.0.1:18765）
//   BILI_WHALE_DIR   dsh-bili-whale 目录（必传，取 bili 工具链 tools/ 的上级）
//   DSH_HOME         数据目录（插件自己读，默认 ~/.dsh）
//
// 运行前提：
//   1. agent-java 在跑（托盘常驻）——没跑时 prompt 调用会失败，原版投递层
//      自带 pendingRequest 落盘重试，不会丢消息，起回来会补投
//   2. ~/.agentjava/api-token 已生成（AgentApiServer 启动时懒生成，先于本进程）
//
// 停止：whale 工具 stop（Windows 下强杀不走清理链，未完成投递由原版落盘重试兜底）
// ============================================================================
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const API = process.env.AGENT_JAVA_API || 'http://127.0.0.1:18765'
const WHALE_DIR = process.env.BILI_WHALE_DIR
  || path.join(os.homedir(), '.dsh', 'plugins', 'dsh-bili-whale')

const tokenFile = path.join(os.homedir(), '.agentjava', 'api-token')
if (!fs.existsSync(tokenFile)) {
  console.error(`[dsh-shim] 找不到 ${tokenFile} —— 先用 whale 工具 start（它会先拉起回环 API 生成 token）`)
  process.exit(2)
}
const TOKEN = fs.readFileSync(tokenFile, 'utf8').trim()
const HEADERS = { 'content-type': 'application/json', 'x-agent-token': TOKEN }

async function post(apiPath, body, signal) {
  const res = await fetch(API + apiPath, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify(body ?? {}),
    signal,
  })
  let data = {}
  try { data = await res.json() } catch {}
  if (!res.ok || data.ok === false) {
    // 抛错 = 投递失败：session-delivery 会置 ready=false 并保留 pendingRequest 重试
    throw new Error(`agent-java ${apiPath} 失败 (${res.status}): ${data.error ?? res.statusText}`)
  }
  return data
}

// content 是 [{type:'text',text}] 块数组（session-delivery/scheduler 的约定），
// 全是文本块，这里拍平成一段 prompt 交给 agent-java
const sessionController = {
  async create({ sessionId, workspace }) {
    return post('/agent/api/session', { sessionId, workspace })
  },
  async prompt(request, signal) {
    const blocks = Array.isArray(request.content) ? request.content : []
    const text = blocks
      .map((b) => (typeof b === 'string' ? b : String(b?.text ?? '')))
      .filter(Boolean)
      .join('\n\n')
    if (!text.trim()) throw new Error('prompt content 为空，拒绝注入空回合')
    return post('/agent/api/prompt', { sessionId: request.sessionId, text }, signal)
  },
}

// 只服务设置页（DSH 的 Web UI），自治链路用不到；注册接口保持形状即可
const webServer = { register() { return () => {} } }
const connection = { requestRejection() { return undefined }, isTrusted() { return true } }

// cordis 风格 ctx：插件用 ctx.effect(fn) 注册清理链（fn() 立即执行返回 disposer）
const effects = []
const SERVICES = { sessionController, webServer, connection }
const ctx = {
  ...SERVICES,
  get(name) { return SERVICES[name] ?? null },
  effect(make) {
    const dispose = make()
    if (typeof dispose === 'function') effects.push(dispose)
  },
}

const entry = path.join(WHALE_DIR, 'lib', 'index.js')
if (!fs.existsSync(entry)) {
  console.error(`[dsh-shim] 没找到插件入口 ${entry} —— BILI_WHALE_DIR 没对（应指向 dsh-bili-whale 目录，即 tools/ 的上级）`)
  process.exit(2)
}
const plugin = (await import(pathToFileURL(entry).href)).default
const missing = (plugin.inject ?? []).filter((n) => !(n in SERVICES))
if (missing.length) {
  console.error(`[dsh-shim] 插件需要本 shim 未提供的服务: ${missing.join(', ')}`)
  process.exit(2)
}

await plugin.apply(ctx)
console.log(`[dsh-shim] 已挂载 ${plugin.name} → ${API}（注入服务: ${plugin.inject?.join(', ')}）`)

const stop = () => {
  console.log('[dsh-shim] 退出中…')
  for (const dispose of effects.reverse()) {
    try { dispose() } catch {}
  }
  process.exit(0)
}
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
setInterval(() => {}, 1 << 30) // 保活：定时器/轮询都在插件侧，本进程只当壳
