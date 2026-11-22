#!/usr/bin/env bash
# =====================================================================
# agent-java 插件打包并安装脚本(Linux / macOS / Git Bash / WSL 通用)
#
# 用法:
#   bash scripts/install-plugin.sh plugin-tool-edit
#   bash scripts/install-plugin.sh plugin-tool-edit plugin-context-ai-brief
#   bash scripts/install-plugin.sh --all
#
# 选项:
#   -a, --all           安装仓库根下所有 plugin-* 工程
#   -s, --skip-deps     跳过本地仓库构件检查与安装
#   -r, --refresh-deps  强制重装 parent POM 与 spi(改过 spi 接口后必须用)
#   -h, --help          显示帮助
#
# 做三件事:
#   1. 确保本地仓库有 parent POM 与 spi 构件
#      —— 插件编译只依赖 spi(pom 里 scope=provided),不依赖宿主 app;
#         spi 的 pom 带 <parent>,所以消费方解析时 parent POM 也必须在仓库里
#      默认只补"缺失"的;加 --refresh-deps 则无条件重装,用于 spi 源码已改、
#      仓库里那份还是旧的情况(否则插件用旧 spi 编译,运行时 NoSuchMethodError)
#   2. 逐个 mvn clean package
#   3. 把 target 下打包出的 jar(排除 shade 产生的 original-* 瘦包)拷到
#      ~/.agentjava/plugins/(宿主启动时扫描该目录的 *.jar)
#
# 依赖检查在多个插件间只做一次;某个插件失败不影响其余,最后汇总退出码。
# 宿主 app 不在本脚本职责内,需要时自行 (cd app && mvn install)。
# =====================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 插件安装目录(Constants.PLUGIN_DIR = ~/.agentjava/plugins,启动时扫描 *.jar)
PLUGIN_INSTALL_DIR="$HOME/.agentjava/plugins"

PLUGINS=()
ALL=0
DO_DEPS=1
FORCE_DEPS=0

usage() {
    cat <<'EOF'
用法: install-plugin.sh [选项] <插件目录>...

  <插件目录>      相对仓库根的目录名(如 plugin-tool-edit)或绝对路径,可传多个
  -a, --all           安装所有 plugin-* 工程
  -s, --skip-deps     跳过 parent/spi 的本地仓库检查与安装
  -r, --refresh-deps  强制重装 parent POM 与 spi(改过 spi 接口后用)
  -h, --help          显示本帮助

示例:
  ./scripts/install-plugin.sh plugin-tool-edit
  ./scripts/install-plugin.sh plugin-tool-edit plugin-context-ai-brief
  ./scripts/install-plugin.sh --refresh-deps --all
  ./scripts/install-plugin.sh --all
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -a|--all)            ALL=1 ;;
        -s|--skip-deps)      DO_DEPS=0 ;;
        -r|--refresh-deps)   FORCE_DEPS=1 ;;
        -h|--help)           usage; exit 0 ;;
        -*)                  echo "未知选项: $1" >&2; usage >&2; exit 1 ;;
        *)                   PLUGINS+=("$1") ;;
    esac
    shift
done

if [ "$DO_DEPS" = 0 ] && [ "$FORCE_DEPS" = 1 ]; then
    echo "选项冲突: --skip-deps 与 --refresh-deps 互斥。" >&2
    exit 1
fi

if [ "$ALL" = 1 ]; then
    for pom in "$REPO_ROOT"/plugin-*/pom.xml; do
        [ -f "$pom" ] || continue
        PLUGINS+=("$(basename "$(dirname "$pom")")")
    done
fi

