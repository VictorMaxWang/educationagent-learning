#requires -Version 7.0
<# 仅用既有MySQL程序创建临时测试实例，不安装服务、不读取或修改本机MySQL配置。 #>
[CmdletBinding()]
param(
    [string]$MySqlBin,
    [string]$WorkDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('educationagent-mysql-' + [guid]::NewGuid().ToString('N'))),
    [int]$Port = 0
)
$ErrorActionPreference='Stop'
if (-not $MySqlBin) { $MySqlBin=Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source }
$taskServer=Join-Path $MySqlBin 'mysqld.exe'
$taskClient=Join-Path $MySqlBin 'mysql.exe'
$taskAdmin=Join-Path $MySqlBin 'mysqladmin.exe'
foreach($taskBinary in @($taskServer,$taskClient,$taskAdmin)) { if(-not (Test-Path -LiteralPath $taskBinary -PathType Leaf)){throw "测试所需程序不存在：$taskBinary"} }
$taskRoot=[IO.Path]::GetFullPath($WorkDirectory)
if(Test-Path -LiteralPath $taskRoot){throw '测试实例目录已存在；请检查已有实例，不覆盖或再次初始化。'}
if($Port -eq 0){$taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskListener.Start();$Port=$taskListener.LocalEndpoint.Port;$taskListener.Stop()}
if($Port -lt 1024 -or $Port -gt 65535){throw '测试端口须为1024到65535。'}
New-Item -ItemType Directory -Path $taskRoot | Out-Null
$taskData=Join-Path $taskRoot 'data'
New-Item -ItemType Directory -Path $taskData | Out-Null
$taskBase=Split-Path $MySqlBin
& $taskServer --no-defaults --initialize-insecure "--basedir=$taskBase" "--datadir=$taskData" *> (Join-Path $taskRoot 'initialize.log')
if($LASTEXITCODE -ne 0){throw "独立MySQL初始化失败，请检查 $taskRoot/initialize.log"}
$taskConfig=Join-Path $taskRoot 'my.ini'
@"
[mysqld]
basedir=$($taskBase.Replace('\','/'))
datadir=$($taskData.Replace('\','/'))
bind-address=127.0.0.1
port=$Port
mysqlx=0
skip-log-bin
innodb_buffer_pool_size=128M
max_connections=24
performance_schema=OFF
log-error=$($taskRoot.Replace('\','/'))/server.log
"@ | Set-Content -LiteralPath $taskConfig -Encoding utf8NoBOM
$taskProcess=Start-Process -FilePath $taskServer -ArgumentList ('--defaults-file="'+$taskConfig+'"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'stdout.log') -RedirectStandardError (Join-Path $taskRoot 'stderr.log')
$taskDeadline=[DateTime]::UtcNow.AddSeconds(60)
do {
    $taskProcess.Refresh();if($taskProcess.HasExited){throw "独立MySQL已退出，请检查 $taskRoot/server.log"}
    & $taskAdmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root ping *> $null
    $taskReady=$LASTEXITCODE -eq 0
    if(-not $taskReady){Start-Sleep -Milliseconds 300}
}while(-not $taskReady -and [DateTime]::UtcNow -lt $taskDeadline)
if(-not $taskReady){throw '独立MySQL未按时就绪；保留目录和进程供检查，不自动重放初始化。'}
$taskPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$taskRootPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$taskSql="ALTER USER 'root'@'localhost' IDENTIFIED BY '$taskRootPassword'; CREATE DATABASE educationagent_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER 'educationagent_test'@'localhost' IDENTIFIED BY '$taskPassword'; GRANT ALL ON educationagent_test.* TO 'educationagent_test'@'localhost';"
$taskSql | & $taskClient --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --batch *> (Join-Path $taskRoot 'bootstrap.log')
if($LASTEXITCODE -ne 0){throw '独立测试账号初始化未完成，请检查bootstrap.log；不要重放初始化。'}
$taskRuntime=[ordered]@{pid=$taskProcess.Id; executable=$taskServer; directory=$taskRoot; port=$Port; url="jdbc:mysql://127.0.0.1:$Port/educationagent_test?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai";user='educationagent_test';password=$taskPassword;rootPassword=$taskRootPassword}
$taskRuntimePath=Join-Path $taskRoot 'test-runtime.json'
$taskRuntime | ConvertTo-Json | Set-Content -LiteralPath $taskRuntimePath -Encoding utf8NoBOM
# 公开回执不输出测试口令；测试进程只从本机临时文件读入环境变量。
[pscustomobject]@{status='READY_ISOLATED_MYSQL';pid=$taskProcess.Id;port=$Port;runtimeConfig=$taskRuntimePath} | ConvertTo-Json -Compress
