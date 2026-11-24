# Jio STB Playbook — everything done to the box

Distilled from `jiostb.md` (the full unlock/debloat/tweak transcript).
This is the reference for anything we change on the STB later, and the
source list for features built into STB Control.

Box: **JHSD200**, serial `CCF55F-JHSD200-RFLSBCHF2071986`, LAN `192.168.29.111:5555`.

---

## 1. Hardware & OS facts (design constraints)

| Item | Reality | Consequence for our app |
|---|---|---|
| SoC | Amlogic S905X4-class, 4× Cortex-A55 ~2.0 GHz | — |
| Userspace | **32-bit only (`armeabi-v7a`)** | Ship v7a APKs; `arm64`/`x86` wastes storage or fails |
| RAM | 2 GB (1.9 GB usable), idle target ~1.1 GB / 55 % | Keep services small; foreground service for anything persistent |
| GPU | Mali-G31 MP2 — AV1/VP9/HEVC 4K60 hw decode | Stream files, don't mirror the laptop |
| OS | JioOS = AOSP Android 9 (Pie) fork, **no GMS** | No FCM, no Google Sign-In, no Play Services |
| Root | None. Bootloader locked (Amlogic TrustZone) | `tinycap`/ALSA blocked → app-level `AudioRecord` only |
| Bluetooth | BT 4.0 + BLE; remote is a BLE HID | Mic is firmware-gated; HID host role available |
| Storage | 8 GB eMMC (some variants 32/64) | Prefer v7a-only APKs (~90 MB saved on Nuvio alone) |

---

## 2. How ADB was unlocked (and what keeps it alive)

The box refuses normal Developer Options after reboot, so access was taken over
via its own provisioning channel:

1. **Local DNS override** — Technitium DNS (or hosts file) points
   `acs.oss.jio.com` / the Jio ACS hostnames at the PC's LAN IP
   (`10.81.46.226`, later `192.168.29.21`).
2. **CWMP/ACS proxy** — `./jhs/run_acs.sh` mints a forged cert and listens on
   **8443 / 9443**, forwarding to GenieACS on 7547.
   * Python-arch trap: an ARM64 Python breaks `cryptography`; run it with
     `py -3.12-64 jhs\acs_proxy.py` (x64 interpreter).
3. **Queue the ADB-enable task**, then **cold boot** — the STB only contacts
   the ACS during boot. `adb connect 192.168.29.128:5555` (old IP) → accept the
   on-screen debugging prompt.
4. **Persistence** came from `com.jio.tr069client` / `insight.tr069.client`
   re-asserting USB debugging on every boot.

### ⚠ The trade-off that was made later
To reclaim ~40 MB RAM, both TR-069 clients were disabled
(`insight.tr069.client`, `com.jio.tr069client`). That **kills the boot-time
ADB re-assert**: after a reboot, port 5555 may not come back on its own.
Recovery = re-enable the two packages over ADB while still connected, or
re-run the ACS flow, or toggle debugging by hand with the remote.
A factory reset also wipes the whole unlock.

Other access notes:
- The DNS/ACS spoof is only needed for the *initial* unlock, not for daily ADB.
- OTA is blocked separately by disabling the `com.iwedia.*` updaters — that is
  what stops Jio from silently re-flashing and revoking access.
- 5555 open on the LAN = anyone on the Wi-Fi can control the box. Fine at home;
  switch the launcher to "Secure Mode" once modding stops.

---

## 3. Package map

### Never disable (breaks TV / access)
```
com.iwedia.comedia.service      live-TV engine (JioTV+ decode)
com.iwedia.tvinput              TV input framework
com.iwedia.displayservice       display pipeline
com.iwedia.bluetoothconnectionservice
com.jio.remote.btserver         BLE remote daemon (incl. mic routing)
com.media.jio.sso.stb           Jio SSO — JioTV+ AND JioHotstar login
com.jio.media.jiotvplus         JioTV+ app
com.android.htmlviewer          JioHotstar's web renderer (PWA wrapper)
com.android.bluetooth, com.droidlogic, com.android.systemui,
com.android.tv.settings, com.android.permissioncontroller
<launcher>                      com.klevico.monet or com.techgenius.tvoslauncher
com.iwedia.resetservice         factory-reset path
```
Keep `com.jio.keyboard.swarachakra` **or** another IME enabled — with zero IMEs
installed, `ime list -s` is empty and no text field on the box can be typed into.

### OTA / update blockers
```
com.iwedia.httpupdate  com.iwedia.updateservice
com.iwedia.pvodupdateservice  com.iwedia.updatercuotaservice
com.jio.stbadservice
```

