require 'xcodeproj'

# React Native handles the bridge's declared SPM dependency. Register its remote
# package with the app workspace too, without linking the static product twice.
module NotificationDevExpo
  URL = 'https://github.com/TheBoldApps/notification.dev-SDKs.git'.freeze
  VERSION = '0.2.0'.freeze

  def self.attach(project, target)
    local_path = ENV['NOTIFICATION_DEV_SWIFT_PACKAGE_PATH']
    package = project.root_object.package_references.find do |ref|
      local_path ? (ref.respond_to?(:relative_path) && ref.relative_path == local_path) : (ref.respond_to?(:repositoryURL) && ref.repositoryURL == URL)
    end
    unless package
      if local_path
        raise 'notification.dev: local Swift package must contain Package.swift' unless File.file?(File.join(local_path, 'Package.swift'))
        package = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
        package.relative_path = local_path
      else
        package = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
        package.repositoryURL = URL
      end
      project.root_object.package_references << package
    end
    package.requirement = { 'kind' => 'exactVersion', 'version' => VERSION } unless local_path
    if local_path
      # A previous release-based pod install may already have registered this package.
      project.root_object.package_references.to_a.each do |reference|
        next unless reference.respond_to?(:repositoryURL) && reference.repositoryURL == URL
        project.targets.each do |app_target|
          app_target.package_product_dependencies.each do |product|
            product.package = package if product.package == reference
          end
        end
        reference.remove_from_project
      end
    end
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
