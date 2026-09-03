# Firebase coexistence

Most applications that adopt this SDK already use Firebase. This page is about not breaking them.

## The rules

1. **The SDK never ships Firebase configuration.** No `google-services.json`, no
   `GoogleService-Info.plist`, no service account, no server key, no `.p8`. Each host application
   provides its own.
2. **The SDK never initializes a second `FirebaseApp`.** It attaches to whatever the host has
   already set up. If nothing has, it logs one clear error and keeps running without a token.
3. **The SDK never overwrites host configuration.** Not the default app, not the sender id, not
   the project.

The same SDK version works against different Firebase projects in different applications:

```
Application A  ->  Firebase project A
Application B  ->  Firebase project B
Application C  ->  Firebase project C
```

Nothing project-specific is compiled into the SDK, so no rebuild is required.

## Android setup

The host application provides these, exactly as it would without the SDK:

```kotlin
// project build.gradle.kts
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}
```

Plus `app/google-services.json` from the Firebase console.

The SDK depends on `com.google.firebase:firebase-messaging` as an `implementation` dependency,
so it does not force a version on you. If your app pins a Firebase BoM, normal Gradle version
resolution applies and your version wins:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
implementation("com.google.firebase:firebase-messaging")
```

### Existing FirebaseMessagingService

This is the case that needs a decision from you, and the SDK will not make it silently.

**The problem.** FCM delivers `com.google.firebase.MESSAGING_EVENT` to exactly one service.
When two are declared in the merged manifest, which one wins depends on manifest merge order.
It is not something an SDK can control, and an SDK that pretends otherwise produces an app whose
notifications work until an unrelated dependency changes the merge order.

**The fix.** Keep your service. Remove the SDK's. Forward two calls.

```xml
<!-- your AndroidManifest.xml -->
<service
    android:name="com.ary.push.messaging.ARYPushFirebaseMessagingService"
    tools:node="remove" />
```

```kotlin
class AppMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Returns true only for messages sent through the ARY push backend. Your own
        // operational messages fall through to the handling you already had.
        if (ARYPushMessaging.handleMessage(this, message)) return

        handleYourOwnMessage(message)
    }

    override fun onNewToken(token: String) {
        // Safe alongside your own registration: the SDK keeps its own copy and ignores
        // unchanged values.
        ARYPushMessaging.handleNewToken(this, token)

        registerTokenWithYourBackend(token)
    }
}
```

`ARYPushMessaging` is the one seam host applications are asked to implement, and it is
stable: it will not change within a major version.

A message is recognised as the SDK's when it carries the `ary_push` marker the backend sets,
or the conventional `notification_id` key. Everything else returns false and is yours.

See the working sample in `android/sample-existing-firebase/`.

## iOS setup

The host application provides the Push Notifications capability, the `aps-environment`
entitlement, and its APNs key or certificate uploaded to whichever service sends.

The SDK does **not** depend on Firebase on iOS. It talks to APNs directly and has no Firebase
code in the package at all, which keeps it out of your dependency graph.

### If your iOS app uses Firebase Messaging

An APNs device token and an FCM registration token are **different values**. Firebase issues its
token after being given the APNs one, and a backend that sends through Firebase must be given the
Firebase token. Sending to the wrong one reaches nobody.

Tell the SDK which one to use with one line:

```swift
extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken else { return }
        ARYPush.setFCMToken(fcmToken)
    }
}
```

The SDK then records `fcm` as the provider and synchronises the FCM token. It keeps the APNs
token too, readable via `ARYPush.getAPNsToken()`, and stops overwriting the active token with
it.

Applications that talk to APNs directly never call this and get `apns` as the provider.

## Flutter coexistence

Applications using `firebase_core` and `firebase_messaging` keep both. The plugin bridges to the
native SDKs, which attach to the Firebase the app already configured.

Two things to get right:

**Avoid double display.** If you already show your own in-app banner from
`FirebaseMessaging.onMessage`, set the SDK to deliver the event without rendering:

```dart
await ARYPush.initialize(
  const ARYPushConfig(foregroundDisplay: ForegroundDisplayPolicy.eventOnly),
);
```

**Avoid double handling.** `FirebaseMessaging.onMessageOpenedApp` and
`ARYPush.onNotificationOpened` will both fire for a message the SDK recognises. Route from
one of them, not both, or your user gets navigated twice.

Your `FirebaseMessaging.onBackgroundMessage` handler keeps working and is not touched.

See the working sample in `flutter/example-firebase/`.

## Exactly what the SDK does and does not do

| | Android | iOS |
| --- | --- | --- |
| Initializes Firebase | No | No |
| Requires Firebase | Yes (FCM is the transport) | No |
| Declares a messaging service | Yes, removable | Not applicable |
| Replaces your messaging service | No | Not applicable |
| Replaces your notification delegate | Not applicable | No, it proxies |
| Reads your `google-services.json` | Only through the Firebase SDK, as any app does | No |
| Contains any Firebase credential | No | No |

## Verifying coexistence

1. Send a message through the ARY push backend. Both your handler and the SDK's should see
   it exactly once, and the user should see one notification.
2. Send a message through your own existing pipeline. Your handler sees it; the SDK ignores it.
3. Clear app data, relaunch, and confirm both your token registration and the SDK's run.
4. Check the SDK logs for `Firebase is not initialized`, which means the host configuration is
   missing.
