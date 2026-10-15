#requires -Version 7.0
<#
.SYNOPSIS
检查或生成独立的 EducationAgent 源码发布快照，不操作原仓库的 Git 历史。
.DESCRIPTION
默认只检查。指定 -Apply 后写入项目内 target/github-publish；只复制白名单，
并在复制前后检查敏感值。重复执行保留快照自身的 .git，仅更新本脚本管理的文件。
本脚本不创建远程仓库、不执行 git init/commit/push，也不复制运行数据库。
#>
[CmdletBinding()]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$snapshotRoot = [IO.Path]::GetFullPath((Join-Path $targetRoot 'github-publish'))
$manifestName = '.educationagent-publish-manifest.json'
$producer = 'educationagent/Prepare-GitHubSnapshot.ps1'

# 白名单以源代码边界为单位；不依赖 gitignore，因为原仓库已追踪过本地密钥和依赖。
$includeDirectories = @('src', 'desktop-app', 'course-engine', 'vendor/educationagent', 'scripts', 'docs', 'deploy', '.mvn')
$includeRootFiles = @('pom.xml', 'package.json', 'package-lock.json', 'mvnw', 'mvnw.cmd', 'start-desktop.bat',
    '.gitattributes', '.dockerignore', '.env.example', 'README.md', 'LICENSE', 'NOTICE', 'Dockerfile',
    'compose.yaml', 'compose.yml', 'docker-compose.yml')
$skipDirectoryNames = @('node_modules', 'target', 'dist', 'build', '.git', '.idea', '.vscode', '.codex', '.agents',
    '.claude', '.cache', '__pycache__', 'coverage', 'test-results', 'playwright-report', 'logs', 'output', '.runtime')
$snapshotIgnoredDirectories = @('.git', 'node_modules', 'dist', 'target', 'build', 'coverage', 'test-results', 'playwright-report')
$requiredFiles = @('pom.xml', 'package.json', 'package-lock.json', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties',
    'src/main/java/com/demo/DemoApplication.java', 'src/main/resources/application.properties',
    'src/main/resources/application-dev.properties', 'src/main/resources/schema.sql',
    'desktop-app/package.json', 'desktop-app/package-lock.json', 'desktop-app/src/App.jsx', 'course-engine/package.json',
    'vendor/educationagent/THIRD_PARTY_NOTICES.md', 'vendor/educationagent/dsl/LICENSE',
    'vendor/educationagent/generation/LICENSE', 'vendor/educationagent/renderer/LICENSE')

function Get-RelativePublishPath([string]$Path) {
    return [IO.Path]::GetRelativePath($projectRoot, $Path).Replace('\', '/')
}

function Assert-ContainedPath([string]$Path, [string]$Parent) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径超出预期目录：$absolute"
    }
    return $absolute
}

function Test-PublishExcluded([IO.FileInfo]$File) {
    $relative = Get-RelativePublishPath $File.FullName
    # Tauri 按平台生成的权限 schema 会由原目录 gitignore 排除，不是可移植源码输入。
    if ($relative.StartsWith('desktop-app/src-tauri/gen/', [StringComparison]::OrdinalIgnoreCase)) { return $true }
    foreach ($segment in $relative.Split('/')) {
        if ($segment -in $skipDirectoryNames) { return $true }
    }
    $name = $File.Name
    if ($name -like '.env*' -and $name -notin @('.env.example', '.env.sample')) { return $true }
    if ($name -match '^application-(local|private|secret)([.-].*)?\.properties$') { return $true }
    if ($name -match '(?i)(^credentials\.|^secrets?\.|\.local\.|\.bak(?:-|\.|$)|~$|^~\$)') { return $true }
    if ($name -match '(?i)\.(log|class|pyc|db|sqlite3?|sqlitedb|pem|key|p12|pfx|jks|keystore|dump|zip|tar|gz|7z|docx?|pdf|xlsx?)$') { return $true }
    return $false
}

