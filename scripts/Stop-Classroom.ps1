#requires -Version 7.0
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'Classroom.Common.ps1')
$runDirectory = Join-Path (Get-ClassroomRoot) 'data/classroom/run'
$statePath = Join-Path $runDirectory 'processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有课堂进程记录，未结束任何进程。'; return }
$lock = [IO.File]::Open((Join-Path $runDirectory 'launch.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.schemaVersion -ne 1) { throw '不支持的课堂进程记录格式，未结束任何进程。' }
    # 先核实全部身份，避免记录被修改或 PID 重用后误结束其他任务。
    foreach ($entry in @($state.web, $state.backend)) { if ($entry) { Get-ClassroomOwnedProcess $entry | Out-Null } }
    foreach ($entry in @($state.web, $state.backend)) { if ($entry) { Stop-ClassroomOwnedProcess $entry } }
    $state.status = 'stopped'
    $state | Add-Member -NotePropertyName stoppedUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
    Write-ClassroomState $statePath $state
    Write-Host '本次课堂自有进程已停止。复用的后端、其他进程与网络设置未改动。'
} finally { $lock.Dispose() }
