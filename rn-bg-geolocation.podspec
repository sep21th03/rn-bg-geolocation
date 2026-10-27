require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = 'rn-bg-geolocation'
  s.version      = package['version']
  s.summary      = 'Background geolocation for React Native (Android + iOS), no license key.'
  s.description  = package['description']
  s.license      = package['license'] || 'MIT'
  s.authors      = { 'sep21th' => 'sep21th03@gmail.com' }
  s.homepage     = 'https://github.com/sep21th03/rn-bg-geolocation'
  s.source       = { :git => 'https://github.com/sep21th03/rn-bg-geolocation.git',
                     :tag => s.version.to_s }
  s.platforms    = { :ios => '15.1' }
  s.swift_version = '5.0'

  s.source_files = 'ios/**/*.{swift,h,m}'
  s.frameworks   = 'CoreLocation', 'CoreMotion', 'UIKit', 'AVFoundation', 'Security'
  s.libraries    = 'sqlite3'

  # RN >= 0.71 có helper này: tự thêm dependency + cờ New Architecture đúng chuẩn.
  # Máy cũ không có thì rơi về React-Core (legacy bridge).
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency 'React-Core'
  end
end