function Get-PublishDirectoryFiles([string]$Directory) {
    # 不跟随 junction/symlink，避免允许目录内的链接把其他工作区或用户数据带入快照。
    foreach ($entry in Get-ChildItem -LiteralPath $Directory -Force) {
        if ($entry.Name -in $skipDirectoryNames) { continue }
        if ($entry.LinkType -in @('SymbolicLink', 'Junction')) { throw "白名单内存在文件系统链接，请先核对来源：$(Get-RelativePublishPath $entry.FullName)" }
        if ($entry.PSIsContainer) { Get-PublishDirectoryFiles $entry.FullName }
        elseif (-not (Test-PublishExcluded $entry)) { $entry }
    }
}

function Get-ManagedSnapshotFiles([string]$Directory) {
    # 独立构建可能创建 npm workspace junction；构建产物不是发布输入，先按目录名跳过，
    # 既不遍历/跟随其中的链接，也不复制、删除或改写其中任何文件。
    foreach ($entry in Get-ChildItem -LiteralPath $Directory -Force) {
        if ($entry.Name -eq '.git') { continue }
        if ($entry.PSIsContainer -and $entry.Name -in $snapshotIgnoredDirectories) { continue }
        if ($entry.LinkType -in @('SymbolicLink', 'Junction')) { throw '旧快照受管理源码范围内存在文件系统链接，拒绝覆盖。' }
        if ($entry.PSIsContainer) { Get-ManagedSnapshotFiles $entry.FullName }
        else { $entry }
    }
}

function Assert-SnapshotManifestPath([string]$Relative) {
    $normalized = $Relative.Replace('\', '/')
    $null = Assert-ContainedPath (Join-Path $snapshotRoot $normalized) $snapshotRoot
    foreach ($segment in $normalized.Split('/')) {
        if ($segment -in $snapshotIgnoredDirectories) { throw '清单不得管理 .git 或被排除的构建产物目录。' }
    }
}

$candidateFiles = @(
    foreach ($directory in $includeDirectories) {
        $path = Join-Path $projectRoot $directory
        if (Test-Path -LiteralPath $path -PathType Container) { Get-PublishDirectoryFiles $path }
    }
    foreach ($name in $includeRootFiles) {
        $path = Join-Path $projectRoot $name
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $file = Get-Item -LiteralPath $path
            if ($file.LinkType -in @('SymbolicLink', 'Junction')) { throw "白名单根文件不能是符号链接：$name" }
            $file
        }
    }
) | Sort-Object FullName -Unique
$relativeFiles = @($candidateFiles | ForEach-Object { Get-RelativePublishPath $_.FullName })
foreach ($required in $requiredFiles) {
    if ($required -notin $relativeFiles) { throw "快照缺少构建必需文件：$required" }
}

# 只在内存中读取被排除的配置作为泄漏比对字典；输出仅含文件、行号、属性名。
$sensitiveAssignment = '(?i)^\s*["'']?([A-Za-z0-9_.-]*(?:password|passwd|passphrase|api[-_.]?key|api[-_.]?secret|secret[-_.]?key|secret[-_.]?id|access[-_.]?key|bot[-_.]?secret|token|secret))["'']?\s*[:=]\s*(.*?)\s*$'
function Get-LiteralSecret([string]$Raw) {
    $value = $Raw.Trim().TrimEnd(',').Trim().Trim('"', "'")
    if (-not $value -or $value -match '^(\$\{|\$env:|\$[A-Za-z_]|<|(?i:your[-_ ]|change[-_ ]?me|replace[-_ ]|example|placeholder|test[-_]|demo[-_]))') { return $null }
    if ($value -match '^(true|false|null|none)$') { return $null }
    return $value
}
$localSecretValues = [Collections.Generic.List[object]]::new()
foreach ($localName in @('config/local-secrets.properties', 'desktop-app/.env', 'deploy/.env', '.env')) {
    $localPath = Join-Path $projectRoot $localName
    if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) { continue }
    foreach ($line in [IO.File]::ReadLines($localPath)) {
        $match = [regex]::Match($line, $sensitiveAssignment)
        if (-not $match.Success) {
            $urlMatch = [regex]::Match($line, '^\s*(local\.secrets\.[A-Za-z0-9_.-]+)\s*=\s*(.*?)\s*$')
            if ($urlMatch.Success -and $urlMatch.Groups[2].Value -match '(?i)[?&](api[-_]?key|token|secret)=') { $match = $urlMatch }
        }
        if (-not $match.Success) { continue }
        $value = Get-LiteralSecret $match.Groups[2].Value
        # 公开演示夹具的固定样例不属于部署秘密；实际教师密码应由服务器配置覆盖。
        if ($match.Groups[1].Value -match 'edu\.auth\.demo-teacher\.' -and $value -match '^(teacher|demo|test)[-_.]?[0-9]{1,6}$') { continue }
        if ($value -and $value.Length -ge 6) {
            $localSecretValues.Add([pscustomobject]@{ Name = $match.Groups[1].Value; Value = $value })
        }
    }
}

