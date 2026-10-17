// Adapter for the pinned SDL3 distribution. No engine objects, instructions,
// PrePresent/Underlay/Overlay callbacks or renderer choices are replaced.
#include "krkrsdl3_stage_geometry.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char* kBegin = "_ZN8krkrsdl322TVPOpenGLRenderBackend10BeginFrameEii";
constexpr const char* kEnd = "_ZN8krkrsdl322TVPOpenGLRenderBackend8EndFrameEv";
constexpr const char* kDraw = "_ZN8krkrsdl322TVPOpenGLRenderBackend19DrawWindowTextureExEPvffffb";
constexpr const char* kRender = "_ZN8krkrsdl313TVPRenderOnceEii";
constexpr const char* kVtable = "_ZTVN8krkrsdl322TVPOpenGLRenderBackendE";
constexpr const char* kSprite = "_ZN8krkrsdl323KRKR_Get_Current_SpriteEv";
constexpr const char* kBackend = "_ZN8krkrsdl319TVPGetRenderBackendEv";
constexpr size_t kVtableBytes = 304;
constexpr uint64_t kBudget = 256ull * 1024 * 1024;
enum Status { Ready = 0, MissingAbi = 1, Es3Unavailable = 2, NativeFailed = 3,
              FilterFailed = 4, Waiting = 5, SizeUnavailable = 7 };

// Public TVPSprite ABI, environ/PlatformView.h at upstream commit
// e5cabc20a0ebcc4bc024fa245fc661036bc1790a. texture remains opaque.
struct PublicSprite {
    void* texture;
    int type, xPos, yPos;
    float scale;
    int width, height;
    bool visible;
};
static_assert(sizeof(PublicSprite) == 40, "Pinned arm64 public sprite ABI");
using Begin = void (*)(void*, int, int);
using End = void (*)(void*);
using Draw = void (*)(void*, void*, float, float, float, float, bool);
using Render = void (*)(int, int);
using Sprite = PublicSprite* (*)();
using Backend = void* (*)();
using DestroyContext = bool (*)(void*);
Begin originalBegin = nullptr;
End originalEnd = nullptr;
Draw originalDraw = nullptr;
Render originalRender = nullptr;
Sprite currentSprite = nullptr;
Backend currentBackend = nullptr;
DestroyContext originalDestroyContext = nullptr;
std::atomic<uint64_t> contextGeneration{0};
void** glAddressPoint = nullptr;
void* engineHandle = nullptr;
void* sdlHandle = nullptr;
JavaVM* javaVm = nullptr;
jobject callback = nullptr;
jmethodID renderMethod = nullptr, statusMethod = nullptr, contextMethod = nullptr;
std::thread::id installThread;
std::mutex lifecycle;
std::atomic<bool> allowed{false};

struct Env {
    JNIEnv* value = nullptr;
    Env() {
        // Render callbacks run on SDLThread, which already belongs to Java.
        // Never attach arbitrary engine/plugin worker threads to this session.
        if (javaVm) javaVm->GetEnv(reinterpret_cast<void**>(&value), JNI_VERSION_1_6);
    }
    bool clear() {
        if (!value || !value->ExceptionCheck()) return false;
        value->ExceptionClear(); return true;
    }
};
void status(int code) {
    Env env;
    if (env.value && callback && statusMethod) {
        env.value->CallVoidMethod(callback, statusMethod, code);
        env.clear();
    }
}

struct Image {
    uintptr_t base = 0;
    const Elf64_Phdr* phdr = nullptr;
    size_t count = 0;
    bool contains(uintptr_t at, size_t bytes, unsigned flags) const {
        if (!at || bytes > std::numeric_limits<uintptr_t>::max() - at) return false;
        for (size_t i = 0; i < count; ++i) {
            const auto& p = phdr[i];
            if (p.p_type != PT_LOAD || (p.p_flags & flags) != flags
                    || p.p_vaddr > std::numeric_limits<uintptr_t>::max() - base) continue;
            uintptr_t start = base + p.p_vaddr;
            if (at >= start && at - start <= p.p_memsz && bytes <= p.p_memsz - (at - start)) return true;
        }
        return false;
    }
    uintptr_t pointer(uintptr_t value) const {
        if (contains(value, 1, PF_R)) return value;
        if (value > std::numeric_limits<uintptr_t>::max() - base) return 0;
        value += base;
        return contains(value, 1, PF_R) ? value : 0;
    }
};
int findImage(dl_phdr_info* info, size_t, void* output) {
    auto& image = *static_cast<Image*>(output);
    if (image.base != static_cast<uintptr_t>(info->dlpi_addr)) return 0;
    image.phdr = info->dlpi_phdr; image.count = info->dlpi_phnum; return 1;
}
std::string buildId(const Image& image) {
    for (size_t i = 0; i < image.count; ++i) {
        const auto& p = image.phdr[i];
        if (p.p_type != PT_NOTE || p.p_vaddr > UINTPTR_MAX - image.base) continue;
        uintptr_t start = image.base + p.p_vaddr;
        if (!image.contains(start, p.p_memsz, PF_R)) continue;
        auto* at = reinterpret_cast<const unsigned char*>(start);
        size_t remaining = p.p_memsz;
        while (remaining >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{}; std::memcpy(&note, at, sizeof(note));
            size_t name = (static_cast<size_t>(note.n_namesz) + 3) & ~size_t{3};
            size_t desc = (static_cast<size_t>(note.n_descsz) + 3) & ~size_t{3};
            if (name > remaining - sizeof(note) || desc > remaining - sizeof(note) - name) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == 20
                    && std::memcmp(at + sizeof(note), "GNU", 4) == 0) {
                std::string hex;
                const auto* id = at + sizeof(note) + name;
                for (size_t n = 0; n < 20; ++n) {
                    hex += "0123456789abcdef"[id[n] >> 4];
                    hex += "0123456789abcdef"[id[n] & 15];
                }
                return hex;
            }
            size_t total = sizeof(note) + name + desc;
            at += total; remaining -= total;
        }
    }
    return {};
}
bool identify(void* handle, const char* anchor, const char* expected, Image& image) {
    void* symbol = dlsym(handle, anchor);
    Dl_info info{};
    if (!symbol || !dladdr(symbol, &info)) return false;
    image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(findImage, &image);
    return image.phdr && image.contains(reinterpret_cast<uintptr_t>(symbol), 4, PF_R | PF_X)
            && buildId(image) == expected;
}

