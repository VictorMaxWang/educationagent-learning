param(
    [ValidateSet('offline-fixtures','classify-live','feedback-live','features-live','replay')][string]$Mode = 'offline-fixtures',
    [string]$Output = 'target/research-upgrade-20261016/evaluation/cli',
    [string]$Snapshots = 'target/research-upgrade-20261016/evaluation/bilibili',
    [string]$Decisions = 'target/research-upgrade-20261016/evaluation/features',
    [int]$Parallel = 1,
    [ValidateSet('development-engineering-check','contract-repair-regression','fixed-input-replay','fixture-validation')][string]$RunPurpose = 'development-engineering-check',
    [string]$ClasspathFile = 'target/classroom-acceptance-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    if (-not (Test-Path -LiteralPath $ClasspathFile)) { throw 'Run Maven dependency:build-classpath with test scope first.' }
    $harnessSource = Get-Item -LiteralPath 'src/test/java/com/demo/edu/classroom/evaluation/ResearchEvaluationCli.java'
    $harnessClass = Get-Item -LiteralPath 'target/test-classes/com/demo/edu/classroom/evaluation/ResearchEvaluationCli.class'
    if ($harnessClass.LastWriteTimeUtc -lt $harnessSource.LastWriteTimeUtc) { throw 'Evaluation class is older than source. Run the coordinated Maven test-compile first; stale cli-check directories must not override current classes.' }
    $dependencyPath = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim()
    $evaluationClassPath = "target/test-classes;target/classes;$dependencyPath"
    $evaluationArgs = @('-cp',$evaluationClassPath,'com.demo.edu.classroom.evaluation.ResearchEvaluationCli','--mode',$Mode,'--output',$Output,'--parallel',[string]$Parallel,'--run-purpose',$RunPurpose)
    if ($Mode -eq 'features-live') { $evaluationArgs += @('--snapshots',$Snapshots) }
    if ($Mode -eq 'replay') { $evaluationArgs += @('--decisions',$Decisions) }
    & java @evaluationArgs
    if ($LASTEXITCODE -ne 0) { throw "Evaluation failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
