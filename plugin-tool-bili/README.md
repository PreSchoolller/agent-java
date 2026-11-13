# plugin-tool-bili

把 [dsh-bili-whale](https://github.com/)（B 站"养鱼"插件）的能力接入 agent-java 的 Tool SPI：

- **`bili` / `bili_write`** 两个工具包装其统一 CLI 入口 `tools/bili.mjs`，让 agent 会话能直接读视频/发评论/回私信
- **`whale`** 工具把它的宿主侧（`lib/`，生物钟 + 私信/评论监听 + 人设注入）通过 dsh-shim 兼容层跑在 agent-java 上，普通会话里说"启动她"即可

原版代码零修改复用，本插件只做包装。

## 工具一览

| 工具 | `readOnly()` | 内容 | 权限行为 |
|---|---|---|---|
| `bili` | `true` | 27 个只读命令白名单（video/subtitles/comments/feed/search/debrief/quota/selftest…） | EDIT/PLAN 下自由调用 |
| `bili_write` | `false` | 10 个写命令白名单（comment/dm/fav/dynamic/danmaku/notify…） | EDIT 下每轮弹确认卡，PLAN 全拦，FULL_ACCESS 直通（与 bash 同待遇） |
| `whale` | `false` | start/stop/status/log，管理 shim 宿主进程 | EDIT 下 start/stop 弹确认 |

读写白名单互不重叠。改白名单对照 `dsh-bili-whale/tools/bili.mjs` 的命令 map 表。

## 安装

```powershell
# 在仓库根目录（会构建宿主 + 插件并装入 ~/.agentjava/plugins/）
powershell -ExecutionPolicy Bypass -File scripts/install-plugin.ps1 plugin-tool-bili
```

重启 agent-java 生效（插件 jar 启动时经 ServiceLoader 装载）。

## whale 源码的构建期打包（重要）

**dsh-bili-whale 的源码不进本仓库。** 构建时由 pom 里的 `maven-antrun bundle-whale`
步骤从外部目录现拷进 `target/` 打进 jar，git 里只有这条复制规则：

- 源码位置默认 `../../dsh-bili-whale`（相对模块目录），可用 `-Dbili.whale.src=路径` 覆盖
- 打包内容：`lib/`、`tools/`、`package.json` + 3 个纯 JS npm 依赖（`qrcode`/`dijkstrajs`/`pngjs`
  ——`lib/index.js` 顶层 import auth.js → qrcode，缺了模块加载即崩；yargs 只是 qrcode 的
  CLI bin 依赖不用带；pngjs 剔除 coverage 产物）
- **缺源码时构建照常通过**（打 warning），运行时走外部目录兜底
- whale 原项目升级后：拉新源码 → 重新 `install-plugin.ps1` → 运行时按 package.json
  版本变化自动重新解压，git 无感

## 运行时配置

路径解析每次执行重新校验，按优先级：

1. **设置面板**：工具页 `bili` / `bili_write` 条目的交互输入（键 = 工具 name()，
   两个工具共用一份工具链）
2. **环境变量**：`BILI_TOOLS_DIR`（tools 目录）/ `BILI_MJS`（bili.mjs 路径）/
   `BILI_NODE`（node 路径）/ `BILI_WHALE_DIR`（whale 根目录，调试用）
3. **默认位置**：`~/.dsh/plugins/dsh-bili-whale/tools`、`~/.dsh/bili-whale/tools`、
   打包副本解压目录 `~/.agentjava/whale/tools`

node 运行时：`BILI_NODE` → PATH → Windows 常见安装位置。bili.mjs 内部还会自选更优的
DSH runtime node（两层任一可用即可）。

### whale 的目录优先级

`env BILI_WHALE_DIR` → 打包副本解压目录（`~/.agentjava/whale/`，jar 自包含主路径）→
外部目录兜底（bili 工具链 tools/ 的上一级）。

## whale 用法

普通会话里对模型说：

- 「启动她」→ `whale start`：拉回环 API（127.0.0.1:18765，token 鉴权）→ 解压打包副本 →
  起 shim 子进程（日志 `~/.agentjava/logs/whale-shim.log`）。生物钟/监听/人设全跑起来，
  到点自动往 `bili-whale` 会话注入回合，走正常 agent 链路与权限门
- 「她怎么样了」→ `whale status`（注意：回环 API ✓ 只代表宿主在听，不代表 shim 活着）
- 「停掉她」→ `whale stop`；排障 → `whale log`

前提：**agent-java 托盘常驻在跑**（进程死了注入链路就没了）。正常退出（托盘退出/窗口
关闭）会经应用的 ShutDownJob 退出链**自动停掉她**（按 pid 杀，含子进程树，并清 pid 文件）；
只有强杀 JVM（taskkill /F）会留下孤儿进程——那种场景下次 start/status 会自动接管
（pid 落 `~/.agentjava/shim/shim.pid` 作为跨重启事实源，存活判定校验 command 含 node
且参数含 dsh-shim.mjs，防 pid 复用误判误杀）。

注入回合的权限模式取 `bili-whale` 会话的记忆，没盖过戳就回落输入区全局当前值——想让她
夜里自由干活就在那个会话切 FULL_ACCESS 盖戳；保守就留 EDIT（写操作卡待确认）。

## 配置文件（没有 UI，全靠手改）

**主配置 `~/.dsh/bili-whale.config.json`**（JSON，改完 ≤60 秒热生效——监听器每个
tick 重读该文件，不用重启 shim）。当前实际在用的完整样例：

```json
{
  "persona": { "name": "本小姐", "ownerTitle": "master", "selfClaim": "肥鱼", "diaryTitle": "今日摘要" },
  "preferences": {
    "like": ["AI 工具与插件", "鬼畜与怀旧向剪辑", "猫咪", "Live2D/模型"],
    "dislike": ["带货", "擦边", "标题党", "纯搬运"]
  },
  "owner": { "mid": "", "name": "" },
  "self": { "mid": "", "name": "" },
  "quota": { "shareDaily": 3 },
  "share": { "aiCommentGuard": false, "aiThreshold": 30, "enabled": true, "via": "comment", "minScore": 13, "silentAfterDays": 3 },
  "listen": { "comment": true, "commentIntervalSec": 60, "scope": "both", "oncePerPerson": true, "allDay": false, "dm": true, "dmIntervalSec": 60, "live": false, "liveRoom": "", "liveScope": "owner" },
  "night": { "windows": [{ "start": "20:00", "end": "24:00", "wakes": 1 }], "skipChance": 0.3, "minGapMin": 10, "maxVideos": 5 },
  "notify": { "type": "none", "url": "", "template": "text", "cmd": "" }
}
```

逐块说明：

| 块 | 字段 | 含义 |
|---|---|---|
| `persona` | `name` / `ownerTitle` / `selfClaim` / `diaryTitle` | 她的角色名、对你的称呼、自称、日记标题——影响所有注入 prompt 的口吻和日记署名 |
| `preferences` | `like[]` / `dislike[]` | 内容偏好，feed 选题和分享打分的参考系 |
| `owner` | `mid` / `name` | **主人**（你自己）的 B 站号——@ 分享的对象、私信白名单的锚点 |
| `self` | `mid` / `name` | **她自己的**号，必须与 credentials.json 是同一个号（selftest 会核对） |
| `quota` | `shareDaily` | 每日分享上限 |
| `share` | `enabled` / `via` / `minScore` / `aiThreshold` / `aiCommentGuard` / `silentAfterDays` | 分享开关、方式（comment=评论区 @）、打分门槛、AI 味保护、沉默期 |
| `listen` | `comment` / `commentIntervalSec` / `scope` / `oncePerPerson` / `allDay` | 评论监听：轮询「回复我的 + @我的」（60s 是硬下限）、`scope=both/reply`、`allDay=false` 时**只在夜窗内监听** |
| | `dm` / `dmIntervalSec` | 私信监听（**不受夜窗限制**，全天轮询） |
| | `live` / `liveRoom` / `liveScope` / `mentionSummary` | 直播间监看（`liveScope=owner/mention`）、主人 @ 请求总结开关 |
| `night` | `windows[]` / `skipChance` / `minGapMin` / `maxVideos` | 作息窗口（到点**随机**醒来）、今晚装死的概率、唤醒间隔下限、单次最多看几个视频 |
| `notify` | `type` / `url` / `template` / `cmd` | 投递/日记的外部推送通道（none=不发） |

注意事项：

- **第一次开启监听只把"现在"记为基线**，不倒历史——只有之后的回复/@ 才会触发注入
- 风控红线：`commentIntervalSec` 低于 60 会被源码强制拉回；`listen.allDay=true` 会全天
  每分钟锤 B 站接口，新号慎开
- 新号 / 老号与 `share` 的关系：保守默认是 `silentAfterDays=3` + `minScore=13`

**凭据 `~/.bili-ai/credentials.json`**（本插件不读不写，由工具链自管）：

```json
{ "sessdata": "SESSDATA的值", "bili_jct": "bili_jct的值", "buvid3": "buvid3的值" }
```

三个值从浏览器 F12 → Application → Cookies → https://www.bilibili.com 手动复制
（`document.cookie` 读不到，必须从面板复制）。

**运行状态文件**（都在 `~/.dsh/`，程序自管，一般不用动）：`whale-session.json`（会话
启动与投递状态）、`whale-night.json`（今晚的生物钟计划）、`whale-diary.md` /
`whale-diary-private.md`（两本日记，她会自己恢复记忆）。

## 与 dsh-bili-whale 原版的关系

- **原版代码零修改**：宿主侧经 shim（`resources/dsh-shim.mjs`，伪造 cordis ctx：
  sessionController→HTTP 转调、webServer/connection 空壳）原样 `apply`；
  工具侧直接调 CLI
- **不可用部分**：DSH 设置页 UI（配置改 `~/.dsh/bili-whale.config.json`）、扫码登录
  （cookie 手动维护在 `~/.bili-ai/credentials.json`，本插件不读不写凭据）
- **写安全**：模型参数里的 `--go`/`--dry` 一律剥掉，`--go` 只能由 `go=true` 参数追加；
  NEEDS_FLAG 命令缺省自动补 `--dry` 纯预演（对齐原版 dry/go 强制约定）
- **进程安全**：不经 shell，参数直传 ProcessBuilder；输出 16K 截尾；超时可配
  （默认 120s 上限 900s），超时强杀并如实报告

## 已知边界

- 单号模式下 `dm` 私信专线需双号互关，永远不可用；分享评论（via comment）单号下是
  "自己 @ 自己"
- `whale stop` 在 Windows 下强杀不走 node 清理链，未完成投递由原版 pendingRequest
  落盘重试兜底，不丢消息
- 外部源码目录若比打包版本新，需重新构建打包（见上文"构建期打包"）

## 测试

`mvn test`（在模块目录下）：19 例——工具白名单互斥/schema/参数净化/超时钳制（9）、
回环 API 鉴权/注入/异常路径（7）、shim 存活语义/解压完整性/版本跳过（3）。
构建时未打包 whale 时解压用例自动跳过。
