require 'json'
package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name = 'ExpoNotificationDev'
  s.version = package['version']
  s.summary = package['description']
  s.description = package['description']
  s.license = package['license']
  s.author = 'The Bold Apps, LLC'
  s.homepage = 'https://notification.dev'
  s.platform = :ios, '16.4'
  s.swift_version = '5.9'
  s.source = { :git => 'https://github.com/TheBoldApps/notification.dev-SDKs.git', :tag => s.version.to_s }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.source_files = '*.swift'
  # React Native's SPM integration adds this release to the bridge target.
  # `pod ipc spec` (used for autolinking discovery) runs outside the Podfile.
  if defined?(spm_dependency)
    spm_dependency(s,
      url: 'https://github.com/TheBoldApps/notification.dev-SDKs.git',
      requirement: { kind: 'exactVersion', version: '0.2.0' },
      products: ['NotificationDev'])
  end
end