struct Dynamic {
    const Elf64_Sym* symbols = nullptr;
    const char* strings = nullptr;
    const Elf64_Rela* relocations = nullptr;
    const uint32_t* gnuHash = nullptr;
    size_t stringBytes = 0, relocationBytes = 0;
    bool read(const Image& image) {
        const Elf64_Dyn* items = nullptr; size_t count = 0;
        for (size_t i = 0; i < image.count; ++i) {
            const auto& p = image.phdr[i];
            if (p.p_type != PT_DYNAMIC || p.p_vaddr > UINTPTR_MAX - image.base) continue;
            uintptr_t at = image.base + p.p_vaddr;
            if (!image.contains(at, p.p_memsz, PF_R)) return false;
            items = reinterpret_cast<const Elf64_Dyn*>(at); count = p.p_memsz / sizeof(*items);
        }
        bool rela = false, symbolSize = false, terminated = false;
        for (size_t i = 0; items && i < count; ++i) {
            const auto& d = items[i];
            if (d.d_tag == DT_NULL) { terminated = true; break; }
            if (d.d_tag == DT_SYMTAB) symbols = reinterpret_cast<const Elf64_Sym*>(image.pointer(d.d_un.d_ptr));
            if (d.d_tag == DT_STRTAB) strings = reinterpret_cast<const char*>(image.pointer(d.d_un.d_ptr));
            if (d.d_tag == DT_STRSZ) stringBytes = d.d_un.d_val;
            if (d.d_tag == DT_SYMENT) symbolSize = d.d_un.d_val == sizeof(Elf64_Sym);
            if (d.d_tag == DT_JMPREL) relocations = reinterpret_cast<const Elf64_Rela*>(image.pointer(d.d_un.d_ptr));
            if (d.d_tag == DT_PLTRELSZ) relocationBytes = d.d_un.d_val;
            if (d.d_tag == DT_PLTREL) rela = d.d_un.d_val == DT_RELA;
            if (d.d_tag == DT_GNU_HASH) gnuHash = reinterpret_cast<const uint32_t*>(image.pointer(d.d_un.d_ptr));
        }
        return terminated && symbolSize && rela && stringBytes && relocationBytes
                && relocationBytes % sizeof(Elf64_Rela) == 0
                && image.contains(reinterpret_cast<uintptr_t>(strings), stringBytes, PF_R)
                && image.contains(reinterpret_cast<uintptr_t>(relocations), relocationBytes, PF_R);
    }
    const char* name(const Image& image, size_t index) const {
        if (!symbols || index > SIZE_MAX / sizeof(Elf64_Sym)) return nullptr;
        uintptr_t base = reinterpret_cast<uintptr_t>(symbols);
        size_t offset = index * sizeof(Elf64_Sym);
        if (offset > UINTPTR_MAX - base || !image.contains(base + offset, sizeof(Elf64_Sym), PF_R)) return nullptr;
        const auto* symbol = reinterpret_cast<const Elf64_Sym*>(base + offset);
        if (symbol->st_name >= stringBytes) return nullptr;
        const char* result = strings + symbol->st_name;
        return std::memchr(result, 0, stringBytes - symbol->st_name) ? result : nullptr;
    }
    const Elf64_Sym* find(const Image& image, const char* wanted) const {
        if (!image.contains(reinterpret_cast<uintptr_t>(gnuHash), 4 * sizeof(uint32_t), PF_R)) return nullptr;
        uint32_t buckets = gnuHash[0], first = gnuHash[1], bloom = gnuHash[2];
        if (!buckets || bloom > 65536 || buckets > 65536) return nullptr;
        uintptr_t bucketAddress = reinterpret_cast<uintptr_t>(gnuHash) + 16 + static_cast<size_t>(bloom) * sizeof(Elf64_Addr);
        if (!image.contains(bucketAddress, static_cast<size_t>(buckets) * 4, PF_R)) return nullptr;
        uint32_t hash = 5381;
        for (auto* p = reinterpret_cast<const unsigned char*>(wanted); *p; ++p) hash = hash * 33 + *p;
        uint32_t index = reinterpret_cast<const uint32_t*>(bucketAddress)[hash % buckets];
        uintptr_t chains = bucketAddress + static_cast<size_t>(buckets) * 4;
        if (index < first) return nullptr;
        for (size_t i = 0; i < 65536; ++i, ++index) {
            uintptr_t chain = chains + static_cast<size_t>(index - first) * 4;
            if (!image.contains(chain, 4, PF_R)) return nullptr;
            uint32_t value = *reinterpret_cast<const uint32_t*>(chain);
            if ((value | 1) == (hash | 1)) {
                const char* candidate = name(image, index);
                if (candidate && std::strcmp(candidate, wanted) == 0) return symbols + index;
            }
            if (value & 1) break;
        }
        return nullptr;
    }
};

