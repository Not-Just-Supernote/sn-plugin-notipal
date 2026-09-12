const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');









const config = {
  cacheVersion: process.env.WITH_LOGS === '1' ? 'with-logs' : 'no-logs',
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
