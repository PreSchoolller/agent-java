# Agent-Java

Java 桌面 AI Agent 应用。学习用途。未完成。前端 99% 由 AI 生成，不入坑 Swing，后端手写并借助 AI 审核。

## 运行

需要 JDK 17+ 和 Maven。

```bash
mvn compile
mvn exec:java
```

（或者 IDE 里直接跑 `Main`。）  

本地数据地址
```
~/.agentjava/
├── history.json         # 会话索引（只存元数据：标题/时间/置顶/工作区），启动只读它
├── settings.properties  # 应用设置
├── workspace/           # 默认工作区（新建对话不选工作区时，工具的相对路径落这里）
└── sessions/            # 单会话消息文件，文件名即会话 id（首次打开该会话才读）
    └── ...
```
默认后端地址指向本地服务（如 LM Studio），在设置面板里改成你的即可。

## 功能

- 多会话管理：侧栏搜索/重命名/置顶/删除，从任一 AI 回复分叉成新会话，本地 JSON 持久化（消息按需加载，
  启动不读会话文件），长会话视口窗口化；多个会话可同时生成（各会话独立串行，切换不互相打断），
  侧栏用彩色小标签标注"生成中/待确认"的会话（折叠态汉堡按钮带角标）；会话内 Ctrl+F 搜索、
  输入草稿按会话保留；窗口尺寸/位置记忆
- agent 工具调用卡片：运行中/待确认/完成/失败/已中断五态，默认收成一行、点头部展开结果，结果等宽展示、
  超长内部滚动，可复制参数与结果
- 工具审批：权限模式定首轮确认（完全访问直通 / 编辑拦写操作 / 计划全拦），工具卡变“待确认”
  可允许或拒绝；只读工具读取工作区之外（绝对路径或 `..` 穿越出工作区）会临时发起额外确认，
  完全访问模式下不追问
- 工作区：新建对话时可选（路径框直接输 + 「浏览…」弹系统资源管理器选目录，目录不存在可现场创建），
  不选就是默认 `~/.agentjava/workspace`；会话里工具的相对路径以工作区为基准，并在系统提示词里告知模型；
  侧栏会话按工作区分组显示，组头右侧"＋"跳过弹窗直接在该工作区新建对话；分叉出的会话沿用源会话工作区
- AI 回复 Markdown 渲染：标题/列表/引用/表格/代码块（高亮底 + 复制）/diff 增删行着色/任务勾选，`<think>` 推理块
  单独成块展示（默认收起显示已思考时长，点击展开），链接可点击跳转，流式逐段渲染（120ms 节流），可中断；
  消息文本可划词选中 Ctrl+C 复制
- 设置面板：分「通用 / 模型服务」两页（顶部胶囊标签切换）；模型服务页管理多套「配置方案」
  （地址 / Key / API 类型 / 温度 / 最大 Tokens / 系统提示词）+ 模型目录：每个模型一条可展开行，
  编辑名字 / 上下文窗口 / 最大输出 / 默认推理等级（选中时输入区思考强度自动带出）/ 输入能力
  （文本、图片），保存即热生效（后端监听变更重建 client）；通用页管主题（深浅、可跟随系统）
  与日志等级（TinyLog，改后重启生效）；
  API 类型可选 response（当前实现）/ chatcompletion / messages（后端待支持）
- 输入区：左下权限模式（计划 / 编辑 / 完全访问 / 纯聊天），右下模型与思考强度按对话选择，
  底部胶囊显示生成状态与 token 用量/耗时，Esc 停止生成；输入框高度随内容增高，
  到上限后变内部滚动（光标自动跟着打字位置走），回车发送 / Shift+回车换行
- 托盘常驻：关闭按钮可配置为最小化到托盘，右键菜单 / 双击恢复
- 后端：OpenAI Responses API（`backend/provider`），通过 `Provider` 接口对接，工厂创建

## 结构

```
src/main/java/io/github/preschoolller/agentjava/
├── Main.java            # 接线：把输入回调接到 Provider，流式回填窗口
├── frontend/
│   ├── gui/             # Swing 界面（AI 生成）：Window / Sidebar / MarkdownRenderer / Ui / Theme ...
│   └── store/           # 会话与设置持久化（AI 生成）：ConversationStore / AppSettings / Profile
└── backend/
    ├── tool/            # 本人练手区：ToolManager + Tool 接口（只读/额外审批能力）+ ToolRead 等
    └── provider/        # 本人练手区：Provider 接口 + OpenAiProvider（Responses API）+ 工厂
        ├── common/      # PromptParams / ProviderResponse / ReasoningEffortEnum / AgentMode
        └── interfaces/  # Provider
```

前后端的边界就两个方法：`frontend` 把用户输入和输入区选择（模型/思考强度/权限模式）
打包成 `PromptParams` 交给 `Provider.sendPrompt`，拿回 `ProviderResponse` 渲染成卡片。

## foragent/：前端文档与回归工具

`foragent/` 是给 AI（和人）看的前端交接文档，改前端前必读：

- **组件计划.md** —— 前端做了什么/还欠什么，改动要在这里登记状态；
- **踩坑记录.md** —— Swing/工程真实踩过的 28 个坑（EDT 线程、首选宽反馈循环、z-order、
  假 exited……），改到对应模块先扫雷；
- **src/test/** —— JUnit 5 回归测试：纯逻辑单测（持久化往返/配置方案/Markdown 解析）+
  GUI 回归 `WindowGuiTest`（有序用例，出 28 张截图 + UI 断言）：

```bash
mvn test
# 纯逻辑 20 例 + GUI 19 例（无托盘环境托盘段自动跳过），截图落在 target/regression/ 下
```

GUI 回归另带**交互巡检向导**（默认关）：`-Dgui.interactive=true` 时每个观察点截图后弹
主题化说明卡（写明"该站看什么"），人工点"下一个"才继续，人眼兜底观感/手感这类断言
够不着的维度；点"取消"=人工终止向导，剩余流程静默自动跑完。搭配
`-Dgui.interactive.auto=true` 向导卡照弹、600ms 自动点"下一个"（agent/CI 冒烟用）：

```bash
mvn test -Dtest=WindowGuiTest -Dgui.interactive=true                    # 人工巡检
mvn test -Dtest=WindowGuiTest -Dgui.interactive=true -Dgui.interactive.auto=true   # 冒烟
```