struct Change { void** slot; void* before; void* after; };
std::vector<Change> installed;
// Read actual mapping permissions, including RELRO and 16-KiB page rounding.
int protection(uintptr_t page, size_t bytes) {
    FILE* maps = std::fopen("/proc/self/maps", "r");
    if (!maps) return -1;
    char line[768]; int result = -1;
    while (std::fgets(line, sizeof(line), maps)) {
        unsigned long long start = 0, end = 0; char mode[5]{};
        if (std::sscanf(line, "%llx-%llx %4s", &start, &end, mode) != 3) continue;
        if (page < start || page >= end || bytes > end - page) continue;
        result = (mode[0] == 'r' ? PROT_READ : 0) | (mode[1] == 'w' ? PROT_WRITE : 0)
                | (mode[2] == 'x' ? PROT_EXEC : 0); break;
    }
    std::fclose(maps); return result;
}
bool transact(const std::vector<Change>& changes, bool restoring) {
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0 || (pageSize & (pageSize - 1))) return false;
    std::map<uintptr_t, int> pages;
    for (const auto& c : changes) {
        if (reinterpret_cast<uintptr_t>(c.slot) % alignof(void*) != 0) return false;
        if (__atomic_load_n(c.slot, __ATOMIC_ACQUIRE) != (restoring ? c.after : c.before)) return false;
        uintptr_t page = reinterpret_cast<uintptr_t>(c.slot) & ~(static_cast<uintptr_t>(pageSize) - 1);
        int flags = protection(page, pageSize);
        if (flags < 0 || !(flags & PROT_READ) || (flags & PROT_EXEC)) return false;
        pages.emplace(page, flags);
    }
    std::vector<uintptr_t> writable;
    // Complete every allocation before the first page permission mutation.
    writable.reserve(pages.size());
    for (const auto& page : pages) {
        if (mprotect(reinterpret_cast<void*>(page.first), pageSize, page.second | PROT_WRITE)) {
            for (uintptr_t opened : writable) mprotect(reinterpret_cast<void*>(opened), pageSize, pages[opened]);
            return false;
        }
        writable.push_back(page.first);
    }
    size_t applied = 0;
    for (const auto& c : changes) {
        void* expected = restoring ? c.after : c.before;
        if (!__atomic_compare_exchange_n(c.slot, &expected, restoring ? c.before : c.after,
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) break;
        ++applied;
    }
    bool complete = applied == changes.size();
    bool permissions = true;
    if (complete) for (const auto& page : pages)
        permissions = mprotect(reinterpret_cast<void*>(page.first), pageSize, page.second) == 0 && permissions;
    if (!complete || !permissions) {
        bool reopened = true;
        for (const auto& page : pages)
            reopened = mprotect(reinterpret_cast<void*>(page.first), pageSize, page.second | PROT_WRITE) == 0 && reopened;
        if (reopened) while (applied) {
            const auto& c = changes[--applied];
            void* expected = restoring ? c.before : c.after;
            __atomic_compare_exchange_n(c.slot, &expected, restoring ? c.after : c.before,
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        }
        for (const auto& page : pages) mprotect(reinterpret_cast<void*>(page.first), pageSize, page.second);
        __android_log_print(ANDROID_LOG_ERROR, "Krkrsdl3Graphics", "Pointer transaction rejected; rollback writable=%d", reopened);
        return false;
    }
    return true;
}

struct GlState {
    GLint read = 0, draw = 0, viewport[4]{}, program = 0, vao = 0, active = 0;
    GLint texture = 0, sampler = 0, renderbuffer = 0, unpack = 0;
    GLint stencilFront = 0, stencilBack = 0;
    GLboolean depthMask = GL_TRUE;
    GLboolean mask[4]{};
    GLfloat clear[4]{};
    static constexpr GLenum caps[] = {GL_BLEND, GL_DEPTH_TEST, GL_STENCIL_TEST,
        GL_SCISSOR_TEST, GL_CULL_FACE, GL_RASTERIZER_DISCARD,
        GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE, GL_DITHER};
    GLboolean enabled[sizeof(caps) / sizeof(caps[0])]{};
    GlState() {
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read); glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
        glGetIntegerv(GL_VIEWPORT, viewport); glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao); glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer); glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask); glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        glGetBooleanv(GL_DEPTH_WRITEMASK, &depthMask);
        glGetIntegerv(GL_STENCIL_WRITEMASK, &stencilFront); glGetIntegerv(GL_STENCIL_BACK_WRITEMASK, &stencilBack);
        for (size_t i = 0; i < sizeof(caps) / sizeof(caps[0]); ++i) enabled[i] = glIsEnabled(caps[i]);
        glActiveTexture(GL_TEXTURE0); glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler); glActiveTexture(active);
    }
    void restore(GLuint stage = 0, int sw = 0, int sh = 0) {
        bool redirected = stage && static_cast<GLuint>(draw) == stage;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, stage && static_cast<GLuint>(read) == stage ? 0 : read);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, redirected ? 0 : draw);
        if (redirected) glViewport(0, 0, sw, sh);
        else glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glUseProgram(program); glBindVertexArray(vao);
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
        glColorMask(mask[0], mask[1], mask[2], mask[3]); glClearColor(clear[0], clear[1], clear[2], clear[3]);
        glDepthMask(depthMask); glStencilMaskSeparate(GL_FRONT, static_cast<GLuint>(stencilFront));
        glStencilMaskSeparate(GL_BACK, static_cast<GLuint>(stencilBack));
        for (size_t i = 0; i < sizeof(caps) / sizeof(caps[0]); ++i) {
            if (enabled[i]) glEnable(caps[i]); else glDisable(caps[i]);
        }
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture); glBindSampler(0, sampler);
        glActiveTexture(active);
    }
};

