param(
    [Parameter(Mandatory = $false)]
    [string]$ProjectRoot = "D:\Brainserve-connect-Appointment-System"
)

$ErrorActionPreference = "Stop"

$packageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRootPath = (Resolve-Path $ProjectRoot).Path

$files = @(
    @{
        Source = Join-Path $packageRoot "app\brainserve-app.tsx"
        Destination = Join-Path $projectRootPath "app\brainserve-app.tsx"
    },
    @{
        Source = Join-Path $packageRoot "backend\src\main\java\com\brainserve\appointment\iam\domain\SystemRole.java"
        Destination = Join-Path $projectRootPath "backend\src\main\java\com\brainserve\appointment\iam\domain\SystemRole.java"
    }
)

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $projectRootPath ".local-backups\record-visibility-$timestamp"

foreach ($file in $files) {
    if (-not (Test-Path $file.Source)) {
        throw "Package file is missing: $($file.Source)"
    }

    if (-not (Test-Path $file.Destination)) {
        throw "Project destination is missing: $($file.Destination)"
    }

    $relative = $file.Destination.Substring($projectRootPath.Length).TrimStart("\")
    $backup = Join-Path $backupRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backup) | Out-Null
    Copy-Item $file.Destination $backup -Force
    Copy-Item $file.Source $file.Destination -Force
    Write-Host "Updated $relative"
}

Write-Host ""
Write-Host "Backups created at:"
Write-Host $backupRoot
Write-Host ""
Write-Host "Run validation:"
Write-Host "  cd $projectRootPath"
Write-Host "  npm run typecheck"
Write-Host "  npm test"
Write-Host "  cd backend"
Write-Host "  mvn clean test"