# 에뮬레이터에서 스티어링 휠 음성 버튼을 누른 것처럼 Gleo AI를 띄운다.
# 화면 위쪽에 "운전석에서 듣는 중…"이 뜨면 에뮬레이터를 클릭하지 말고 바로 말한다.
# 예) "세이프파일럿, 주차 확인해 줘"
$adb = Join-Path ($env:ANDROID_HOME ?? 'C:\Android\Sdk') 'platform-tools\adb.exe'
# PC 마이크를 에뮬레이터에 연결한다(에뮬레이터는 -allow-host-audio로 띄워야 한다)
& $adb emu avd hostmicon | Out-Null
& $adb shell cmd voiceinteraction show | Out-Null
Write-Host '듣는 중입니다. 지금 말씀하세요.'
