param(
    [string]$ApkDirectory = 'app/build/outputs/apk/release',
    [string]$ReportDirectory = 'build/android-release-verification'
)

$ErrorActionPreference = 'Stop'
$taskBuildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\37.0.0'
$taskJava = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$taskExpectedCertificate = '3189ce56aeecf435eace45369fe2d9bc1a7420774e9d0a607015105a2d130ce6'
$taskApks = @(Get-ChildItem -LiteralPath $ApkDirectory -Filter '*.apk' -File)
if ($taskApks.Count -ne 2) { throw "Expected two release APKs, found $($taskApks.Count)." }
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
$taskResults = @()

foreach ($taskApk in $taskApks) {
    $taskName = $taskApk.BaseName
    $taskSigning = & $taskJava -jar (Join-Path $taskBuildTools 'lib\apksigner.jar') verify --verbose --print-certs $taskApk.FullName 2>&1
    $taskSigningExit = $LASTEXITCODE
    $taskSigning | Set-Content -LiteralPath (Join-Path $ReportDirectory "$taskName-signing.txt") -Encoding utf8
    if ($taskSigningExit -ne 0) { throw "Signature verification failed: $taskName" }
    if (-not ($taskSigning -match '^Number of signers: 1\s*$') -or
        -not ($taskSigning -match "^(?:Signer #1|V2 Signer): certificate SHA-256 digest: $taskExpectedCertificate\s*$")) {
        throw "Signing certificate changed: $taskName"
    }

    $taskBadging = & (Join-Path $taskBuildTools 'aapt2.exe') dump badging $taskApk.FullName 2>&1
    $taskBadgingExit = $LASTEXITCODE
    $taskBadging | Set-Content -LiteralPath (Join-Path $ReportDirectory "$taskName-badging.txt") -Encoding utf8
    if ($taskBadgingExit -ne 0) { throw "Manifest read failed: $taskName" }
    $taskPackageLine = @($taskBadging | Where-Object { $_.ToString().StartsWith('package: ') })
    if ($taskPackageLine.Count -ne 1 -or -not $taskPackageLine[0].ToString().StartsWith("package: name='com.theveloper.pixelplay' versionCode='13' versionName='0.7.6-beta2'")) {
        throw "Unexpected release package or version: $taskName"
    }
    if ($taskBadging -match '^application-debuggable') { throw "Release is debuggable: $taskName" }

    $taskAlignment = & (Join-Path $taskBuildTools 'zipalign.exe') -c -P 16 4 $taskApk.FullName 2>&1
    $taskAlignmentExit = $LASTEXITCODE
    $taskAlignment | Set-Content -LiteralPath (Join-Path $ReportDirectory "$taskName-alignment.txt") -Encoding utf8
    if ($taskAlignmentExit -ne 0) { throw "ZIP alignment failed: $taskName" }

    $taskResults += [pscustomobject]@{
        file = $taskApk.FullName
        bytes = $taskApk.Length
        sha256 = (Get-FileHash -LiteralPath $taskApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        signatureVerified = $true
        certificateSha256 = $taskExpectedCertificate
        package = 'com.theveloper.pixelplay'
        versionName = '0.7.6-beta2'
        versionCode = 13
        debuggable = $false
        zipAlignmentVerified = $true
    }
}
$taskResults | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $ReportDirectory 'results.json') -Encoding utf8
$taskResults | ConvertTo-Json -Depth 4

