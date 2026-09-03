#
# ARY Push SDK for iOS, as a CocoaPods spec.
#
# Swift Package Manager is the primary distribution channel for native iOS applications
# (see Package.swift). This spec exists because Flutter applications build their iOS side with
# CocoaPods, and the ary_push plugin has to be able to depend on the SDK from there.
#
# The pod is private: it is never pushed to the CocoaPods trunk. Consumers reference it from the
# ARY's private spec repository, or directly from the private Git repository:
#
#   pod 'ARYPush', :git => 'git@github.com:arysoftware/ary-push-sdk.git',
#                      :tag => 'v1.0.0', :branch => nil
#
Pod::Spec.new do |s|
  s.name             = 'ARYPush'
  s.version          = '1.0.0'
  s.summary          = 'Private ARY push notification SDK for iOS.'
  s.description      = <<-DESC
    Handles the complete client-side push lifecycle: authorization, APNs registration and token
    management, installation identity, notification delivery and taps, tags, topics, user
    identity, backend synchronisation with an offline queue, retries and deduplication.
  DESC
  s.homepage         = 'https://github.com/arysoftware/ary-push-sdk'
  s.license          = { :type => 'Proprietary', :file => '../LICENSE' }
  s.author           = { 'ARY' => 'mobile@ary.com' }
  s.source           = { :git => 'git@github.com:arysoftware/ary-push-sdk.git', :tag => "v#{s.version}" }

  s.ios.deployment_target = '13.0'
  s.swift_version    = '5.9'

  s.source_files     = 'Sources/ARYPush/**/*.swift'
  s.frameworks       = 'UIKit', 'UserNotifications', 'Network'

  # No third-party dependencies on purpose: the SDK must never force a Firebase version, a
  # networking library or an analytics SDK onto the host application's dependency graph.
end
