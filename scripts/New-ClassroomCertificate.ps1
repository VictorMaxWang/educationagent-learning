#requires -Version 7.0
<#
.SYNOPSIS
生成仅供本课堂使用的本地 CA 与 HTTPS 证书；不会安装到系统信任库。
.EXAMPLE
./scripts/New-ClassroomCertificate.ps1 -LanAddress '192.168.1.20'
#>
[CmdletBinding()]
param(
    [string[]]$LanAddress = @(),
    [string[]]$DnsName = @(),
    [string]$OutputDirectory = ''
)
. (Join-Path $PSScriptRoot 'Classroom.Common.ps1')
$keytool = Get-ClassroomExecutable 'keytool.exe'
$projectRoot = Get-ClassroomRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'data/classroom/tls' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ((Test-Path -LiteralPath $OutputDirectory) -and @(Get-ChildItem -LiteralPath $OutputDirectory -Force).Count -gt 0) {
    throw "证书目录非空，未覆盖已有证书：$OutputDirectory。IP 改变时请使用新的 -OutputDirectory。"
}
$addresses = [Collections.Generic.List[string]]::new()
$addresses.Add('127.0.0.1')
foreach ($value in $LanAddress) {
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($value, [ref]$parsedAddress)) { throw "无效 IP 地址：$value" }
    $addresses.Add($parsedAddress.ToString())
}
$names = @('localhost') + @($DnsName)
foreach ($value in $names) {
    if ($value.Length -gt 253 -or $value -notmatch '^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$') { throw "无效 DNS 名称：$value" }
}
$sans = @($addresses | Sort-Object -Unique | ForEach-Object { "ip:$_" }) + @($names | Sort-Object -Unique | ForEach-Object { "dns:$_" })
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
# 私钥与随机口令只留在当前 Windows 用户可读的目录，不写全局证书/网络设置。
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($identity)
$acl.SetAccessRuleProtection($true, $false)
$rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $OutputDirectory -AclObject $acl
$passphrase = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$oldPassword = $env:CLASSROOM_KEYSTORE_PASSWORD
$env:CLASSROOM_KEYSTORE_PASSWORD = $passphrase
$caStore = Join-Path $OutputDirectory 'ca.p12'
$serverStore = Join-Path $OutputDirectory 'server.p12'
$caCertificate = Join-Path $OutputDirectory 'ca.crt'
$serverRequest = Join-Path $OutputDirectory 'server.csr'
$serverCertificate = Join-Path $OutputDirectory 'server.crt'
function Invoke-ClassroomKeytool([string[]]$ToolArguments) {
    # keytool 的中文输出在部分 Windows JDK 使用旧代码页；仅为本次子进程使用 ASCII 英文日志。
    & $keytool '-J-Duser.language=en' '-J-Duser.country=US' @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败（退出码 $LASTEXITCODE）；保留当前目录供检查，不自动重试或覆盖。" }
}
try {
    Invoke-ClassroomKeytool @('-genkeypair', '-alias', 'classroom-ca', '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA', '-validity', '3650', '-dname', 'CN=Local Classroom CA', '-ext', 'bc:c', '-ext', 'ku=keyCertSign,cRLSign', '-storetype', 'PKCS12', '-keystore', $caStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-noprompt')
    Invoke-ClassroomKeytool @('-exportcert', '-rfc', '-alias', 'classroom-ca', '-keystore', $caStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-file', $caCertificate)
    Invoke-ClassroomKeytool @('-genkeypair', '-alias', 'classroom-server', '-keyalg', 'RSA', '-keysize', '2048', '-sigalg', 'SHA256withRSA', '-validity', '365', '-dname', 'CN=Local Classroom', '-storetype', 'PKCS12', '-keystore', $serverStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-noprompt')
    Invoke-ClassroomKeytool @('-certreq', '-alias', 'classroom-server', '-keystore', $serverStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-file', $serverRequest)
    Invoke-ClassroomKeytool @('-gencert', '-rfc', '-alias', 'classroom-ca', '-keystore', $caStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-infile', $serverRequest, '-outfile', $serverCertificate, '-validity', '365', '-ext', 'bc=ca:false', '-ext', 'ku=digitalSignature,keyEncipherment', '-ext', 'eku=serverAuth', '-ext', ('san=' + ($sans -join ',')))
    Invoke-ClassroomKeytool @('-importcert', '-noprompt', '-alias', 'classroom-ca', '-keystore', $serverStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-file', $caCertificate)
    Invoke-ClassroomKeytool @('-importcert', '-noprompt', '-alias', 'classroom-server', '-keystore', $serverStore, '-storepass:env', 'CLASSROOM_KEYSTORE_PASSWORD', '-file', $serverCertificate)
    [ordered]@{ schemaVersion = 1; pfx = 'server.p12'; passphrase = $passphrase; subjectAltNames = $sans; createdUtc = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'tls.json') -Encoding utf8NoBOM
    Write-Host "课堂证书已生成：$OutputDirectory"
    Write-Host "只把 ca.crt 传到手机并手动信任；不要传出 .p12 或 tls.json。覆盖地址：$($sans -join ', ')"
    if ($LanAddress.Count -eq 0) { Write-Warning '未加入局域网 IP，此证书仅适用于 localhost；手机访问请用包含电脑实际局域网 IP 的新证书目录。' }
} finally {
    $env:CLASSROOM_KEYSTORE_PASSWORD = $oldPassword
}
