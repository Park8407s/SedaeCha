# 음성 파일 모드(개발 명세 7 대비책)용 문장 음성 일괄 생성
#
# 콘텐츠 팩의 voice_sentences·퀴즈 음성·공통 멘트(인트로는 {route}·{mode_label}·{n} 의 경우의 수만큼)를
# 이 PC 의 한국어 음성(Windows SAPI, Microsoft Heami)으로 읽어 WAV(16kHz mono) 로 저장한다.
# 파일 이름은 문장 텍스트의 SHA-1 앞 16자리, 목록은 voice/index.json (문장 → 파일).
# 앱은 기기에 한국어 TTS 가 없을 때만 이 파일을 재생한다 (SpeechPlayer).
#
# 실행: powershell -ExecutionPolicy Bypass -File tools\gen_voice.ps1
# 콘텐츠 팩을 고치면 다시 실행한다 (이미 있는 파일은 건너뜀, 쓰지 않는 파일은 지움).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$packPath = Join-Path $root "app\src\main\assets\sedae_radio_content_pack_v0.5.json"
$outDir = Join-Path $root "app\src\main\assets\voice"
New-Item -ItemType Directory -Force $outDir | Out-Null

$pack = Get-Content $packPath -Raw -Encoding UTF8 | ConvertFrom-Json
$texts = New-Object System.Collections.Generic.List[string]
function Add-Text($t) { if ($t -and $t.Trim().Length -gt 0) { $texts.Add($t) } }

foreach ($it in $pack.items) {
    $it.voice_sentences | ForEach-Object { Add-Text $_ }
    Add-Text $it.quiz.voice.question; Add-Text $it.quiz.voice.answer
    if ($it.then_mode) {
        $it.then_mode.voice_sentences | ForEach-Object { Add-Text $_ }
        Add-Text $it.then_mode.quiz.voice.question; Add-Text $it.then_mode.quiz.voice.answer
    }
}
foreach ($c in $pack.culture_cards) { $c.voice_sentences | ForEach-Object { Add-Text $_ } }

$t = $pack.templates
$routes = @($t.route_label.PSObject.Properties | ForEach-Object { $_.Value })
$modes = @($t.mode_label.PSObject.Properties | ForEach-Object { $_.Value })
foreach ($line in $t.intro) {
    if ($line -match "\{") {
        foreach ($r in $routes) { foreach ($m in $modes) { foreach ($n in 0..5) {
            Add-Text ($line.Replace("{route}", $r).Replace("{mode_label}", $m).Replace("{n}", "$n"))
        } } }
    } else { Add-Text $line }
}
foreach ($key in @("intro_carry_over", "quiz_lead", "resume_after_hard_event", "bonus_lead", "outro_all_done", "outro_replay", "outro_carry_over")) {
    $t.$key | ForEach-Object { Add-Text $_ }
}

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq "ko-KR" } | Select-Object -First 1
if (-not $voice) { throw "한국어 음성(ko-KR)이 이 PC 에 없습니다. 설정 > 시간 및 언어 > 음성에서 한국어 음성을 추가하세요." }
$synth.SelectVoice($voice.VoiceInfo.Name)
$synth.Rate = 0
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$sha = [System.Security.Cryptography.SHA1]::Create()

$index = [ordered]@{}
$made = 0
foreach ($text in ($texts | Select-Object -Unique)) {
    $hash = ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text)) | ForEach-Object { $_.ToString("x2") }) -join ""
    $name = $hash.Substring(0, 16) + ".wav"
    $index[$text] = $name
    $file = Join-Path $outDir $name
    if (-not (Test-Path $file)) {
        $synth.SetOutputToWaveFile($file, $format)
        $synth.Speak($text)
        $synth.SetOutputToNull()
        $made++
    }
}
$synth.Dispose()

# 쓰지 않는 파일 정리
$keep = @($index.Values)
Get-ChildItem $outDir -Filter *.wav | Where-Object { $keep -notcontains $_.Name } | Remove-Item

$json = $index | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText((Join-Path $outDir "index.json"), $json, (New-Object System.Text.UTF8Encoding($false)))
"voice: $($index.Count) sentences, $made new, engine=$($voice.VoiceInfo.Name)"
