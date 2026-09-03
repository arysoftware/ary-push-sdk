#
# Flutter plugin podspec for the ARY Push SDK.
#
# This pod is a bridge, not an implementation: it depends on the ARYPush pod and forwards to
# it. There is no notification handling here.
#
Pod::Spec.new do |s|
  s.name             = 'ary_push'
  s.version          = '1.0.0'
  s.summary          = 'Flutter bridge for the private ARY Push SDK.'
  s.description      = 'Thin MethodChannel and EventChannel bridge over the native ARYPush SDK.'
  s.homepage         = 'https://github.com/ary/ary-push-sdk'
  s.license          = { :type => 'Proprietary', :file => '../../LICENSE' }
  s.author           = { 'ARY' => 'mobile@ary.com' }
  s.source           = { :path => '.' }

  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'

  # The native SDK. Host applications resolve this from ARY's private spec repository,
  # or add the following to their ios/Podfile so it comes straight from the private Git
  # repository:
  #
  #   pod 'ARYPush', :git => 'git@github.com:ary/ary-push-sdk.git', :tag => 'v1.0.0'
  #
  s.dependency 'ARYPush', '~> 1.0'

  s.platform = :ios, '13.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.9'
end
