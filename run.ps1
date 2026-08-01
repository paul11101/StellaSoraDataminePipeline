[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $PipelineArguments
)

$ErrorActionPreference = 'Stop'
$pythonCommand = Get-Command python -ErrorAction Stop
& $pythonCommand.Source -u (Join-Path $PSScriptRoot 'run.py') @PipelineArguments
exit $LASTEXITCODE
