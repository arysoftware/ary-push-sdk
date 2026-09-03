# REST API contract

The versioned contract between the SDK and the ARY push API. Both the Android and iOS
clients implement exactly this: one backend contract, two clients.

- **Base URL** is supplied by the host application. Nothing is hardcoded in the SDK.
- **All paths are versioned**: `{baseUrl}/v1/...`. The SDK never calls an unversioned endpoint.
- **All requests and responses are JSON.**
- **All requests are idempotent** and may be retried by the durable queue.

## Common headers

| Header | Example | Notes |
| --- | --- | --- |
| `Content-Type` | `application/json; charset=utf-8` | On requests with a body |
| `Accept` | `application/json` | |
| `X-SDK-Version` | `1.0.0` | |
| `X-Platform` | `android`, `ios` | |
| `X-App-Version` | `5.2.0` | Host application version |
| `X-Application-Id` | `wallet_android` | Public label; **not** a credential |
| `X-Installation-Id` | `4f1c...` | |
| `X-Request-ID` | UUID | New per logical request, stable across its retries |
| `Authorization` | `Bearer <token>` | Only when an `AuthProvider` is configured |

Header names are configurable through `PushBackendConfig.headerNames` for gateways with another
convention. `Authorization` is never logged, at any log level.

## Error shape

```json
{
  "error": {
    "code": "invalid_token",
    "message": "The push token is not valid for this application"
  }
}
```

A bare `{"code": ..., "message": ...}` is also accepted.

## Retry semantics

The SDK classifies every response, and the backend should behave accordingly.

| Status | SDK behaviour |
| --- | --- |
| 2xx | Success. Operation removed from the queue |
| 400, 403, 404, 409, 422 | **Permanent.** Dropped, never retried |
| 401 | One `AuthProvider` refresh, then one retry. Never a loop |
| 408, 425, 429 | Retried with backoff |
| 5xx | Retried with backoff |
| Transport failure | Retried with backoff |

Backoff is exponential with **full jitter** and is capped. `Retry-After` (delta-seconds or an
HTTP date) is honoured when present, clamped to 15 minutes so a mistaken header cannot park a
device's queue.

A 2xx body the SDK cannot parse is treated as **permanent**, not transient: a malformed success
is a contract violation, and retrying it forever would wedge the queue.

---

## POST /v1/installations

Creates or refreshes the installation record. Called on first launch and whenever the record
changes. The SDK hashes the payload locally and skips the call when nothing changed, so repeated
launches do not produce repeated writes.

**Idempotency:** keyed on `installationId`. Repeated identical calls must not create duplicates.

```json
{
  "applicationId": "wallet_android",
  "installationId": "8f14e45f-ea1e-4f7a-b2c1-5b2d7a1c9e33",
  "platform": "android",
  "provider": "fcm",
  "pushToken": "dGhpcyBpcyBhIHRva2Vu...",
  "userId": "USER_123",
  "appVersion": "5.2.0",
  "appBuild": "520",
  "sdkVersion": "1.0.0",
  "notificationsEnabled": true,
  "device": {
    "osVersion": "14",
    "deviceModel": "Google Pixel 8",
    "locale": "en-PK",
    "timezone": "Asia/Karachi"
  }
}
```

`userId` is null when logged out. The `device` block is **absent entirely** when the host set
`collectDeviceInfo = false`.

| Status | Meaning |
| --- | --- |
| 200, 201 | Registered |
| 400 | Malformed body |
| 401 | Authentication required or expired |
| 422 | Unknown `applicationId`, or a token the provider rejects |

## PUT /v1/installations/{installationId}/token

Replaces the push token. Called on every refresh, without host involvement.

```json
{ "token": "NEW_TOKEN", "provider": "fcm" }
```

`provider` is `fcm` or `apns` and matters: an APNs device token and an FCM registration token are
different values, and sending through the wrong one reaches nobody.

| Status | Meaning |
| --- | --- |
| 200, 204 | Updated |
| 404 | Unknown installation. The SDK re-registers and retries |
| 422 | Token rejected by the provider |

## POST /v1/installations/{installationId}/identify

Associates the installation with a user.

```json
{ "userId": "USER_123" }
```

A user may own many installations. An installation belongs to at most one user at a time;
identifying replaces any previous association rather than merging.

## DELETE /v1/installations/{installationId}/user

Clears the user association.

**Deletes the association only.** The installation, its token and its device registration must
survive: a backend that unregisters the device here stops ARY being able to reach that
user with anything at all, including the win-back campaigns logout exists to enable.

| Status | Meaning |
| --- | --- |
| 200, 204 | Cleared |
| 404 | Already absent. Treated as success |

## PATCH /v1/installations/{installationId}/tags

Merges tag values. Not a replacement: absent keys keep their values.

```json
{ "tags": { "subscription": "premium", "language": "en" } }
```

The SDK debounces and coalesces, so a burst of `addTag` calls arrives as one request.

## DELETE /v1/installations/{installationId}/tags

Removes named tags, or all of them.

| Query | Effect |
| --- | --- |
| `?keys=a,b,c` | Remove those keys |
| `?all=true` | Remove every tag |

## PUT /v1/installations/{installationId}/topics

Replaces the recorded topic subscription set.

```json
{ "topics": ["sports", "news"] }
```

On Android, FCM performs the fan-out and this call is bookkeeping so the backend can report on
it. On iOS, APNs has no topic concept and **the backend performs the fan-out**, so this call is
the source of truth.

## PATCH /v1/installations/{installationId}

Updates mutable installation state. Currently only reachability.

```json
{ "notificationsEnabled": false }
```

Sent when the SDK notices the permission state changed, including the case where the user turned
notifications off in Settings, which the OS never announces.

## POST /v1/events

Submits a batch of push-related events.

```json
{
  "installationId": "8f14e45f-ea1e-4f7a-b2c1-5b2d7a1c9e33",
  "events": [
    {
      "name": "notification_opened",
      "occurredAt": 1730000000000,
      "properties": { "notificationId": "order-42", "actionId": "track" }
    }
  ]
}
```

`occurredAt` is epoch milliseconds and is the **client's** timestamp: an event queued offline
keeps the time it happened, not the time it was delivered. Events emitted by the SDK itself are
`notification_received` and `notification_opened`.

---

## Ordering the backend can rely on

```
POST   /v1/installations
PUT    /v1/installations/{id}/token
POST   /v1/installations/{id}/identify
PATCH  /v1/installations/{id}/tags
```

The SDK never sends a dependent operation before the installation exists. If a dependent call
does arrive first (a race the backend should still tolerate), answering 404 is correct: the SDK
re-registers and retries.

## Versioning

Additive changes (new optional fields, new endpoints) stay within `v1`. Anything that removes a
field, changes a type or changes a status code's meaning requires `v2` alongside `v1`. Old SDK
versions stay in the field for a long time, and a shipped app cannot be asked to update on a
backend's schedule.
