$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pwd = Get-Location
Set-Location $root
$src = Join-Path $root "src"
$out = Join-Path $root "out"
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

javac -d $out (Join-Path $src "Main.java")
if ($LASTEXITCODE -ne 0) {
  Write-Host "Compile failed."
  Set-Location $pwd
  exit $LASTEXITCODE
}

Write-Host "Backend running at http://localhost:8080"
java -cp $out Main
