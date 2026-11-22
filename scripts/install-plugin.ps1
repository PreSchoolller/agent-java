<#
=====================================================================
agent-java 插件打包并安装脚本(Windows PowerShell 版,与 install-plugin.sh 等价)

用法:
    powershell -ExecutionPolicy Bypass -File scripts\install-plugin.ps1 plugin-tool-edit
    powershell -ExecutionPolicy Bypass -File scripts\install-plugin.ps1 plugin-tool-edit plugin-tool-bili
    powershell -ExecutionPolicy Bypass -File scripts\install-plugin.ps1 -All

参数:
    <插件目录>        相对仓库根的目录名(如 plugin-tool-edit)或绝对路径,可传多个
    -All              安装仓库根下所有 plugin-* 工程
    -SkipDeps         跳过本地仓库构件检查与安装
    -RefreshDeps      强制重装 parent POM 与 spi(改过 spi 接口后必须用)

做三件事:
  1. 确保本地仓库有 parent POM 与 spi 构件
     —— 插件编译只依赖 spi(pom 里 scope=provided),不依赖宿主 app;
        spi 的 pom 带 <parent>,所以 consumer 侧解析时 parent POM 也必须在仓库里
     默认只补"缺失"的;加 -RefreshDeps 则无条件重装,用于 spi 源码已改、
     仓库里那份还是旧的情况(否则插件用旧 spi 编译,运行时 NoSuchMethodError)
  2. 逐个 mvn clean package 打包插件
  3. 把 target 下打包出的 jar(排除 shade 产生的 original-* 瘦包)拷到
     %USERPROFILE%\.agentjava\plugins\(宿主启动时扫描该目录的 *.jar)

依赖检查在多个插件间只做一次;某个插件失败不影响其余,最后汇总退出码。
宿主 app 不在本脚本职责内,需要时自行 (cd app; mvn install)。
=====================================================================
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PluginDir = @(),

    [switch]$All,
    [switch]$SkipDeps,
    [switch]$RefreshDeps,
    [Alias('h', '?')]
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# 插件安装目录(Constants.PLUGIN_DIR = ~/.agentjava/plugins,启动时扫描 *.jar)
$PluginInstallDir = Join-Path $env:USERPROFILE '.agentjava\plugins'

if ($SkipDeps -and $RefreshDeps) {
    Write-Host '选项冲突:-SkipDeps 与 -RefreshDeps 互斥。' -ForegroundColor Red
    exit 1
}

function Show-Usage {
    @'
用法: install-plugin.ps1 [选项] <插件目录>...

  <插件目录>      相对仓库根的目录名(如 plugin-tool-edit)或绝对路径,可传多个
  -All            安装所有 plugin-* 工程
  -SkipDeps       跳过 parent/spi 的本地仓库检查与安装
  -RefreshDeps    强制重装 parent POM 与 spi(改过 spi 接口后用)
  -Help           显示本帮助

示例:
  .\scripts\install-plugin.ps1 plugin-tool-edit
  .\scripts\install-plugin.ps1 plugin-tool-edit plugin-tool-bili
  .\scripts\install-plugin.ps1 -RefreshDeps -All
  .\scripts\install-plugin.ps1 -All
'@ | Write-Host
}

if ($Help) {
    Show-Usage
    exit 0
}

# 读 pom 首个指定标签的值:先删掉 <parent> 块再取。
# 子工程自己不写 groupId/version(继承 parent),所以这两个必须从根 pom 读。
function Get-PomTag {
    param([string]$PomPath, [string]$Tag)

    $raw = Get-Content -Raw -Encoding UTF8 $PomPath
    $raw = [regex]::Replace($raw, '(?s)<parent>.*?</parent>', '')
    $m = [regex]::Match($raw, "<$Tag>\s*([^<]+?)\s*</$Tag>")
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return $null
}

