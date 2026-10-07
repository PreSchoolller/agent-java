# Agent-Java

Java 桌面 AI Agent 应用。学习用途。未完成。前端 99% 由 AI 生成，不入坑 Swing，后端手写并借助 AI 审核。

## 运行

需要 JDK 17+ 和 Maven。

```bash
mvn compile
mvn exec:java -Dexec.mainClass=io.github.preschoolller.agentjava.Main
```

（或者 IDE 里直接跑 `Main`。）  

本地数据地址
```
~/.agentjava/
├── history.json         # 会话索引（只存元数据：标题/时间/置顶）
├── settings.properties  # 应用设置
└── sessions/            # 单会话消息文件，文件名即会话 id
    └── ...
```
默认后端地址指向本地服务（如 LM Studio），在设置面板里改成你的即可。

## 功能

- 多会话管理：侧栏搜索/重命名/置顶/删除，从任一 AI 回复分叉成新会话，本地 JSON 持久化，长会话视口窗口化
- AI 回复 Markdown 渲染：标题/列表/引用/代码块（高亮底 + 复制）/任务勾选，`<think>` 推理块
  单独成块展示，流式逐段渲染（120ms 节流），可中断
- 设置面板：多套「配置方案」（地址 / Key / 可选模型列表 / 温度 / 最大 Tokens / 系统提示词），
  保存即热生效（后端监听变更重建 client），深浅主题跟随
- 输入区：左下权限模式（计划 / 编辑 / 完全访问），右下模型与思考强度按对话选择
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
    └── provider/        # 本人练手区：Provider 接口 + OpenAiProvider（Responses API）+ 工厂
        ├── common/      # PromptParams / ProviderResponse / ReasoningEffortEnum / AgentMode
        └── interfaces/  # Provider
```

前后端的边界就两个方法：`frontend` 把用户输入和输入区选择（模型/思考强度/权限模式）
打包成 `PromptParams` 交给 `Provider.sendPrompt`，拿回 `ProviderResponse` 渲染成卡片。

## foragent/：前端文档与回归工具

`foragent/` 是给 AI（和人）看的前端交接文档，改前端前必读：

- **组件计划.md** —— 前端做了什么/还欠什么，改动要在这里登记状态；
- **踩坑记录.md** —— Swing 真实踩过的 21 个坑（EDT 线程、首选宽反馈循环、z-order、
  假 exited……），改到对应模块先扫雷；
- **tools/Shot.java** —— 回归工具：跑一遍出 15 张截图 + 持久化/解析/流式/窗口化/托盘断言，

```bash
javac -encoding UTF-8 -cp target/classes -d target/classes foragent/tools/Shot.java
java -cp target/classes foragent.tools.Shot target/window
# 断言全过打印 SHOT_OK，截图落在 target/ 下
```
