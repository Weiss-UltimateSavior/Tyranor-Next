const {readFileSync, writeFileSync, mkdirSync, existsSync} = require('fs');

const Concat = require('concat-with-sourcemaps');

function concatSource(name, sourceMap){
    const order = JSON.parse(readFileSync(`./${name}.json`).toString());

    let concat = new Concat(true, `${name}.js`, '\n');

    order.forEach(fileName=>{
        const path = `${__dirname}/${fileName}`;
        concat.add(path, readFileSync(path).toString());
    });

    const outFolder = 'dist';
    if(!existsSync(outFolder)) mkdirSync(outFolder);

    writeFileSync(`./${outFolder}/${name}.js`, concat.content);
    if(sourceMap)writeFileSync(`./${outFolder}/${name}.js.map`, concat.sourceMap);
}

if(require.main === module) {
    concatSource(process.argv[2], process.argv[3]);
}

module.exports = concatSource;