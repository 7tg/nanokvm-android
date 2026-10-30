# NanoKVM for Android

Control a PC through its [Sipeed NanoKVM](https://github.com/sipeed/NanoKVM) from your phone or tablet, at home or
remotely over Tailscale: see whether it's on, press power or reset, force it off, and open the KVM screen. It also
switches TP-Link Tapo smart plugs, such as the ones powering your monitor and speakers.

<p>
<img src="docs/screenshots/power-pulse.gif" width="200" alt="Power button pulsing while the PC is on">
<img src="docs/screenshots/main.png" width="200" alt="Main screen">
<img src="docs/screenshots/devices-status.png" width="200" alt="Tapo plugs and status">
<img src="docs/screenshots/widgets.png" width="200" alt="Widgets">
</p>
<p><img src="docs/screenshots/tablet-split.png" width="824" alt="Tablet layout"></p>

## Features
- **Power:** tap the big button for a short power press, which also wakes the PC. It glows and pulses green while
  the PC is on. Reset and Force off (an 8 s hold) have their own buttons and ask first.
- **Status:** on/off from the power LED, HDMI signal, latency, last state change, NanoKVM version.
- **Finds the NanoKVM itself:** scans your Wi-Fi for it on first launch, and learns its Tailscale address after
  login. When the LAN address stops answering it falls back to Tailscale, and rescans if the address changed.
- **KVM screen:** the NanoKVM web UI, already logged in.
- **Tapo plugs:** log in with your TP-Link account once; the app finds the plugs on your Wi-Fi and shows each one
  as a card that you tap to switch. Works locally (no cloud), so only on your LAN.
- **Widgets:** 1x1 power, 2x1 status + power, 4x1 status + power + reset + screen, and a 1x1 widget per Tapo plug
  (pick the plug when you place it). Tap a status widget's text to refresh.
- **Tablet layout:** controls on the left, the KVM screen on the right.
- **Animations:** state changes, press feedback and screen entrances; they follow Android's animation settings.

Requires Android 12+ and a NanoKVM running app 2.5 or later (multi-user API). Tested with the NanoKVM PCIe.
Wake from off needs the NanoKVM to stay powered while the PC is off (the PCIe version does).

## Install
With [Obtainium](https://github.com/ImranR98/Obtainium): **Add app** → `https://github.com/7tg/nanokvm-android`.
It picks up new releases automatically. Or download the APK from [Releases](https://github.com/7tg/nanokvm-android/releases).

On first launch the app searches your Wi-Fi for the NanoKVM and fills in its address; enter it yourself (LAN IP or
Tailscale name) if it isn't found. Then log in with a NanoKVM account.

## Tapo plugs
- Turn on **Third-Party Compatibility** in the Tapo app (Me → Third-Party Services); the plugs then accept local
  KLAP connections. Tested with P100 plugs on firmware 1.4.6.
- Plugs are found by Tapo's UDP discovery (port 20002) and remembered by MAC address, so they're found again if
  their IP changes. The app switches the plug directly; it doesn't know about automations that also switch it.

## Security
NanoKVM has no API keys, only username/password login with a session cookie. The app stores the NanoKVM and
TP-Link passwords encrypted with an Android Keystore key, so it can log in again when the session (31 days by default) expires.
It talks plain HTTP to the NanoKVM like the web UI does; use it on your LAN or over Tailscale, not the open internet.

## How it works
- Status: `GET /api/vm/gpio` (`pwr` = power LED). Buttons: `POST /api/vm/gpio` `{"type":"power"|"reset","duration":ms}`.
- Login: `POST /api/auth/login` with the password CryptoJS-AES encrypted, as the web UI sends it.
- Tailscale address: `GET /api/extensions/tailscale/status`. Video: `GET /api/vm/hdmi`. Version: `GET /api/vm/info`.
- LAN discovery: probes the phone's Wi-Fi /24 for a page titled `NanoKVM`.
- Tapo: KLAP v2 (`/app/handshake1`, `/app/handshake2`, `/app/request`) with `get_device_info` / `set_device_info`.

## Build
```
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
```
Release builds (`assembleRelease`) are minified and signed with the key from `keystore.properties`; without that file
they use the debug key. The app has no dependencies: framework Views, `HttpURLConnection` and a WebView.

## Development
No hardware needed: `python3 tools/mock_nanokvm.py` (needs `cryptography`) runs a fake NanoKVM on port 8088
with a PC that turns on and off. In the emulator, log in to `10.0.2.2:8088` as `admin` / `admin`.
`python3 tools/mock_tapo.py` runs two fake Tapo plugs (KLAP on ports 8091/8092, discovery on UDP 20002); log in
as `test@example.com` / `tapo`. Broadcasts don't reach the host from the emulator, so first seed a plug at
`10.0.2.2` in the app's `shared_prefs/tapo.xml` (`run-as`); discovery then asks it directly.
Wi-Fi discovery can't see the mocks, so test that on a real device.
Debug builds install as `dev.neo.nanokvm.debug` next to the release app. On a real phone,
`adb shell am start -n dev.neo.nanokvm.debug/dev.neo.nanokvm.TapoProbe` runs Tapo discovery and logs the result
(`adb logcat -s TapoProbe`). It has to run in the foreground: Android blocks sockets of background apps (EPERM). Tablet layout on a phone emulator:
`adb shell wm size 2560x1600 && adb shell wm density 320`.

## Releases
Pushing a `v*` tag makes GitHub Actions build a signed APK and attach it to a GitHub release
(`versionName` = tag without `v`, `versionCode` = run number). Other pushes build a debug APK as a workflow artifact.
Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`.
