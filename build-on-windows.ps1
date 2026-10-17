param([string]$IdePath = '', [string]$AiAssistantPath = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Set JAVA_HOME to a JDK 21 installation or add Java 21 to PATH.'
}
$buildArgs = @('buildPlugin')
if ($IdePath) { $buildArgs += "-PidePath=$IdePath" }
if ($AiAssistantPath) { $buildArgs += "-PaiAssistantPath=$AiAssistantPath" }
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Node.js is required to run the router regression tests.' }
$buildArgs += "-PnodeExecutable=$($nodeCommand.Source)"
& (Join-Path $PSScriptRoot 'gradlew.bat') @buildArgs
if ($LASTEXITCODE -ne 0) { throw 'Gradle buildPlugin failed' }
Get-ChildItem (Join-Path $PSScriptRoot 'build/distributions') -Filter 'ai-multi-profiles-*.zip' |
    Select-Object FullName, Length, LastWriteTime
