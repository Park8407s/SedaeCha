# 내장 음성 WAV → AAC(.m4a, 16kHz mono 32kbps) 변환 (APK 용량 줄이기)
#
# 추가 설치 없이 Windows 기본 AAC 인코더(Windows.Media.Transcoding)를 쓴다.
# gen_voice.ps1 이 끝에서 자동으로 부른다. 변환한 WAV 는 지우고 index.json 의 파일 이름을 .m4a 로 바꾼다.
#
# 실행: powershell -ExecutionPolicy Bypass -File tools\compress_voice.ps1

param([string]$Dir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outDir = if ($Dir) { (Resolve-Path $Dir).Path } else { Join-Path $root "app\src\main\assets\voice" }
$Bitrate = 32000

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Media.Transcoding.MediaTranscoder, Windows.Media.Transcoding, ContentType = WindowsRuntime]
$null = [Windows.Media.MediaProperties.MediaEncodingProfile, Windows.Media.MediaProperties, ContentType = WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
$asTaskProgress = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncActionWithProgress`1' })[0]
function Await($op, [Type]$t) { $task = $asTaskGeneric.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }
function AwaitAction($op) { $task = $asTaskProgress.MakeGenericMethod([double]).Invoke($null, @($op)); $task.Wait(-1) | Out-Null }

$profile = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateM4a([Windows.Media.MediaProperties.AudioEncodingQuality]::Low)
$profile.Audio.SampleRate = 16000
$profile.Audio.ChannelCount = 1
$profile.Audio.Bitrate = $Bitrate
$transcoder = New-Object Windows.Media.Transcoding.MediaTranscoder
$folder = Await ([Windows.Storage.StorageFolder]::GetFolderFromPathAsync($outDir)) ([Windows.Storage.StorageFolder])

$indexPath = Join-Path $outDir "index.json"
$index = Get-Content $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
$made = 0
foreach ($p in $index.PSObject.Properties) {
    if (-not $p.Value.EndsWith(".wav")) { continue }
    $m4a = $p.Value -replace '\.wav$', '.m4a'
    $wavPath = Join-Path $outDir $p.Value
    if (-not (Test-Path (Join-Path $outDir $m4a))) {
        $src = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($wavPath)) ([Windows.Storage.StorageFile])
        $dst = Await ($folder.CreateFileAsync($m4a, [Windows.Storage.CreationCollisionOption]::ReplaceExisting)) ([Windows.Storage.StorageFile])
        $prep = Await ($transcoder.PrepareFileTranscodeAsync($src, $dst, $profile)) ([Windows.Media.Transcoding.PrepareTranscodeResult])
        if (-not $prep.CanTranscode) { throw "변환 불가: $($p.Value) ($($prep.FailureReason))" }
        AwaitAction ($prep.TranscodeAsync())
        $made++
    }
    if (Test-Path $wavPath) { Remove-Item $wavPath }
    $p.Value = $m4a
}
$json = $index | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($indexPath, $json, (New-Object System.Text.UTF8Encoding($false)))
"voice: $made files → m4a ($($Bitrate / 1000)kbps)"
