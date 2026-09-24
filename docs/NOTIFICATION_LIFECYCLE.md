# Notification lifecycle

What actually happens to a message, in each application state, on each platform. This page is
deliberately blunt about the cases the SDK cannot control: an SDK that promises behaviour the OS
does not guarantee is worse than one that documents the gap.

## The three states

| State | Meaning |
| --- | --- |
| **Foreground** | Your app is on screen |
| **Background** | Your process is alive but not on screen |
| **Terminated** | Your process is not running: swiped away, killed for memory, or never started since boot |

## The rule that surprises people

> **A notification message displayed by the system while your app is not in the foreground never
> reaches your code until the user taps it.**

This is true on both platforms and is not something an SDK can change. If you need code to run
on delivery, send a **data-only** message.

| Payload | Foreground | Background | Terminated |
| --- | --- | --- | --- |
| Notification (Android `notification` block, iOS `aps.alert` alone) | Delivered to the SDK; SDK renders per policy | System renders; **your code does not run** | System renders; **your code does not run** |
| Data-only (Android data only, iOS `content-available: 1`) | Delivered to the SDK; SDK renders | Delivered to the SDK, subject to the limits below | Android: process started, delivered. iOS: best effort only |
| Both | Delivered to the SDK | System renders the visible part; data reaches iOS code only via `content-available` | As above |

**Recommendation: send data-only messages.** They are the only shape whose behaviour the SDK can
make identical across foreground, background and terminated, and across the two platforms.

## Foreground

You control this with `foregroundDisplay`:

| Policy | Notification shown | `onNotificationReceived` |
| --- | --- | --- |
| `SHOW` (default) | Yes | Yes |
| `EVENT_ONLY` | No | Yes |
| `SUPPRESS` | No | No |

Use `EVENT_ONLY` when your app already shows its own in-app banner: otherwise the user sees the
same message twice, once from you and once from the SDK.

On iOS the SDK's presentation options are **unioned** with whatever the host's own
`UNUserNotificationCenterDelegate` returns, so neither side can silence the other. With `SHOW` the
SDK asks for `.banner`, `.list`, `.sound` and `.badge` every time iOS asks: deduplication only
decides whether `onNotificationReceived` fires, never whether the banner appears, so a push that
also carries `content-available` is no longer swallowed.

On Android a foreground message is shown as a heads-up banner. That needs two things the SDK now
arranges itself:

- **The message has to reach the SDK.** FCM starts one `FirebaseMessagingService` per message, and
  another library's service — `firebase_messaging`'s, in every Flutter app using it — outranks the
  SDK's. The SDK also registers a receiver for the broadcast Google Play services sends each
  message as, which reaches every receiver, so it sees foreground messages whichever service won.
  Nothing is handled twice: both paths share one deduplication. No `flutter_local_notifications`
  or `onMessage` listener is needed to show them.
