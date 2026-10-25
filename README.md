# Llama Swap Mobile Controller

An Android app for controlling a [llama-swap](https://github.com/mostlygeek/llama-swap) server from your phone: load and unload models, watch requests and logs live, chat with the loaded model, and check GPU/CPU/RAM.

> **Unofficial.** This project is not affiliated with or endorsed by the llama-swap project or Meta.

## Features

Five tabs, all fed by llama-swap's own HTTP API:

| Tab | What it does |
|---|---|
| **Models** | Lists every configured model and whether it's running. Load or unload any of them. Before a load evicts a running model, the app asks you to confirm. |
| **Activity** | Shows requests through the proxy as they happen (`/api/metrics/activity`, updated from `/api/events`). |
| **Playground** | Streams a chat with a model, rendering Markdown, LaTeX, and highlighted code. |
| **Logs** | Shows the proxy's log stream, filterable by All / Proxy / Upstream / HTTP, with a pause button. |
| **Performance** | Plots GPU usage, VRAM, temperature, and power, plus server CPU and RAM, polled from `/api/performance`. |

There's no account, no cloud service, and no telemetry. The app only talks to the server URL you give it.

## Requirements

- Android 8.0 (API 26) or newer.
- A llama-swap server your phone can reach. Development and testing used llama-swap **v265**. Older versions may not have the `/api/events` or `/api/performance` endpoints that some tabs use.
- The app sends **no authentication**. Plain `http://` is allowed because the app is meant for trusted networks such as your LAN or a Tailscale tailnet. Don't expose llama-swap to the public internet just to use this app.

## Install

Release APKs are on the [Releases page](https://github.com/nevermore131315/llama-swap-mobile-controller/releases). Each release includes `SHA256SUMS`.

> Current builds are **release candidates** marked as GitHub pre-releases. Update apps skip pre-releases by default, so turn on the pre-release option shown below until 0.1.0 is final.

### Obtainium

1. **Add App**, then paste `https://github.com/nevermore131315/llama-swap-mobile-controller`.
2. Turn on **Include prereleases**.
3. **Add**, then **Install**. Obtainium will keep the app updated after that.

### Komi Store

1. Search for or open `nevermore131315/llama-swap-mobile-controller`.
2. Open the release list (pre-releases are listed there) and install the `llama-swap-mobile-controller-<version>.apk` asset.

### Manual

Download the `.apk` from a release, check it against `SHA256SUMS`, and open it on your phone.

### Verify the signature

Every release is signed with the same key:

```
CN=Llama Swap Mobile Controller, O=nevermore131315
SHA-256: f0:31:f8:2b:45:40:3e:52:6a:1e:63:4d:36:fe:9a:0f:e4:44:32:de:16:81:bb:38:6f:7c:e7:d2:fb:5d:6b:b3
```

Check it with:

```sh
apksigner verify --print-certs llama-swap-mobile-controller-<version>.apk
```

If the fingerprint doesn't match, don't install the APK. Android also refuses an update signed with a different key, so once the app is installed, every update has to come from the same signer.

## First run

Open the settings (gear icon) and enter your server's base URL, the same one you'd open in a browser for the llama-swap UI. For example, `http://192.168.1.50:8080` or `http://my-server.tailnet-name.ts.net:8080`.

## Building from source

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/, app id suffixed .dev
./gradlew testDebugUnitTest    # unit tests
```

Gradle builds with a JDK 21 toolchain and downloads one automatically if you don't have it. You need the Android SDK with platform `android-37.0` and build-tools `37.0.0`. Debug builds install next to a release build instead of replacing it.

### About the test fixtures

The JSON files in `app/src/test/resources/llamaswap/` are real responses captured from a home llama-swap v265 server, used to test the parsers against real output. Identifying values are redacted: GPU UUIDs are zeroed and network interface names are replaced. They're test data only and never ship in the APK.

## Credits and license

This app started as a fork of [Hermes Mobile](https://github.com/Hy4ri/hermes-mobile) by Hy4ri, which supplied the app shell, theming, and the Markdown/LaTeX chat rendering. The Hermes Agent client was removed and replaced with the llama-swap control panel.

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
