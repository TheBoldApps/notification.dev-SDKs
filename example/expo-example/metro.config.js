const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
const singletons = ['react', 'react-native', 'expo', 'expo-modules-core'];

// The linked SDK has its own development dependencies. Resolve framework imports
// from the app so Metro does not initialize a second React Native runtime.
config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (singletons.some(name => moduleName === name || moduleName.startsWith(`${name}/`))) {
    return context.resolveRequest({ ...context, originModulePath: path.join(__dirname, 'package.json') }, moduleName, platform);
  }
  return context.resolveRequest(context, moduleName, platform);
};

module.exports = config;
