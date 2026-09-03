import 'package:ary_push/ary_push.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('PushNotification.fromMap', () {
    test('reads every field the native SDKs send', () {
      final PushNotification notification = PushNotification.fromMap(
        const <Object?, Object?>{
          'id': 'order-42',
          'title': 'Order shipped',
          'body': 'On its way',
          'imageUrl': 'https://cdn.ary.com/a.png',
          'data': <Object?, Object?>{
            'action': 'open_order',
            'orderId': '12345'
          },
          'receivedAt': 1700000000000,
          'channelId': 'orders',
          'threadId': 'orders-thread',
          'categoryId': 'ORDER',
          'collapseKey': 'orders',
          'actionId': 'track',
          'wasForeground': true,
        },
      );

      expect(notification.id, 'order-42');
      expect(notification.title, 'Order shipped');
      expect(notification.body, 'On its way');
      expect(notification.imageUrl, 'https://cdn.ary.com/a.png');
      expect(notification.data['orderId'], '12345');
      expect(notification.action, 'open_order');
      expect(
        notification.receivedAt,
        DateTime.fromMillisecondsSinceEpoch(1700000000000),
      );
      expect(notification.channelId, 'orders');
      expect(notification.threadId, 'orders-thread');
      expect(notification.categoryId, 'ORDER');
      expect(notification.collapseKey, 'orders');
      expect(notification.actionId, 'track');
      expect(notification.wasForeground, isTrue);
    });

    test('tolerates a minimal payload without throwing', () {
      // A notification that cannot be parsed must never take down the app that received it.
      final PushNotification notification =
          PushNotification.fromMap(const <Object?, Object?>{'id': 'n1'});

      expect(notification.id, 'n1');
      expect(notification.title, isNull);
      expect(notification.data, isEmpty);
      expect(notification.actionId, isNull);
      expect(notification.wasForeground, isFalse);
    });

    test('falls back to now when the timestamp is missing or unusable', () {
      final DateTime before = DateTime.now();

      final PushNotification notification = PushNotification.fromMap(
        const <Object?, Object?>{'id': 'n1', 'receivedAt': 'not a number'},
      );

      expect(
        notification.receivedAt
            .isBefore(before.subtract(const Duration(seconds: 1))),
        isFalse,
      );
    });

    test('accepts a floating point timestamp, which iOS may send', () {
      final PushNotification notification = PushNotification.fromMap(
        const <Object?, Object?>{'id': 'n1', 'receivedAt': 1700000000000.0},
      );

      expect(
        notification.receivedAt,
        DateTime.fromMillisecondsSinceEpoch(1700000000000),
      );
    });

    test('equality is keyed on identity, action and time', () {
      final DateTime now = DateTime.now();
      final PushNotification a =
          PushNotification(id: 'n1', receivedAt: now, actionId: 'track');
      final PushNotification b =
          PushNotification(id: 'n1', receivedAt: now, actionId: 'track');
      final PushNotification c =
          PushNotification(id: 'n1', receivedAt: now, actionId: 'dismiss');

      expect(a, equals(b));
      expect(a, isNot(equals(c)));
    });
  });

  group('PushPermissionStatus', () {
    test('parses every value the native SDKs report', () {
      expect(
        PushPermissionStatus.fromWire('granted'),
        PushPermissionStatus.granted,
      );
      expect(
        PushPermissionStatus.fromWire('notDetermined'),
        PushPermissionStatus.notDetermined,
      );
      expect(
        PushPermissionStatus.fromWire('provisional'),
        PushPermissionStatus.provisional,
      );
      expect(
        PushPermissionStatus.fromWire('restricted'),
        PushPermissionStatus.restricted,
      );
    });

    test('an unknown or absent value never claims authorization', () {
      // A native SDK newer than this package must not be able to report a status that Dart
      // silently reads as "granted".
      expect(
        PushPermissionStatus.fromWire('quantum'),
        PushPermissionStatus.notDetermined,
      );
      expect(
        PushPermissionStatus.fromWire(null),
        PushPermissionStatus.notDetermined,
      );
      expect(PushPermissionStatus.fromWire(null).isAuthorized, isFalse);
    });

    test('isAuthorized covers quiet delivery as well as an explicit grant', () {
      expect(PushPermissionStatus.granted.isAuthorized, isTrue);
      expect(PushPermissionStatus.provisional.isAuthorized, isTrue);
      expect(PushPermissionStatus.ephemeral.isAuthorized, isTrue);
      expect(PushPermissionStatus.denied.isAuthorized, isFalse);
      expect(PushPermissionStatus.restricted.isAuthorized, isFalse);
      expect(PushPermissionStatus.notDetermined.isAuthorized, isFalse);
    });
  });

  group('PushProvider', () {
    test('parses the wire value and defaults to FCM', () {
      expect(PushProvider.fromWire('apns'), PushProvider.apns);
      expect(PushProvider.fromWire('fcm'), PushProvider.fcm);
      expect(PushProvider.fromWire(null), PushProvider.fcm);
    });
  });
}
