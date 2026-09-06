if(window.hasWebGL2 == undefined){
    window.hasWebGL2 = !!(document.createElement('canvas').getContext('webgl2')) && NWJSApi.isTranspileEnabled();
}

function WebGLDummyExtension(gl) {
    this.gl = gl;
    this.createVertexArrayOES = function(){
        return this.gl.createVertexArray();
    };
    this.deleteVertexArrayOES = function(arrayObject){
        return this.gl.deleteVertexArray(arrayObject);
    };
    this.isVertexArrayOES = function(arrayObject){
        return this.gl.isVertexArray(arrayObject);
    };
    this.bindVertexArrayOES = function(arrayObject){
        return this.gl.bindVertexArray(arrayObject);
    };
    this.VERTEX_ATTRIB_ARRAY_DIVISOR_ANGLE = this.gl.VERTEX_ATTRIB_ARRAY_DIVISOR;
    this.drawArraysInstancedANGLE = function(...args){
        return this.gl.drawArraysInstanced(args);
    }
    this.drawElementsInstancedANGLE = function(...args){
        return this.gl.drawElementsInstanced(args);
    }
    this.vertexAttribDivisorANGLE = function(...args){
        return this.gl.vertexAttribDivisor(args);
    }
    this.vertexAttribDivisorANGLE = function(...args){
        return this.gl.vertexAttribDivisor(args);
    }
    this.COLOR_ATTACHMENT0_WEBGL = this.gl.COLOR_ATTACHMENT0;
    this.COLOR_ATTACHMENT1_WEBGL = this.gl.COLOR_ATTACHMENT1;
    this.COLOR_ATTACHMENT2_WEBGL = this.gl.COLOR_ATTACHMENT2;
    this.COLOR_ATTACHMENT3_WEBGL = this.gl.COLOR_ATTACHMENT3;
    this.COLOR_ATTACHMENT4_WEBGL = this.gl.COLOR_ATTACHMENT4;
    this.COLOR_ATTACHMENT5_WEBGL = this.gl.COLOR_ATTACHMENT5;
    this.COLOR_ATTACHMENT6_WEBGL = this.gl.COLOR_ATTACHMENT6;
    this.COLOR_ATTACHMENT7_WEBGL = this.gl.COLOR_ATTACHMENT7;
    this.COLOR_ATTACHMENT8_WEBGL = this.gl.COLOR_ATTACHMENT8;
    this.COLOR_ATTACHMENT9_WEBGL = this.gl.COLOR_ATTACHMENT9;
    this.COLOR_ATTACHMENT10_WEBGL = this.gl.COLOR_ATTACHMENT10;
    this.COLOR_ATTACHMENT11_WEBGL = this.gl.COLOR_ATTACHMENT11;
    this.COLOR_ATTACHMENT12_WEBGL = this.gl.COLOR_ATTACHMENT12;
    this.COLOR_ATTACHMENT13_WEBGL = this.gl.COLOR_ATTACHMENT13;
    this.COLOR_ATTACHMENT14_WEBGL = this.gl.COLOR_ATTACHMENT14;
    this.COLOR_ATTACHMENT15_WEBGL = this.gl.COLOR_ATTACHMENT15;

    this.DRAW_BUFFER0_WEBGL = this.gl.DRAW_BUFFER0;
    this.DRAW_BUFFER1_WEBGL = this.gl.DRAW_BUFFER1;
    this.DRAW_BUFFER2_WEBGL = this.gl.DRAW_BUFFER2;
    this.DRAW_BUFFER3_WEBGL = this.gl.DRAW_BUFFER3;
    this.DRAW_BUFFER4_WEBGL = this.gl.DRAW_BUFFER4;
    this.DRAW_BUFFER5_WEBGL = this.gl.DRAW_BUFFER5;
    this.DRAW_BUFFER6_WEBGL = this.gl.DRAW_BUFFER6;
    this.DRAW_BUFFER7_WEBGL = this.gl.DRAW_BUFFER7;
    this.DRAW_BUFFER8_WEBGL = this.gl.DRAW_BUFFER8;
    this.DRAW_BUFFER9_WEBGL = this.gl.DRAW_BUFFER9;
    this.DRAW_BUFFER10_WEBGL = this.gl.DRAW_BUFFER10;
    this.DRAW_BUFFER11_WEBGL = this.gl.DRAW_BUFFER11;
    this.DRAW_BUFFER12_WEBGL = this.gl.DRAW_BUFFER12;
    this.DRAW_BUFFER13_WEBGL = this.gl.DRAW_BUFFER13;
    this.DRAW_BUFFER14_WEBGL = this.gl.DRAW_BUFFER14;
    this.DRAW_BUFFER15_WEBGL = this.gl.DRAW_BUFFER15;

    this.MAX_COLOR_ATTACHMENTS_WEBGL = this.gl.MAX_COLOR_ATTACHMENTS;
    this.MAX_DRAW_BUFFERS_WEBGL = this.gl.MAX_DRAW_BUFFERS;

    this.drawBuffersWEBGL = function(...args){
        return this.gl.drawBuffers(args);
    }
}

