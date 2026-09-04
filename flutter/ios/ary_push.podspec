#
# Flutter plugin podspec for the ARY Push SDK.
#
# This pod carries the native iOS SDK with it rather than declaring a dependency on a separate
# ARYPush pod. That is deliberate: CocoaPods cannot resolve a git-sourced dependency named by a
# podspec, so a separate pod would force every Flutter application to add a `pod 'ARYPush',
# :git => ...` line to its Podfile. Vendoring keeps the promise that a Flutter integration is
# one pubspec entry and nothing else.
#
# The sources are copied in by prepare_command rather than referenced with a relative path,
# because CocoaPods only reliably compiles files inside the pod root. `pub` checks out the whole
# repository into its cache, so ../../ios/Sources is present whether the plugin came from a git
# dependency or a local path.
#
Pod::Spec.new do |s|
  s.name             = 'ary_push'
  s.version          = '1.0.0'
  s.summary          = 'Flutter bridge for the private ARY Push SDK.'
  s.description      = 'MethodChannel and EventChannel bridge over the native ARYPush SDK.'
  s.homepage         = 'https://github.com/arysoftware/ary-push-sdk'
  s.license          = { :type => 'Proprietary', :file => '../../LICENSE' }
  s.author           = { 'ARY' => 'apps@ary.com' }
  s.source           = { :path => '.' }

  # Copies the native SDK in beside the bridge. Idempotent: safe to re-run on every pod install.
  s.prepare_command = <<-CMD
    set -e
    rm -rf Classes/ARYPushCore
    if [ -d ../../ios/Sources/ARYPush ]; then
      mkdir -p Classes/ARYPushCore
      cp -R ../../ios/Sources/ARYPush/. Classes/ARYPushCore/
    else
      echo "warning: ../../ios/Sources/ARYPush not found; expecting a separate ARYPush pod"
    fi
  CMD

  s.source_files     = 'Classes/**/*.swift'
  s.dependency 'Flutter'
  s.frameworks       = 'UIKit', 'UserNotifications', 'Network'

  s.platform = :ios, '13.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.9'
end
