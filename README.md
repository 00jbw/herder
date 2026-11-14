# Herder

A local model server for Android. Herder downloads a pinned, checksum-verified
[llama.cpp](https://github.com/ggml-org/llama.cpp) backend, runs `llama-server`
under a foreground service, and serves an OpenAI-compatible API on the device
itself. No account, no telemetry — nothing leaves the device except the
downloads you start.

- **Web UI:** http://[::1]:8080/
- **API base:** http://[::1]:8080/v1 — point any local client at it, or use
  Herder's own built-in chat tab.

## Features

- **Verified backend.** llama.cpp v0.9.0 (build b12110) is downloaded from the
  ggml-org releases and checked against a compiled-in SHA-256 before anything
  is extracted or run. The standard ARM64 build works on any arm64 device; a
  Snapdragon (Hexagon) build is offered as an experimental option for capable
  hardware.
- **Verified models.** One-tap downloads of Qwen3.5 0.8B, 2B and 4B
  (UD-Q4_K_XL), each SHA-256-verified, with a memory-fit check before install.
  You can also import your own `.gguf` files.
- **Operational levels.** Basic, Expert and Guru, set from the menu. Basic
  just runs the server. Expert adds a custom backend build (you confirm its
  SHA-256 before it installs) and optional server-log-to-file, which is off by
  default. Guru adds extra `llama-server` arguments.
- **Housekeeping.** Superseded backend downloads and partial model files can
  be cleaned up in one tap.
- **Small.** The app itself is about 270 KB; the backend and models are
  downloaded on demand, not bundled.

## Requirements

- Android 9 (API 28) or newer, arm64 device
- Enough storage and RAM for the model you choose (the 2B model is ~1.3 GB)

## Install

Download `Herder-v0.3.3-arm64.apk` from the
[Releases](https://github.com/00jbw/herder/releases) page and install it
(sideload). Release APKs are signed with the Herder release key:

```
86:E2:BD:CD:C7:43:8C:04:E8:27:90:5A:33:B2:34:54:62:3D:C1:25:73:AC:E4:AD:90:DB:1D:0A:78:32:65:3A
```

## Building

There is no Gradle wrapper in this repo. The supported build path is the
GitHub Actions workflow: dispatch it and it provisions JDK 17, Gradle 8.10.2
and the Android SDK, builds the unsigned APK, and uploads it as an artifact
named `Herder-v<version>-arm64`. To build locally you need the same pieces
(JDK 17, Gradle 8.10.2, Android SDK platform 35) and then:

```
gradle :app:assembleRelease
```

Signing is done outside the repository — the release keystore is not, and
will not be, in this repo.

## Privacy

Herder has no analytics and no telemetry of any kind. Its only network use is
downloading backends (GitHub) and models (Hugging Face) when you ask it to.

## Licence

MIT — see [LICENSE](LICENSE). Copyright ai.bin.org.
