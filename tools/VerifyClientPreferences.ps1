param([Parameter(Mandatory=$true)][string]$IdePath, [Parameter(Mandatory=$true)][string]$AiAssistantPath, [Parameter(Mandatory=$true)][string]$AirPluginPath, [string]$JavaHome=$env:JAVA_HOME, [string]$OutputDirectory='build/preferences78-verification', [switch]$AirOnly, [switch]$AirVisible, [switch]$FoldedAgent)
$ErrorActionPreference='Stop'
$taskDirectory=[IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
if (!$taskDirectory.StartsWith((Join-Path (Get-Location) 'build/'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Verification output must be under build' }
New-Item -ItemType Directory -Force (Join-Path $taskDirectory 'classes') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $taskDirectory 'project') | Out-Null
if ($AirOnly) { [IO.File]::WriteAllText((Join-Path $taskDirectory 'air-only'), 'air-only') }
if ($FoldedAgent) { [IO.File]::WriteAllText((Join-Path $taskDirectory 'folded-agent'), 'codex') }
if ($AirVisible) {
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'verify-visible'), 'yes')
    New-Item -ItemType Directory -Force (Join-Path $taskDirectory 'home/.jetbrains') | Out-Null
    $registration=@{agent_servers=@{'AI Multi'=@{command=(Get-Command node -ErrorAction Stop).Source;args=@("$taskDirectory/home/.jetbrains/ai-agent-project-switcher/router.js","$taskDirectory/home/.jetbrains/ai-agent-project-switcher/routes.json")}}} | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'home/.jetbrains/acp.json'), $registration)
    New-Item -ItemType Directory -Force (Join-Path $taskDirectory 'home/.jetbrains/ai-agent-project-switcher') | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'home/.jetbrains/ai-agent-project-switcher/registration.json'), (($registration | ConvertFrom-Json).agent_servers.'AI Multi' | ConvertTo-Json -Depth 5))
}
$ErrorActionPreference='Continue'
& (Join-Path $JavaHome 'bin/javac.exe') -d (Join-Path $taskDirectory 'classes') tools/SettingsRefreshProbe.java tools/ChatStartupSupport.java tools/ChatStartupProbe.java tools/ClientPreferencesProbe.java tools/AirProfileProbe.java tools/ModelDefaultsProbe.java
$compileExitCode=$LASTEXITCODE
$ErrorActionPreference='Stop'
if ($compileExitCode -ne 0) { throw 'Probe compilation failed' }
[IO.File]::WriteAllText((Join-Path $taskDirectory 'MANIFEST.MF'), "Manifest-Version: 1.0`nPremain-Class: ClientPreferencesProbe`n`n")
& (Join-Path $JavaHome 'bin/jar.exe') cfm (Join-Path $taskDirectory 'probe.jar') (Join-Path $taskDirectory 'MANIFEST.MF') -C (Join-Path $taskDirectory 'classes') .
$env:JAVA_HOME=$JavaHome
$ErrorActionPreference='Continue'
./gradlew.bat runIde --offline --max-workers=1 "-PidePath=$IdePath" "-PaiAssistantPath=$AiAssistantPath" "-PairPluginPath=$AirPluginPath" "-PverificationSandbox=$taskDirectory/sandbox" "-PsandboxHome=$taskDirectory/home" "-PsandboxProject=$taskDirectory/project" "-PuiProbeAgent=$taskDirectory/probe.jar=$taskDirectory" *> (Join-Path $taskDirectory 'native.log')
$taskExitCode=$LASTEXITCODE
$ErrorActionPreference='Stop'
if ($taskExitCode -ne 0) { Get-Content (Join-Path $taskDirectory 'error.txt') -ErrorAction SilentlyContinue; throw 'Native verification failed' }
Get-Content (Join-Path $taskDirectory 'result.txt')
