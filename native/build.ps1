# Rebuilds libbyedpi.so (PIE executable) and libhev-socks5-tunnel.so into app/src/main/jniLibs/<abi>/.
# Needs the Android NDK (ANDROID_HOME\ndk\<ver>). Sources: native\third_party (byedpi v0.17.3, hev-socks5-tunnel 2.19.3).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { (Get-ChildItem "$sdk\ndk" -Directory | Sort-Object Name | Select-Object -Last 1).FullName }
if (-not $ndk) { throw 'NDK not found' }
$bin = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin"
$jni = "$root\app\src\main\jniLibs"
$tp = "$PSScriptRoot\third_party"
$api = 24

$targets = @{ 'arm64-v8a' = 'aarch64-linux-android'; 'armeabi-v7a' = 'armv7a-linux-androideabi' }
$srcs = 'packets.c', 'main.c', 'conev.c', 'proxy.c', 'desync.c', 'mpool.c', 'extend.c' | ForEach-Object { "$tp\byedpi\$_" }
foreach ($abi in $targets.Keys) {
    New-Item -ItemType Directory -Force "$jni\$abi" | Out-Null
    $a = @("--target=$($targets[$abi])$api", '-std=c99', '-D_DEFAULT_SOURCE', '-O2', '-fPIE', '-pie', "-I$tp\byedpi",
        '-Wl,-z,max-page-size=16384', '-Wl,--build-id=none', '-s') + $srcs + @('-o', "$jni\$abi\libbyedpi.so")
    & "$bin\clang.exe" @a
    if ($LASTEXITCODE) { throw "byedpi $abi failed" }
}

$out = "$PSScriptRoot\build\hev"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
& "$ndk\ndk-build.cmd" NDK_PROJECT_PATH="$out" NDK_APPLICATION_MK="$PSScriptRoot\Application.mk" `
    APP_BUILD_SCRIPT="$tp\hev-socks5-tunnel\Android.mk" NDK_OUT="$out\obj" NDK_LIBS_OUT="$out\libs" REV_ID=2.19.3 -j8
if ($LASTEXITCODE) { throw 'hev ndk-build failed' }
foreach ($abi in $targets.Keys) {
    Copy-Item "$out\libs\$abi\libhev-socks5-tunnel.so" "$jni\$abi\" -Force
}
Write-Host 'done'
