#requires -Version 7.0
<#
.SYNOPSIS
启动已构建的课堂 HTTPS 网页；Java 可由本脚本启动或显式复用已有后端。
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$HttpsPort = 1443,
    [ValidateRange(1, 65535)][int]$BackendPort = 8888,
    [string]$TlsDirectory = '',
    [string]$JarPath = '',
    [switch]$UseExistingBackend,
    [switch]$StartWechat,
    [ValidateRange(5, 600)][int]$BackendWaitSeconds = 120
)
. (Join-Path $PSScriptRoot 'Classroom.Common.ps1')
if ($HttpsPort -eq $BackendPort) { throw 'HTTPS 与 Java 不能使用同一端口。' }
if ($UseExistingBackend -and $StartWechat) { throw '-StartWechat 只适用于本脚本新启动的 Java；已有后端请使用原控制台 bot start 1。' }
$projectRoot = Get-ClassroomRoot
$node = Get-ClassroomExecutable 'node.exe'
$nodeVersion = & $node --version
if ($LASTEXITCODE -ne 0 -or [version]($nodeVersion.TrimStart('v')) -lt [version]'22.19.0') { throw '课堂需要 Node.js >= 22.19.0。' }
$runDirectory = Join-Path $projectRoot 'data/classroom/run'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$statePath = Join-Path $runDirectory 'processes.json'
# 防止两次点击启动同时检查到空闲端口后互相覆盖进程记录。
$lock = [IO.File]::Open((Join-Path $runDirectory 'launch.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$state = $null
try {
    if (Test-Path -LiteralPath $statePath) {
        $oldState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($entry in @($oldState.web, $oldState.backend)) {
            if ($entry -and (Get-ClassroomOwnedProcess $entry)) { throw '已有本脚本启动的课堂进程，请先运行 Stop-Classroom.ps1。' }
        }
    }
    if (-not $TlsDirectory) { $TlsDirectory = Join-Path $projectRoot 'data/classroom/tls' }
    $tlsConfig = Join-Path ([IO.Path]::GetFullPath($TlsDirectory)) 'tls.json'
    if (-not (Test-Path -LiteralPath $tlsConfig)) { throw '缺少课堂证书，请先运行 New-ClassroomCertificate.ps1。' }
    $dist = Join-Path $projectRoot 'desktop-app/dist'
    if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw '缺少网页构建产物，请先运行 Build-Classroom.ps1。' }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'course-engine/dist/worker.js'))) { throw '缺少课堂生成器，请先在根目录运行 npm run build:classroom-engine。' }
    $httpsOwners = @(Get-ClassroomPortOwners $HttpsPort)
    if ($httpsOwners.Count -gt 0) { throw "HTTPS 端口 $HttpsPort 已占用（PID $($httpsOwners.ProcessId -join ', ')），未结束任何进程。" }
    $backendOwners = @(Get-ClassroomPortOwners $BackendPort)
    if ($UseExistingBackend) {
        if ($backendOwners.Count -eq 0) { throw "-UseExistingBackend 已指定，但端口 $BackendPort 未监听。" }
        Write-Host "复用现有后端端口 $BackendPort，PID $($backendOwners.ProcessId -join ', ')；本脚本不会停止它。"
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/classroom/health" -TimeoutSec 10
        Assert-ClassroomBackendHealth $health
    } else {
        if ($backendOwners.Count -gt 0) { throw "Java 端口 $BackendPort 已占用（PID $($backendOwners.ProcessId -join ', ')）。确认是本项目后端后可显式使用 -UseExistingBackend。" }
        $java = Get-ClassroomExecutable 'java.exe'
        if (-not $JarPath) { $JarPath = Join-Path $projectRoot 'target/NewBot_Agent-0.0.1-SNAPSHOT.jar' }
        $JarPath = [IO.Path]::GetFullPath($JarPath)
        if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw '缺少可运行 JAR，请先运行 Build-Classroom.ps1。' }
    }
    $runId = [Guid]::NewGuid().ToString('N')
    $state = [ordered]@{ schemaVersion = 1; runId = $runId; status = 'starting'; web = $null; backend = $null; httpsPort = $HttpsPort; backendPort = $BackendPort; reusedBackend = [bool]$UseExistingBackend; startedUtc = [DateTime]::UtcNow.ToString('o') }
    Write-ClassroomState $statePath $state
    if (-not $UseExistingBackend) {
        $backendMarker = "--classroom.launch-id=$runId"
        $javaArgumentList = @('-jar', $JarPath, "--server.port=$BackendPort", '--server.address=127.0.0.1', $backendMarker)
        if ($StartWechat) { $javaArgumentList += '--bot.auto-start-count=1' }
        $javaArguments = ConvertTo-ClassroomArgumentString $javaArgumentList
        $backendProcess = Start-Process -FilePath $java -ArgumentList $javaArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDirectory "$runId-java.out.log") -RedirectStandardError (Join-Path $runDirectory "$runId-java.err.log") -PassThru
        $state.backend = Get-ClassroomProcessRecord $backendProcess $java $backendMarker
        Write-ClassroomState $statePath $state
        Wait-ClassroomListener $BackendPort $backendProcess.Id $BackendWaitSeconds
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/classroom/health" -TimeoutSec 10
        Assert-ClassroomBackendHealth $health
    }
    $serverScript = Join-Path $PSScriptRoot 'classroom-server.mjs'
    $webArguments = ConvertTo-ClassroomArgumentString @($serverScript, '--dist', $dist, '--tls-config', $tlsConfig, '--port', "$HttpsPort", '--backend-port', "$BackendPort", '--run-id', $runId)
    $webProcess = Start-Process -FilePath $node -ArgumentList $webArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDirectory "$runId-web.out.log") -RedirectStandardError (Join-Path $runDirectory "$runId-web.err.log") -PassThru
    $state.web = Get-ClassroomProcessRecord $webProcess $node $runId
    Write-ClassroomState $statePath $state
    Wait-ClassroomListener $HttpsPort $webProcess.Id 15
    $state.status = 'running'
    Write-ClassroomState $statePath $state
    Write-Host "课堂服务已启动；本机 https://localhost:$HttpsPort"
    $tls = Get-Content -LiteralPath $tlsConfig -Raw | ConvertFrom-Json
    foreach ($san in $tls.subjectAltNames) {
        if ($san.StartsWith('ip:') -and $san -ne 'ip:127.0.0.1') {
            $address = $san.Substring(3)
            if ($address.Contains(':')) { $address = "[$address]" }
            Write-Host "证书覆盖的手机入口：https://${address}:$HttpsPort（需同一 Wi-Fi、地址仍属于本机且手机信任 ca.crt）"
        }
    }
    Write-Host "请从手机可达的局域网 URL 打开首页生成微信配对码；localhost 链接仅本机可用。"
    Write-Host "日志：$runDirectory；停止：./scripts/Stop-Classroom.ps1"
    if ($StartWechat) { Write-Host "微信扫码链接见 $runDirectory/$runId-java.out.log；扫码需由你本人完成。" }
} catch {
    if ($state) {
        $state.status = 'failed'
        foreach ($entry in @($state.web, $state.backend)) {
            if ($entry) { try { Stop-ClassroomOwnedProcess $entry } catch { Write-Warning "清理本次进程失败：$($_.Exception.Message)" } }
        }
        Write-ClassroomState $statePath $state
    }
    throw
} finally { $lock.Dispose() }