struct Resources {
    EGLContext context = EGL_NO_CONTEXT;
    uint64_t generation = 0;
    GLuint framebuffer = 0, texture = 0, depthStencil = 0, program = 0, vao = 0;
    GLint bounds = -1, source = -1;
    int width = 0, height = 0;
    void abandon() { framebuffer = texture = depthStencil = program = vao = 0; width = height = 0; }
    void release() {
        if (context != EGL_NO_CONTEXT && context == eglGetCurrentContext() && generation == contextGeneration.load()) {
            glDeleteFramebuffers(1, &framebuffer); glDeleteTextures(1, &texture);
            glDeleteRenderbuffers(1, &depthStencil); glDeleteVertexArrays(1, &vao);
            if (program) glDeleteProgram(program);
        }
        abandon(); context = EGL_NO_CONTEXT;
    }
} resources;
struct ResourceRetry {
    EGLContext context = EGL_NO_CONTEXT;
    uint64_t generation = 0;
    int width = 0, height = 0, code = SizeUnavailable;
    unsigned failures = 0;
    bool deterministic = false;
    std::chrono::steady_clock::time_point next{};
    void configure(EGLContext current, uint64_t epoch, int w, int h) {
        if (context == current && generation == epoch && width == w && height == h) return;
        context = current; generation = epoch; width = w; height = h;
        failures = 0; deterministic = false; next = {};
    }
    bool admit() const { return !failures || (!deterministic && std::chrono::steady_clock::now() >= next); }
    void failed(int statusCode, bool permanent) {
        code = statusCode; deterministic = permanent;
        unsigned seconds = failures >= 4 ? 30 : 2u << failures;
        if (failures < 5) ++failures;
        next = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    }
    void succeeded() { failures = 0; deterministic = false; next = {}; }
} resourceRetry;
GLuint shader(GLenum type, const char* text) {
    GLuint result = glCreateShader(type); if (!result) return 0;
    glShaderSource(result, 1, &text, nullptr); glCompileShader(result);
    GLint ok = 0; glGetShaderiv(result, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(result); return 0; } return result;
}
bool ensureResources(int width, int height) {
    EGLContext context = eglGetCurrentContext();
    if (context == EGL_NO_CONTEXT) { status(Es3Unavailable); return false; }
    if (context != resources.context || resources.generation != contextGeneration.load()) {
        resources.abandon(); resources.context = context;
        resources.generation = contextGeneration.load();
        Env env;
        if (!env.value) return false;
        env.value->CallVoidMethod(callback, contextMethod, JNI_TRUE);
        if (env.clear()) { status(FilterFailed); return false; }
    }
    resourceRetry.configure(context, contextGeneration.load(), width, height);
    if (!resourceRetry.admit()) { status(resourceRetry.code); return false; }
    GLint major = 0; glGetIntegerv(GL_MAJOR_VERSION, &major);
    if (major < 3) { resourceRetry.failed(Es3Unavailable, true); status(Es3Unavailable); return false; }
    GLint maxTexture = 0; glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTexture);
    uint64_t bytes = static_cast<uint64_t>(width) * height * 8;
    if (width <= 0 || height <= 0 || width > maxTexture || height > maxTexture || bytes >= kBudget) {
        resourceRetry.failed(SizeUnavailable, true); status(SizeUnavailable); return false;
    }
    if (resources.width == width && resources.height == height && resources.framebuffer && resources.program) return true;
    if (resources.width || resources.height) {
        Env env;
        if (!env.value) return false;
        env.value->CallVoidMethod(callback, contextMethod, JNI_FALSE);
        if (env.clear()) { status(FilterFailed); return false; }
    }
    GlState saved;
    resources.release(); resources.context = context;
    const char* vertex = "#version 300 es\nuniform vec4 bounds;out vec2 uv;void main(){"
        "vec2 p=vec2(float(gl_VertexID&1),float((gl_VertexID>>1)&1));uv=p;"
        "gl_Position=vec4((bounds.xy+bounds.zw*p)*2.0-1.0,0.0,1.0);}";
    const char* fragment = "#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D source;"
        "out vec4 color;void main(){color=texture(source,uv);}";
    GLuint vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
    if (vs && fs) {
        resources.program = glCreateProgram(); glAttachShader(resources.program, vs); glAttachShader(resources.program, fs);
        glLinkProgram(resources.program);
    }
    if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs);
    GLint linked = 0;
    if (resources.program) glGetProgramiv(resources.program, GL_LINK_STATUS, &linked);
    if (!linked) {
        resources.release(); resources.context = context; resources.generation = contextGeneration.load();
        saved.restore(); resourceRetry.failed(NativeFailed, true); status(NativeFailed); return false;
    }
    resources.bounds = glGetUniformLocation(resources.program, "bounds");
    resources.source = glGetUniformLocation(resources.program, "source");
    glGenVertexArrays(1, &resources.vao);
    glGenTextures(1, &resources.texture); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, resources.texture);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenRenderbuffers(1, &resources.depthStencil); glBindRenderbuffer(GL_RENDERBUFFER, resources.depthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
    glGenFramebuffers(1, &resources.framebuffer); glBindFramebuffer(GL_FRAMEBUFFER, resources.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, resources.texture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, resources.depthStencil);
    bool complete = resources.vao && resources.texture && resources.depthStencil && resources.framebuffer
            && resources.bounds >= 0 && resources.source >= 0
            && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (complete) { resources.width = width; resources.height = height; }
    else { resources.release(); resources.context = context; resources.generation = contextGeneration.load(); }
    saved.restore();
    if (!complete) { resourceRetry.failed(SizeUnavailable, false); status(SizeUnavailable); }
    else resourceRetry.succeeded();
    return complete;
}

