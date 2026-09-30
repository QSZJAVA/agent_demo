#Requires -Version 7.2
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'stop-mcp.ps1')
Write-Output 'Workspace application stopped. MySQL and Redis remain running.'
