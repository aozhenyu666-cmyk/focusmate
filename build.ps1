# 固定构建入口。所有路径都写死成 ASCII，原因见 docs/DECISIONS.md D-10：
# 非 ASCII 的工程路径会让 AGP 直接拒绝，非 ASCII 的 GRADLE_USER_HOME 会让 test worker 起不来。
$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:GRADLE_USER_HOME = 'C:\gh'
$env:TEMP = 'C:\dsh-tmp'
$env:TMP = 'C:\dsh-tmp'
if (-not (Test-Path $env:TEMP)) { New-Item -ItemType Directory -Path $env:TEMP | Out-Null }

$gradle = 'C:\gradle\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path 'C:\focusmate')) { throw '工程不在 C:\focusmate，请先恢复该目录或改这里的路径' }

Push-Location 'C:\focusmate'
try {
    & $gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

"--- 单测结果 ---"
Get-ChildItem 'C:\focusmate\app\build\test-results\testDebugUnitTest' -Filter *.xml -ErrorAction SilentlyContinue | ForEach-Object {
    [xml]$x = Get-Content $_.FullName
    "$($x.testsuite.name): tests=$($x.testsuite.tests) failures=$($x.testsuite.failures) errors=$($x.testsuite.errors)"
}
"--- APK ---"
Get-ChildItem 'C:\focusmate\app\build\outputs\apk\debug\app-debug.apk' -ErrorAction SilentlyContinue |
    Select-Object Name, Length, @{n='SHA256';e={ (Get-FileHash $_.FullName -Algorithm SHA256).Hash }}
"--- lint ---"
Get-Content 'C:\focusmate\app\build\reports\lint-results-debug.txt' -ErrorAction SilentlyContinue | Select-Object -Last 1

exit $code
