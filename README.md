# kScaler

[![](https://jitpack.io/v/ssquadteam/kScaler.svg)](https://jitpack.io/#ssquadteam/kScaler)

Server-side GUI width detection.

Paper/Folia 1.21.11, 26.2, 26.3. Clients 1.21.6+.

## How

A core text shader stalls the GPU when a marker glyph's colour-encoded threshold matches the GUI width
(`2 / ProjMat[0][0]`). The client answers pings once per frame, so the server reads frame time from pong bursts and
compares A/B blocks. Calibrated strength and result are stored in player PDC; later checks only verify.

## Install

Hosted on [JitPack](https://jitpack.io/#ssquadteam/kScaler).

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.ssquadteam:kScaler:1.0.0")
}
```

Maven:

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.ssquadteam</groupId>
    <artifactId>kScaler</artifactId>
    <version>1.0.0</version>
</dependency>
```

Shade it. Runtime needs `kotlin-stdlib` and `kotlinx-coroutines-core` (shade or `libraries:`).

## Use

```kotlin
val scaler = KScaler(plugin, KScalerSettings(threshold = 608, namespace = "myplugin"))
scaler.start()

scaler.request(player) { redraw(player) }
val compact = scaler.isNarrow(player)

scaler.close()
```

`request` calibrates or verifies; the callback runs only when the result changes. `onResult` gets every `ScaleResult`.
Also `fit`, `profile`, `reset`, `supports`, `available`.

## Pack

```kotlin
KScalerPack("myplugin").write(file)
```

Or merge `entries()` into your pack and copy the `overlays` from `mcmeta(...)`. Namespace must match `KScalerSettings`.

## Notes

- Replaces `rendertype_text` / `text` core shaders.
- Marker goes in the action bar.

## Build

```
./gradlew build
```
