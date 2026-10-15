#requires -Version 7.0
<#
.SYNOPSIS
构建课堂网页、生成任务与 Java。只有 -InstallDependencies 才安装 npm 依赖。
#>
[CmdletBinding()]
param(
    [switch]$InstallDependencies,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipCourseEngine
)
. (Join-Path $PSScriptRoot 'Classroom.Common.ps1')
$projectRoot = Get-ClassroomRoot
$node = Get-ClassroomExecutable 'node.exe'
$npm = Get-ClassroomExecutable 'npm.cmd'
$nodeVersion = & $node --version
if ($LASTEXITCODE -ne 0 -or [version]($nodeVersion.TrimStart('v')) -lt [version]'22.19.0') { throw '课堂需要 Node.js >= 22.19.0。' }
function Invoke-ClassroomNpmBuild([string]$Directory, [string]$Label, [string]$BuildScript = 'build') {
    if (-not (Test-Path -LiteralPath (Join-Path $Directory 'package.json'))) { throw "$Label 缺少 package.json：$Directory" }
    Push-Location -LiteralPath $Directory
    try {
        if ($InstallDependencies) {
            if (Test-Path -LiteralPath 'package-lock.json') { & $npm ci } else { & $npm install }
            if ($LASTEXITCODE -ne 0) { throw "$Label 依赖安装失败：$LASTEXITCODE" }
        } elseif (-not (Test-Path -LiteralPath 'node_modules')) {
            throw "$Label 尚未安装依赖，请显式使用 -InstallDependencies。"
        }
        & $npm run $BuildScript
        if ($LASTEXITCODE -ne 0) { throw "$Label 构建失败：$LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not $SkipCourseEngine) { Invoke-ClassroomNpmBuild $projectRoot '课堂生成器与共享包' 'build:classroom-engine' }
if (-not $SkipFrontend) { Invoke-ClassroomNpmBuild (Join-Path $projectRoot 'desktop-app') '课堂网页' }
if (-not $SkipBackend) {
    Get-ClassroomExecutable 'java.exe' | Out-Null
    Push-Location -LiteralPath $projectRoot
    try {
        # 打包阶段不启动测试中的整套应用；针对性验收命令见运行指南。
        & (Join-Path $projectRoot 'mvnw.cmd') '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) { throw "Java 打包失败：$LASTEXITCODE" }
        if (-not (Test-Path -LiteralPath 'target/NewBot_Agent-0.0.1-SNAPSHOT.jar')) { throw 'Maven 未产生预期的可运行 JAR。' }
    } finally { Pop-Location }
}
Write-Host '所选构建步骤完成。没有启动 Java、数据库或 HTTPS 服务。'
