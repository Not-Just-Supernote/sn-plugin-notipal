





function stripLogsPlugin({ types: t }) {
  const targets = new Set(['console', 'FileLogger']);

  function isStripTarget(path) {
    const callee = path.get('callee');
    if (!callee.isMemberExpression()) return false;
    const object = callee.get('object');
    return object.isIdentifier() && targets.has(object.node.name);
  }

  return {
    name: 'strip-logs',
    visitor: {
      CallExpression(path) {
        if (!isStripTarget(path)) return;

        const target = path.parentPath.isAwaitExpression()
          ? path.parentPath
          : path;

        if (target.parentPath.isExpressionStatement()) {
          target.parentPath.remove();
        } else {
          path.replaceWith(t.unaryExpression('void', t.numericLiteral(0)));
        }
      },
    },
  };
}

module.exports = function (api) {
  const withLogs = process.env.WITH_LOGS === '1';

  
  api.cache.using(() => process.env.WITH_LOGS);

  return {
    presets: ['module:@react-native/babel-preset'],
    plugins: withLogs ? [] : [stripLogsPlugin],
  };
};
