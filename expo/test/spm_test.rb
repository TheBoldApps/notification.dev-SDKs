require 'minitest/autorun'
require 'tmpdir'
require_relative '../plugin/with_spm'

class SwiftPackageIntegrationTest < Minitest::Test
  def test_release_dependency_survives_save_and_repeated_installation
    Dir.mktmpdir do |directory|
      project = Xcodeproj::Project.new(File.join(directory, 'Consumer.xcodeproj'))
      target = project.new_target(:application, 'Consumer', :ios, '16.4')
      2.times { NotificationDevExpo.attach(project, target) }
      project.save
      reopened = Xcodeproj::Project.open(project.path)
      assert_equal 1, reopened.root_object.package_references.size
      reference = reopened.root_object.package_references.first
      assert_equal NotificationDevExpo::URL, reference.repositoryURL
      assert_equal({ 'kind' => 'exactVersion', 'version' => '0.2.0' }, reference.requirement)
      target = reopened.targets.first
      assert_equal 0, target.package_product_dependencies.size
      assert_equal 0, target.frameworks_build_phase.files.count { |file| file.product_ref }
      assert_includes target.build_configurations.first.build_settings['EXCLUDED_ARCHS[sdk=iphonesimulator*]'], 'x86_64'
    end
  end
end
