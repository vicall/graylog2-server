const WebpackCleanupPlugin = require('webpack-cleanup-plugin');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const merge = require('webpack-merge');
const path = require('path');

const PluginWebpackConfig = function(fqcn, options, additionalConfig) {
  const moduleJsonTemplate = path.resolve(module.parent.filename, '../templates/module.json.template');
  const config = {
    entry: {
    },
    output: {
      path: options.build_path,
      filename: '[name].[hash].js',
      publicPath: '',
    },
    module: {
      loaders: [
        { test: /\.(woff(2)?|svg|eot|ttf|gif|jpg)(\?.+)?$/, loader: 'file-loader' },
        { test: /\.png$/, loader: 'url-loader' },
        { test: /\.less$/, loaders: ['style', 'css', 'less'] },
        { test: /\.css$/, loaders: ['style', 'css'] },
        { test: /\.json$/, loader: 'json-loader' },
        { test: /\.js(x)?$/, loader: 'babel-loader', exclude: /node_modules|\.node_cache/ }
      ],
    },
    plugins: [
      new WebpackCleanupPlugin({}),
      new HtmlWebpackPlugin({filename: 'module.json', template: moduleJsonTemplate}),
    ],
    resolve: {
      extensions: ['', '.js', '.json', '.jsx'],
      modulesDirectories: ['src/web', 'node_modules'],
    },
  };
  config.entry['plugin.' + fqcn] = options.entry_path;
  
  if (additionalConfig) {
    return merge(config, additionalConfig);
  }
  
  return config;
}

module.exports = PluginWebpackConfig;
