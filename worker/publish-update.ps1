# Выкладывает текущую версию Fixer как обновление: собирает подписанный APK, кладёт его
# и version.json в public/update и деплоит Worker. Fixer предложит обновиться при запуске.
#   .\worker\publish-update.ps1 -Notes "Что нового, одной-двумя фразами"
param([string]$Notes = "")
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if (-not (Test-Path "$root\keystore.properties")) {
    throw "Нет keystore.properties: debug-подписанный APK у пользователей не встанет (см. docs/RELEASE.md)"
}

$gradle = Get-Content "$root\app\build.gradle.kts" -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
Write-Host "Fixer $name (versionCode $code)"

Push-Location $root
try {
    & .\gradlew.bat assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "сборка не удалась" }
} finally {
    Pop-Location
}

$out = "$PSScriptRoot\public\update"
if (Test-Path $out) { Remove-Item "$out\*" -Force }
New-Item -ItemType Directory -Force $out | Out-Null
$apk = "Fixer-$name.apk"
Copy-Item "$root\app\build\outputs\apk\release\app-release.apk" "$out\$apk"
$sha = (Get-FileHash "$out\$apk" -Algorithm SHA256).Hash.ToLower()

$json = [ordered]@{ versionCode = $code; versionName = $name; apk = $apk; sha256 = $sha; notes = $Notes } | ConvertTo-Json
[IO.File]::WriteAllText("$out\version.json", $json, (New-Object Text.UTF8Encoding $false))
Write-Host $json

Push-Location $PSScriptRoot
try {
    npx wrangler deploy --config wrangler.fixer.toml
    if ($LASTEXITCODE -ne 0) { throw "деплой не удался" }
} finally {
    Pop-Location
}
Write-Host "Готово: https://tg.teneighty.bet/update/version.json"
