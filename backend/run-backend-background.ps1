$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root "src"
$out = Join-Path $root "out"
$pidFile = Join-Path $root "backend.pid"
$stdoutFile = Join-Path $root "server.out"
$stderrFile = Join-Path $root "server.err"

if (-not (Test-Path $out)) {
  New-Item -ItemType Directory -Path $out | Out-Null
}

javac -d $out (Join-Path $src "Main.java")
if ($LASTEXITCODE -ne 0) {
  Write-Host "Compile failed."
  exit $LASTEXITCODE
}

if (Test-Path $pidFile) {
  $existingPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
  if ($existingPid) {
    $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
    if ($existing) {
      Write-Host "Backend already running with PID $existingPid"
      exit 0
    }
  }
  Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

$process = Start-Process -FilePath "java" `
  -ArgumentList @("-cp", ".\out", "Main") `
  -WorkingDirectory $root `
  -RedirectStandardOutput $stdoutFile `
  -RedirectStandardError $stderrFile `
  -WindowStyle Hidden `
  -PassThru

$process.Id | Set-Content $pidFile
Write-Host "Backend started in background at http://localhost:8080 (PID $($process.Id))"
