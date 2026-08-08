param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Command,
    [string]$AdminKey = "stella-local",
    [string]$BaseUrl = "http://127.0.0.1:18080"
)

$ErrorActionPreference = "Stop"
$body = @{ command = $Command } | ConvertTo-Json -Compress
$request = @{
    Uri = $BaseUrl.TrimEnd("/") + "/admin/command"
    Method = "Post"
    Headers = @{ "X-Admin-Key" = $AdminKey }
    ContentType = "application/json; charset=utf-8"
    Body = [System.Text.Encoding]::UTF8.GetBytes($body)
}
Invoke-RestMethod @request
