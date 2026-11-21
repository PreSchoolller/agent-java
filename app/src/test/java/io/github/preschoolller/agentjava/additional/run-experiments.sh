#!/usr/bin/env bash
# ToolBash WSL 慢的根因实验：复刻 tryBash(2s)/execute 形状，配合 wsl 启停实测。
# 五组实验：
#   E1 暖机基线    —— 探测/执行各来几发，拿到“暖态耗时”
#   E2 冷启动+立即探测 —— wsl --shutdown 后立刻按应用逻辑(2s预算)探测，
#                        复现“误判 git bash”；随后立刻再探一次（重试能救吗）
#   E3 冷启动+延迟探测 —— shutdown 后等 8 秒再探（预热若不杀进程的假想收益）
#   E4 工作目录代价   —— 暖态下 exec 在 C:\ 与 D:\ workspace 各 3 发对比
#   E5 空闲自关机    —— 暖态后静默 75 秒，看 --list --running 是否已空 + 首发耗时
set -u
cd "$(dirname "$0")"

if [ ! -f WslProbe.class ]; then
  echo "[setup] compiling WslProbe.java"
  javac -encoding utf-8 WslProbe.java || { echo "javac failed"; exit 1; }
fi

CP="D:/CodingSpace/IdeaProjects/agent-java"
stamp() { date +%H:%M:%S; }
section() { echo; echo "======== [$1] $(stamp) $2 ========"; }

section E1 "暖机基线（3 探测 @2s + C盘 exec + D盘 exec）"
java WslProbe probe 3 2
java WslProbe exec "C:/Users/gwang" 1
java WslProbe exec "D:/CodingSpace/IdeaProjects/agent-java" 1

section E2 "冷启动：wsl --shutdown 后立即按应用逻辑探测（2s 预算）"
echo "[$(stamp)] wsl.exe --shutdown"
wsl.exe --shutdown
sleep 1
echo "[$(stamp)] --- 应用视角：3 连发探测，2s 预算 ---"
java WslProbe probe 3 2
echo "[$(stamp)] --- 立刻 exec 一发（真实工具调用的体验）---"
java WslProbe exec "D:/CodingSpace/IdeaProjects/agent-java" 1

section E3 "冷启动：shutdown 后等 8 秒再探（预热不杀进程的假想收益）"
echo "[$(stamp)] wsl.exe --shutdown"
wsl.exe --shutdown
sleep 8
echo "[$(stamp)] --- 8 秒后探测 ---"
java WslProbe probe 3 2

section E4 "暖态 exec：C盘 cwd vs D盘 workspace（各 3 发）"
java WslProbe exec "C:/Users/gwang" 3
java WslProbe exec "D:/CodingSpace/IdeaProjects/agent-java" 3

section E5 "空闲自关机：暖态后静默 75 秒，查 --list --running + 首发探测"
echo "[$(stamp)] 静默 75 秒（不碰 wsl）……"
sleep 75
echo "[$(stamp)] wsl.exe --list --running:"
wsl.exe --list --running 2>&1 | tr -d '\0'
echo "[$(stamp)] --- 静默后第一发探测 ---"
java WslProbe probe 2 2

echo
echo "[$(stamp)] done."