if [ ${#PLUGINS[@]} -eq 0 ]; then
    usage >&2
    exit 1
fi

# 从 pom 读首个指定标签的值:先删掉 <parent> 块再取。
# 子工程自己不写 groupId/version(继承 parent),所以这两个必须从根 pom 读。
pom_first_tag() {
    sed -e '/<parent>/,/<\/parent>/d' "$1" \
        | sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" \
        | head -1
}

ROOT_POM="$REPO_ROOT/pom.xml"
GROUP_ID="$(pom_first_tag "$ROOT_POM" groupId)"
VERSION="$(pom_first_tag "$ROOT_POM" version)"
PARENT_GAV="$GROUP_ID:$(pom_first_tag "$ROOT_POM" artifactId):$VERSION:pom"
SPI_GAV="$GROUP_ID:$(pom_first_tag "$REPO_ROOT/spi/pom.xml" artifactId):$VERSION"

# dependency:get -o 只查本地仓库,查不到即非零退出,正好当"有没有"用
in_local_repo() {
    mvn -q dependency:get -o -Dartifact="$1" >/dev/null 2>&1
}

ensure_build_deps() {
    if [ "$FORCE_DEPS" = 1 ]; then
        echo "[deps] 强制重装 parent POM 与 spi..."
        (cd "$REPO_ROOT" && mvn -q -N install -DskipTests) || {
            echo "  ✗ parent POM 安装失败" >&2; return 1; }
        (cd "$REPO_ROOT/spi" && mvn -q install -DskipTests) || {
            echo "  ✗ spi 安装失败" >&2; return 1; }
        echo "       就绪($SPI_GAV)"
        return 0
    fi

    echo "[deps] 检查本地仓库构件..."
    if ! in_local_repo "$PARENT_GAV"; then
        echo "       缺 parent POM,安装中..."
        (cd "$REPO_ROOT" && mvn -q -N install -DskipTests) || {
            echo "  ✗ parent POM 安装失败" >&2; return 1; }
    fi
    if ! in_local_repo "$SPI_GAV"; then
        echo "       缺 spi 构件,安装中..."
        (cd "$REPO_ROOT/spi" && mvn -q install -DskipTests) || {
            echo "  ✗ spi 安装失败" >&2; return 1; }
    fi
    echo "       就绪($SPI_GAV)"
}

# 支持相对仓库根的目录名、绝对路径(含 Windows 盘符)
resolve_plugin() {
    local p="$1"
    case "$p" in
        /*|[A-Za-z]:[\\/]*) ;;
        *) p="$REPO_ROOT/$p" ;;
    esac
    (cd "$p" 2>/dev/null && pwd)
}

# 打包并安装单个插件;返回 0 成功,非 0 失败
install_one() {
    local dir="$1" name jar=""
    name="$(basename "$dir")"

    echo "──── $name"
    if ! (cd "$dir" && mvn -q clean package); then
        echo "  ✗ 打包失败" >&2
        return 1
    fi

    # shade 打包后 target 下会有 original-*(未打进依赖的瘦包),要排除;产物名动态找
    for j in $(ls -t "$dir"/target/*.jar 2>/dev/null || true); do
        case "$(basename "$j")" in original-*) continue ;; esac
        jar="$j"
        break
    done
    if [ -z "$jar" ]; then
        echo "  ✗ target 下没找到打包产物" >&2
        return 1
    fi

    mkdir -p "$PLUGIN_INSTALL_DIR" || return 1
    if ! cp "$jar" "$PLUGIN_INSTALL_DIR/"; then
        echo "  ✗ 拷贝到 $PLUGIN_INSTALL_DIR 失败" >&2
        return 1
    fi
    echo "  ✓ $(basename "$jar")"
}

if [ "$DO_DEPS" = 1 ]; then
    ensure_build_deps || { echo "构建依赖未就绪,中止。" >&2; exit 1; }
fi

OK=()
FAIL=()
SEEN=""

for p in "${PLUGINS[@]}"; do
    # 去重(--all 与显式参数可能重叠)
    case " $SEEN " in *" $p "*) continue ;; esac
    SEEN="$SEEN $p"

    if ! dir="$(resolve_plugin "$p")"; then
        FAIL+=("$p(目录不存在)")
        continue
    fi
    if [ ! -f "$dir/pom.xml" ]; then
        FAIL+=("$p(没有 pom.xml)")
        continue
    fi
    if install_one "$dir"; then
        OK+=("$(basename "$dir")")
    else
        FAIL+=("$(basename "$dir")")
    fi
done

echo
echo "═══ 结果:成功 ${#OK[@]} 个,失败 ${#FAIL[@]} 个"
if [ ${#OK[@]} -gt 0 ]; then
    printf '  ✓ %s\n' "${OK[@]}"
fi
if [ ${#FAIL[@]} -gt 0 ]; then
    printf '  ✗ %s\n' "${FAIL[@]}" >&2
    echo "已成功安装的插件仍可用;修好失败的再单独跑一次即可。" >&2
    exit 1
fi
echo "安装目录: $PLUGIN_INSTALL_DIR"
echo "重启宿主应用后生效。"
