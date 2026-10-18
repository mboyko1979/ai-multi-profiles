param(
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$IdePath,
    [string]$JavaHome = 'C:\jdks\jdk-21.0.4.7-hotspot',
    [string]$SupersedeQueuedVersion = '',
    [switch]$MigrateLegacyPlugin,
    [Parameter(Mandatory=$true)][string]$PluginsPath,
    [Parameter(Mandatory=$true)][string]$StagingPath
)
# Explicit deployment only after check/buildPlugin and migration review; see docs/plugin-id-migration-2026-10-18.md.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if ($Version -notmatch '^\d+\.\d+\.\d+(?:-dev\.\d+)?$') { throw 'Invalid version' }
$workspace = [IO.Path]::GetFullPath($PSScriptRoot)
$pluginsRoot = [IO.Path]::GetFullPath($PluginsPath)
$legacyInstalled = Join-Path $pluginsRoot 'AIMulti'
$hasLegacy = Test-Path -LiteralPath $legacyInstalled
if ($hasLegacy -and -not $MigrateLegacyPlugin) { throw 'Old plugin is installed: explicit -MigrateLegacyPlugin authorization is required.' }
if ($MigrateLegacyPlugin -and -not $hasLegacy) { throw 'Legacy plugin is not installed.' }
$installed = Join-Path $pluginsRoot 'ai-multi-profiles'
$stagingRoot = [IO.Path]::GetFullPath($StagingPath)
$source = Join-Path $workspace "build\distributions\ai-multi-profiles-$Version.zip"
$archive = Join-Path $stagingRoot "ai-multi-profiles-$Version-deploy.zip"
$queue = Join-Path $stagingRoot 'action.script'
foreach ($directory in @($pluginsRoot, $installed, $legacyInstalled, $stagingRoot) | Where-Object { Test-Path -LiteralPath $_ }) {
    if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected deployment path' }
}
if ((Split-Path $installed -Parent) -ne $pluginsRoot -or ((Test-Path -LiteralPath $installed) -and (Resolve-Path -LiteralPath $installed).Path -ne $installed)) { throw 'Invalid installed plugin target' }
if ($MigrateLegacyPlugin) {
    if (Test-Path -LiteralPath $installed) { throw 'Both plugin identities are present; resolve the installation conflict first.' }
    $legacyDescriptors = @()
    foreach ($legacyJar in Get-ChildItem -LiteralPath (Join-Path $legacyInstalled 'lib') -Filter '*.jar' -File) {
        $legacyZip = [IO.Compression.ZipFile]::OpenRead($legacyJar.FullName)
        try {
            $descriptor = $legacyZip.GetEntry('META-INF/plugin.xml')
            if ($null -eq $descriptor) { continue }
            $legacyReader = [IO.StreamReader]::new($descriptor.Open())
            try { $legacyDescriptors += $legacyReader.ReadToEnd() } finally { $legacyReader.Dispose() }
        } finally { $legacyZip.Dispose() }
    }
    if ($legacyDescriptors.Count -ne 1 -or $legacyDescriptors[0] -notmatch '<id>dev\.boyko\.ai-agent-project-switcher</id>') { throw 'Unexpected legacy plugin identity' }
}
if (Test-Path -LiteralPath $archive) { throw 'This version is already staged' }
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$zip = [IO.Compression.ZipFile]::OpenRead($source)
try {
    if ($zip.Entries | Where-Object { $_.FullName -notlike 'ai-multi-profiles/*' -or $_.FullName.Contains('..') }) { throw 'Unexpected plugin archive paths' }
    $mainJar = $zip.GetEntry("ai-multi-profiles/lib/ai-multi-profiles-$Version.jar")
    if ($null -eq $mainJar) { throw 'Plugin JAR is missing' }
    $memory = [IO.MemoryStream]::new(); $stream = $mainJar.Open()
    try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
    $memory.Position = 0; $jar = [IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read)
    try {
        $reader = [IO.StreamReader]::new($jar.GetEntry('META-INF/plugin.xml').Open())
        try { $xml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if (-not $xml.Contains('<id>dev.boyko.ai-multi-profiles</id>') -or -not $xml.Contains('<name>AI Multi Profiles</name>') -or -not $xml.Contains("<version>$Version</version>")) { throw 'Unexpected plugin metadata' }
    } finally { $jar.Dispose(); $memory.Dispose() }
} finally { $zip.Dispose() }
$queueBefore = if (Test-Path -LiteralPath $queue) { [IO.File]::ReadAllBytes($queue) } else { $null }
$supersededArguments = @()
if ($SupersedeQueuedVersion) {
    if ($SupersedeQueuedVersion -notmatch '^\d+\.\d+\.\d+-dev\.\d+$') { throw 'Invalid queued development version' }
    $supersededArchive = Join-Path $stagingRoot "ai-multi-profiles-$SupersedeQueuedVersion-deploy.zip"
    if ((Split-Path $supersededArchive -Parent) -ne $stagingRoot) { throw 'Invalid superseded archive location' }
    if (Test-Path -LiteralPath $supersededArchive) {
        $record = Get-ChildItem -LiteralPath (Join-Path $workspace 'deployment-backups') -Filter deployment.json -Recurse -File |
            ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json } |
            Where-Object { $_.version -eq $SupersedeQueuedVersion -and $_.archive -eq $supersededArchive -and $_.status -eq 'queued' } |
            Select-Object -Last 1
        if ($null -eq $record -or (Get-FileHash -LiteralPath $supersededArchive -Algorithm SHA256).Hash -ne $record.sha256) {
            throw 'Pending development archive is not a verified deployment from this workspace'
        }
    }
    $supersededArguments += $supersededArchive
}
if ($MigrateLegacyPlugin) { $supersededArguments += '--migrate-legacy' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $workspace "deployment-backups\$stamp-$Version"
New-Item -ItemType Directory -Path $backupDir | Out-Null
$backupFile = Join-Path $backupDir 'ai-multi-profiles-before-update.zip'
$output = [IO.File]::Open($backupFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $backupZip = [IO.Compression.ZipArchive]::new($output,[IO.Compression.ZipArchiveMode]::Create,$true)
    try {
        $backupTargets = @($installed, $legacyInstalled) | Where-Object { Test-Path -LiteralPath $_ }
        foreach ($backupTarget in $backupTargets) {
            foreach ($file in Get-ChildItem -LiteralPath $backupTarget -File -Recurse) {
                if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected installed plugin file' }
                $entry = $backupZip.CreateEntry($file.FullName.Substring($pluginsRoot.Length + 1).Replace('\','/'))
                $writer = $entry.Open()
                $reader = [IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                try { $reader.CopyTo($writer) } finally { $reader.Dispose(); $writer.Dispose() }
            }
        }
    } finally { $backupZip.Dispose() }
} finally { $output.Dispose() }
$compileDir = Join-Path $workspace 'build\deploy-helper'
New-Item -ItemType Directory -Path $compileDir -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -d $compileDir (Join-Path $workspace 'tools\PreparePluginDeployment.java')
if ($LASTEXITCODE -ne 0) { throw 'Deployment helper compilation failed' }
$queueDraft = Join-Path $compileDir "pending-$Version.script"
$installerJava = Join-Path $IdePath 'jbr\bin\java.exe'
& $installerJava -cp "$compileDir;$IdePath\lib\*" PreparePluginDeployment $queue $queueDraft $archive $pluginsRoot @supersededArguments
if ($LASTEXITCODE -ne 0) { throw 'Native deployment preparation failed' }
$archiveTemp = Join-Path $stagingRoot ('ai-multi-' + [Guid]::NewGuid() + '.tmp')
$queueTemp = Join-Path $stagingRoot ('ai-multi-queue-' + [Guid]::NewGuid() + '.tmp')
Copy-Item -LiteralPath $source -Destination $archiveTemp
Copy-Item -LiteralPath $queueDraft -Destination $queueTemp
if ((Get-FileHash -LiteralPath $archiveTemp -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Staged archive mismatch' }
$queueNow = if (Test-Path -LiteralPath $queue) { [IO.File]::ReadAllBytes($queue) } else { $null }
if ($null -eq $queueBefore) { if ($null -ne $queueNow) { throw 'IDE update queue changed during staging' } }
elseif ($null -eq $queueNow -or [Convert]::ToBase64String($queueBefore) -ne [Convert]::ToBase64String($queueNow)) { throw 'IDE update queue changed during staging' }
if ($null -ne $queueBefore) { [IO.File]::WriteAllBytes((Join-Path $backupDir 'previous-action.script'), $queueBefore) }
[IO.File]::Move($archiveTemp,$archive)
if ($null -eq $queueBefore) { [IO.File]::Move($queueTemp,$queue) } else { [IO.File]::Replace($queueTemp,$queue,(Join-Path $backupDir 'replaced-action.script')) }
[ordered]@{version=$Version;status='queued';archive=$archive;sha256=$sourceHash;backup=$backupFile;ideRestarted=$false;legacyMigration=[bool]$MigrateLegacyPlugin} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupDir 'deployment.json') -Encoding UTF8
Write-Output "PASS: AI Multi Profiles $Version staged for native installation at the next IDEA restart; backup: $backupFile"
