[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [int]$Port = 18080,
    [string]$DiagnosticLog = $(
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\config.json")) {
            Join-Path $PSScriptRoot "..\..\runtime\fiddler-sessions.log"
        }
        else {
            Join-Path $PSScriptRoot "..\server\runtime\fiddler-sessions.log"
        }
    ),
    [switch]$Remove
)

if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port must be between 1 and 65535."
}

$documentsRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
$fiddlerInstallRoot = Join-Path $env:LOCALAPPDATA "Programs\Fiddler"
$sampleRulesPath = Join-Path $fiddlerInstallRoot "Scripts\SampleRules.js"
$customRulesDirectory = Join-Path $documentsRoot "Fiddler2\Scripts"
$customRulesPath = Join-Path $customRulesDirectory "CustomRules.js"

if (Test-Path -LiteralPath $customRulesPath) {
    $rulesText = [System.IO.File]::ReadAllText($customRulesPath)
}
elseif (Test-Path -LiteralPath $sampleRulesPath) {
    $rulesText = [System.IO.File]::ReadAllText($sampleRulesPath)
}
else {
    throw "Fiddler Classic SampleRules.js was not found under $fiddlerInstallRoot"
}

$markerPattern = '(?ms)^\s*// STELLA_SORA_LOCAL_BEGIN\r?\n.*?^\s*// STELLA_SORA_LOCAL_END\r?\n?'
$rulesWithoutLocalBlock = [regex]::Replace($rulesText, $markerPattern, "")

if ($Remove) {
    if ($rulesWithoutLocalBlock -eq $rulesText) {
        Write-Host "The Stella Sora local redirect rule is not installed."
        return
    }
    $updatedRules = $rulesWithoutLocalBlock
    $operation = "remove Stella Sora local redirect rule"
}
else {
    $signature = "    static function OnBeforeRequest(oSession: Session) {"
    if (-not $rulesWithoutLocalBlock.Contains($signature)) {
        throw "Cannot locate Handlers.OnBeforeRequest in $customRulesPath"
    }
    $diagnosticLogPath = [System.IO.Path]::GetFullPath($DiagnosticLog).Replace('\', '/')
    $localBlock = @"
        // STELLA_SORA_LOCAL_BEGIN
        var stellaHost: String = oSession.host.ToLower();
        if (stellaHost.IndexOf("stargazer-games.com") >= 0) {
            var stellaPath: String = oSession.PathAndQuery;
            var stellaQueryAt: int = stellaPath.IndexOf("?");
            if (stellaQueryAt >= 0) {
                stellaPath = stellaPath.Substring(0, stellaQueryAt);
            }
            System.IO.File.AppendAllText(
                "$diagnosticLogPath",
                System.DateTime.UtcNow.ToString("o") + " " + oSession.RequestMethod
                    + " " + oSession.host + stellaPath + System.Environment.NewLine
            );
        }
        if (oSession.HostnameIs("nova-static.stargazer-games.com")) {
            if (oSession.PathAndQuery.StartsWith("/meta/serverlist.html")) {
                oSession.fullUrl = "http://127.0.0.1:$Port/meta/serverlist.html";
                return;
            }
            if (oSession.PathAndQuery.StartsWith("/meta/win.html")) {
                oSession.fullUrl = "http://127.0.0.1:$Port/meta/win.html";
                return;
            }
        }
        // STELLA_SORA_LOCAL_END
"@
    $updatedRules = $rulesWithoutLocalBlock.Replace(
        $signature,
        $signature + [Environment]::NewLine + $localBlock.TrimEnd()
    )
    $operation = "install Stella Sora local redirect rule for port $Port"
}

if ($PSCmdlet.ShouldProcess($customRulesPath, $operation)) {
    [System.IO.Directory]::CreateDirectory($customRulesDirectory) | Out-Null
    if (Test-Path -LiteralPath $customRulesPath) {
        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $backupPath = "$customRulesPath.$timestamp.bak"
        Copy-Item -LiteralPath $customRulesPath -Destination $backupPath -ErrorAction Stop
        Write-Host "Backup: $backupPath"
    }
    $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($customRulesPath, $updatedRules, $utf8WithoutBom)
    Write-Host "Updated: $customRulesPath"
    Write-Host "Reload FiddlerScript or restart Fiddler Classic."
}
