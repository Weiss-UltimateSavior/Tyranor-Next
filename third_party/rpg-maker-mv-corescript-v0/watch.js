const {readFileSync} = require('fs');

const {copySync} = require('cpx');
const watch = require('node-watch');

const concatSource = require('./concat.js');

JSON.parse(readFileSync('./modules.json').toString())
    .map(name=>({name, target:`./js/${name}`}))
    .forEach(({name,target})=>{
        watch(target, ()=>{
            concatSource(name);
            copySync(`./dist/${name}.js`, './game/js/');
            console.log(`${target} changed. copy ${name}.js`);
        });
    });