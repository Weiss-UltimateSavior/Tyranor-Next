const files = [
    'js/libs/fpsmeter.js',
    'js/libs/iphone-inline-video.browser.js',
    'js/libs/lz-string.js',
    'js/libs/pixi.js',
    'js/libs/pixi-picture.js',
    'js/libs/pixi-tilemap.js',
    'js/rpg_core.js',
    'js/rpg_managers.js',
    'js/rpg_objects.js',
    'js/rpg_scenes.js',
    'js/rpg_sprites.js',
    'js/rpg_windows.js',
];

const CRC32 = require('crc-32');
const cpx = require('cpx');
const fs = require('fs');
const path = require('path');

let crc = {};
files.forEach(file=>{
    const content = fs.readFileSync(path.join('corescript', file)).toString();
    crc[file] = CRC32.bstr(content);
});

const plugin = fs.readFileSync('healthcheck/Debug_HealthCheck.js').toString().replace('__CRC__', JSON.stringify(crc));
fs.writeFileSync('corescript/js/plugins/Debug_HealthCheck.js', plugin);