function Find-PublishProblems([string]$BasePath, [string[]]$RelativePaths) {
    $legacyScope = '@' + 'openmaic'
    $highConfidence = '(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_.-]{18,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    $textExtensions = @('.java', '.js', '.jsx', '.ts', '.tsx', '.json', '.yml', '.yaml', '.xml', '.properties',
        '.env', '.md', '.sh', '.ps1', '.bat', '.cmd', '.toml', '.sql', '.txt', '.conf', '.css', '.html', '.svg', '.rs')
    foreach ($relative in $RelativePaths) {
        $filePath = Assert-ContainedPath (Join-Path $BasePath $relative) $BasePath
        $file = Get-Item -LiteralPath $filePath
        if ($file.Extension -notin $textExtensions -and $file.Name -notmatch '^(\.env|Dockerfile|Caddyfile|mvnw|\.gitignore|LICENSE|NOTICE)') { continue }
        $lineNumber = 0
        foreach ($line in [IO.File]::ReadLines($filePath)) {
            $lineNumber++
            if ($line -match $highConfidence) {
                [pscustomobject]@{ File = $relative; Line = $lineNumber; Rule = 'credential-pattern'; Property = '' }
            }
            foreach ($secret in $localSecretValues) {
                if ($line.Contains($secret.Value, [StringComparison]::Ordinal)) {
                    [pscustomobject]@{ File = $relative; Line = $lineNumber; Rule = 'local-secret-value'; Property = $secret.Name }
                }
            }
            if ($file.Extension -eq '.properties' -or $file.Name -like '.env*') {
                $match = [regex]::Match($line, $sensitiveAssignment)
                if ($match.Success -and (Get-LiteralSecret $match.Groups[2].Value)) {
                    [pscustomobject]@{ File = $relative; Line = $lineNumber; Rule = 'literal-sensitive-property'; Property = $match.Groups[1].Value }
                }
            }
            # 保留法律归属文档中的上游名称，业务包和 import 不再使用旧 scope。
            if ($line.Contains($legacyScope, [StringComparison]::OrdinalIgnoreCase) -and $file.Name -notmatch '^(LICENSE|NOTICE|THIRD_PARTY_NOTICES)') {
                [pscustomobject]@{ File = $relative; Line = $lineNumber; Rule = 'legacy-package-scope'; Property = '' }
            }
        }
    }
}

function Assert-PublishClean([string]$BasePath, [string[]]$RelativePaths) {
    $findings = @(Find-PublishProblems $BasePath $RelativePaths)
    if ($findings.Count) {
        $findings | Select-Object File, Line, Rule, Property | Format-Table -AutoSize | Out-Host
        throw "发布检查发现 $($findings.Count) 项疑似秘密或命名问题；未输出值，禁止继续发布。"
    }
}

Assert-PublishClean $projectRoot $relativeFiles
$totalBytes = ($candidateFiles | Measure-Object -Property Length -Sum).Sum
if (-not $Apply) {
    [pscustomobject]@{ Mode = 'Preview'; FileCount = $candidateFiles.Count; Bytes = $totalBytes; SecretCheck = 'PASS'; Destination = $snapshotRoot } | ConvertTo-Json
    return
}