### Telemetry / logging / ad
```
com.jio.systemstats  com.rjil.podlogservice  com.rjil.jiostblogservice
com.jio.adc.aka  com.jio.adc.embedded  com.jio.bioscopecontentservice
com.jio.smartmemorymanager  com.jio.appoptimizer  com.android.traceur
```

### Jio bloat / portals / store
```
com.jio.media.stb.ondemand  com.jio.media.jiobeats  com.jio.jiogames.tv
com.jio.jiostore  com.jio.store  com.jio.voiceassistant  com.jio.photos
com.jio.browser  com.jio.jse  com.jio.sphere  com.jio.web
com.jio.universalsetup  com.jio.myjio  com.jio.join  com.jio.jiopay
com.jio.stb.feedback  com.jio.stb.bot  com.jio.stb.remotediagnose
com.jio.media.jionews(.stb)  com.jio.mymusic.app  com.jio.jiocall
com.jio.netvelocity  com.jio.product.exploremyremote  com.jio.myjiotv
com.android.app.jio.voiceassist  com.jiostb.jiogames  com.jio.stbwidget
com.jio.cloudpc / com.jio.jiopc / com.jio.jiocloudpc  com.jio.dialserver
```

### Third-party / preloads removed
```
com.netflix.ninja  com.netflix.partnertoken  com.amazon.amazonvideo.livingroom
com.suntv.sunnxt(.tv)  com.sonyliv  com.sony.liv.tv  com.etvwin.androidtv
com.viewlift.hoichoi  com.hoichoi.mitv  com.zee5.aosp
com.konka.tv.explorer  com.mobisystems.fileman  com.jio.media.stb.explorer
com.deepakpk.tvexplorer  com.duns.padiapp
com.omni.sci  com.omni.touchclient  com.omni.simplesetup
com.teamviewer.quicksupport.addon.jio_stb  com.communitake.remotecontrolservice
com.inn.nvengineer  com.aksys.jiogamepad  com.android.camera2  com.android.music
com.android.printspooler  com.android.printservice.recommendation
```

### Deferred on purpose
```
com.google.android.apps.mediashell      keep → Chromecast casting from phone
com.jio.myhome.mediasharedmp            keep → Jio native casting
com.rjil.mobileremote.receiver          phone-remote receiver (disabled late, for RAM)
```

### Removed for RAM in the final pass
```
insight.tr069.client  com.jio.tr069client  com.jio.keyboard.swarachakra
com.rjl.mobileremote.receiver  com.jio.profileutility  com.jio.jiopay
com.jio.dialserver  com.android.printspooler  com.iwedia.usbbenchmarkservice
com.rjil.podlogservice
```

Commands used throughout:
```bash
adb -s 192.168.29.111:5555 shell pm disable-user --user 0 <pkg>
adb -s 192.168.29.111:5555 shell pm enable <pkg>
adb -s 192.168.29.111:5555 shell cmd package install-existing <pkg>   # after uninstall -k
adb -s 192.168.29.111:5555 shell pm list packages -d                  # what's off
adb -s 192.168.29.111:5555 shell pm list packages -e                  # what's on
```

---

## 4. Launchers

| Launcher | Package | Notes |
|---|---|---|
| Stock Jio / iWedia | `com.iwedia.ui.rjio.launcher.reliance` | disabled |
| Monet | `com.klevico.monet` | animated wallpaper + Material You sampling, ~120 MB PSS — heavy |
| tvOS-Launcher | `com.techgenius.tvoslauncher` | ~30 MB private RAM, 0 % idle CPU, built-in recents killer |

Switch home:
```bash
adb shell cmd package set-home-activity com.techgenius.tvoslauncher/.MainActivity
adb shell pm uninstall --user 0 com.klevico.monet    # only after the new home works
```
Ghost icons after debloating are a launcher cache problem, not a live app:
`am force-stop <launcher>` first, `pm clear <launcher>` only as the nuclear option.

---

## 5. Keyboard / IME

```bash
adb shell ime list -s                                   # ALWAYS check first
adb shell ime enable org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService
adb shell ime set    org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService
adb shell ime set    com.jio.keyboard.swarachakra/.SwarachakraIME   # revert
```
- The component name must be the **full** path — the short `.ime.LeanbackImeService`
  variant throws "Unknown input method".
- LeanKeyboard: ~5 MB, no telemetry, D-pad native, English-first.
- Swarachakra: ~25 MB persistent, but it is the only thing wiring the **physical
  remote's mic button** to voice typing. Going Lean = losing the remote mic.