# dependency:get -o 只查本地仓库,查不到即非零退出,正好当"有没有"用
function Test-InLocalRepo {
    param([string]$Artifact)

    & mvn -q dependency:get -o "-Dartifact=$Artifact" 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Install-BuildDeps {
    if ($RefreshDeps) {
        Write-Host '[deps] 强制重装 parent POM 与 spi...'

        Push-Location $RepoRoot
        try {
            & mvn -q -N install -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'parent POM 安装失败' }
        } finally { Pop-Location }

        Push-Location (Join-Path $RepoRoot 'spi')
        try {
            & mvn -q install -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'spi 安装失败' }
        } finally { Pop-Location }

        Write-Host "       就绪($SpiGav)"
        return
    }

    Write-Host '[deps] 检查本地仓库构件...'

    if (-not (Test-InLocalRepo $ParentGav)) {
        Write-Host '       缺 parent POM,安装中...'
        Push-Location $RepoRoot
        try {
            & mvn -q -N install -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'parent POM 安装失败' }
        } finally { Pop-Location }
    }

    if (-not (Test-InLocalRepo $SpiGav)) {
        Write-Host '       缺 spi 构件,安装中...'
        Push-Location (Join-Path $RepoRoot 'spi')
        try {
            & mvn -q install -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'spi 安装失败' }
        } finally { Pop-Location }
    }

    Write-Host "       就绪($SpiGav)"
}

# 支持相对仓库根的目录名、绝对路径;不存在返回 $null
function Resolve-PluginDir {
    param([string]$Name)

    if ([System.IO.Path]::IsPathRooted($Name)) { $candidate = $Name }
    else { $candidate = Join-Path $RepoRoot $Name }

    if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    return $null
}

function Install-One {
    param([string]$Dir)

    $name = Split-Path $Dir -Leaf
    Write-Host "──── $name"

    Push-Location $Dir
    try {
        & mvn -q clean package
        if ($LASTEXITCODE -ne 0) { throw '打包失败' }
    } finally { Pop-Location }

    # shade 打包后 target 下会有 original-*(未打进依赖的瘦包),要排除;产物名动态找
    $jar = Get-ChildItem (Join-Path $Dir 'target') -Filter '*.jar' |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw 'target 下没找到打包产物' }

    if (-not (Test-Path $PluginInstallDir)) {
        New-Item -ItemType Directory -Path $PluginInstallDir | Out-Null
    }
    Copy-Item $jar.FullName (Join-Path $PluginInstallDir $jar.Name) -Force
    Write-Host "  ✓ $($jar.Name)"
}

# ---------------------------------------------------------------------

if ($All) {
    $PluginDir += Get-ChildItem $RepoRoot -Directory -Filter 'plugin-*' |
        Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
        ForEach-Object { $_.Name }
}

if (-not $PluginDir -or $PluginDir.Count -eq 0) {
    Show-Usage
    exit 1
}

$rootPom = Join-Path $RepoRoot 'pom.xml'
$GroupId = Get-PomTag $rootPom 'groupId'
$Version = Get-PomTag $rootPom 'version'
$ParentGav = '{0}:{1}:{2}:pom' -f $GroupId, (Get-PomTag $rootPom 'artifactId'), $Version
$SpiGav = '{0}:{1}:{2}' -f $GroupId, (Get-PomTag (Join-Path $RepoRoot 'spi\pom.xml') 'artifactId'), $Version

if (-not $SkipDeps) {
    try {
        Install-BuildDeps
    } catch {
        Write-Host "构建依赖未就绪,中止:$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

# 去重(-All 与显式参数可能重叠)
$seen = @{}
$targets = @()
foreach ($p in $PluginDir) {
    $key = $p.ToLowerInvariant()
    if (-not $seen.ContainsKey($key)) {
        $seen[$key] = $true
        $targets += $p
    }
}

$ok = @()
$fail = @()

foreach ($p in $targets) {
    $dir = Resolve-PluginDir $p
    if (-not $dir) { $fail += "$p(目录不存在)"; continue }
    if (-not (Test-Path (Join-Path $dir 'pom.xml'))) { $fail += "$p(没有 pom.xml)"; continue }

    $leaf = Split-Path $dir -Leaf
    try {
        Install-One $dir
        $ok += $leaf
    } catch {
        $fail += "$leaf($($_.Exception.Message))"
    }
}

Write-Host ''
Write-Host "═══ 结果:成功 $($ok.Count) 个,失败 $($fail.Count) 个"
if ($ok.Count -gt 0) { $ok | ForEach-Object { Write-Host "  ✓ $_" } }
if ($fail.Count -gt 0) {
    $fail | ForEach-Object { Write-Host "  ✗ $_" -ForegroundColor Red }
    Write-Host '已成功安装的插件仍可用;修好失败的再单独跑一次即可。' -ForegroundColor Yellow
    exit 1
}

Write-Host "安装目录: $PluginInstallDir"
Write-Host '重启宿主应用后生效。'