const joiCanvasGetContext = HTMLCanvasElement.prototype.getContext;
HTMLCanvasElement.prototype.getContext = function(contextType, contextAttributes = null){
    if(((contextType === "webgl") || (contextType === "experimental-webgl")) && window.hasWebGL2){
        console.log("WebGL1 context is requested. Returning WebGL2 context instead.")
        window.isTranspiling = true;
        return joiCanvasGetContext.apply(this,["webgl2", contextAttributes]);
    }

    window.isTranspiling = false;

    return joiCanvasGetContext.apply(this,[contextType, contextAttributes]);
}

const joiCreateShader = WebGL2RenderingContext.prototype.createShader;
WebGL2RenderingContext.prototype.createShader = function(stype){
    if(!window.isTranspiling) return joiCreateShader.apply(this,[stype]);

    var shader = joiCreateShader.apply(this,[stype]);
    shader.type = stype;
    return shader;
}

const joiShaderSource = WebGL2RenderingContext.prototype.shaderSource;
WebGL2RenderingContext.prototype.shaderSource = function(shader, source){
    if(!window.isTranspiling) return joiShaderSource.apply(this, [shader, source]);

    return joiShaderSource.apply(this, [shader, NWJSApi.transpileToGLSL3(source, shader.type == WebGL2RenderingContext.FRAGMENT_SHADER)]);
}

const joiGetExtension = WebGL2RenderingContext.prototype.getExtension;
WebGL2RenderingContext.prototype.getExtension = function(name){
    if(!window.isTranspiling) return joiGetExtension.apply(this, [name]);

    switch(name){
        case "OES_vertex_array_object":
        case "ANGLE_instanced_arrays":
        case "WEBGL_draw_buffers":
            return new WebGLDummyExtension(this);
            break;
        case "WEBGL_color_buffer_float":
        case "OES_texture_half_float":
            return joiGetExtension.apply(this, ["EXT_color_buffer_float"]);
            break;
        case "EXT_disjoint_timer_query":
            var ext = joiGetExtension.apply(this, ["EXT_disjoint_timer_query_webgl2"]);
            var cpext = {
                ...ext,
                getQueryObject: function(...args){
                    getQueryParameter(args);
                }
            }
            return cpext;
            break;
        default:
            return joiGetExtension.apply(this, [name]);
            break;
    }
}

const joiBindTexture1 = WebGLRenderingContext.prototype.bindTexture;
WebGLRenderingContext.prototype.bindTexture = function(target, texture){
    joiBindTexture1.apply(this, [target, texture]);

    this.texParameteri(target, this.TEXTURE_MAG_FILTER, this.NEAREST);
    this.texParameteri(target, this.TEXTURE_MIN_FILTER, this.NEAREST);
    this.texParameteri(target, this.TEXTURE_WRAP_S, this.CLAMP_TO_EDGE);
    this.texParameteri(target, this.TEXTURE_WRAP_T, this.CLAMP_TO_EDGE);
}