- Key Mapper (`io.github.sds100.keymapper`) hijacks the IME with an invisible
  keyboard — that is why text fields "don't open a keyboard". Typing still works
  through `input text` / scrcpy.

---

## 6. Known breakages and the exact fixes

| Symptom | Cause | Fix |
|---|---|---|
| JioHotstar won't load / black | `com.android.htmlviewer` or SSO disabled | `cmd package install-existing com.android.htmlviewer com.media.jio.sso.stb com.jio.web` → `pm enable` → `pm clear in.startv.hotstar` → reboot |
| JioTV+ won't play live TV | iWedia engine or SSO disabled | enable `com.media.jio.sso.stb`, `com.iwedia.comedia.service`, `com.iwedia.tvinput`, `com.jio.myjiotv` → `pm clear com.jio.media.jiotvplus` |
| "Limited connection" / no internet | captive-portal probe + broken Private DNS | `settings put global captive_portal_mode 0`; `settings put global private_dns_mode off`; `settings delete global private_dns_specifier`; verify clock |
| DNS ad-blocking desired | — | `settings put global private_dns_mode hostname` + `settings put global private_dns_specifier dns.adguard-dns.com` |
| No keyboard pops up | zero enabled IMEs | `ime list -s` → enable + set (see §5) |
| Remote mic dead | LeanKeyboard has no Jio voice hook | revert IME, or `am force-stop com.jio.remote.btserver`, or re-pair the remote |
| Mouse cursor invisible | Android TV only draws a pointer when the kernel sees `/dev/input` | BT HID mouse (our app) or any USB mouse dongle in the back |
| `tinycap` → Permission denied | no root, raw ALSA blocked | app-level `AudioRecord` + `RECORD_AUDIO` |
| `adb: device not found` | DHCP moved the box / adb server restarted | `adb kill-server; adb start-server; adb connect <ip>:5555`; reserve the IP in the router |
| Sideloaded app can't self-update | `REQUEST_INSTALL_PACKAGES` off | `appops set <pkg> REQUEST_INSTALL_PACKAGES allow` |
| Animations feel sluggish | default 1.0× scales | `settings put global window_animation_scale 0.5` (+ transition, animator) |

---

## 7. Apps on the box now

- **SmartTube** — ad-free YouTube, TV UI, account linked by code.
- **Aurora Store** — anonymous Play downloads (no GMS needed).
- **NuvioTV** — `armeabi-v7a` build only (1.5.8 v7a ≈ 54 MB vs 144 MB universal).
- **VLC** — SMB playback of the laptop's shared movie folder.
- **LeanKeyboard**, **tvOS-Launcher**, **Key Mapper**, **CPU-Z**.
- **STB Control** (this project) — ADB remote, trackpad, dashboard, BT HID air mouse, mic stream.

### Media path (zero laptop load)
Windows folder share (`BOOK-6EQ42SP6NJ`, Read-only, `fsmgmt.msc` to verify no
`C`/`Users` share) → VLC on STB → hardware decode. Mirroring (Moonlight/Sunshine,
Miracast) only for games; SMB is the right answer for movies.
Optional: Jio ONT USB port exposes the drive over Samba/DLNA so the laptop can be off.

### Network
- JioFiber ONT: `192.168.29.1`, admin / `Jiocentrum`.
- **Reserve the STB's IP** (DHCP reservation) — everything we build assumes `.111`.
- Split 2.4/5 GHz or wire Ethernet if high-bitrate SMB stutters.
- CGNAT → no inbound port-forward; use Tailscale for remote access.
- Don't block TR-069/DNS at the router: it can break fiber auth and VoIP.

---

## 8. What this means for STB Control (build backlog)

1. **Debloat manager** — categorized presets (telemetry, OTA block, Jio apps,
   streaming preloads), per-package toggles, hard guardrails on the keep-list,
   live `pm list packages -d` view, one-tap restore.
2. **Dependency repair** — "Fix JioHotstar", "Fix JioTV+", "Restore keyboard"
   recipes: `install-existing` → `enable` → `pm clear` → reboot.
3. **Diagnostics / fixes** — captive-portal off, Private DNS off/adguard, clock
   check, animation scales, `appops REQUEST_INSTALL_PACKAGES`.
4. **ADB health** — reconnect helper (`kill-server`/`start-server`), persistence
   warning when TR-069 is disabled, IP reservation reminder.
5. **Keyboard & launcher manager** — `ime list -s` picker + set, home-activity
   switch, launcher cache flush for ghost icons.
6. **RAM dashboard** — top processes from `dumpsys meminfo`, force-stop per app.

Rules for anything we add: v7a-only, no GMS, foreground service for background
work, nothing that touches the keep-list without an explicit confirmation.
