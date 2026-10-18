param(
    [string]$IdePath = '',
    [string]$AiAssistantPath = '',
    [string]$AirPluginPath = '',
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($JavaHome) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JavaHome must point to a JDK installation.' }
    $env:JAVA_HOME = $JavaHome
} elseif ($IdePath -and (Test-Path -LiteralPath (Join-Path $IdePath 'jbr\bin\java.exe'))) {
    $env:JAVA_HOME = Join-Path $IdePath 'jbr'
} elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Provide -JavaHome, set JAVA_HOME, or provide -IdePath with its bundled JDK. Compilation also requires JDK 21.'
}
$buildArgs = @('check', 'buildPlugin', '--max-workers=2')
if ($IdePath) { $buildArgs += "-PidePath=$IdePath" }
if ($AiAssistantPath) { $buildArgs += "-PaiAssistantPath=$AiAssistantPath" }
if ($AirPluginPath) { $buildArgs += "-PairPluginPath=$AirPluginPath" }
$toolchainPaths = @()
if ($env:JAVA_HOME) { $toolchainPaths += $env:JAVA_HOME }
if ($IdePath -and (Test-Path -LiteralPath (Join-Path $IdePath 'jbr\bin\java.exe'))) { $toolchainPaths += (Join-Path $IdePath 'jbr') }
if ($toolchainPaths.Count) { $buildArgs += "-Porg.gradle.java.installations.paths=$(($toolchainPaths | Select-Object -Unique) -join ',')" }
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Node.js is required to run the router regression tests.' }
$buildArgs += "-PnodeExecutable=$($nodeCommand.Source)"
& (Join-Path $PSScriptRoot 'gradlew.bat') @buildArgs
if ($LASTEXITCODE -ne 0) { throw 'Gradle check/buildPlugin failed' }
Get-ChildItem (Join-Path $PSScriptRoot 'build/distributions') -Filter 'ai-multi-profiles-*.zip' |
    Select-Object FullName, Length, LastWriteTime