struct Frame {
    bool scope = false, redirect = false, mainSeen = false, valid = true;
    bool containedInMain = true;
    void* backend = nullptr;
    PublicSprite* sprite = nullptr;
    void* mainTexture = nullptr;
    Krkrsdl3StageGeometry geometry;
};
thread_local Frame frame;
struct SuspendedCapture {
    EGLContext context = EGL_NO_CONTEXT;
    uint64_t generation = 0;
    int width = 0, height = 0, screenWidth = 0, screenHeight = 0;
    bool matches(const Krkrsdl3StageGeometry& g) const {
        return context != EGL_NO_CONTEXT && context == eglGetCurrentContext()
                && generation == contextGeneration.load() && width == g.width && height == g.height
                && screenWidth == g.screenWidth && screenHeight == g.screenHeight;
    }
    void set(const Krkrsdl3StageGeometry& g) {
        context = eglGetCurrentContext(); generation = contextGeneration.load();
        width = g.width; height = g.height; screenWidth = g.screenWidth; screenHeight = g.screenHeight;
    }
} suspended;
bool frameOwner() {
    return allowed.load(std::memory_order_acquire) && frame.scope && frame.redirect
            && std::this_thread::get_id() == installThread && resources.context == eglGetCurrentContext()
            && resources.generation == contextGeneration.load();
}
bool destroyContext(void* context) {
    // Invalidates names even if EGL later recycles the same context handle.
    // Worker-thread destruction cannot touch SDLThread's GL or JNI state.
    bool destroyed = originalDestroyContext(context);
    if (destroyed) contextGeneration.fetch_add(1);
    return destroyed;
}
void bindFramebuffer(GLenum target, GLuint name) {
    bool redirected = frameOwner() && name == 0;
    glBindFramebuffer(target, redirected ? resources.framebuffer : name);
    if (redirected && (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER))
        glViewport(0, 0, frame.geometry.canvasWidth, frame.geometry.canvasHeight);
}
void viewport(GLint x, GLint y, GLsizei width, GLsizei height) {
    if (frameOwner()) {
        GLint target = 0; glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &target);
        if (static_cast<GLuint>(target) == resources.framebuffer) {
            if (x || y || width != frame.geometry.screenWidth || height != frame.geometry.screenHeight) frame.valid = false;
            glViewport(0, 0, frame.geometry.canvasWidth, frame.geometry.canvasHeight); return;
        }
    }
    glViewport(x, y, width, height);
}
void drawWindow(void* backend, void* texture, float x, float y, float w, float h, bool premultiplied) {
    if (frameOwner() && backend == frame.backend) {
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h)) frame.valid = false;
        // Observe actual draws after Underlay/main/Overlay, so a sprite joined
        // or made visible during this very frame cannot escape capture.
        if (texture == frame.mainTexture) {
            PublicSprite* active = currentSprite();
            bool geometry = active && active == frame.sprite && active->width == frame.geometry.width
                    && active->height == frame.geometry.height && frame.geometry.mainDraw(x, y, w, h);
            frame.mainSeen = true; frame.valid = frame.valid && geometry;
            if (geometry) {
                // Exact raw pixel centers, avoiding roundoff from an otherwise
                // algebraically identity physical-to-stage inverse transform.
                const auto& g = frame.geometry;
                originalDraw(backend, texture, g.mainX(), g.mainY(), g.mainW(), g.mainH(), premultiplied); return;
            }
        }
        if (!frame.geometry.containsDraw(x, y, w, h)) frame.containedInMain = false;
        originalDraw(backend, texture, frame.geometry.x(x), frame.geometry.y(y),
                frame.geometry.w(w), frame.geometry.h(h), premultiplied); return;
    }
    originalDraw(backend, texture, x, y, w, h, premultiplied);
}
void beginFrame(void* backend, int width, int height) {
    if (allowed.load() && frame.scope && !frame.redirect && std::this_thread::get_id() == installThread) {
        PublicSprite* sprite = currentSprite();
        bool geometry = sprite && sprite->texture && frame.geometry.set(sprite->width, sprite->height, width, height);
        bool blocked = geometry && suspended.matches(frame.geometry);
        if (geometry && !blocked && ensureResources(frame.geometry.canvasWidth, frame.geometry.canvasHeight)) {
            frame.backend = backend; frame.sprite = sprite; frame.mainTexture = sprite->texture;
            frame.redirect = true;
            // Every frame starts with a full color clear, even if a previous
            // plugin left scissor/color masks enabled. The original BeginFrame
            // also clears, and still receives the physical dimensions.
            GlState saved;
            glBindFramebuffer(GL_FRAMEBUFFER, resources.framebuffer);
            glDisable(GL_SCISSOR_TEST); glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
            glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
            glDepthMask(GL_TRUE); glStencilMaskSeparate(GL_FRONT_AND_BACK, ~GLuint{0});
            glClearBufferfi(GL_DEPTH_STENCIL, 0, 1.0f, 0);
            saved.restore();
        } else if (!geometry || blocked) status(SizeUnavailable);
    }
    originalBegin(backend, width, height);
}
void endFrame(void* backend) {
    originalEnd(backend);
    if (!frameOwner() || frame.backend != backend) return;
    frame.redirect = false;
    const auto& g = frame.geometry;
    GlState saved;
    GLuint output = resources.texture;
    bool presentMain = false;
    if (frame.mainSeen && frame.valid) {
        Env env;
        if (env.value) {
            jint result = env.value->CallIntMethod(callback, renderMethod, static_cast<jint>(output),
                    g.width, g.height, g.canvasWidth, g.canvasHeight, g.screenWidth, g.screenHeight,
                    g.padLeft, g.padBottom, frame.containedInMain ? JNI_TRUE : JNI_FALSE);
            if (env.clear()) status(FilterFailed);
            else if (result != 0 && glIsTexture(static_cast<GLuint>(result))) {
                output = static_cast<GLuint>(result); presentMain = frame.containedInMain;
            } else if (result != 0) status(FilterFailed);
            // Zero is a deliberate whole-chain bypass. Present the complete
            // original canvas so letterbox overlays survive allocation errors.
        } else status(FilterFailed);
    } else {
        // The fixed compositor's full-window contract was violated. We cannot
        // replay an already composed frame; present the full canvas without filters
        // and leave all later frames entirely with the engine until geometry
        // or context changes. Unknown direct-GL plugins are not certified.
        suspended.set(g); status(SizeUnavailable);
    }
    // A verified contained stage may use its exact source crop. Every other
    // path presents the padded canvas at its physical bounds, clipping only
    // beyond the real window, never content in the engine's letterbox bars.
    glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, g.screenWidth, g.screenHeight);
    for (GLenum cap : GlState::caps) glDisable(cap);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(resources.program); glBindVertexArray(resources.vao);
    glActiveTexture(GL_TEXTURE0); glBindSampler(0, 0); glBindTexture(GL_TEXTURE_2D, output);
    glUniform1i(resources.source, 0);
    glUniform4f(resources.bounds, (presentMain ? g.left : g.canvasLeft) / g.screenWidth,
            (presentMain ? g.bottom : g.canvasBottom) / g.screenHeight,
            g.scale * (presentMain ? g.width : g.canvasWidth) / g.screenWidth,
            g.scale * (presentMain ? g.height : g.canvasHeight) / g.screenHeight);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    saved.restore(resources.framebuffer, g.screenWidth, g.screenHeight);
}
void renderOnce(int width, int height) {
    if (!allowed.load() || std::this_thread::get_id() != installThread || frame.scope) {
        originalRender(width, height); return;
    }
    struct Scope {
        Scope() { frame = {}; frame.scope = true; }
        ~Scope() {
            // A C++ exception must not leave engine PLT calls redirected.
            bool pending = frame.redirect;
            frame.redirect = frame.scope = false;
            if (pending && resources.context == eglGetCurrentContext()) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, frame.geometry.screenWidth, frame.geometry.screenHeight);
            }
        }
    } scope;
    originalRender(width, height);
    void* backend = currentBackend();
    // C++ ABI address point inspection; no private backend data is read.
    if (backend && *static_cast<void***>(backend) != glAddressPoint) status(MissingAbi);
}

