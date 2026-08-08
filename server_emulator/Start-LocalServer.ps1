param(
    [string]$Config = ""
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent $scriptDirectory
$serverRoot = Join-Path $repositoryRoot "server"

if ([string]::IsNullOrWhiteSpace($Config)) {
    $Config = Join-Path $serverRoot "config.example.json"
}

$resolvedConfig = (Resolve-Path -LiteralPath $Config).Path
$gradle = Join-Path $serverRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle wrapper is missing: $gradle"
}

Push-Location $serverRoot
try {
    & $gradle installDist --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Java server build exited with code $LASTEXITCODE"
    }
    $launcher = Join-Path $serverRoot "build\install\stella-sora-server\bin\stella-sora-server.bat"
    & $launcher --config $resolvedConfig
    if ($LASTEXITCODE -ne 0) {
        throw "Java server exited with code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
