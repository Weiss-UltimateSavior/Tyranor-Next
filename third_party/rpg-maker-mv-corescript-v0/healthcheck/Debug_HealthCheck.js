/*:
 * @plugindesc file health check.
 * @author RM CoreScript team
 */

/*:ja
 * @plugindesc コアスクリプトが正常に導入されているかどうか診断します。
 * @author RM CoreScript team
 */


(function(){
    /* crc32.js (C) 2014-present SheetJS -- http://sheetjs.com */
    /* vim: set ts=2: */
    /*exported CRC32 */
    /*
     Copyright (C) 2014-present  SheetJS

     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
     */
    var CRC32 = {};
    (function(CRC32) {
        CRC32.version = '1.0.2';
        /* see perf/crc32table.js */
        /*global Int32Array */
        function signed_crc_table() {
            var c = 0, table = new Array(256);

            for(var n =0; n != 256; ++n){
                c = n;
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                c = ((c&1) ? (-306674912 ^ (c >>> 1)) : (c >>> 1));
                table[n] = c;
            }

            return typeof Int32Array !== 'undefined' ? new Int32Array(table) : table;
        }

        var T = signed_crc_table();
        function crc32_bstr(bstr, seed) {
            var C = seed ^ -1, L = bstr.length - 1;
            for(var i = 0; i < L;) {
                C = (C>>>8) ^ T[(C^bstr.charCodeAt(i++))&0xFF];
                C = (C>>>8) ^ T[(C^bstr.charCodeAt(i++))&0xFF];
            }
            if(i === L) C = (C>>>8) ^ T[(C ^ bstr.charCodeAt(i))&0xFF];
            return C ^ -1;
        }

        function crc32_buf(buf, seed) {
            if(buf.length > 10000) return crc32_buf_8(buf, seed);
            var C = seed ^ -1, L = buf.length - 3;
            for(var i = 0; i < L;) {
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
            }
            while(i < L+3) C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
            return C ^ -1;
        }

        function crc32_buf_8(buf, seed) {
            var C = seed ^ -1, L = buf.length - 7;
            for(var i = 0; i < L;) {
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
                C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
            }
            while(i < L+7) C = (C>>>8) ^ T[(C^buf[i++])&0xFF];
            return C ^ -1;
        }

        function crc32_str(str, seed) {
            var C = seed ^ -1;
            for(var i = 0, L=str.length, c, d; i < L;) {
                c = str.charCodeAt(i++);
                if(c < 0x80) {
                    C = (C>>>8) ^ T[(C ^ c)&0xFF];
                } else if(c < 0x800) {
                    C = (C>>>8) ^ T[(C ^ (192|((c>>6)&31)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|(c&63)))&0xFF];
                } else if(c >= 0xD800 && c < 0xE000) {
                    c = (c&1023)+64; d = str.charCodeAt(i++)&1023;
                    C = (C>>>8) ^ T[(C ^ (240|((c>>8)&7)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|((c>>2)&63)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|((d>>6)&15)|((c&3)<<4)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|(d&63)))&0xFF];
                } else {
                    C = (C>>>8) ^ T[(C ^ (224|((c>>12)&15)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|((c>>6)&63)))&0xFF];
                    C = (C>>>8) ^ T[(C ^ (128|(c&63)))&0xFF];
                }
            }
            return C ^ -1;
        }
        CRC32.table = T;
        CRC32.bstr = crc32_bstr;
        CRC32.buf = crc32_buf;
        CRC32.str = crc32_str;
    })(CRC32);

    //end crc

    function healthCheckAll(){
        var CRC = __CRC__;

        if(!window.makeVideoPlayableInline){
            return 'index.html';
        }

        var fs = require('fs');
        var path = require('path');

        var cwd = path.dirname(process.mainModule.filename);

        for(var key in CRC){
            if(CRC.hasOwnProperty(key)){
                var filePath = path.join(cwd, key);
                try{
                    var content = fs.readFileSync(filePath).toString();
                    if(CRC32.bstr(content) !== CRC[key]){
                        return key;
                    }
                }catch(exception){
                    return key;
                }
            }
        }
    }


    var Scene_Boot_prototype_start = Scene_Boot.prototype.start;
    Scene_Boot.prototype.start = function(){
        Scene_Boot_prototype_start.call(this);
        if($gameTemp.isPlaytest()){
            var invalidFile;
            if(invalidFile = healthCheckAll()){
                throw new Error(invalidFile + ' is not valid.');
            }
        }
    }
})();