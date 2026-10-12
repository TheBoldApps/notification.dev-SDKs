require 'xcodeproj'

# React Native handles the bridge's declared SPM dependency. Register its remote
# package with the app workspace too, without linking the static product twice.
module NotificationDevExpo
  URL = 'https://github.com/TheBoldApps/notification.dev-SDKs.git'.freeze
  VERSION = '0.2.0'.freeze

  def self.attach(project, target)
    package = project.root_object.package_references.find { |ref| ref.respond_to?(:repositoryURL) && ref.repositoryURL == URL }
    unless package
      package = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
      package.repositoryURL = URL
      project.root_object.package_references << package
    end
    package.requirement = { 'kind' => 'exactVersion', 'version' => VERSION }
    # The bridge archive already includes the static Swift package product.
    # Adding a product dependency here would produce duplicate symbols.
    target.build_configurations.each do |configuration|
      configuration.build_settings['EXCLUDED_ARCHS[sdk=iphonesimulator*]'] = '$(inherited) x86_64'
    end
  end

  def self.install_swift_package(installer)
    bridge = installer.pods_project.targets.find { |target| target.name == 'ExpoNotificationDev' }
    raise 'notification.dev: ExpoNotificationDev pod is missing; check Expo autolinking' unless bridge
    bridge.build_configurations.each do |configuration|
      configuration.build_settings['EXCLUDED_ARCHS[sdk=iphonesimulator*]'] = '$(inherited) x86_64'
    end
    installer.aggregate_targets.each do |aggregate|
      aggregate.user_targets.select { |target| target.product_type == 'com.apple.product-type.application' }.each do |target|
        attach(aggregate.user_project, target)
      end
      aggregate.user_project.save
    end
  end
end
