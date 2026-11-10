require 'minitest/autorun'
require 'tmpdir'
require 'open3'
require 'xcodeproj'

class WidgetAttachmentTest < Minitest::Test
  def test_repeated_attachment_embeds_one_widget_with_a_real_product_name
    Dir.mktmpdir do |directory|
      path = File.join(directory, 'Example.xcodeproj')
      project = Xcodeproj::Project.new(path)
      app = project.new_target(:application, 'Example', :ios, '16.2')
      app.build_configurations.each { |c| c.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'dev.notification.fixture' }
      app.new_shell_script_build_phase('Thin Binary')
      project.save
      2.times do
        output, status = Open3.capture2e('ruby', File.join(__dir__, 'attach.rb'), path)
        assert status.success?, output
      end
      loaded = Xcodeproj::Project.open(path)
      widgets = loaded.targets.select { |t| t.name == 'NotificationDevLiveActivityWidget' }
      assert_equal 1, widgets.length
      widget = widgets.first
      assert_equal ['NotificationDevActivityKit'], widget.package_product_dependencies.map(&:product_name)
      widget.build_configurations.each do |config|
        assert_equal '$(TARGET_NAME)', config.build_settings['PRODUCT_NAME']
        assert_equal 'dev.notification.fixture.live-activity', config.build_settings['PRODUCT_BUNDLE_IDENTIFIER']
      end
      phases = loaded.targets.first.build_phases
      assert_operator phases.index { |p| p.display_name == 'Embed App Extensions' }, :<, phases.index { |p| p.display_name == 'Thin Binary' }
      assert_equal 1, loaded.targets.first.dependencies.length
      assert_equal 1, loaded.targets.first.copy_files_build_phases.find { |phase| phase.name == 'Embed App Extensions' }.files.length
    end
  end
end
