$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $root "backend.pid"

if (-not (Test-Path $pidFile)) {
  Write-Host "No backend.pid file found."
  exit 0
}

$pidValue = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
if (-not $pidValue) {
  Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
  Write-Host "backend.pid was empty."
  exit 0
}

$process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
if ($process) {
  Stop-Process -Id $pidValue -Force
  Write-Host "Stopped backend PID $pidValue"
} else {
  Write-Host "Process $pidValue is not running."
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
