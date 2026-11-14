# MoneyLens test runner: compiles every Android-free source file from scratch and fails fast.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11" }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$jsonJar = Get-ChildItem 'tests\lib\json-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $jsonJar) {
  $jsonJar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.json\json" -Recurse -Filter 'json-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jsonJar) { throw 'org.json jar not found. Put json-20240303.jar from Maven Central into tests\lib\.' }

$out = 'build\test-classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem 'app\src\main\java\app\moneylens\*.java' |
  Where-Object { -not (Select-String -Path $_.FullName -Pattern '^\s*import\s+android' -Quiet) } |
  ForEach-Object { $_.FullName }
$sources += (Get-ChildItem 'tests\*.java' | ForEach-Object { $_.FullName })
Write-Host "Compiling $($sources.Count) files..."
& javac -encoding UTF-8 -cp $jsonJar -d $out @sources
if ($LASTEXITCODE -ne 0) { Write-Host 'FAIL: javac reported errors; no tests were run.' -ForegroundColor Red; exit 1 }

foreach ($cls in 'app.moneylens.CoreTests', 'app.moneylens.ExtendedTests') {
  & java -ea -cp "$out;$jsonJar" $cls
  if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: $cls" -ForegroundColor Red; exit 1 }
}

foreach ($js in 'app\src\main\assets\i18n.js', 'app\src\main\assets\format.js', 'app\src\main\assets\views\home.js', 'app\src\main\assets\views\plan.js', 'app\src\main\assets\views\transactions.js', 'app\src\main\assets\views\settings.js', 'app\src\main\assets\actions.js', 'app\src\main\assets\app.js') {
  & node -c $js
  if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: syntax error in $js" -ForegroundColor Red; exit 1 }
}
& node tests\ui.test.js
if ($LASTEXITCODE -ne 0) { Write-Host 'FAIL: UI tests' -ForegroundColor Red; exit 1 }
Write-Host 'ALL TESTS PASSED' -ForegroundColor Green
