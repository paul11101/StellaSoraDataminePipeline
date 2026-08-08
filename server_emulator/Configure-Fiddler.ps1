[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [int]$ProxyPort = 8888,
    [switch]$DisableHttpsDecryption
)

if ($ProxyPort -lt 1 -or $ProxyPort -gt 65535) {
    throw "ProxyPort must be between 1 and 65535."
}

$running = Get-Process -Name Fiddler -ErrorAction SilentlyContinue
if ($running) {
    throw "Close Fiddler Classic before changing its persistent HTTPS settings."
}

$fiddlerRoot = Join-Path $env:LOCALAPPDATA "Programs\Fiddler"
$fiddlerExecutable = Join-Path $fiddlerRoot "Fiddler.exe"
if (-not (Test-Path -LiteralPath $fiddlerExecutable)) {
    throw "Fiddler Classic was not found at $fiddlerExecutable"
}

[Environment]::CurrentDirectory = $fiddlerRoot
$assembly = [Reflection.Assembly]::LoadFrom($fiddlerExecutable)
$staticFlags = [Reflection.BindingFlags]"Public,NonPublic,Static"
$instanceFlags = [Reflection.BindingFlags]"Public,NonPublic,Instance"
$configType = $assembly.GetType("Fiddler.CONFIG", $true)
$applicationType = $assembly.GetType("Fiddler.FiddlerApplication", $true)
$certificateType = $assembly.GetType("Fiddler.CertMaker", $true)

function Get-StaticConfigValue([string]$Name) {
    $property = $configType.GetProperty($Name, $staticFlags)
    if (-not $property) {
        throw "Fiddler CONFIG.$Name was not found."
    }
    return $property.GetValue($null)
}

function Set-StaticConfigValue([string]$Name, $Value) {
    $property = $configType.GetProperty($Name, $staticFlags)
    if (-not $property -or -not $property.CanWrite) {
        throw "Fiddler CONFIG.$Name is not writable."
    }
    $property.SetValue($null, $Value)
}

function Invoke-StaticMethod([Type]$Type, [string]$Name, [object[]]$Arguments = @()) {
    $method = $Type.GetMethod($Name, $staticFlags)
    if (-not $method) {
        throw "$($Type.FullName).$Name was not found."
    }
    return $method.Invoke($null, $Arguments)
}

$before = [ordered]@{
    decrypt_https = [bool](Get-StaticConfigValue "DecryptHTTPS")
    decrypt_processes = [string](Get-StaticConfigValue "DecryptWhichProcesses")
    listen_port = [int](Get-StaticConfigValue "ListenPort")
    attach_on_boot = [bool](Get-StaticConfigValue "AttachOnBoot")
    allow_remote = [bool](Get-StaticConfigValue "bAllowRemoteConnections")
    ignore_server_certificate_errors = [bool](Get-StaticConfigValue "IgnoreServerCertErrors")
    root_exists = [bool](Invoke-StaticMethod $certificateType "rootCertExists")
    root_trusted = [bool](Invoke-StaticMethod $certificateType "rootCertIsTrusted")
}

$description = if ($DisableHttpsDecryption) {
    "disable Fiddler HTTPS decryption"
}
else {
    "enable loopback-only Fiddler HTTPS decryption and trust its generated root certificate"
}

if ($PSCmdlet.ShouldProcess($fiddlerExecutable, $description)) {
    Set-StaticConfigValue "ListenPort" $ProxyPort
    Set-StaticConfigValue "AttachOnBoot" $true
    Set-StaticConfigValue "bAllowRemoteConnections" $false
    Set-StaticConfigValue "IgnoreServerCertErrors" $false

    $processFilterType = $assembly.GetType("Fiddler.ProcessFilterCategories", $true)
    Set-StaticConfigValue "DecryptWhichProcesses" ([Enum]::Parse($processFilterType, "All"))
    Set-StaticConfigValue "DecryptHTTPS" (-not $DisableHttpsDecryption)

    Invoke-StaticMethod $configType "SaveNonUISettings" | Out-Null
    $preferences = $applicationType.GetProperty("Prefs", $staticFlags).GetValue($null)
    $writeRegistry = $preferences.GetType().GetMethod("WriteRegistry", $instanceFlags)
    if ($writeRegistry) {
        $writeRegistry.Invoke($preferences, @()) | Out-Null
    }

    if (-not $DisableHttpsDecryption) {
        if (-not [bool](Invoke-StaticMethod $certificateType "rootCertExists")) {
            if (-not [bool](Invoke-StaticMethod $certificateType "createRootCert")) {
                throw "Fiddler failed to create a root certificate."
            }
        }
        if (-not [bool](Invoke-StaticMethod $certificateType "rootCertIsTrusted")) {
            if (-not [bool](Invoke-StaticMethod $certificateType "trustRootCert")) {
                throw "Fiddler failed to trust its root certificate."
            }
        }
    }
}

$rootCertificate = Invoke-StaticMethod $certificateType "GetRootCertificate"
$after = [ordered]@{
    decrypt_https = [bool](Get-StaticConfigValue "DecryptHTTPS")
    decrypt_processes = [string](Get-StaticConfigValue "DecryptWhichProcesses")
    listen_port = [int](Get-StaticConfigValue "ListenPort")
    attach_on_boot = [bool](Get-StaticConfigValue "AttachOnBoot")
    allow_remote = [bool](Get-StaticConfigValue "bAllowRemoteConnections")
    ignore_server_certificate_errors = [bool](Get-StaticConfigValue "IgnoreServerCertErrors")
    root_exists = [bool](Invoke-StaticMethod $certificateType "rootCertExists")
    root_trusted = [bool](Invoke-StaticMethod $certificateType "rootCertIsTrusted")
    root_subject = if ($rootCertificate) { $rootCertificate.Subject } else { $null }
    root_thumbprint = if ($rootCertificate) { $rootCertificate.Thumbprint } else { $null }
    root_expires = if ($rootCertificate) { $rootCertificate.NotAfter.ToString("o") } else { $null }
}

[pscustomobject]@{
    before = $before
    after = $after
} | ConvertTo-Json -Depth 5
