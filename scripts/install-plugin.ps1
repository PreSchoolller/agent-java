<#
=====================================================================
agent-java 插件打包并安装脚本(Windows PowerShell 版,与 install-plugin.sh 等价)
用法(插件目录相对仓库根,也可以传绝对路径):
    powershell -ExecutionPolicy Bypass -File scripts\install-plugin.ps1 plugin-provider
    powershell -ExecutionPolicy Bypass -File scripts\install-plugin.ps1 external-plugin
pwsh 7 下可以直接: ./scripts/install-plugin.ps1 plugin-provider
(ExecutionPolicy 是为了避免"禁止运行脚本"报错,只对本次进程生效)

做三件事:
  1. 用 mvn dependency:get 离线探测本地仓库(settings.xml 指定的 localRepository,
     自定义位置也能命中)有没有宿主构件,没有才安装宿主
     —— 插件编译期要靠它解析 Tool/Provider 接口(pom 里 scope=provided)
  2. mvn clean package 打包插件
  3. 把 target 下打包出的 jar(排除 shade 产生的 original-* 瘦包)拷到
     %USERPROFILE%\.agentjava\plugins\(宿主启动时扫描该目录的 *.jar)

宿主坐标从宿主 pom.xml 动态解析,产物名从 target 动态找,
所以新增插件零改动,直接传目录即可。
=====================================================================
#>
param(
    [string]$PluginDir
)

$ErrorActionPreference = 'Stop'

if (-not $PluginDir) {
    throw '用法: install-plugin.ps1 <插件工程目录,如 plugin-provider>'
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([System.IO.Path]::IsPathRooted($PluginDir)) {
    $PluginPath = $PluginDir
} else {
    $PluginPath = Join-Path $RepoRoot $PluginDir
}
$PluginPath = (Resolve-Path $PluginPath).Path
if (-not (Test-Path (Join-Path $PluginPath 'pom.xml'))) {
    throw "在 $PluginPath 下没找到 pom.xml,请传入插件工程目录"
}

# 宿主坐标从宿主 pom.xml 里读,不写死(G:A:V)
$pom = [xml](Get-Content -Raw -Encoding UTF8 (Join-Path $RepoRoot 'pom.xml'))
$hostArtifact = '{0}:{1}:{2}' -f $pom.project.groupId, $pom.project.artifactId, $pom.project.version

# 插件安装目录(ProviderFactory/ToolManager 的 loadExternal 启动时扫描这里的 *.jar)
$pluginInstallDir = Join-Path $env:USERPROFILE '.agentjava\plugins'

# ---- 第 1 步:确保宿主已 install 到本地仓库 --------------------------
# dependency:get -o 只查本地仓库,查不到就非零退出,正好当"没有"用。
# 注意:宿主接口有改动后,即使探测命中也可能是旧版本,要手动重跑 mvn install,
#       否则插件编译用的还是旧接口,运行期会 NoSuchMethodError
Write-Host "[1/3] 检查本地仓库是否已有宿主构件 $hostArtifact ..."
& mvn -q dependency:get -o "-Dartifact=$hostArtifact" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host '      本地仓库没有宿主构件,先安装宿主...'
    Push-Location $RepoRoot
    & mvn -q install -DskipTests
    $failed = ($LASTEXITCODE -ne 0)
    Pop-Location
    if ($failed) { throw '宿主安装失败' }
} else {
    Write-Host '      宿主构件已存在,跳过安装'
}

# ---- 第 2 步:打包插件 ------------------------------------------------
Write-Host '[2/3] 打包插件...'
Push-Location $PluginPath
& mvn -q clean package
$failed = ($LASTEXITCODE -ne 0)
Pop-Location
if ($failed) { throw '插件打包失败' }

# ---- 第 3 步:安装 jar 到插件目录 ------------------------------------
# shade 打包后 target 下会有 original-*(未打进依赖的瘦包),要排除;
# 产物名由 pom 的 artifactId/version 决定,动态找,不写死
$jar = Get-ChildItem (Join-Path $PluginPath 'target') -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'original-*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $jar) {
    throw "$PluginPath\target 下没找到打包产物"
}

if (-not (Test-Path $pluginInstallDir)) {
    New-Item -ItemType Directory -Path $pluginInstallDir | Out-Null
}
Copy-Item $jar.FullName (Join-Path $pluginInstallDir $jar.Name) -Force
Write-Host "[3/3] 已安装: $(Join-Path $pluginInstallDir $jar.Name)"
Write-Host '重启宿主应用后生效。'