bool collect(const Image& image, std::vector<Change>& changes, void** slot, void* before, void* after) {
    uintptr_t at = reinterpret_cast<uintptr_t>(slot);
    if (!before || !after || !image.contains(at, sizeof(void*), PF_R)
            || at % alignof(void*) || __atomic_load_n(slot, __ATOMIC_ACQUIRE) != before) return false;
    for (const auto& c : changes) if (c.slot == slot) return false;
    changes.push_back({slot, before, after}); return true;
}
bool collectVtable(const Image& image, const Dynamic& dynamic, std::vector<Change>& changes) {
    void* table = dlsym(engineHandle, kVtable);
    if (!table || !image.contains(reinterpret_cast<uintptr_t>(table), kVtableBytes, PF_R)) return false;
    // GNU-hash lookup verifies the loaded symbol's size; slots are located by
    // exact function identity rather than assumed virtual method ordinals.
    const Elf64_Sym* symbol = dynamic.find(image, kVtable);
    bool exactSymbol = symbol && symbol->st_size == kVtableBytes && ELF64_ST_TYPE(symbol->st_info) == STT_OBJECT
            && symbol->st_value <= UINTPTR_MAX - image.base
            && image.base + symbol->st_value == reinterpret_cast<uintptr_t>(table);
    if (!exactSymbol) return false;
    auto** slots = static_cast<void**>(table);
    glAddressPoint = slots + 2;
    size_t begins = 0, ends = 0;
    for (size_t i = 2; i < kVtableBytes / sizeof(void*); ++i) {
        void* target = slots[i];
        if (target == reinterpret_cast<void*>(originalBegin)) {
            if (!collect(image, changes, slots + i, target, reinterpret_cast<void*>(beginFrame))) return false;
            ++begins;
        }
        if (target == reinterpret_cast<void*>(originalEnd)) {
            if (!collect(image, changes, slots + i, target, reinterpret_cast<void*>(endFrame))) return false;
            ++ends;
        }
    }
    return begins == 1 && ends == 1;
}
bool collectPlt(const Image& image, const Dynamic& dynamic, std::vector<Change>& changes) {
    std::map<std::string, void*> hooks = {{"glBindFramebuffer", reinterpret_cast<void*>(bindFramebuffer)},
        {"glViewport", reinterpret_cast<void*>(viewport)}, {kDraw, reinterpret_cast<void*>(drawWindow)},
        {kRender, reinterpret_cast<void*>(renderOnce)}, {"SDL_GL_DestroyContext", reinterpret_cast<void*>(destroyContext)}};
    std::map<std::string, int> counts;
    for (size_t i = 0; i < dynamic.relocationBytes / sizeof(Elf64_Rela); ++i) {
        const auto& r = dynamic.relocations[i];
        if (ELF64_R_TYPE(r.r_info) != R_AARCH64_JUMP_SLOT) continue;
        const char* name = dynamic.name(image, ELF64_R_SYM(r.r_info));
        if (!name) return false;
        auto hook = hooks.find(name); if (hook == hooks.end()) continue;
        if (r.r_offset > UINTPTR_MAX - image.base) return false;
        void* before = dlsym(engineHandle, name);
        if (!collect(image, changes, reinterpret_cast<void**>(image.base + r.r_offset), before, hook->second)) return false;
        ++counts[hook->first];
    }
    for (const auto& hook : hooks) if (counts[hook.first] != 1) return false;
    return true;
}
void clearSession(JNIEnv* env) {
    if (callback) env->DeleteGlobalRef(callback);
    callback = nullptr;
    if (engineHandle) dlclose(engineHandle);
    if (sdlHandle) dlclose(sdlHandle);
    engineHandle = sdlHandle = nullptr;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_core_krkrsdl3_Krkrsdl3Graphics_nativeInstall(JNIEnv* env, jobject owner) {
    try {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (allowed.load() || callback || !installed.empty()) return JNI_FALSE;
#if !defined(__aarch64__)
    return JNI_FALSE;
#else
    engineHandle = dlopen("libkrkrsdl3.so", RTLD_NOW | RTLD_NOLOAD);
    sdlHandle = dlopen("libSDL3.so", RTLD_NOW | RTLD_NOLOAD);
    Image image, sdl;
    bool identity = engineHandle && sdlHandle
            && identify(engineHandle, kBegin, "e088c4563fdbc75118564e68acf6e3e70ec83a91", image)
            && identify(sdlHandle, "SDL_GL_SwapWindow", "c6ae4383b991cdf2c14bc5555f7273923e47581a", sdl);
    if (!identity) { clearSession(env); return JNI_FALSE; }
    auto function = [&](const char* name) -> void* {
        void* value = dlsym(engineHandle, name);
        return image.contains(reinterpret_cast<uintptr_t>(value), 4, PF_R | PF_X) ? value : nullptr;
    };
    originalBegin = reinterpret_cast<Begin>(function(kBegin)); originalEnd = reinterpret_cast<End>(function(kEnd));
    originalDraw = reinterpret_cast<Draw>(function(kDraw)); originalRender = reinterpret_cast<Render>(function(kRender));
    currentSprite = reinterpret_cast<Sprite>(function(kSprite)); currentBackend = reinterpret_cast<Backend>(function(kBackend));
    originalDestroyContext = reinterpret_cast<DestroyContext>(dlsym(sdlHandle, "SDL_GL_DestroyContext"));
    Dynamic dynamic;
    std::vector<Change> changes;
    if (!originalBegin || !originalEnd || !originalDraw || !originalRender || !currentSprite || !currentBackend
            || !sdl.contains(reinterpret_cast<uintptr_t>(originalDestroyContext), 4, PF_R | PF_X)
            || !dynamic.read(image) || !collectVtable(image, dynamic, changes) || !collectPlt(image, dynamic, changes)) {
        clearSession(env); return JNI_FALSE;
    }
    jclass cls = env->GetObjectClass(owner);
    if (env->ExceptionCheck() || !cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        clearSession(env); return JNI_FALSE;
    }
    auto method = [&](const char* name, const char* signature) -> jmethodID {
        jmethodID value = env->GetMethodID(cls, name, signature);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; } return value;
    };
    renderMethod = method("renderStage", "(IIIIIIIIIZ)I"); statusMethod = method("nativeStatus", "(I)V");
    contextMethod = method("contextChanged", "(Z)V"); env->DeleteLocalRef(cls);
    if (!renderMethod || !statusMethod || !contextMethod || env->GetJavaVM(&javaVm) != JNI_OK) {
        clearSession(env); return JNI_FALSE;
    }
    callback = env->NewGlobalRef(owner);
    if (env->ExceptionCheck() || !callback) { env->ExceptionClear(); clearSession(env); return JNI_FALSE; }
    installThread = std::this_thread::get_id();
    suspended = {}; resourceRetry = {};
    if (!transact(changes, false)) {
        // Keep the adapter and engine alive if an OS permission failure made
        // rollback unprovable. Wrappers remain pass-through with allowed=false.
        bool allOriginal = true;
        for (const auto& c : changes) allOriginal = allOriginal && __atomic_load_n(c.slot, __ATOMIC_ACQUIRE) == c.before;
        status(NativeFailed);
        if (allOriginal) clearSession(env);
        else installed = std::move(changes);
        return JNI_FALSE;
    }
    installed = std::move(changes); allowed.store(true, std::memory_order_release);
    status(Waiting); return JNI_TRUE;
#endif
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        status(NativeFailed);
        if (!allowed.load() && installed.empty()) clearSession(env);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_krkrsdl3_Krkrsdl3Graphics_nativeClose(JNIEnv* env, jobject owner) {
    try {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!callback || !env->IsSameObject(callback, owner) || std::this_thread::get_id() != installThread) return;
    allowed.store(false, std::memory_order_release);
    frame = {};
    bool abandoned = resources.context == EGL_NO_CONTEXT || resources.context != eglGetCurrentContext()
            || resources.generation != contextGeneration.load();
    env->CallVoidMethod(callback, contextMethod, abandoned ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    resources.release();
    if (!installed.empty() && !transact(installed, true)) {
        // Do not unload code still referenced by a PLT/vtable or overwrite a
        // third-party hook. This bounded single session is retained to exit.
        status(NativeFailed); return;
    }
    installed.clear(); clearSession(env);
    } catch (...) {
        // Allocations in transaction planning occur before mprotect. Keep any
        // still-referenced adapter/session alive and pass through to the engine.
        if (env->ExceptionCheck()) env->ExceptionClear();
        status(NativeFailed);
    }
}
