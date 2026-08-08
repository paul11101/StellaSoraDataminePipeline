[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$AnalysisRoot,

    [string]$Region = "tw",

    [Parameter(Mandatory = $true)]
    [string]$ResourceVersion,

    [string]$DescriptorHash = "1a70da105b39911e"
)

$resolvedAnalysis = (Resolve-Path -LiteralPath $AnalysisRoot -ErrorAction Stop).Path
$serverRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = Join-Path $serverRoot "resources\$Region\$ResourceVersion"
$luaSource = Join-Path $resolvedAnalysis "protocol\lua_decrypted_$ResourceVersion"
$gameDataSource = Join-Path $resolvedAnalysis "output\local_datamine_$Region"

$sources = [ordered]@{
    "protocol\message_ids.json" = Join-Path $resolvedAnalysis "protocol\message_ids.json"
    "protocol\network.desc" = Join-Path $luaSource "hashes\$DescriptorHash.bin"
    "bootstrap\serverlist.json" = Join-Path $resolvedAnalysis "protocol\input\lua_manifest\serverlist.json"
    "bootstrap\resource_manifest.bin" = Join-Path $resolvedAnalysis "protocol\input\lua_manifest\resource_manifest.encrypted.bin"
}

$protoSource = Join-Path $resolvedAnalysis "protocol\proto\network"
$required = @($sources.Values) + $protoSource + $gameDataSource
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Recovered resource does not exist: $path"
    }
}

if ($PSCmdlet.ShouldProcess($targetRoot, "sync recovered server resources")) {
    foreach ($relativePath in $sources.Keys) {
        $destination = Join-Path $targetRoot $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $sources[$relativePath] -Destination $destination -Force
    }

    $protoTarget = Join-Path $targetRoot "protocol\proto"
    New-Item -ItemType Directory -Path $protoTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $protoSource "*.proto") -Destination $protoTarget -Force

    $gameTarget = Join-Path $targetRoot "game"
    New-Item -ItemType Directory -Path $gameTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $gameDataSource "*") -Destination $gameTarget -Recurse -Force

    $escapedAnalysisRoot = $resolvedAnalysis.Replace('\', '\\')
    $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
    Get-ChildItem -LiteralPath $targetRoot -Recurse -Filter "*.json" -File | ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        $sanitized = $content.Replace($escapedAnalysisRoot, "<analysis-root>")
        if ($sanitized -ne $content) {
            [System.IO.File]::WriteAllText($_.FullName, $sanitized, $utf8WithoutBom)
        }
    }

    $files = @(Get-ChildItem -LiteralPath $targetRoot -Recurse -File)
    Write-Host "Synced $($files.Count) files to $targetRoot"
}
