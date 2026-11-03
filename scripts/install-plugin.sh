#!/usr/bin/env bash
# =====================================================================
# agent-java 插件打包并安装脚本(Linux / macOS / Git Bash / WSL 通用)
# 用法(插件目录相对仓库根,也可以传绝对路径):
#   bash scripts/install-plugin.sh plugin-provider
#   ./scripts/install-plugin.sh external-plugin
# 做三件事:
#   1. 用 mvn dependency:get 离线探测本地仓库(settings.xml 指定的 localRepository,
#      自定义位置也能命中)有没有宿主构件,没有才安装宿主
#      —— 插件编译期要靠它解析 Tool/Provider 接口(pom 里 scope=provided)
#   2. mvn clean package 打包插件
#   3. 把 target 下打包出的 jar(排除 shade 产生的 original-* 瘦包)拷到
#      ~/.agentjava/plugins/(宿主启动时扫描该目录的 *.jar)
# 宿主坐标从宿主 pom.xml 动态解析,产物名从 target 动态找,
# 所以新增插件零改动,直接传目录即可。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PLUGIN_DIR="${1:?用法: $0 <插件工程目录,如 plugin-provider>}"
case "$PLUGIN_DIR" in
    /*) ;;                                   # 绝对路径直接用
    *)  PLUGIN_DIR="$REPO_ROOT/$PLUGIN_DIR" ;;
esac
PLUGIN_DIR="$(cd "$PLUGIN_DIR" && pwd)"
[ -f "$PLUGIN_DIR/pom.xml" ] || { echo "错误: $PLUGIN_DIR 下没有 pom.xml" >&2; exit 1; }

# 从 pom 读首个指定标签的值:先删掉 <parent> 块再取,避免将来加了 parent 拿到 parent 的坐标
pom_first_tag() {
    sed -e '/<parent>/,/<\/parent>/d' "$1" \
        | sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" \
        | head -1
}

HOST_POM="$REPO_ROOT/pom.xml"
HOST_ARTIFACT="$(pom_first_tag "$HOST_POM" groupId):$(pom_first_tag "$HOST_POM" artifactId):$(pom_first_tag "$HOST_POM" version)"

# 插件安装目录(ProviderFactory/ToolManager 的 loadExternal 启动时扫描这里的 *.jar)
PLUGIN_INSTALL_DIR="$HOME/.agentjava/plugins"

# ---- 第 1 步:确保宿主已 install 到本地仓库 --------------------------
# dependency:get -o 只查本地仓库,查不到就非零退出,正好当"没有"用。
# 注意:宿主接口有改动后,即使探测命中也可能是旧版本,要手动重跑 mvn install,
#       否则插件编译用的还是旧接口,运行期会 NoSuchMethodError
echo "[1/3] 检查本地仓库是否已有宿主构件 $HOST_ARTIFACT ..."
if mvn -q dependency:get -o -Dartifact="$HOST_ARTIFACT" >/dev/null 2>&1; then
    echo "      宿主构件已存在,跳过安装"
else
    echo "      本地仓库没有宿主构件,先安装宿主..."
    (cd "$REPO_ROOT" && mvn -q install -DskipTests)
fi

# ---- 第 2 步:打包插件 ------------------------------------------------
echo "[2/3] 打包插件..."
(cd "$PLUGIN_DIR" && mvn -q clean package)

# ---- 第 3 步:安装 jar 到插件目录 ------------------------------------
# shade 打包后 target 下会有 original-*(瘦包),要排除;产物名动态找,不写死
JAR=""
for j in $(ls -t "$PLUGIN_DIR"/target/*.jar 2>/dev/null || true); do
    case "$(basename "$j")" in original-*) continue ;; esac
    JAR="$j"
    break
done
if [ -z "$JAR" ]; then
    echo "错误: $PLUGIN_DIR/target 下没找到打包产物" >&2
    exit 1
fi

mkdir -p "$PLUGIN_INSTALL_DIR"
cp "$JAR" "$PLUGIN_INSTALL_DIR/"
echo "[3/3] 已安装: $PLUGIN_INSTALL_DIR/$(basename "$JAR")"
echo "重启宿主应用后生效。"
