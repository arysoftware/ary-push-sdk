# Security and privacy

## The one rule

**Anything inside an APK or an IPA can be extracted.** Not "is hard to extract": can be
extracted, by anyone, with free tools. So the SDK contains no privileged credential of any kind,
and no version of it ever will.

### Never in the SDK, and never in an application binary

| | Where it belongs |
| --- | --- |
| FCM service account or server key | Backend secret store |
| APNs authentication key (`.p8`) or certificate | Backend secret store |
| Database credentials | Backend |
| Admin or master API keys | Backend |
| Any long-lived shared secret | Nowhere in a mobile app |

`google-services.json` and `GoogleService-Info.plist` are **host application** files, provided by
each app for its own Firebase project. They are `.gitignore`d in this repository so they cannot
be committed by accident.

### The application identifier is not a secret

```kotlin
PushBackendConfig(baseUrl = "...", applicationId = "wallet_android")
```

`applicationId` is a **public label** that tells the backend which application a device belongs
to. It is not authentication, and a backend that treats it as such has no authentication.

Authenticate with the user's own credentials, through `AuthProvider`.

## Authentication

The SDK never owns credentials. It asks the host application for a token when it needs one:

```kotlin
class AppAuthProvider : AuthProvider {
    override suspend fun getAccessToken(): String? = session.accessToken
    override suspend fun refreshAccessToken(): Boolean = session.refresh()
}
```

- Tokens are read per request, so a token refreshed between attempts is actually used.
- A `401` triggers **one** refresh and **one** retry. Never a loop.
- An `AuthProvider` that throws degrades to an unauthenticated request, not a crash.
- Prefer short-lived tokens. A long-lived one on a device is a long-lived one in an attacker's
  hands after a single compromise.

## Transport

Production traffic **must** use HTTPS. A plaintext base URL is accepted for local development and
logs a warning every time the client is built.

The SDK does not, and will not:

- trust all certificates
- disable hostname verification
- ignore TLS errors
- install a custom `TrustManager` or `URLSessionDelegate` challenge handler

TLS is left entirely at the platform defaults. An SDK that ships an SSL bypass ships it to every
application that embeds it, and to everyone on those users' networks.

Certificate pinning is not built in. Applications that require it should supply their own
transport through `customBackend`, keeping the pinning policy where the security team can see it
rather than buried in a shared dependency.

## Logging

Off by default. A library that writes to logcat or the console unprompted leaks its users' data
into bug reports and sysdiagnoses.

When enabled, the logger enforces two things at the sink rather than trusting call sites:

- **Tokens are masked.** `dGhp***4n(163)` is enough to tell two tokens apart in a support ticket
  and useless for sending anything.
- **`Authorization`, `Proxy-Authorization` and `Cookie` are redacted** wherever headers are
  logged.

Never logged at any level: passwords, private keys, bearer tokens, full authorization headers, or
notification payload contents. Message **identifiers** are logged; message **bodies** are not.

```kotlin
ARYPushConfig(enableLogging = BuildConfig.DEBUG, logLevel = PushLogLevel.DEBUG)
```

## Storage

SDK state lives in its own namespaced store: a private `SharedPreferences` file on Android, a
dedicated `UserDefaults` suite on iOS, every key prefixed `ary_push.`.

Two isolation guarantees follow:

- **From the host application.** The SDK cannot collide with, read or clobber an application's
  own preferences, and vice versa.
- **Between applications.** Both stores are per-application container storage, so two apps
  embedding this SDK on one device get entirely separate installation ids, tokens, users, tags
  and queues, with no configuration.

Nothing stored is a credential. The stored push token is a delivery route issued by the platform,
already known to the OS, and useless without the sending credential held by the backend.

## What is collected

The complete list. Conduct a privacy review against this table.

| Field | Example | Why |
| --- | --- | --- |
| Installation ID | `8f14e45f-...` | SDK-generated UUID; the delivery unit |
| Push token | provider-issued | The delivery route |
| Provider | `fcm`, `apns` | Which transport to send through |
| Platform | `android`, `ios` | Payload shape |
| App version and build | `5.2.0`, `520` | Targeting and debugging |
| SDK version | `1.0.0` | Support |
| Notifications enabled | `true` | Suppress sends to unreachable devices |
| User ID | your own id | Only when the app calls `login()` |
| Tags | `subscription=premium` | Only what the app sets |
| Topics | `sports` | Only what the app subscribes to |
| OS version | `14` | Optional |
| Device model | `Pixel 8` | Optional; a model, not a device |
| Locale | `en-PK` | Optional; localisation |
| Timezone | `Asia/Karachi` | Optional; send-time targeting |

The last four are the `device` block and are omitted entirely with `collectDeviceInfo = false`.

### Never collected

Contacts, location, photos, microphone, camera, calendar, files, browsing history, IDFA, Android
advertising id, `identifierForVendor`, hardware serial, IMEI, MAC address, phone number,
installed applications, or notification content from other apps.

The SDK requests exactly one permission: notifications. It reads nothing that requires another.

## Reviewing a release

- No `google-services.json`, `GoogleService-Info.plist`, `.p8`, `.p12` or `.cer` in the tree
- No hardcoded URL, project id, sender id or API key in SDK source
- No new permission in the merged manifest
- No new field sent to the backend that is not in the table above
- No `TrustManager`, `URLSessionDelegate` challenge handler or hostname verifier
- Logging still off by default; masking still applied

The CI workflow runs the first four as a `secret-scan` job on every push.

## Reporting

Report a suspected vulnerability to the mobile platform team through the internal security
channel. Do not open a ticket describing it in a system with broader access than the repository.
