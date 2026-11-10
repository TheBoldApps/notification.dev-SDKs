require 'xcodeproj'
require 'pathname'

# Contributor helper for the Flutter and Expo examples. Re-run after Expo prebuild.
raise 'Usage: ruby attach.rb PATH_TO_XCODEPROJ [PATH_TO_NATIVE_IOS_SDK]' unless (1..2).cover?(ARGV.length)
project = Xcodeproj::Project.open(ARGV[0])
app = project.targets.find { |target| target.product_type == 'com.apple.product-type.application' }
raise 'No application target found' unless app
name = 'NotificationDevLiveActivityWidget'
# Match the bridges' published dependency unless native development is explicit.
if ARGV[1]
  sdk = Pathname.new(ARGV[1]).realpath
  raise 'Native SDK Package.swift is missing' unless sdk.join('Package.swift').file?
  package = project.root_object.package_references.find { |reference| reference.isa == 'XCLocalSwiftPackageReference' && reference.relative_path == sdk.relative_path_from(project.project_dir.realpath).to_s }
  package ||= project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
  package.relative_path = sdk.relative_path_from(project.project_dir.realpath).to_s
else
  url = 'https://github.com/TheBoldApps/notification.dev-SDKs.git'
  package = project.root_object.package_references.find { |reference| reference.isa == 'XCRemoteSwiftPackageReference' && reference.repositoryURL == url }
  package ||= project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
  package.repositoryURL = url
  package.requirement = { 'kind' => 'exactVersion', 'version' => '0.3.0' }
end
project.root_object.package_references << package unless project.root_object.package_references.include?(package)
existing = project.targets.find { |target| target.name == name }
if existing
  product = existing.package_product_dependencies.find { |dependency| dependency.product_name == 'NotificationDevActivityKit' }
  previous = product.package
  product.package = package
  if previous && previous != package && project.objects.grep(Xcodeproj::Project::Object::XCSwiftPackageProductDependency).none? { |dependency| dependency.package == previous }
    previous.remove_from_project
  end
end
unless project.targets.any? { |target| target.name == name }
  target = project.new_target(:app_extension, name, :ios, '16.2')
  group = project.main_group.new_group(name, Pathname.new(__dir__).realpath.relative_path_from(project.project_dir.realpath).to_s)
  target.source_build_phase.add_file_reference(group.new_file('DefaultActivityWidget.swift'))
  group.new_file('Info.plist')
  product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
  product.package = package
  product.product_name = 'NotificationDevActivityKit'
  target.package_product_dependencies << product
  build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
  build_file.product_ref = product
  target.frameworks_build_phase.files << build_file
  # Use SDKROOT rather than pinning the local Xcode SDK version.
  target.frameworks_build_phase.files.each do |file|
    next unless file.file_ref && file.file_ref.path.end_with?('/Foundation.framework')
    file.file_ref.path = 'System/Library/Frameworks/Foundation.framework'
    file.file_ref.source_tree = 'SDKROOT'
  end
  target.build_configurations.each do |configuration|
    parent = app.build_configurations.find { |item| item.name == configuration.name } || app.build_configurations.first
    settings = parent.build_settings
    configuration.build_settings.merge!({
      'APPLICATION_EXTENSION_API_ONLY' => 'YES', 'CODE_SIGN_STYLE' => 'Automatic',
      'GENERATE_INFOPLIST_FILE' => 'NO', 'INFOPLIST_FILE' => File.join(group.path, 'Info.plist'),
      'PRODUCT_BUNDLE_IDENTIFIER' => settings.fetch('PRODUCT_BUNDLE_IDENTIFIER') + '.live-activity',
      'SWIFT_VERSION' => '5.0', 'SKIP_INSTALL' => 'YES', 'TARGETED_DEVICE_FAMILY' => '1,2',
      'MARKETING_VERSION' => settings.fetch('MARKETING_VERSION', '1.0.0'),
      'CURRENT_PROJECT_VERSION' => settings.fetch('CURRENT_PROJECT_VERSION', '1'),
      'LD_RUNPATH_SEARCH_PATHS' => ['$(inherited)', '@executable_path/Frameworks', '@executable_path/../../Frameworks']
    })
    configuration.build_settings['DEVELOPMENT_TEAM'] = settings['DEVELOPMENT_TEAM'] if settings['DEVELOPMENT_TEAM']
  end
  app.add_dependency(target)
  embed = app.copy_files_build_phases.find { |phase| phase.name == 'Embed App Extensions' }
  unless embed
    embed = app.new_copy_files_build_phase('Embed App Extensions')
    embed.dst_subfolder_spec = '13'
  end
  embed.add_file_reference(target.product_reference).settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
end
# Widget targets do not inherit Flutter's app-only xcconfig variables.
flutter = {}
generated = project.project_dir.join('Flutter/Generated.xcconfig')
if generated.file?
  generated.read.each_line do |line|
    key, value = line.strip.split('=', 2)
    flutter[key] = value if value
  end
end
project.targets.find { |target| target.name == name }.build_configurations.each do |configuration|
  settings = (app.build_configurations.find { |item| item.name == configuration.name } || app.build_configurations.first).build_settings
  info = {}
  path = settings['INFOPLIST_FILE']
  if path && project.project_dir.join(path).file?
    info = Xcodeproj::Plist.read_from_path(project.project_dir.join(path))
  end
  resolve = lambda do |value|
    value.to_s.gsub(/\$\(([^)]+)\)/) { flutter[Regexp.last_match(1)] || settings[Regexp.last_match(1)].to_s }
  end
  configuration.build_settings['PRODUCT_NAME'] = '$(TARGET_NAME)'
  configuration.build_settings['MARKETING_VERSION'] = resolve.call(info['CFBundleShortVersionString'] || settings['MARKETING_VERSION'] || '1.0.0')
  configuration.build_settings['CURRENT_PROJECT_VERSION'] = resolve.call(info['CFBundleVersion'] || settings['CURRENT_PROJECT_VERSION'] || '1')
end
# Flutter's Thin Binary phase reads Info.plist and must run after extension embedding.
embed = app.copy_files_build_phases.find { |phase| phase.name == 'Embed App Extensions' }
thin = app.shell_script_build_phases.find { |phase| phase.name == 'Thin Binary' }
if embed && thin
  app.build_phases.delete(embed)
  app.build_phases.insert(app.build_phases.index(thin), embed)
end
project.save
puts "Embedded #{name} in #{app.name}. Match app/extension signing and version before device installation."
