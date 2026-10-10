param(
    [string]$AppDir,
    [string]$Out,
    [string]$BuildDir,
    [string]$Sdk,
    [string]$Jdk,
    [int]$TargetSdk = 34
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $AppDir) { $AppDir = Join-Path $root 'app' }
if (-not $Out) { $Out = Join-Path $root 'dist\jevfilter.apk' }
if (-not $BuildDir) { $BuildDir = Join-Path $root 'build' }
$app = $AppDir

# SDK: параметр -> ANDROID_HOME -> ANDROID_SDK_ROOT -> sdk/ рядом со скриптом
if (-not $Sdk) {
    if (Test-Path (Join-Path $root 'sdk\build-tools')) { $Sdk = Join-Path $root 'sdk' }
    elseif ($env:ANDROID_HOME) { $Sdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $Sdk = $env:ANDROID_SDK_ROOT }
    else { throw 'не нашёл Android SDK: укажи -Sdk или задай ANDROID_HOME' }
}
# JDK: параметр -> JAVA_HOME -> tools/jdk/* рядом со скриптом
if (-not $Jdk) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $Jdk = $env:JAVA_HOME
    } else {
        $cand = Get-ChildItem (Join-Path $root 'tools\jdk') -Directory -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($cand) { $Jdk = $cand.FullName } else { throw 'не нашёл JDK: укажи -Jdk или задай JAVA_HOME' }
    }
}
# build-tools и platforms берём самые свежие, а не жёстко 34.0.0
$bt = (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
$plat = (Get-ChildItem (Join-Path $Sdk 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-(\d+)$' } |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending | Select-Object -First 1)
$ajar = Join-Path $plat.FullName 'android.jar'
$jdk = $Jdk
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
if (-not (Test-Path (Split-Path $Out))) { New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null }

foreach ($need in @($ajar, "$bt\aapt2.exe", "$bt\d8.bat", "$bt\zipalign.exe", "$bt\apksigner.bat")) {
    if (-not (Test-Path $need)) { throw "нет $need" }
}

$build = $BuildDir
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\classes", "$build\dex" | Out-Null

Write-Host "== aapt2 compile res =="
$hasRes = Test-Path "$app\res"
$resZip = "$build\res.zip"
if ($hasRes) {
    & "$bt\aapt2.exe" compile --dir "$app\res" -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
} else {
    Write-Host "   ресурсов нет, пропускаю"
}

Write-Host "== aapt2 link =="
$linkArgs = @('link', '-o', "$build\base.apk", '-I', $ajar, '--manifest', "$app\AndroidManifest.xml",
    '--min-sdk-version', '26', '--target-sdk-version', "$TargetSdk", '--version-code', '1', '--version-name', '1.0',
    '--java', "$build\gen")
if ($hasRes) { $linkArgs += @($resZip) }
& "$bt\aapt2.exe" @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$sources = @(Get-ChildItem "$app\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem "$build\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
Write-Host ("   источников: " + $sources.Count)
& javac -encoding UTF-8 -nowarn --release 11 -classpath $ajar -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== jar =="
& jar cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "== d8 =="
& "$bt\d8.bat" --release --min-api 26 --lib $ajar --output "$build\dex" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== pack classes.dex =="
if (-not (Test-Path "$build\dex\classes.dex")) { throw "d8 не создал classes.dex" }
Copy-Item "$build\base.apk" "$build\packed.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [System.IO.Compression.ZipFile]::Open("$build\packed.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()
Write-Host ("   classes.dex: " + (Get-Item "$build\dex\classes.dex").Length + " байт")

Write-Host "== zipalign =="
& "$bt\zipalign.exe" -f -p 4 "$build\packed.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== keystore =="
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "== apksigner =="
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $Out "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== verify =="
& "$bt\apksigner.bat" verify --print-certs $Out | Select-Object -First 4
Get-Item $Out | Select-Object FullName, Length | Format-List
"BUILD OK"
