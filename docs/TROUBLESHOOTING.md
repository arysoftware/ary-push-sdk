# Troubleshooting

Symptom, cause, fix. Turn logging on first:

```kotlin
ARYPushConfig(enableLogging = true, logLevel = PushLogLevel.DEBUG)
```

Then filter for `ARYPush` in logcat, or the `com.ary.push` subsystem in Console.app.

A healthy launch looks like this:

```
[ARYPush] Initialization started
[ARYPush] Installation ID loaded
[ARYPush] Permission status: GRANTED
[ARYPush] Push token received: dGhp***4n(163) (fcm)
[ARYPush] POST /v1/installations -> 201
[ARYPush] Synced REGISTER_INSTALLATION
```

---

## No push token

**`Firebase is not initialized in this application` (Android)**

The host application has no Firebase configuration. Add `google-services.json` to `app/` and the
`com.google.gms.google-services` plugin. The SDK deliberately does not ship these; see
[FIREBASE.md](FIREBASE.md).

**`APNs registration failed` (iOS)**

In order of likelihood:

1. Running on the Simulator without a paired Mac. Use a device.
2. Push Notifications capability not enabled, so no `aps-environment` entitlement.
3. Provisioning profile does not include push.
4. No network at launch. It retries.

**Token is null but no error is logged**

The token has not been issued yet. It is requested asynchronously during initialization; use
`addTokenRefreshListener` rather than reading it synchronously right after `initialize()`.

## Notifications do not appear

Work down this list in order.

1. **Permission.** `getPermissionStatus()` must report an authorized value. It reflects both the
   runtime permission and the app-level toggle, so `DENIED` can mean "the user turned
   notifications off in Settings", which no callback announces.
2. **The message shape.** A `notification`-block message displayed by the system while your app
   is backgrounded never reaches your code. Send data-only. See
   [NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md).
3. **The foreground policy.** `EVENT_ONLY` and `SUPPRESS` deliberately show nothing.
4. **The channel (Android).** A payload naming a channel that does not exist is silently dropped
   by API 26+. The SDK falls back to its own and logs `Channel ... does not exist`; if you see
   that line, fix the payload or create the channel.
5. **Two messaging services (Android).** If your app declares its own `FirebaseMessagingService`
   as well as the SDK's, exactly one wins and which is not guaranteed. Remove the SDK's and
   forward; see [FIREBASE.md](FIREBASE.md#existing-firebasemessagingservice).
6. **OEM battery management.** Xiaomi, Huawei, Oppo, Vivo and Samsung can stop background
   delivery for an app the user has not whitelisted. Nothing an SDK can do. Test on the OEM your
   users actually have.
7. **Force-stopped.** An app force-stopped from Settings receives nothing until the user launches
   it again.

## Tapping a notification does nothing

**Only when the app was terminated**

Your listener is attached too late. Attach it in `Application.onCreate`, in
`didFinishLaunchingWithOptions`, or before `runApp`.

The SDK persists an open that arrives before any listener exists and replays it to the **first**
listener that attaches. A listener attached after that first one will not see it.

**Always**

Check that a listener is registered at all, and that `initialize()` ran before it. Calling an API
before `initialize()` logs `... called before ARYPush.initialize(); the call was ignored`.

**The app opens but lands on the wrong screen**

Expected: the SDK never navigates. It launches your app and hands you the payload; routing is
your `onNotificationOpened` handler.

## Duplicate notifications

| Cause | Fix |
| --- | --- |
| Your app renders foreground messages too | Set `foregroundDisplay = EVENT_ONLY` |
| Both your messaging service and the SDK's handle the same message | Remove the SDK's, forward from yours |
| `flutter_local_notifications` still showing incoming pushes | Remove that path; keep it for local notifications |
| Sender omits `notification_id` | Send a stable `notification_id`; the content-hash fallback cannot group a payload whose timestamp changes |

## Duplicate events after a hot restart (Flutter)

Should not happen: the plugin detaches native listeners when Dart stops listening. If it does,
check that your own `StreamSubscription`s are cancelled in `dispose()`.

## Backend synchronisation is not happening

**Nothing in the logs at all**

No `backend` configured. The SDK is running on `NoopPushBackend`, which is a valid mode: push
works, nothing is synchronised.

**`Offline; N operation(s) stay queued`**

Working as designed. The queue is durable and drains when connectivity returns.

**`Dropping X: permanent failure HTTP 422`**

The backend rejected the request in a way that retrying cannot fix. Check the request against
[REST_API.md](REST_API.md). Common causes: an unknown `applicationId`, or a token the provider
has already invalidated.

**`Dropping X after N attempt(s)`**

The attempt budget ran out, usually a sustained outage. Local state is still correct; the device
re-registers on the next launch because the registration hash no longer matches.

**Requests are 401 and never succeed**

`AuthProvider.getAccessToken()` is returning an expired token and `refreshAccessToken()` is
returning false. The SDK retries once after a successful refresh and never loops.

## The same registration is sent on every launch

It should not be: the SDK hashes the payload and skips unchanged registrations. If you see
repeated `POST /v1/installations`, something in the payload is changing every launch. Check
`appVersion`, `locale` and `timezone`, and check that your backend is not returning a non-2xx
status that prevents the hash from being stored.

## Android notification icon is a grey square

Android renders a non-silhouette icon as a solid square. Supply a white-on-transparent icon:

```kotlin
ARYPushConfig(smallIconResId = R.drawable.ic_notification)
```

Without one the SDK falls back to your launcher icon, which is usually not a silhouette.

## Build failures

**`Could not find com.ary:ary-push`**

The private Maven repository is not declared, or the credentials are missing. See
[ANDROID.md](ANDROID.md#dependency).

**Duplicate class or Firebase version conflict**

The SDK depends on `firebase-messaging` as `implementation`, so your version wins through normal
resolution. Pin a Firebase BoM in your app if you need a specific one.

**`MissingPluginException` (Flutter)**

Full stop and rebuild after adding the plugin; a hot restart does not register new plugins.

## Getting help

Include: SDK version, platform and OS version, whether the app was foreground, background or
terminated, the message payload with anything sensitive removed, and SDK logs at `DEBUG`. Tokens
are already masked in the logs, so they are safe to attach.
