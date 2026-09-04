# Backend

What the server side needs to look like for this SDK to work, and where the boundary sits.

This document describes the contract and the data model. Building the service, and any campaign
dashboard, is separate work and is deliberately not implemented here.

## The boundary

**The SDK sends attributes. The backend decides what they mean.**

That single line explains most of the design. Segment rules change far more often than a mobile
app is released, so an SDK that evaluated them would freeze last quarter's marketing logic into
a binary that takes weeks to roll out and months to fully adopt.

```
SDK                          Backend
---                          -------
installation                 applications, users, installations
push token                   tokens
user id                      segments (computed)
tags        ---------->      campaigns
topics                       delivery
events                       reporting
```

## Data model

```
Application
  |
  +-- Users
  |     |
  |     +-- Installations
  |           |
  |           +-- Push token (one, with a provider)
  |           +-- Tags (many)
  |           +-- Topics (many)
  |
  +-- Segments (computed from tags)
  +-- Campaigns
```

### Entities

| Entity | Key fields | Notes |
| --- | --- | --- |
| **Application** | `id` (`wallet_android`), platform, credentials | The public id the SDK sends; credentials live only here |
| **User** | `id` (the host's own user id) | Owns many installations |
| **Installation** | `id` (SDK-generated UUID), `applicationId`, `platform`, `provider`, `pushToken`, `userId`, versions, device fields, `notificationsEnabled` | The unit of delivery |
| **Tag** | `installationId`, `key`, `value` | Flat attributes |
| **Topic** | `installationId`, `name` | Opted into by the device |
| **Segment** | `id`, `name`, rule | Computed, never sent by the SDK |
| **Notification** | `id`, campaign, payload | |
| **Event** | `installationId`, `name`, `occurredAt`, properties | |

### The three identifiers

Keeping these distinct is the backend's most important job:

| | Identifies | Changes when |
| --- | --- | --- |
| **Installation ID** | An app on a device | Never (app reinstall or explicit reset only) |
| **Push token** | A delivery route | The transport decides: reinstall, restore, data clear |
| **User ID** | A person | Login and logout |

A user with a phone and a tablet has one user id and two installations. A device whose token
rotates keeps its installation id, its tags and its history. A device whose user logs out keeps
everything except the user association.

## Segments

Tags are attributes; segments are backend-defined groups over them.

```
Tags on an installation        Segment "Premium Pakistan Users"
  subscription = premium         subscription == premium
  country      = PK        -->   AND country == PK
  language     = en
```

Membership is recomputed by the backend when tags change. The SDK never evaluates a rule, but it
can read the result: `GET /v1/installations/{id}/segments` backs `ARYPush.getSegments()`, so an
application can branch on membership without duplicating the rule.

That read is a convenience, not a contract the campaign system depends on. Targeting happens
entirely server-side.

## Campaigns

Not implemented here, and not implemented client-side by design. The architecture leaves room for:

```
Dashboard -> Campaign -> Segment -> ARY Push API -> FCM / APNs -> device
```

## Sending

The backend holds the credentials and does the sending:

| Platform | Credential | Where it lives |
| --- | --- | --- |
| Android | FCM service account (HTTP v1 API) | Backend secret store |
| iOS | APNs authentication key (`.p8`) or certificate | Backend secret store |

None of these ever appear in an APK or an IPA. See [SECURITY.md](SECURITY.md).

### Payload conventions the SDK understands

```json
{
  "ary_push": "1",
  "notification_id": "order-42",
  "title": "Order shipped",
  "body": "Your order is on its way",
  "image_url": "https://cdn.ary.com/order.png",
  "channel_id": "orders",
  "action": "open_order",
  "orderId": "12345",
  "actions": "[{\"id\":\"track\",\"title\":\"Track\"}]"
}
```

| Key | Purpose |
| --- | --- |
| `ary_push` | Marks the message as the SDK's, so an app with its own messaging service can tell them apart |
| `notification_id` | **Stable identity.** Send this. It is what makes deduplication work across resends |
| `title`, `body` | Content for data-only messages |
| `image_url` | Big-picture image |
| `channel_id` | Android channel; falls back to the SDK's if it does not exist |
| `action` plus your own keys | Delivered verbatim to the host application |
| `actions` | JSON array of action buttons |

**Send data-only messages.** They are the only shape whose behaviour is identical across
foreground, background and terminated, and across both platforms. See
[NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md).

## What the backend must get right

**Idempotency.** Every endpoint is retried by a durable client queue. Registration keyed on
installation id; token, identity, tag and topic writes as merges or replacements, never deltas.

**Correct status codes.** The SDK's retry policy is driven entirely by them. A transient failure
returned as 400 is dropped and never retried; a permanent failure returned as 503 is retried
until the attempt budget runs out. Both are silent data loss.

**404 on an unknown installation.** The SDK responds by re-registering, which self-heals a device
whose record was pruned.

**Logout keeps the device.** `DELETE /installations/{id}/user` must not unregister anything else.

**Token uniqueness.** A push token can move between installations after a device restore. The
newest registration for a token wins; the older installation should have its token cleared rather
than duplicated, or the same device gets every message twice.

**Retry-After on 429.** Cheaper for everyone than the SDK's own backoff guessing.

## Reachability

`notificationsEnabled` tells the backend whether a device can still be reached. It changes
silently on the device (a user turning notifications off in Settings), so the SDK samples it at
every launch and reports changes.

Suppressing sends to unreachable installations is worth doing: it keeps delivery rates honest and
avoids paying to send messages nobody can receive.

## Environments

```
https://push-api-dev.ary.com
https://push-api-qa.ary.com
https://push-api-staging.ary.com
https://push-api.ary.com
```

Selected by the consuming application, never compiled into the SDK. Each environment should have
its own applications, installations and credentials: a staging campaign that reaches production
devices is a bad afternoon.
