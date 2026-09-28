# BIPSS

Privacy-preserving personal health companion — Android app (v1.0.4).

Reads vitals from a companion wearable band over BLE, fuses them with
environmental data (AQI, weather, disaster alerts), and produces explainable
risk scores entirely on-device. Analysis works with no network and no AI
model installed; the on-device LLM is an optional enrichment layer.

## Requirements

- Android Studio (Ladybug or newer)
- JDK 17 — the Kotlin Gradle plugin fails to parse newer JDK version strings,
  so make sure Gradle runs on 17 (Settings → Build Tools → Gradle → Gradle JDK)
- Android SDK 35, `minSdk 26`

## Setup

Create `local.properties` in the project root (it is gitignored — never commit it):

```properties
sdk.dir=/path/to/your/Android/Sdk

# Optional. Degrades gracefully when absent.
CPCB_AQI_API_KEY=your_key_here
```

- **CPCB_AQI_API_KEY** — register at [data.gov.in](https://data.gov.in) for real-time
  CPCB station AQI. Without it there is no regional station reading: the air
  quality row shows only the band's own reading when one is streaming, otherwise
  "—". Nothing is substituted.

Weather (temperature, humidity, UV, pressure, wind) comes from Open-Meteo, which
needs no key. There is no other weather provider.

Then:

```bash
./gradlew assembleDebug
```

## External items still to be provisioned

These are placeholders in source, each flagged with a `TODO`:

| Item | Where | Needed for |
|---|---|---|
| Model manifest URL | `ai/ModelManifest.kt` | Downloading the on-device LLM |
| BLE service/characteristic UUIDs and byte layout | `ble/BleConstants.kt` | Real band data (firmware-dependent) |
| Google OAuth client ID (Drive `appdata` scope) | `data/backup/DriveBackupManager.kt` | Cloud backup/restore |
| Sachet/NDMA bulk alert feed endpoint | `data/environmental/SachetCapFetcher.kt` | Geo-scoped disaster alerts |

## Architecture

```
ui/            Compose screens + shared design system (theme/, components/)
navigation/    NavGraph, routes, bottom-nav items
domain/        Pure-Kotlin risk engine (no Android imports, unit-testable)
data/          Room, DataStore, environmental fetchers, Drive backup
ble/           BluetoothGatt scan/connect/notify pipeline
ai/            MediaPipe LLM engine, model download + SHA-256 verification
sos/           Countdown, Fused Location, SmsManager, Room audit log
worker/        WorkManager periodic environmental sync
```

The risk engine (`domain/RiskScoreCalculator.kt`) has no Android dependencies
and is the fallback for everything: if the model is missing, the network is
down, or sensors are absent, scores still compute and the UI still renders.
