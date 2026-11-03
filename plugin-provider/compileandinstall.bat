@echo off
chcp 65001 >nul
rem =====================================================================
rem agent-java 插件打包并安装脚本(Windows 版,与 compileandinstall.sh 等价)
rem 做三件事:
rem   1. 本地 maven 仓库(%USERPROFILE%\.m2)里没有宿主构件时,先安装宿主
rem      —— 插件编译期要靠它解析 Tool 接口(pom 里 scope=provided)
rem   2. mvn clean package 打包插件,产物在 external-plugin\target\ 下
rem   3. 把 jar 拷到 %USERPROFILE%\.agentjava\plugins\(宿主启动时扫描该目录的 *.jar)
rem 不想用脚本的话,照下面各步的"手动等价操作"执行同样三步即可。
rem =====================================================================
setlocal

rem 插件工程目录 = 本脚本所在目录(%~dp0 自带结尾反斜杠);宿主工程在上一级
set "SCRIPT_DIR=%~dp0"
set "HOST_DIR=%SCRIPT_DIR%.."
rem 插件 jar 名(版本升级时改这里,和 pom.xml 的 version 保持一致)
set "JAR_NAME=agent-java-plugin-1.0-SNAPSHOT.jar"
rem 宿主构件在本机 maven 仓库的落地位置
set "HOST_REPO_JAR=%USERPROFILE%\.m2\repository\io\github\preschoolller\agent-java\1.0-SNAPSHOT\agent-java-1.0-SNAPSHOT.jar"
rem 插件安装目录(ToolManager.loadExternal 启动时扫描这里的 *.jar)
set "PLUGIN_DIR=%USERPROFILE%\.agentjava\plugins"

rem ---- 第 1 步:确保宿主已 install 到本地仓库 --------------------------
rem 手动等价操作:在宿主根目录(agent-java\)执行  mvn install -DskipTests
rem 注意:宿主的 Tool 接口有改动后要重跑这一步(或删掉 .m2 里的对应目录),
rem       否则插件编译时用的还是旧接口,运行期会 NoSuchMethodError
if exist "%HOST_REPO_JAR%" (
    echo [1/3] 宿主构件已存在,跳过安装
) else (
    echo [1/3] 本地仓库没有宿主构件,先安装宿主...
    pushd "%HOST_DIR%"
    call mvn -q install -DskipTests
    if errorlevel 1 (popd & echo 宿主安装失败 & exit /b 1)
    popd
)

rem ---- 第 2 步:打包插件 ------------------------------------------------
rem 手动等价操作:在本目录(external-plugin\)执行  mvn clean package
echo [2/3] 打包插件...
pushd "%SCRIPT_DIR%"
call mvn -q clean package
if errorlevel 1 (popd & echo 插件打包失败 & exit /b 1)
popd

rem ---- 第 3 步:安装 jar 到插件目录 ------------------------------------
rem 手动等价操作:copy target\agent-java-plugin-1.0-SNAPSHOT.jar "%USERPROFILE%\.agentjava\plugins\"
if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"
copy /Y "%SCRIPT_DIR%target\%JAR_NAME%" "%PLUGIN_DIR%\" >nul
echo [3/3] 已安装: %PLUGIN_DIR%\%JAR_NAME%
echo 重启宿主应用后生效。
endlocal
