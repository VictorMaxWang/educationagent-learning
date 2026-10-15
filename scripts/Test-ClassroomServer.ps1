#requires -Version 7.0
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'Classroom.Common.ps1')
$projectRoot = Get-ClassroomRoot
$node = Get-ClassroomExecutable 'node.exe'
Assert-ClassroomBackendHealth ('{"learnerId":"classroom","engine":{}}' | ConvertFrom-Json)
$foreignHealthRejected = $false
try { Assert-ClassroomBackendHealth ('{"status":"UP"}' | ConvertFrom-Json) } catch { $foreignHealthRejected = $true }
if (-not $foreignHealthRejected) { throw '非课堂健康接口没有被拒绝。' }
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*Classroom*.ps1') {
    $parseTokens = $null
    $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) { throw "PowerShell 语法失败 $($file.Name): $($parseErrors.Message -join ', ')" }
}
$tlsFixture = Join-Path $projectRoot ('target/classroom-tls-test-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $PSScriptRoot 'New-ClassroomCertificate.ps1') -OutputDirectory $tlsFixture
$testMarker = 'classroom-process-test-' + [Guid]::NewGuid().ToString('N')
$fixtureArguments = ConvertTo-ClassroomArgumentString @('-e', 'setInterval(() => {}, 1000)', $testMarker)
$fixtureProcess = Start-Process -FilePath $node -ArgumentList $fixtureArguments -WindowStyle Hidden -PassThru
try {
    $record = Get-ClassroomProcessRecord $fixtureProcess $node $testMarker
    $null = Get-ClassroomOwnedProcess $record
    $wrongRecord = [ordered]@{ pid = $record.pid; startedUtcTicks = '0'; executable = $record.executable; marker = $record.marker }
    $rejected = $false
    try { $null = Get-ClassroomOwnedProcess $wrongRecord } catch { $rejected = $true }
    if (-not $rejected) { throw '错误进程创建时间未被拒绝。' }
    Stop-ClassroomOwnedProcess $record
    if (Get-Process -Id $fixtureProcess.Id -ErrorAction SilentlyContinue) { throw '自有测试进程未停止。' }
    Write-Host '进程身份检查通过：正确记录可结束，错误创建时间被拒绝。'
} finally {
    # 这里只清理刚由测试创建并持有的 Process 对象，绝不扩展到同名进程。
    $fixtureProcess.Refresh()
    if (-not $fixtureProcess.HasExited) { $fixtureProcess.Kill(); $fixtureProcess.WaitForExit(5000) | Out-Null }
    $fixtureProcess.Dispose()
}
$oldTlsDirectory = $env:CLASSROOM_TEST_TLS
try {
    $env:CLASSROOM_TEST_TLS = $tlsFixture
    & $node '--test' (Join-Path $PSScriptRoot 'classroom-server.test.mjs')
    if ($LASTEXITCODE -ne 0) { throw "课堂 HTTPS 隔离测试失败：$LASTEXITCODE" }
    Write-Host "测试通过；证书与网页夹具仅保留在 target，不写系统信任库。证书：$tlsFixture"
} finally { $env:CLASSROOM_TEST_TLS = $oldTlsDirectory }
