#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ClassroomRoot {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

function Get-ClassroomExecutable([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "缺少 $Name，请先安装项目所需运行环境，不要修改现有全局 PATH。" }
    $command.Source
}

function Get-ClassroomPortOwners([int]$Port) {
    # 读取当前监听及准确进程身份，不将端口/PID 当作结束进程的依据。
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    foreach ($ownerId in @($listeners | Select-Object -ExpandProperty OwningProcess | Sort-Object -Unique)) {
        Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" -OperationTimeoutSec 5
    }
}

function ConvertTo-ClassroomArgumentString([string[]]$Arguments) {
    # Start-Process 会把 ArgumentList 拼成字符串，必须自行按 Windows argv 规则保留含空格的路径。
    ($Arguments | ForEach-Object {
        if ($_ -notmatch '[\s"]' -and $_.Length -gt 0) { $_ }
        else { '"' + ([regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1')) + '"' }
    }) -join ' '
}

function Get-ClassroomProcessRecord([Diagnostics.Process]$Process, [string]$Executable, [string]$Marker) {
    $Process.Refresh()
    [ordered]@{
        pid = $Process.Id
        startedUtcTicks = $Process.StartTime.ToUniversalTime().Ticks.ToString()
        executable = [IO.Path]::GetFullPath($Executable)
        marker = $Marker
    }
}

function Get-ClassroomOwnedProcess($Record) {
    if (-not $Record) { return $null }
    $current = Get-Process -Id ([int]$Record.pid) -ErrorAction SilentlyContinue
    if (-not $current) { return $null }
    $details = Get-CimInstance Win32_Process -Filter "ProcessId = $($Record.pid)" -OperationTimeoutSec 5
    if (-not $details -or $current.StartTime.ToUniversalTime().Ticks.ToString() -ne $Record.startedUtcTicks -or
        -not [string]::Equals($details.ExecutablePath, $Record.executable, [StringComparison]::OrdinalIgnoreCase) -or
        -not $details.CommandLine.Contains([string]$Record.marker)) {
        throw "PID $($Record.pid) 的身份与课堂记录不同，已拒绝操作。"
    }
    $current
}

function Stop-ClassroomOwnedProcess($Record) {
    $current = Get-ClassroomOwnedProcess $Record
    if ($current) {
        # 只终止刚核实的进程对象，不按通用名称、端口或整个进程树结束其他组件。
        $current.Kill()
        if (-not $current.WaitForExit(10000)) { throw "课堂进程 $($Record.pid) 尚未退出，请检查实际状态。" }
    }
}

function Write-ClassroomState([string]$Path, $State) {
    $State | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Assert-ClassroomBackendHealth($Health) {
    # 端口可连或任意 JSON 都不足以证明是本项目的课堂 API。
    if ($null -eq $Health -or $Health -isnot [pscustomobject]) { throw '该服务未返回课堂健康检查对象。' }
    $properties = @($Health.PSObject.Properties.Name)
    if ($properties -notcontains 'learnerId' -or $properties -notcontains 'engine' -or
        $Health.learnerId -ne 'classroom' -or $Health.engine -isnot [pscustomobject]) {
        throw '该服务不符合课堂健康接口标识，不能认定为本项目后端。'
    }
    if ($Health.engine.ready -ne $true) {
        throw '课堂后端已响应，但生成器依赖未就绪，请先完成 build:classroom-engine 并检查 Node 安装。'
    }
}

function Wait-ClassroomListener([int]$Port, [int]$ExpectedProcessId, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id $ExpectedProcessId -ErrorAction SilentlyContinue)) { throw "课堂进程 $ExpectedProcessId 提前退出，请检查日志。" }
        $owners = @(Get-ClassroomPortOwners $Port)
        if (@($owners | Where-Object ProcessId -eq $ExpectedProcessId).Count -gt 0) { return }
        if ($owners.Count -gt 0) { throw "端口 $Port 被其他进程占用，未继续启动。" }
        Start-Sleep -Milliseconds 500
    }
    throw "等待端口 $Port 超时；请查看本次日志并核实状态。"
}