- **The channel has to be high importance.** See [Channels](#channels-android).

With `logLevel` at `info` or below, each foreground banner logs
`[ARYPush] Foreground notification intercepted. Displaying native banner.`

## Background

**Android.** A data message wakes the process and reaches `onMessageReceived`. The SDK renders it
and dispatches the received event. Handling is synchronous, because the system only guarantees
the process stays alive for the duration of that callback.

Real limits, none of which the SDK can lift:

- Doze and App Standby delay normal-priority messages. `high` priority messages are delivered
  promptly, but abusing priority gets an app throttled.
- Aggressive OEM battery managers (Xiaomi, Huawei, Oppo, Samsung and others) can stop background
  delivery entirely for an app the user has not whitelisted. This is the most common cause of
  "notifications work on my Pixel but not on the tester's phone".
- Force-stopped apps receive nothing until the user launches them again.

**iOS.** A `content-available: 1` message may wake the app for background execution. The word is
**may**: iOS budgets these by battery state, usage patterns and rate. Silent notifications are not
a delivery guarantee and must never be the only way a piece of state reaches the device.

## Terminated, and the tap that matters

This is the case an SDK earns its keep on. The user taps a notification for an app that is not
running:

```
Notification tapped
        |
Operating system starts the process
        |
ARYPush initializes (from its own service or trampoline, before any host code)
        |
The open event exists, and no listener is attached yet
        |
The event is PERSISTED
        |
Host code runs and attaches its listener
        |
The event is REPLAYED to it
        |
onNotificationOpened -> your router -> the right screen
```

Without the persist-and-replay step, the user taps an order notification and lands on the home
screen. The SDK writes the pending open **durably** (a synchronous commit, not a lazy write),
because the tap usually happens while the process is still starting and a lost write is a lost
event.

**What you must do:** attach your open listener as early as possible.

| Platform | Where |
| --- | --- |
| Android | `Application.onCreate` |
| iOS | `application(_:didFinishLaunchingWithOptions:)` |
| Flutter | Before `runApp`, after `ensureInitialized()` |

An `Activity.onCreate` listener may be too late. The SDK holds the event for the *first* listener
that attaches, so it will still arrive there, but a second listener attached later will not see
it.

Frameworks that prefer to pull rather than subscribe can call `consumeInitialNotification()`
(`getInitialNotification()` in Dart) instead. Use one or the other, not both: the event is
delivered exactly once.

## Platform mechanics of a tap

**Android.** Android 12 forbids starting an Activity from a service or broadcast receiver woken
by a notification, so the SDK's click `PendingIntent` targets an invisible trampoline Activity.
It records the open, dispatches the event, then opens the payload's link if it has one and
otherwise launches your application's own launcher intent, and finishes. It is translucent,
`noHistory`, excluded from recents and has an empty task affinity, so the user never sees it and
it never joins their task stack.

**iOS.** `userNotificationCenter(_:didReceive:)` fires during launch. The SDK's proxy handles it
and forwards to your delegate, so both run.

## Links

A payload carrying `url`, `deep_link`, `link` or `launch_url` is opened automatically on tap, in
all three states. The first key present wins, in that order.

| | |
| --- | --- |
| Where it opens | Inside the app when the app can handle the link, otherwise wherever the system sends it. On Android the host application is tried first, so there is no chooser |
| What is required | An absolute URL: `https://…`, or a scheme the app registers such as `myapp://order/42`. One without a scheme is ignored and the app is simply brought forward |
| Action buttons | Do **not** open it. An action means something specific the app defined, so those stay host-handled |
| Opened once | The open is deduplicated on message id and action before the link is opened, so one tap opens one URL however many times the system redelivers the intent |
| `onNotificationOpened` | Still fires, with the same payload. The value is on the notification as `launchUrl`, so an app that would rather route the link itself can |

### How each platform keeps the link inside the app

**Android.** The SDK builds `Intent(ACTION_VIEW, uri)` with `setPackage(<your package>)` first, so
Android can only resolve it against your own manifest's intent filters: an App Link or custom
scheme you declare opens that screen with no chooser. Only when nothing in your app matches is
the package restriction dropped and the link opened by whichever app can — the browser, for a
plain web link.

**iOS.** A custom scheme your app registers is opened with `UIApplication.open` and comes back to
your `application(_:open:options:)` / `scene(_:openURLContexts:)`. The SDK no longer checks
`canOpenURL` first: that answers false for any scheme missing from `LSApplicationQueriesSchemes`,
your own included, and was blocking exactly these links.

A **Universal Link to your own domain is different**: iOS does not route an app's Universal Link
back to the app that opens it, so `UIApplication.open` sends it to Safari. List your domains and
the SDK delivers those links to you directly, as the `NSUserActivity` iOS would have delivered —
`application(_:continue:restorationHandler:)`, or `scene(_:continue:)` for a scene-based app, and
so Flutter's `app_links` and similar plugins receive it as usual:

```swift
ARYPushConfig(universalLinkDomains: ["ary.com", "*.ary.com"])
```

```dart
ARYPushConfig(universalLinkDomains: <String>['ary.com', '*.ary.com'])
```

or, with no code, in `Info.plist`:

```xml
<key>ARYPush</key>
<dict>
    <key>UniversalLinkDomains</key>
    <array><string>ary.com</string></array>
</dict>
```

Use the same hosts as your `applinks:` Associated Domains entitlement; that spelling is accepted
too. A link on any other host still goes to `UIApplication.open`, which opens another app's
Universal Link in that app and a plain web link in Safari.

Each tap logs `[ARYPush] Notification tap intercepted. Parsing link keys.`, and each link
`[ARYPush] Attempting internal AppLink/Universal Link routing for URL: <url>`, at `info`.

### Both message shapes work

A link opens whether the SDK rendered the notification or the system did, through two paths:

| The notification was rendered by | How the tap reaches the SDK |
| --- | --- |
| **The SDK** (data-only message) | Its click intent targets the SDK's trampoline Activity |
| **The system** (`notification` block, app backgrounded) | FCM never calls the SDK, and the tap launches your own Activity — so the SDK reads the message out of that launch intent instead. iOS needs no equivalent: a tap always reaches the notification delegate |

Both end at the same deduplicated open, so a message that somehow arrives by both paths opens its
link once.

For this to work from a **terminated** start, the SDK is brought up when the Flutter plugin
registers rather than when Dart calls `initialize()`. Dart runs after the Activity exists and,
on iOS, after the system has already delivered the tap — too late to catch it. The `initialize()`
your app calls afterwards reconfigures the SDK in place, as it always has.

This is the one exception to "the SDK never navigates". Everything else in the payload is handed
to your handler untouched.

A **dismissal** is not an open. Swiping a notification away produces no event: reporting it as an
open would inflate engagement and, worse, send the user somewhere they did not ask to go.

## Duplicates

Duplicates are normal, not exceptional:

- FCM guarantees at-least-once delivery and resends after a transport hiccup.
- An app with both its own messaging service and the SDK's can see one message twice.
- iOS can deliver to a notification service extension and to the app.
- A notification can be re-handled after a process restart.

The SDK keeps a **bounded, persistent LRU** of message identities. Identity is resolved in this
order, and the order matters:

1. A sender-supplied `notification_id`, which groups resends of one logical message.
2. The transport's own id (`messageId`, `gcm.message_id`), unique per delivery attempt.
3. A content hash, the last resort, and what makes deduplication work for senders that supply
   neither.

Receipts and opens are tracked separately, and opens are keyed on the action too, so tapping the
body and then an action button are two events while a redelivered intent is one.

## Channels (Android)

The SDK creates and owns a default channel, `ary_push_alerts`, at `IMPORTANCE_HIGH`: anything
lower posts to the shade silently and never shows a banner while the app is open. A payload may
name its own with `channel_id`, whose importance is then yours to choose.

Earlier versions created `ary_push_default` at `IMPORTANCE_DEFAULT`. Android never lets an app
raise an existing channel's importance, so the SDK creates the new channel and deletes that one;
a channel id you configured yourself is never touched.

If a payload names a channel that does not exist, API 26 and above **silently drops the
notification**. The SDK falls back to its own channel instead: a notification on a slightly wrong
channel beats no notification at all, and the fallback is logged.

Channel importance chosen by the user always wins, and re-creating an existing channel is a
no-op, so the SDK can never override a preference.

## Actions

```json
{
  "notification_id": "order-42",
  "title": "Order shipped",
  "body": "Your order is on its way",
  "action": "open_order",
  "orderId": "12345",
  "actions": "[{\"id\":\"track\",\"title\":\"Track\"},{\"id\":\"cancel\",\"title\":\"Cancel\"}]"
}
```

The SDK renders the buttons and delivers `actionId` on the open event. It never interprets them:
what `track` means is your application's business.

On iOS, action buttons come from a registered `UNNotificationCategory` referenced by the payload's
`category`. Register your categories exactly as you always would.
