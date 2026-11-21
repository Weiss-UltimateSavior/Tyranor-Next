package com.core.gl;

import android.opengl.GLES30;

import java.nio.Buffer;

/**
 * Shared Level-1 OpenGL ES 3.0 dispatch interface and default {@link GLES30} binding.
 * Eliminates duplicate per-class GL dispatch wrapper tables across higher-level GL components.
 * Deterministic stubbing happens in the tests themselves: unit-test fakes (e.g. the recording
 * stub in {@code CnnPipelineTest}) implement this interface directly and drive the pipeline
 * without touching {@link GLES30}; the interface itself carries no stubbing.
 */
interface GlOps {
    int glGetError();
    void glGetIntegerv(int pname, int[] data, int offset);
    int glCreateShader(int type);
    void glShaderSource(int shader, String string);
    void glCompileShader(int shader);
    void glGetShaderiv(int shader, int pname, int[] params, int offset);
    String glGetShaderInfoLog(int shader);
    void glDeleteShader(int shader);
    int glCreateProgram();
    void glAttachShader(int program, int shader);
    void glLinkProgram(int program);
    void glGetProgramiv(int program, int pname, int[] params, int offset);
    String glGetProgramInfoLog(int program);
    void glDeleteProgram(int program);
    int glGetUniformLocation(int program, String name);
    void glUseProgram(int program);
    void glUniform1i(int location, int x);
    void glUniform1f(int location, float x);
    void glUniform2f(int location, float x, float y);
    void glGenFramebuffers(int n, int[] framebuffers, int offset);
    void glDeleteFramebuffers(int n, int[] framebuffers, int offset);
    void glBindFramebuffer(int target, int framebuffer);
    void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level);
    int glCheckFramebufferStatus(int target);
    void glInvalidateFramebuffer(int target, int numAttachments, int[] attachments, int offset);
    void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                           int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);
    void glReadBuffer(int mode);
    void glDrawBuffers(int n, int[] bufs, int offset);
    void glGenTextures(int n, int[] textures, int offset);
    void glDeleteTextures(int n, int[] textures, int offset);
    void glActiveTexture(int texture);
    void glBindTexture(int target, int texture);
    void glBindSampler(int unit, int sampler);
    void glTexParameteri(int target, int pname, int param);
    void glTexImage2D(int target, int level, int internalformat, int width, int height,
                      int border, int format, int type, Buffer pixels);
    void glBindBuffer(int target, int buffer);
    void glEnable(int cap);
    void glDisable(int cap);
    void glViewport(int x, int y, int width, int height);
    void glColorMask(boolean red, boolean green, boolean blue, boolean alpha);
    void glDrawArrays(int mode, int first, int count);

    static final class ShaderCompileException extends IllegalStateException {
        ShaderCompileException(String message) {
            super(message);
        }
    }

    static int compileShader(GlOps gl, int type, String source, int[] query) {
        int shader = gl.glCreateShader(type);
        boolean transferred = false;
        try {
            GlErrors.requireTargetName(shader, "shader", gl::glGetError);
            gl.glShaderSource(shader, source);
            gl.glCompileShader(shader);
            query[0] = 0;
            gl.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, query, 0);
            if (query[0] == 0) {
                String log = gl.glGetShaderInfoLog(shader);
                int toDelete = shader;
                shader = 0;
                gl.glDeleteShader(toDelete);
                GlErrors.requireGlSuccess("shader compile", true, gl::glGetError);
                throw new ShaderCompileException("shader compile failed: " + log);
            }
            transferred = true;
            return shader;
        } finally {
            if (!transferred && shader != 0) {
                gl.glDeleteShader(shader);
            }
        }
    }

    GlOps GLES30_OPS = new GlOps() {
        @Override public int glGetError() { return GLES30.glGetError(); }
        @Override public void glGetIntegerv(int pname, int[] data, int offset) { GLES30.glGetIntegerv(pname, data, offset); }
        @Override public int glCreateShader(int type) { return GLES30.glCreateShader(type); }
        @Override public void glShaderSource(int shader, String string) { GLES30.glShaderSource(shader, string); }
        @Override public void glCompileShader(int shader) { GLES30.glCompileShader(shader); }
        @Override public void glGetShaderiv(int shader, int pname, int[] params, int offset) {
            GLES30.glGetShaderiv(shader, pname, params, offset);
        }
        @Override public String glGetShaderInfoLog(int shader) { return GLES30.glGetShaderInfoLog(shader); }
        @Override public void glDeleteShader(int shader) { GLES30.glDeleteShader(shader); }
        @Override public int glCreateProgram() { return GLES30.glCreateProgram(); }
        @Override public void glAttachShader(int program, int shader) { GLES30.glAttachShader(program, shader); }
        @Override public void glLinkProgram(int program) { GLES30.glLinkProgram(program); }
        @Override public void glGetProgramiv(int program, int pname, int[] params, int offset) {
            GLES30.glGetProgramiv(program, pname, params, offset);
        }
        @Override public String glGetProgramInfoLog(int program) { return GLES30.glGetProgramInfoLog(program); }
        @Override public void glDeleteProgram(int program) { GLES30.glDeleteProgram(program); }
        @Override public int glGetUniformLocation(int program, String name) { return GLES30.glGetUniformLocation(program, name); }
        @Override public void glUseProgram(int program) { GLES30.glUseProgram(program); }
        @Override public void glUniform1i(int location, int x) { GLES30.glUniform1i(location, x); }
        @Override public void glUniform1f(int location, float x) { GLES30.glUniform1f(location, x); }
        @Override public void glUniform2f(int location, float x, float y) { GLES30.glUniform2f(location, x, y); }
        @Override public void glGenFramebuffers(int n, int[] framebuffers, int offset) {
            GLES30.glGenFramebuffers(n, framebuffers, offset);
        }
        @Override public void glDeleteFramebuffers(int n, int[] framebuffers, int offset) {
            GLES30.glDeleteFramebuffers(n, framebuffers, offset);
        }
        @Override public void glBindFramebuffer(int target, int framebuffer) { GLES30.glBindFramebuffer(target, framebuffer); }
        @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
            GLES30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
        }
        @Override public int glCheckFramebufferStatus(int target) { return GLES30.glCheckFramebufferStatus(target); }
        @Override public void glInvalidateFramebuffer(int target, int numAttachments, int[] attachments, int offset) {
            GLES30.glInvalidateFramebuffer(target, numAttachments, attachments, offset);
        }
        @Override public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                              int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
            GLES30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
        @Override public void glReadBuffer(int mode) { GLES30.glReadBuffer(mode); }
        @Override public void glDrawBuffers(int n, int[] bufs, int offset) { GLES30.glDrawBuffers(n, bufs, offset); }
        @Override public void glGenTextures(int n, int[] textures, int offset) { GLES30.glGenTextures(n, textures, offset); }
        @Override public void glDeleteTextures(int n, int[] textures, int offset) { GLES30.glDeleteTextures(n, textures, offset); }
        @Override public void glActiveTexture(int texture) { GLES30.glActiveTexture(texture); }
        @Override public void glBindTexture(int target, int texture) { GLES30.glBindTexture(target, texture); }
        @Override public void glBindSampler(int unit, int sampler) { GLES30.glBindSampler(unit, sampler); }
        @Override public void glTexParameteri(int target, int pname, int param) { GLES30.glTexParameteri(target, pname, param); }
        @Override public void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                         int border, int format, int type, Buffer pixels) {
            GLES30.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
        @Override public void glBindBuffer(int target, int buffer) { GLES30.glBindBuffer(target, buffer); }
        @Override public void glEnable(int cap) { GLES30.glEnable(cap); }
        @Override public void glDisable(int cap) { GLES30.glDisable(cap); }
        @Override public void glViewport(int x, int y, int width, int height) { GLES30.glViewport(x, y, width, height); }
        @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
            GLES30.glColorMask(red, green, blue, alpha);
        }
        @Override public void glDrawArrays(int mode, int first, int count) { GLES30.glDrawArrays(mode, first, count); }
    };
}
