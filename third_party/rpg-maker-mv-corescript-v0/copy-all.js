let cpx = require('cpx');
let fs = require('fs');

const root = process.argv[2] || './corescript';
cpx.copySync('./template/**/*', root);
cpx.copySync('./dist/*', root + '/js');
cpx.copySync('./js/libs/*', root + '/js/libs');
cpx.copySync('./plugins/*', root + '/js/plugins');