package com.core.gl;

import java.nio.Buffer;

/**
 * Shared test-only fail-fast base {@link GlOps} implementation.
 * Every unoverridden call throws {@link AssertionError} so unit tests only override
 * the subset of GL entry points their fixture explicitly exercises.
 */
abstract class StrictStubGlOps implements GlOps {
    private static AssertionError unexpected(String op) {
        return new AssertionError("Unexpected GlOps call in test fixture: " + op);
    }

    @Override public int glGetError() { throw unexpected("glGetError"); }
    @Override public void glGetIntegerv(int pname, int[] data, int offset) { throw unexpected("glGetIntegerv"); }
    @Override public int glCreateShader(int type) { throw unexpected("glCreateShader"); }
    @Override public void glShaderSource(int shader, String string) { throw unexpected("glShaderSource"); }
    @Override public void glCompileShader(int shader) { throw unexpected("glCompileShader"); }
    @Override public void glGetShaderiv(int shader, int pname, int[] params, int offset) { throw unexpected("glGetShaderiv"); }
    @Override public String glGetShaderInfoLog(int shader) { throw unexpected("glGetShaderInfoLog"); }
    @Override public void glDeleteShader(int shader) { throw unexpected("glDeleteShader"); }
    @Override public int glCreateProgram() { throw unexpected("glCreateProgram"); }
    @Override public void glAttachShader(int program, int shader) { throw unexpected("glAttachShader"); }
    @Override public void glLinkProgram(int program) { throw unexpected("glLinkProgram"); }
    @Override public void glGetProgramiv(int program, int pname, int[] params, int offset) { throw unexpected("glGetProgramiv"); }
    @Override public String glGetProgramInfoLog(int program) { throw unexpected("glGetProgramInfoLog"); }
    @Override public void glDeleteProgram(int program) { throw unexpected("glDeleteProgram"); }
    @Override public int glGetUniformLocation(int program, String name) { throw unexpected("glGetUniformLocation"); }
    @Override public void glUseProgram(int program) { throw unexpected("glUseProgram"); }
    @Override public void glUniform1i(int location, int x) { throw unexpected("glUniform1i"); }
    @Override public void glUniform1f(int location, float x) { throw unexpected("glUniform1f"); }
    @Override public void glUniform2f(int location, float x, float y) { throw unexpected("glUniform2f"); }
    @Override public void glGenFramebuffers(int n, int[] framebuffers, int offset) { throw unexpected("glGenFramebuffers"); }
    @Override public void glDeleteFramebuffers(int n, int[] framebuffers, int offset) { throw unexpected("glDeleteFramebuffers"); }
    @Override public void glBindFramebuffer(int target, int framebuffer) { throw unexpected("glBindFramebuffer"); }
    @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        throw unexpected("glFramebufferTexture2D");
    }
    @Override public int glCheckFramebufferStatus(int target) { throw unexpected("glCheckFramebufferStatus"); }
    @Override public void glInvalidateFramebuffer(int target, int numAttachments, int[] attachments, int offset) {
        throw unexpected("glInvalidateFramebuffer");
    }
    @Override public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                          int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        throw unexpected("glBlitFramebuffer");
    }
    @Override public void glReadBuffer(int mode) { throw unexpected("glReadBuffer"); }
    @Override public void glDrawBuffers(int n, int[] bufs, int offset) { throw unexpected("glDrawBuffers"); }
    @Override public void glGenTextures(int n, int[] textures, int offset) { throw unexpected("glGenTextures"); }
    @Override public void glDeleteTextures(int n, int[] textures, int offset) { throw unexpected("glDeleteTextures"); }
    @Override public void glActiveTexture(int texture) { throw unexpected("glActiveTexture"); }
    @Override public void glBindTexture(int target, int texture) { throw unexpected("glBindTexture"); }
    @Override public void glBindSampler(int unit, int sampler) { throw unexpected("glBindSampler"); }
    @Override public void glTexParameteri(int target, int pname, int param) { throw unexpected("glTexParameteri"); }
    @Override public void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                     int border, int format, int type, Buffer pixels) {
        throw unexpected("glTexImage2D");
    }
    @Override public void glBindBuffer(int target, int buffer) { throw unexpected("glBindBuffer"); }
    @Override public boolean glIsEnabled(int cap) { throw unexpected("glIsEnabled"); }
    @Override public void glEnable(int cap) { throw unexpected("glEnable"); }
    @Override public void glDisable(int cap) { throw unexpected("glDisable"); }
    @Override public void glViewport(int x, int y, int width, int height) { throw unexpected("glViewport"); }
    @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        throw unexpected("glColorMask");
    }
    @Override public void glDrawArrays(int mode, int first, int count) { throw unexpected("glDrawArrays"); }
}