# 目的路径固定在 target 下；旧快照必须带本脚本的清单，未知文件会阻止覆盖。
$null = Assert-ContainedPath $snapshotRoot $targetRoot
foreach ($directory in @($targetRoot, $snapshotRoot)) {
    if ((Test-Path -LiteralPath $directory) -and (Get-Item -LiteralPath $directory).LinkType -in @('SymbolicLink', 'Junction')) {
        throw '发布目的目录不能是符号链接或 junction。'
    }
}
$oldManifest = $null
if (Test-Path -LiteralPath $snapshotRoot) {
    $manifestPath = Join-Path $snapshotRoot $manifestName
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw '目标目录已存在但不是本脚本管理的快照，拒绝覆盖。' }
    $oldManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($oldManifest.Producer -ne $producer) { throw '目标目录的发布清单来源不匹配，拒绝覆盖。' }
    foreach ($relative in $oldManifest.Files) {
        Assert-SnapshotManifestPath $relative
    }
    foreach ($file in Get-ManagedSnapshotFiles $snapshotRoot) {
        $relative = [IO.Path]::GetRelativePath($snapshotRoot, $file.FullName).Replace('\', '/')
        if ($relative -eq $manifestName) { continue }
        if ($relative -notin $oldManifest.Files) { throw "旧快照中有未受管理文件，拒绝覆盖：$relative" }
    }
}

$stagingRoot = Join-Path $targetRoot ('.github-publish-stage-' + [guid]::NewGuid().ToString('N'))
$null = Assert-ContainedPath $stagingRoot $targetRoot
try {
    $null = New-Item -ItemType Directory -Path $stagingRoot -Force
    foreach ($file in $candidateFiles) {
        $relative = Get-RelativePublishPath $file.FullName
        $destination = Assert-ContainedPath (Join-Path $stagingRoot $relative) $stagingRoot
        $null = New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force
        Copy-Item -LiteralPath $file.FullName -Destination $destination -ErrorAction Stop
    }
    $publishIgnore = @'
# Build output and runtime data
**/node_modules/
**/target/
**/dist/
**/build/
/data/
/chroma/
/logs/
/output/
/runtime/
**/test-results/
**/playwright-report/
# Local credentials and machine settings
**/.env
**/.env.*
!**/.env.example
!**/.env.sample
**/application-local.properties
config/local-secrets.properties
**/*.pem
**/*.key
**/*.pfx
**/*.p12
.idea/
.vscode/
.codex/
.agents/
.claude/
'@
    [IO.File]::WriteAllText((Join-Path $stagingRoot '.gitignore'), $publishIgnore + "`n", [Text.UTF8Encoding]::new($false))
    $snapshotFiles = @($relativeFiles) + '.gitignore'
    Assert-PublishClean $stagingRoot $snapshotFiles
    $manifest = [ordered]@{ Producer = $producer; CreatedAtUtc = [DateTime]::UtcNow.ToString('o'); SecretCheck = 'PASS'; Files = @($snapshotFiles | Sort-Object) }
    $null = New-Item -ItemType Directory -Path $snapshotRoot -Force
    foreach ($relative in $snapshotFiles) {
        $destination = Assert-ContainedPath (Join-Path $snapshotRoot $relative) $snapshotRoot
        $null = New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force
        Copy-Item -LiteralPath (Join-Path $stagingRoot $relative) -Destination $destination -ErrorAction Stop
    }
    if ($oldManifest) {
        foreach ($relative in $oldManifest.Files) {
            if ($relative -in $snapshotFiles) { continue }
            $staleFile = Assert-ContainedPath (Join-Path $snapshotRoot $relative) $snapshotRoot
            if (Test-Path -LiteralPath $staleFile -PathType Leaf) { Remove-Item -LiteralPath $staleFile -Force }
        }
    }
    Assert-PublishClean $snapshotRoot $snapshotFiles
    [IO.File]::WriteAllText((Join-Path $snapshotRoot $manifestName), ($manifest | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{ Mode = 'Written'; FileCount = $snapshotFiles.Count; SecretCheck = 'PASS'; Destination = $snapshotRoot; OriginalGitChanged = $false } | ConvertTo-Json
} finally {
    # 这里只清理本次创建、已核实位于 target 的 staging，不接触工作区或旧快照的 Git。
    $null = Assert-ContainedPath $stagingRoot $targetRoot
    if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
}
