#!/usr/bin/env bash
# =====================================================================
# agent-java 插件打包并安装脚本(Linux / macOS / Git Bash 通用)
# 做三件事:
#   1. 本地 maven 仓库(~/.m2)里没有宿主构件时,先安装宿主
#      —— 插件编译期要靠它解析 Tool 接口(pom 里 scope=provided)
#   2. mvn clean package 打包插件,产物在 external-plugin/target/ 下
#   3. 把 jar 拷到 ~/.agentjava/plugins/(宿主启动时扫描该目录的 *.jar)
# 不想用脚本的话,照下面各步的"手动等价操作"执行同样三步即可。
# =====================================================================
set -euo pipefail

# 插件工程目录 = 本脚本所在目录(不依赖当前工作目录,在哪执行都行);宿主工程在上一级
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# 插件 jar 名(版本升级时改这里,和 pom.xml 的 version 保持一致)
JAR_NAME="agent-java-plugin-1.0-SNAPSHOT.jar"
# 宿主构件在本机 maven 仓库的落地位置
HOST_REPO_JAR="$HOME/.m2/repository/io/github/preschoolller/agent-java/1.0-SNAPSHOT/agent-java-1.0-SNAPSHOT.jar"
# 插件安装目录(ToolManager.loadExternal 启动时扫描这里的 *.jar)
PLUGIN_DIR="$HOME/.agentjava/plugins"

# ---- 第 1 步:确保宿主已 install 到本地仓库 --------------------------
# 手动等价操作:在宿主根目录(agent-java/)执行  mvn install -DskipTests
# 注意:宿主的 Tool 接口有改动后要重跑这一步(或删掉 ~/.m2 里的对应目录),
#       否则插件编译时用的还是旧接口,运行期会 NoSuchMethodError
if [ -f "$HOST_REPO_JAR" ]; then
    echo "[1/3] 宿主构件已存在,跳过安装"
else
    echo "[1/3] 本地仓库没有宿主构件,先安装宿主..."
    (cd "$HOST_DIR" && mvn -q install -DskipTests)
fi

# ---- 第 2 步:打包插件 ------------------------------------------------
# 手动等价操作:在本目录(external-plugin/)执行  mvn clean package
echo "[2/3] 打包插件..."
(cd "$SCRIPT_DIR" && mvn -q clean package)

# ---- 第 3 步:安装 jar 到插件目录 ------------------------------------
# 手动等价操作:cp target/agent-java-plugin-1.0-SNAPSHOT.jar ~/.agentjava/plugins/
mkdir -p "$PLUGIN_DIR"
cp "$SCRIPT_DIR/target/$JAR_NAME" "$PLUGIN_DIR/"
echo "[3/3] 已安装: $PLUGIN_DIR/$JAR_NAME"
echo "重启宿主应用后生效。"
