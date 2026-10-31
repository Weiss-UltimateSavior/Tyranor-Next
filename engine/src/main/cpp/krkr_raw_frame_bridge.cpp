// Host-only adapter for the identified Kirikiroid2 Cocos binaries.
// No private engine object offsets, instruction patches, or engine replacement.
#include <jni.h>
#include "texture_row_origin.h"
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <sys/mman.h>
#include <unistd.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char* kInit = "_ZN7cocos2d16TrianglesCommand4initEfjPNS_14GLProgramStateENS_9BlendFuncERKNS0_9TrianglesERKNS_4Mat4Ej";
struct Vertex { float x,y,z; unsigned char color[4]; float u,v; };
struct Triangles { Vertex* vertices; unsigned short* indices; int vertexCount, indexCount; };
struct Blend { unsigned int src,dst; }; // Public Cocos ABI: passed BY VALUE in x3.
static_assert(sizeof(Vertex)==24 && sizeof(Triangles)==24 && sizeof(Blend)==8);
using Init = void(*)(void*,float,unsigned int,void*,Blend,const Triangles&,const void*,unsigned int);
using Update = void(*)(void*,void*);
using Getter = void*(*)(const void*);
using Draw = void(*)(void*,void*,const void*,unsigned int);
using Dimension = int(*)(const void*);
using Name = unsigned int(*)(const void*);
using Destroy = void(*)(void*);
using TextureCache = void(*)(unsigned int);
// Rect is non-trivial in the public Cocos ABI: its by-value argument is an
// indirect pointer in x1. The engine mutates that temporary, so every call below
// supplies a fresh copy and the hook captures the input before forwarding it.
struct Rect { float x,y,width,height; };
// Texture2D::getContentSize returns the public non-trivial Size through x8.
// A user-defined copy constructor preserves that return ABI without engine headers.
struct Size {
    float width,height;
    Size()=default;
    Size(const Size& value):width(value.width),height(value.height) {}
};
static_assert(sizeof(Rect)==16 && sizeof(Size)==8);
using Coords = void(*)(void*,Rect*);
using PaintBox = void(*)(void*,int,int);
using CreateRenderTexture = void*(*)(int,int);
using ChildCount = size_t(*)(const void*);
using Child = void*(*)(const void*,int);
using SetTexture = void(*)(void*,void*);
using GetBlend = const Blend&(*)(const void*);
using SetBlend = void(*)(void*,const Blend&);
using Opacity = unsigned char(*)(const void*);
using ContentSize = Size(*)(const void*);
using ContentPixels = const Size&(*)(void*);
Coords originalCoords=nullptr;
PaintBox originalPaintBox=nullptr,originalPaintBoxThunk=nullptr;
Destroy originalWindowD0=nullptr,originalWindowD1=nullptr;
Destroy originalWindowD0Thunk=nullptr,originalWindowD1Thunk=nullptr;
CreateRenderTexture createRenderTexture=nullptr;
ChildCount childrenCount=nullptr; Child childByTag=nullptr;
SetTexture setTexture=nullptr; GetBlend getBlend=nullptr; SetBlend setBlend=nullptr;
Opacity displayedOpacity=nullptr; Getter batchNode=nullptr;
ContentSize contentSize=nullptr; ContentPixels contentPixels=nullptr;
Destroy retainRef=nullptr,releaseRef=nullptr;
bool bitmap126=false;
Destroy originalD0=nullptr,originalD1=nullptr,originalD0Thunk=nullptr,originalD1Thunk=nullptr;
using TextureUnitCache = void(*)(unsigned int,unsigned int);
TextureUnitCache bindTextureUnitCache=nullptr;
TextureCache bindTextureCache=nullptr,activeTextureCache=nullptr;
void syncTextureCache() {
    if(!bindTextureCache || !activeTextureCache)return;
    GLint saved=GL_TEXTURE0;glGetIntegerv(GL_ACTIVE_TEXTURE,&saved);
    if(!bitmap126) {
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,0);
        bindTextureCache(0);
        activeTextureCache(static_cast<unsigned int>(saved));
        glActiveTexture(static_cast<GLenum>(saved));return;
    }
    // This ABI caches 16 texture units. Resynchronize through the public GL
    // functions; invalidateStateCache would also reset the scene matrix stack.
    constexpr unsigned int count=16;
    for(unsigned int unit=0;unit<count;++unit) {
        glActiveTexture(GL_TEXTURE0+unit);
        GLint texture=0;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
        glBindTexture(GL_TEXTURE_2D,0);
        bindTextureUnitCache(unit,0);bindTextureUnitCache(unit,static_cast<unsigned int>(texture));
    }
    activeTextureCache(static_cast<unsigned int>(saved));
    glActiveTexture(static_cast<GLenum>(saved));
}
void** spriteTable=nullptr;
Init originalInit=nullptr; Update originalUpdate=nullptr,originalUpdateThunk=nullptr;
Getter originalGetter=nullptr,originalGetterThunk=nullptr; Draw originalDraw=nullptr;
Dimension pixelsWide=nullptr,pixelsHigh=nullptr; Name textureName=nullptr;
jclass callbackClass=nullptr; jmethodID callbackMethod=nullptr;
bool installed=false;
thread_local unsigned int updateDepth=0;
thread_local bool leaseMutation=false;
thread_local void* updateWindow=nullptr;
thread_local void* windowSprite=nullptr;
// Only identity metadata is shared. The engine still owns object lifetimes;
// stage discovery and rendering must stay on one owner thread. Destructors on
// any thread remove tracked identities, but an off-owner tracked destruction
// makes reconstruction unsafe. Never call engine/GL/JNI code under this mutex.
constexpr size_t kMaxLiveSprites=1024;
std::mutex identityMutex;
struct TrackedSprite {
    void* sprite;
    void* window=nullptr;
    Rect rect{};
    bool rectKnown=false;
};
struct WindowStage { void* window; int width,height; };
std::vector<TrackedSprite> liveSprites;
std::vector<WindowStage> windowStages;
auto trackedSprite(void* sprite) {
    return std::find_if(liveSprites.begin(),liveSprites.end(),
        [sprite](const TrackedSprite& value){return value.sprite==sprite;});
}
std::thread::id identityOwner;
bool identityOwnerKnown=false;
std::atomic<bool> identityTrackingFailed{false};
// Called with identityMutex held. Unknown identities after overflow/OOM or an
// owner-thread mismatch cannot be recovered by a GL reset; bypass until restart.
bool identityThreadMatches() {
    const auto current=std::this_thread::get_id();
    if(!identityOwnerKnown) { identityOwner=current;identityOwnerKnown=true; }
    else if(identityOwner!=current) identityTrackingFailed.store(true);
    return !identityTrackingFailed.load();
}
void* completeObject(const void* self) {
    auto* table=*reinterpret_cast<intptr_t* const*>(self);
    return const_cast<char*>(static_cast<const char*>(self))+table[-2];
}
void forget(void* self) {
    void* top=completeObject(self);
    {
        std::lock_guard<std::mutex> lock(identityMutex);
        auto found=trackedSprite(top);
        if(found!=liveSprites.end()) {
            if(identityOwner!=std::this_thread::get_id()) identityTrackingFailed.store(true);
            liveSprites.erase(found);
        }
    }
    if(windowSprite==top)windowSprite=nullptr;
}
void d0Hook(void* self) { forget(self); originalD0(self); }
void d1Hook(void* self) { forget(self); originalD1(self); }
void d0ThunkHook(void* self) { forget(self); originalD0Thunk(self); }
void d1ThunkHook(void* self) { forget(self); originalD1Thunk(self); }
thread_local void* drawingSprite=nullptr;
thread_local JNIEnv* frameEnv=nullptr;
thread_local int surfaceWidth=0,surfaceHeight=0;
thread_local bool used=false,ambiguous=false;
thread_local void* frameSprite=nullptr;
thread_local std::array<Vertex,4> copiedVertices;
thread_local std::array<unsigned short,6> copiedIndices;

void runUpdate(void* self,void* texture,Update forward) {
    void* previous=updateWindow;
    updateWindow=completeObject(self);
    ++updateDepth;forward(self,texture);--updateDepth;
    updateWindow=previous;
}
void updateHook(void* self,void* texture) { runUpdate(self,texture,originalUpdate); }
void updateThunkHook(void* self,void* texture) { runUpdate(self,texture,originalUpdateThunk); }
void rememberStage(void* self,int width,int height) {
    void* top=completeObject(self);
    std::lock_guard<std::mutex> lock(identityMutex);
    if(!identityThreadMatches())return;
    auto found=std::find_if(windowStages.begin(),windowStages.end(),
        [top](const WindowStage& value){return value.window==top;});
    if(found!=windowStages.end()) { found->width=width;found->height=height; }
    else if(windowStages.size()>=kMaxLiveSprites)identityTrackingFailed.store(true);
    else try { windowStages.push_back({top,width,height}); }
    catch(const std::bad_alloc&) { identityTrackingFailed.store(true); }
}
void paintBoxHook(void* self,int width,int height) {
    rememberStage(self,width,height);originalPaintBox(self,width,height);
}
void paintBoxThunkHook(void* self,int width,int height) {
    rememberStage(self,width,height);originalPaintBoxThunk(self,width,height);
}
void forgetWindow(void* self) {
    void* top=completeObject(self);
    std::lock_guard<std::mutex> lock(identityMutex);
    auto found=std::find_if(windowStages.begin(),windowStages.end(),
        [top](const WindowStage& value){return value.window==top;});
    if(found!=windowStages.end()) {
        if(identityOwner!=std::this_thread::get_id())identityTrackingFailed.store(true);
        windowStages.erase(found);
    }
    for(auto& value:liveSprites)if(value.window==top) {
        value.window=nullptr;value.rectKnown=false;
    }
}
void windowD0Hook(void* self) { forgetWindow(self);originalWindowD0(self); }
void windowD1Hook(void* self) { forgetWindow(self);originalWindowD1(self); }
void windowD0ThunkHook(void* self) { forgetWindow(self);originalWindowD0Thunk(self); }
void windowD1ThunkHook(void* self) { forgetWindow(self);originalWindowD1Thunk(self); }
void remember(const void* self) {
    if (!updateDepth || leaseMutation) return;
    // The caller supplies a valid polymorphic Sprite. Itanium ABI gives the
    // adjustment for secondary bases without a private member offset.
    void* top=completeObject(self);
    // Only exact Sprite instances have the destructor slots guarded below.
    // Unknown subclasses remain entirely on their original drawing path.
    if (*static_cast<void***>(top)!=spriteTable+2) return;
    std::lock_guard<std::mutex> lock(identityMutex);
    if(!identityThreadMatches()) { ambiguous=true;return; }
    auto found=trackedSprite(top);
    if(found==liveSprites.end()) {
        if(liveSprites.size()>=kMaxLiveSprites) identityTrackingFailed.store(true);
        else try { liveSprites.push_back({top,updateWindow}); }
        catch(const std::bad_alloc&) {
            // An untracked live object cannot safely be assumed destroyed on reset.
            identityTrackingFailed.store(true);
        }
    }
    else if(bitmap126 && found->window!=updateWindow) {
        // A Sprite reused by a different window needs a new rectangle from that
        // window's UpdateDrawBuffer before it can qualify as a complete stage.
        found->window=updateWindow;found->rectKnown=false;
    }
    if(identityTrackingFailed.load() || liveSprites.size()!=1 || (frameSprite && frameSprite!=top)) ambiguous=true;
    frameSprite=top; windowSprite=top;
}
void* getterHook(const void* self) { remember(self); return originalGetter(self); }
void* getterThunkHook(const void* self) { remember(self); return originalGetterThunk(self); }
void prepareBitmapDraw(void* self);
void drawHook(void* self,void* renderer,const void* matrix,unsigned int flags) {
    void* previous=drawingSprite;
    bool eligible=false;
    if(self==windowSprite && !ambiguous) {
        std::lock_guard<std::mutex> lock(identityMutex);
        eligible=!identityTrackingFailed.load() && identityOwner==std::this_thread::get_id()
            && liveSprites.size()==1 && liveSprites.front().sprite==self;
    }
    drawingSprite=eligible?self:nullptr;
    if(bitmap126 && eligible)prepareBitmapDraw(self);
    originalDraw(self,renderer,matrix,flags);
    drawingSprite=previous;
}
bool near(float a,float b) { return std::abs(a-b)<0.0001f; }
void initHook(void* command,float z,unsigned int texture,void* program,Blend blend,
              const Triangles& triangles,const void* matrix,unsigned int flags) {
    // Commands retain the pointer arrays until nativeRender flushes. The only
    // replacement per frame leases these fixed arrays through nativeEndFrame.
    unsigned int output=0;
    float u0=1,v0=1,u1=0,v1=0;
    if (frameEnv && drawingSprite && !used && !ambiguous && !identityTrackingFailed.load() && texture &&
        blend.src==GL_ONE && blend.dst==GL_ZERO && triangles.vertexCount==4 &&
        triangles.indexCount==6 && triangles.vertices && triangles.indices) {
        void* source=originalGetter(drawingSprite);
        int aw=source?pixelsWide(source):0, ah=source?pixelsHigh(source):0;
        bool valid=source && textureName(source)==texture && aw>0 && ah>0 && aw<=16384 && ah<=16384;
        for(int i=0;i<4 && valid;++i) {
            const auto& p=triangles.vertices[i];
            valid=std::isfinite(p.u)&&std::isfinite(p.v)&&std::isfinite(p.x)&&std::isfinite(p.y)&&
                p.u>=0&&p.u<=1&&p.v>=0&&p.v<=1&&p.color[3]==255;
            u0=std::min(u0,p.u);u1=std::max(u1,p.u);v0=std::min(v0,p.v);v1=std::max(v1,p.v);
        }
        int cornerMask=0,indexMask=0;
        for(int i=0;i<4 && valid;++i) {
            const auto& p=triangles.vertices[i];
            valid=(near(p.u,u0)||near(p.u,u1))&&(near(p.v,v0)||near(p.v,v1));
            cornerMask|=1<<((near(p.u,u1)?1:0)+(near(p.v,v1)?2:0));
        }
        for(int i=0;i<6 && valid;++i) { valid=triangles.indices[i]<4; if(valid) indexMask|=1<<triangles.indices[i]; }
        int sw=std::lround((u1-u0)*aw), sh=std::lround((v1-v0)*ah);
        // Fractional texel crops would require resampling and change the input.
        valid=valid&&cornerMask==15&&indexMask==15&&sw>0&&sh>0&&
            near(u0*aw,std::round(u0*aw))&&near(u1*aw,std::round(u1*aw))&&
            near(v0*ah,std::round(v0*ah))&&near(v1*ah,std::round(v1*ah));
        const int origin=valid?textureRowOrigin(triangles.vertices,u0,v0,u1,v1):-1;
        valid=valid && origin>=0;
        if(valid) {
            used=true;
            output=static_cast<unsigned int>(frameEnv->CallStaticIntMethod(callbackClass,callbackMethod,
                static_cast<jint>(texture),sw,sh,u0,v0,u1,v1,surfaceWidth,surfaceHeight,origin==1?JNI_TRUE:JNI_FALSE));
            if(frameEnv->ExceptionCheck()) { frameEnv->ExceptionClear(); output=0; }
            // Resource deletion/reallocation can invalidate Cocos' texture-ID
            // cache even when the actual GL bindings were restored correctly.
            syncTextureCache();
        }
    }
    if(output && output!=texture) {
        std::copy_n(triangles.vertices,4,copiedVertices.begin());
        std::copy_n(triangles.indices,6,copiedIndices.begin());
        for(auto& p:copiedVertices) { p.u=(p.u-u0)/(u1-u0);p.v=(p.v-v0)/(v1-v0); }
        const Triangles replaced{copiedVertices.data(),copiedIndices.data(),4,6};
        originalInit(command,z,output,program,blend,replaced,matrix,flags);
    } else originalInit(command,z,texture,program,blend,triangles,matrix,flags);
}


// 1.2.6 calls TrianglesCommand::init directly, without an interposable PLT
// entry. Lease a genuine engine-owned Texture2D through the public Sprite APIs
// instead. The Sprite's vertices, content size, transform and hit testing stay
// untouched; its UVs and original texture are restored after renderer flush.
thread_local void* leasedSprite=nullptr;
thread_local void* leasedSource=nullptr;
thread_local Rect leasedRect{};
thread_local Blend leasedBlend{};
void coordsHook(void* self,Rect* rect) {
    const Rect input=*rect;
    if(!leaseMutation) {
        remember(self);
        std::lock_guard<std::mutex> lock(identityMutex);
        auto found=trackedSprite(completeObject(self));
        if(found!=liveSprites.end()) {
            if(identityOwner!=std::this_thread::get_id())identityTrackingFailed.store(true);
            found->rect=input;
            found->rectKnown=updateDepth && found->window==updateWindow;
        }
    }
    // Forward the caller's actual temporary as required by the by-value ABI.
    originalCoords(self,rect);
}
void setCoordsCopy(void* sprite,const Rect& rect) {
    Rect temporary=rect;
    originalCoords(sprite,&temporary);
}
void restoreLease(bool releaseReferences=true) {
    if(!leasedSprite)return;
    void* sprite=leasedSprite;void* source=leasedSource;
    leasedSprite=leasedSource=nullptr;
    leaseMutation=true;
    setTexture(sprite,source);setCoordsCopy(sprite,leasedRect);setBlend(sprite,leasedBlend);
    leaseMutation=false;
    if(releaseReferences) { releaseRef(source);releaseRef(sprite); }
}

// Covers the state changed by Cocos RenderTexture allocation, the shared
// pipeline, and our blit. No host framebuffer attachment or vertex data changes.
struct GlState;
thread_local GlState* currentGlState=nullptr;
struct GlState {
    GlState* previous=nullptr;
    static constexpr size_t kUnits=256;
    std::array<GLint,kUnits> textures{},samplers{};
    GLint count=0,active=GL_TEXTURE0,read=0,draw=0,renderbuffer=0;
    GLint program=0,vao=0,arrayBuffer=0,unpackBuffer=0;
    GLint viewport[4]{},scissor[4]{},unpack[6]{};
    GLboolean mask[4]{};
    GLfloat clear[4]{};
    static constexpr std::array<GLenum,10> capabilities={GL_BLEND,GL_DEPTH_TEST,
        GL_STENCIL_TEST,GL_SCISSOR_TEST,GL_CULL_FACE,GL_RASTERIZER_DISCARD,
        GL_SAMPLE_ALPHA_TO_COVERAGE,GL_SAMPLE_COVERAGE,GL_DITHER,GL_POLYGON_OFFSET_FILL};
    std::array<GLboolean,capabilities.size()> enabled{};
    static constexpr std::array<GLenum,6> unpackNames={GL_UNPACK_ALIGNMENT,
        GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_PIXELS,
        GL_UNPACK_IMAGE_HEIGHT,GL_UNPACK_SKIP_IMAGES};
    bool valid=false;
    GlState() {
        glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS,&count);
        if(count<=0 || static_cast<size_t>(count)>kUnits)return;
        glGetIntegerv(GL_ACTIVE_TEXTURE,&active);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,&read);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING,&draw);
        glGetIntegerv(GL_RENDERBUFFER_BINDING,&renderbuffer);
        glGetIntegerv(GL_CURRENT_PROGRAM,&program);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING,&vao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&arrayBuffer);
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING,&unpackBuffer);
        glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);
        glGetBooleanv(GL_COLOR_WRITEMASK,mask);glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);
        for(size_t i=0;i<capabilities.size();++i)enabled[i]=glIsEnabled(capabilities[i]);
        for(size_t i=0;i<unpackNames.size();++i)glGetIntegerv(unpackNames[i],&unpack[i]);
        for(GLint i=0;i<count;++i) {
            glActiveTexture(GL_TEXTURE0+i);
            glGetIntegerv(GL_TEXTURE_BINDING_2D,&textures[i]);
            glGetIntegerv(GL_SAMPLER_BINDING,&samplers[i]);
        }
        glActiveTexture(active);valid=true;
        previous=currentGlState;currentGlState=this;
    }
    void forgetTexture(unsigned int texture) {
        for(GLint i=0;i<count;++i)if(static_cast<unsigned int>(textures[i])==texture)textures[i]=0;
        if(previous)previous->forgetTexture(texture);
    }
    void forgetFramebuffer(unsigned int framebuffer) {
        if(static_cast<unsigned int>(read)==framebuffer)read=0;
        if(static_cast<unsigned int>(draw)==framebuffer)draw=0;
        if(previous)previous->forgetFramebuffer(framebuffer);
    }
    ~GlState() {
        if(!valid)return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        glScissor(scissor[0],scissor[1],scissor[2],scissor[3]);
        glColorMask(mask[0],mask[1],mask[2],mask[3]);
        glClearColor(clear[0],clear[1],clear[2],clear[3]);
        glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,arrayBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);
        for(size_t i=0;i<unpackNames.size();++i)glPixelStorei(unpackNames[i],unpack[i]);
        for(size_t i=0;i<capabilities.size();++i) {
            if(enabled[i])glEnable(capabilities[i]);else glDisable(capabilities[i]);
        }
        for(GLint i=0;i<count;++i) {
            glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i]);
            glBindSampler(i,samplers[i]);
        }
        glActiveTexture(active);syncTextureCache();
        currentGlState=previous;
    }
};
thread_local void* carrier=nullptr;
thread_local void* carrierTexture=nullptr;
thread_local int carrierWidth=0,carrierHeight=0;
thread_local GLuint readFbo=0,drawFbo=0;
thread_local unsigned int carrierRetryFrames=0;
// On unexpected EGL loss an old RenderTexture destructor could delete recycled
// GL names. At most one retained carrier is abandoned and this entry is disabled
// until process restart. Normal pause releases it while its context is current.
std::atomic<bool> carrierAbandoned{false};
void dropCarrier() {
    if(currentGlState) {
        if(carrierTexture)currentGlState->forgetTexture(textureName(carrierTexture));
        if(readFbo)currentGlState->forgetFramebuffer(readFbo);
        if(drawFbo)currentGlState->forgetFramebuffer(drawFbo);
    }
    if(carrier)releaseRef(carrier);
    carrier=carrierTexture=nullptr;carrierWidth=carrierHeight=0;
    if(readFbo)glDeleteFramebuffers(1,&readFbo);
    if(drawFbo)glDeleteFramebuffers(1,&drawFbo);
    readFbo=drawFbo=0;
}
bool ensureCarrier(int width,int height) {
    if(carrier && carrierWidth==width && carrierHeight==height)return true;
    dropCarrier();
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
    for(size_t i=1;i<GlState::unpackNames.size();++i)glPixelStorei(GlState::unpackNames[i],0);
    leaseMutation=true;
    carrier=createRenderTexture(width,height);
    leaseMutation=false;
    if(!carrier)return false;
    retainRef(carrier);
    // RenderTexture::create has exactly one unnamed display Sprite in this
    // identified ABI. Use Node's public child APIs instead of member offsets.
    void* sprite=childrenCount(carrier)==1?childByTag(carrier,-1):nullptr;
    carrierTexture=sprite && *static_cast<void***>(sprite)==spriteTable+2?originalGetter(sprite):nullptr;
    if(!carrierTexture || pixelsWide(carrierTexture)!=width || pixelsHigh(carrierTexture)!=height) {
        dropCarrier();return false;
    }
    glGenFramebuffers(1,&readFbo);glGenFramebuffers(1,&drawFbo);
    if(!readFbo || !drawFbo) { dropCarrier();return false; }
    carrierWidth=width;carrierHeight=height;return true;
}
bool glStageSucceeded(const char* stage) {
    // Drain inherited errors separately before starting an owned operation, so
    // a later failure is attributed to the operation that actually produced it.
    // A context-lost driver must not turn this into an unbounded error loop.
    bool valid=true;
    for(unsigned int count=0;count<32;++count) {
        const GLenum error=glGetError();
        if(error==GL_NO_ERROR)return valid;
        valid=false;
        __android_log_print(ANDROID_LOG_WARN,"Kr2RawFrame","raw_gl_stage=%s error=0x%x",stage,error);
    }
    return false;
}
bool copyToCarrier(unsigned int output,int width,int height) {
    if(!glStageSucceeded("carrier_entry"))return false;
    const bool ready=ensureCarrier(width,height);
    const bool created=glStageSucceeded("carrier_create");
    if(!ready || !created) {
        dropCarrier();glStageSucceeded("carrier_discard");return false;
    }
    const auto target=textureName(carrierTexture);
    if(!target || target==output || readFbo==drawFbo)return false;
    // The RenderTexture's own FBO may still have target attached, but it is
    // neither bound nor read here. Only our two FBOs participate in the blit;
    // output and target are distinct texture images, so there is no feedback.
    glBindFramebuffer(GL_READ_FRAMEBUFFER,readFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER,drawFbo);
    // Check binding before attachment changes: a failed bind must never leave
    // attachment operations targeting the engine's previously bound framebuffer.
    if(!glStageSucceeded("carrier_bind"))return false;
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
    const GLenum attachment=GL_COLOR_ATTACHMENT0;glDrawBuffers(1,&attachment);
    const GLenum readStatus=glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
    const GLenum drawStatus=glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
    if(readStatus!=GL_FRAMEBUFFER_COMPLETE || drawStatus!=GL_FRAMEBUFFER_COMPLETE)
        __android_log_print(ANDROID_LOG_WARN,"Kr2RawFrame","raw_gl_stage=carrier_setup read=0x%x draw=0x%x",readStatus,drawStatus);
    bool valid=glStageSucceeded("carrier_setup") && readStatus==GL_FRAMEBUFFER_COMPLETE &&
        drawStatus==GL_FRAMEBUFFER_COMPLETE;
    if(valid) {
        glDisable(GL_SCISSOR_TEST);
        glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
        valid=glStageSucceeded("carrier_blit");
    }
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);
    const bool detached=glStageSucceeded("carrier_detach");
    return valid && detached;
}
void prepareBitmapDraw(void* self) {
    if(!frameEnv || used || ambiguous || leasedSprite || carrierAbandoned.load() || identityTrackingFailed.load())return;
    Rect rect{};int width=0,height=0;
    {
        std::lock_guard<std::mutex> lock(identityMutex);
        auto sprite=trackedSprite(self);
        if(sprite==liveSprites.end() || !sprite->rectKnown)return;
        auto stage=std::find_if(windowStages.begin(),windowStages.end(),
            [&](const WindowStage& value){return value.window==sprite->window;});
        if(stage==windowStages.end())return;
        rect=sprite->rect;width=stage->width;height=stage->height;
    }
    if(width<=0 || height<=0 || width>16384 || height>16384 ||
        !near(rect.x,0) || !near(rect.y,0) || !near(rect.width,width) || !near(rect.height,height) ||
        batchNode(self) || displayedOpacity(self)!=255)return;
    void* source=originalGetter(self);
    const Blend blend=getBlend(self);
    if(!source || blend.src!=GL_ONE || blend.dst!=GL_ZERO)return;
    const int aw=pixelsWide(source),ah=pixelsHigh(source);
    const unsigned int input=textureName(source);
    if(!input || aw<width || ah<height || aw>16384 || ah>16384)return;
    // Both APIs are public. Their ratio exposes the current Director content
    // scale without reading Director fields. Only a 1:1 texel rectangle is safe.
    const Size points=contentSize(source);
    const Size& pixels=contentPixels(source);
    if(!std::isfinite(points.width) || !std::isfinite(points.height) ||
        points.width<=0 || points.height<=0 || !near(pixels.width,points.width) ||
        !near(pixels.height,points.height))return;
    GlState state;if(!state.valid)return;
    used=true;
    const auto packed=static_cast<uint64_t>(frameEnv->CallStaticLongMethod(callbackClass,callbackMethod,
        static_cast<jint>(input),width,height,0.0f,0.0f,static_cast<float>(width)/aw,
        static_cast<float>(height)/ah,surfaceWidth,surfaceHeight));
    if(frameEnv->ExceptionCheck()) { frameEnv->ExceptionClear();return; }
    const auto output=static_cast<unsigned int>(packed);
    const int ow=static_cast<int>((packed>>32)&0xffffu),oh=static_cast<int>((packed>>48)&0xffffu);
    if(!output || output==input || ow<=0 || oh<=0 || ow>16384 || oh>16384 ||
        !carrierTexture || carrierWidth!=ow || carrierHeight!=oh)return;
    retainRef(self);retainRef(source);
    leasedSprite=self;leasedSource=source;leasedRect=rect;leasedBlend=blend;
    leaseMutation=true;
    setTexture(self,carrierTexture);
    setCoordsCopy(self,{0,0,static_cast<float>(ow),static_cast<float>(oh)});
    setBlend(self,blend);
    leaseMutation=false;
}

struct Image { uintptr_t base=0; const Elf64_Phdr* phdr=nullptr; size_t count=0; std::string path; };
int findImage(dl_phdr_info* info,size_t,void* value) {
    auto* image=static_cast<Image*>(value);
    // The Android linker stores a resolved FD path, which can differ from
    // Java's /data/user/0 or /data/data spelling for the same loaded file.
    // The verified symbol's image base identifies it without a path alias.
    if(static_cast<uintptr_t>(info->dlpi_addr)==image->base) {
        image->base=info->dlpi_addr;image->phdr=info->dlpi_phdr;image->count=info->dlpi_phnum;return 1;
    } return 0;
}
bool identified(const Image& image) {
    const char* expected=image.path.size()>=11 && image.path.compare(image.path.size()-11,11,"/libgame.so")==0?
        "de22234bffa0545d276b705487ca0c3d35101386":
        image.path.size()>=14 && image.path.compare(image.path.size()-14,14,"/libgame134.so")==0?"97c91143725ba2cf99471c0aed309cf66703fc79":
        image.path.size()>=14 && image.path.compare(image.path.size()-14,14,"/libgame126.so")==0?"aaeecb4392e474ff267639faa0d5689210ca716e":nullptr;
    if(!expected)return false;
    for (size_t i = 0; i < image.count; ++i) {
        const auto& noteSegment = image.phdr[i];
        if (noteSegment.p_type != PT_NOTE) continue;
        bool readable = false;
        for (size_t j = 0; j < image.count; ++j) {
            const auto& load = image.phdr[j];
            if (load.p_type != PT_LOAD || !(load.p_flags & PF_R) || noteSegment.p_vaddr < load.p_vaddr) continue;
            Elf64_Addr offset = noteSegment.p_vaddr - load.p_vaddr;
            if (offset <= load.p_memsz && noteSegment.p_memsz <= load.p_memsz - offset) readable = true;
        }
        if (!readable || noteSegment.p_vaddr > std::numeric_limits<uintptr_t>::max() - image.base) continue;
        uintptr_t start = image.base + noteSegment.p_vaddr;
        if (noteSegment.p_memsz > std::numeric_limits<uintptr_t>::max() - start) continue;
        const auto* at = reinterpret_cast<const unsigned char*>(start);
        size_t length = noteSegment.p_memsz;
        while (length >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{};
            std::memcpy(&note, at, sizeof(note));
            size_t name = (static_cast<size_t>(note.n_namesz) + 3u) & ~size_t{3};
            size_t desc = (static_cast<size_t>(note.n_descsz) + 3u) & ~size_t{3};
            if (name > length - sizeof(note) || desc > length - sizeof(note) - name) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == 20
                    && std::memcmp(at + sizeof(note), "GNU", 4) == 0) {
                char hex[41]{};
                const unsigned char* id = at + sizeof(note) + name;
                for (size_t n = 0; n < 20; ++n) {
                    hex[2 * n] = "0123456789abcdef"[id[n] >> 4];
                    hex[2 * n + 1] = "0123456789abcdef"[id[n] & 15];
                }
                return std::strcmp(hex, expected) == 0;
            }
            size_t total = sizeof(note) + name + desc;
            at += total;
            length -= total;
        }
    }
    return false;
}

struct Change { void** slot; void* before; void* after; int protection; };
bool collect(const Image& image,std::vector<Change>& changes,void** slot,void* before,void* after) {
    if(!before||!after||*slot!=before)return false;
    for(size_t i=0;i<image.count;++i) {
        const auto& p=image.phdr[i];uintptr_t start=image.base+p.p_vaddr,end=start+p.p_memsz;
        if(p.p_type==PT_LOAD && reinterpret_cast<uintptr_t>(slot)>=start &&
           reinterpret_cast<uintptr_t>(slot)+sizeof(void*)<=end) {
            int prot=PROT_READ|(p.p_flags&PF_X?PROT_EXEC:0)|(p.p_flags&PF_W?PROT_WRITE:0);
            // RELRO covers writable .data pages after relocation.
            for(size_t j=0;j<image.count;++j) if(image.phdr[j].p_type==PT_GNU_RELRO) {
                uintptr_t rs=image.base+image.phdr[j].p_vaddr,re=rs+image.phdr[j].p_memsz;
                if(reinterpret_cast<uintptr_t>(slot)>=rs&&reinterpret_cast<uintptr_t>(slot)<re) prot&=~PROT_WRITE;
            }
            changes.push_back({slot,before,after,prot});return true;
        }
    }return false;
}
bool tableChanges(const Image& image,void* handle,std::vector<Change>& changes,
                  const char* tableName,size_t bytes,void* before,void* after) {
    auto** table=static_cast<void**>(dlsym(handle,tableName));if(!table||!before)return false;
    size_t found=0;
    for(size_t i=0;i<bytes/sizeof(void*);++i) if(table[i]==before) {
        if(!collect(image,changes,table+i,before,after))return false;++found;
    }return found>0;
}
bool change(const Change& value,bool restore) {
    long pageSize=sysconf(_SC_PAGESIZE);if(pageSize<=0)return false;
    uintptr_t page=reinterpret_cast<uintptr_t>(value.slot)&~(static_cast<uintptr_t>(pageSize)-1);
    if(mprotect(reinterpret_cast<void*>(page),pageSize,value.protection|PROT_WRITE)!=0)return false;
    __atomic_store_n(value.slot,restore?value.before:value.after,__ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page),pageSize,value.protection)==0;
}
uintptr_t address(uintptr_t base,Elf64_Addr ptr) { return ptr<base?base+ptr:ptr; }
// Stable JNI failure stages; Java maps these values to its typed status.
enum class InstallStage : int {
    Installed=0, Argument=1, LibraryLookup=2, ImageLookup=3, BuildId=4,
    Symbols=5, Vtable=6, Relocation=7, Callback=8, Protection=9, Rollback=10
};
jint report(InstallStage stage) {
    const int value=static_cast<int>(stage);
    __android_log_print(value ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                        "Kr2RawFrame", "raw_install_stage=%d", value);
    return value;
}
InstallStage install(JNIEnv* env,jclass cls,const std::string& path) {
    if(installed)return InstallStage::Installed;
    // Query the existing mapping first. Never load a replacement engine.
    struct LoadedLibrary {
        void* handle;
        ~LoadedLibrary() { if(handle)dlclose(handle); }
    } library{dlopen(path.c_str(),RTLD_NOW|RTLD_NOLOAD)};
    void* handle=library.handle;
    if(!handle) {
        const char* failure=dlerror();
        __android_log_print(ANDROID_LOG_WARN,"Kr2RawFrame","Already-loaded engine lookup failed: %s",
                            failure?failure:"unknown linker error");
        return InstallStage::LibraryLookup;
    }
    auto resolve=[&](const char* symbol){return dlsym(handle,symbol);};
    originalInit=reinterpret_cast<Init>(resolve(kInit));
    if(!originalInit)return InstallStage::Symbols;
    Dl_info symbolImage{};
    if(dladdr(reinterpret_cast<void*>(originalInit),&symbolImage)==0 || !symbolImage.dli_fbase)
        return InstallStage::ImageLookup;
    Image image;
    image.path=path;
    image.base=reinterpret_cast<uintptr_t>(symbolImage.dli_fbase);
    dl_iterate_phdr(findImage,&image);
    if(!image.phdr)return InstallStage::ImageLookup;
    if(!identified(image))return InstallStage::BuildId;
    bitmap126=path.size()>=14 && path.compare(path.size()-14,14,"/libgame126.so")==0;
    originalUpdate=reinterpret_cast<Update>(resolve(bitmap126?
        "_ZN14TVPWindowLayer16UpdateDrawBufferEPK14iTVPBaseBitmap":
        "_ZN14TVPWindowLayer16UpdateDrawBufferEP13iTVPTexture2D"));
    originalUpdateThunk=reinterpret_cast<Update>(resolve(bitmap126?
        "_ZThn1040_N14TVPWindowLayer16UpdateDrawBufferEPK14iTVPBaseBitmap":
        "_ZThn1040_N14TVPWindowLayer16UpdateDrawBufferEP13iTVPTexture2D"));
    originalGetter=reinterpret_cast<Getter>(resolve("_ZNK7cocos2d6Sprite10getTextureEv"));
    originalGetterThunk=reinterpret_cast<Getter>(resolve("_ZThn720_NK7cocos2d6Sprite10getTextureEv"));
    originalDraw=reinterpret_cast<Draw>(resolve("_ZN7cocos2d6Sprite4drawEPNS_8RendererERKNS_4Mat4Ej"));
    pixelsWide=reinterpret_cast<Dimension>(resolve("_ZNK7cocos2d9Texture2D13getPixelsWideEv"));
    pixelsHigh=reinterpret_cast<Dimension>(resolve("_ZNK7cocos2d9Texture2D13getPixelsHighEv"));
    textureName=reinterpret_cast<Name>(resolve("_ZNK7cocos2d9Texture2D7getNameEv"));
    bindTextureCache=reinterpret_cast<TextureCache>(resolve("_ZN7cocos2d2GL13bindTexture2DEj"));
    activeTextureCache=reinterpret_cast<TextureCache>(resolve("_ZN7cocos2d2GL13activeTextureEj"));
    spriteTable=static_cast<void**>(resolve("_ZTVN7cocos2d6SpriteE"));
    originalD0=reinterpret_cast<Destroy>(resolve("_ZN7cocos2d6SpriteD0Ev"));
    originalD1=reinterpret_cast<Destroy>(resolve("_ZN7cocos2d6SpriteD1Ev"));
    originalD0Thunk=reinterpret_cast<Destroy>(resolve("_ZThn720_N7cocos2d6SpriteD0Ev"));
    originalD1Thunk=reinterpret_cast<Destroy>(resolve("_ZThn720_N7cocos2d6SpriteD1Ev"));
    if(!originalUpdate||!originalUpdateThunk||!originalGetter||!originalGetterThunk||
       !originalDraw||!pixelsWide||!pixelsHigh||!textureName||!bindTextureCache||
       !activeTextureCache||!spriteTable||!originalD0||!originalD1||
       !originalD0Thunk||!originalD1Thunk)return InstallStage::Symbols;
    if(bitmap126) {
        originalCoords=reinterpret_cast<Coords>(resolve("_ZN7cocos2d6Sprite16setTextureCoordsENS_4RectE"));
        originalPaintBox=reinterpret_cast<PaintBox>(resolve("_ZN14TVPWindowLayer15SetPaintBoxSizeEii"));
        originalPaintBoxThunk=reinterpret_cast<PaintBox>(resolve("_ZThn1040_N14TVPWindowLayer15SetPaintBoxSizeEii"));
        originalWindowD0=reinterpret_cast<Destroy>(resolve("_ZN14TVPWindowLayerD0Ev"));
        originalWindowD1=reinterpret_cast<Destroy>(resolve("_ZN14TVPWindowLayerD1Ev"));
        originalWindowD0Thunk=reinterpret_cast<Destroy>(resolve("_ZThn760_N14TVPWindowLayerD0Ev"));
        originalWindowD1Thunk=reinterpret_cast<Destroy>(resolve("_ZThn760_N14TVPWindowLayerD1Ev"));
        createRenderTexture=reinterpret_cast<CreateRenderTexture>(resolve("_ZN7cocos2d13RenderTexture6createEii"));
        childrenCount=reinterpret_cast<ChildCount>(resolve("_ZNK7cocos2d4Node16getChildrenCountEv"));
        childByTag=reinterpret_cast<Child>(resolve("_ZNK7cocos2d4Node13getChildByTagEi"));
        setTexture=reinterpret_cast<SetTexture>(resolve("_ZN7cocos2d6Sprite10setTextureEPNS_9Texture2DE"));
        getBlend=reinterpret_cast<GetBlend>(resolve("_ZNK7cocos2d6Sprite12getBlendFuncEv"));
        setBlend=reinterpret_cast<SetBlend>(resolve("_ZN7cocos2d6Sprite12setBlendFuncERKNS_9BlendFuncE"));
        displayedOpacity=reinterpret_cast<Opacity>(resolve("_ZNK7cocos2d4Node19getDisplayedOpacityEv"));
        batchNode=reinterpret_cast<Getter>(resolve("_ZNK7cocos2d6Sprite12getBatchNodeEv"));
        contentSize=reinterpret_cast<ContentSize>(resolve("_ZNK7cocos2d9Texture2D14getContentSizeEv"));
        contentPixels=reinterpret_cast<ContentPixels>(resolve("_ZN7cocos2d9Texture2D22getContentSizeInPixelsEv"));
        retainRef=reinterpret_cast<Destroy>(resolve("_ZN7cocos2d3Ref6retainEv"));
        releaseRef=reinterpret_cast<Destroy>(resolve("_ZN7cocos2d3Ref7releaseEv"));
        bindTextureUnitCache=reinterpret_cast<TextureUnitCache>(resolve("_ZN7cocos2d2GL14bindTexture2DNEjj"));
        if(!originalCoords || !originalPaintBox || !originalPaintBoxThunk || !originalWindowD0 ||
            !originalWindowD1 || !originalWindowD0Thunk || !originalWindowD1Thunk || !createRenderTexture ||
            !childrenCount || !childByTag || !setTexture || !getBlend || !setBlend || !displayedOpacity ||
            !batchNode || !contentSize || !contentPixels || !retainRef || !releaseRef || !bindTextureUnitCache)return InstallStage::Symbols;
    }
    std::vector<Change> changes;
    const size_t windowTableBytes=bitmap126?2120:2360;
    bool ok=tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalUpdate,(void*)updateHook)&&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalUpdateThunk,(void*)updateThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalGetter,(void*)getterHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalGetterThunk,(void*)getterThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalDraw,(void*)drawHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD0,(void*)d0Hook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD1,(void*)d1Hook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD0Thunk,(void*)d0ThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD1Thunk,(void*)d1ThunkHook);
    if(bitmap126)ok=ok &&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalCoords,(void*)coordsHook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalPaintBox,(void*)paintBoxHook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalPaintBoxThunk,(void*)paintBoxThunkHook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalWindowD0,(void*)windowD0Hook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalWindowD1,(void*)windowD1Hook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalWindowD0Thunk,(void*)windowD0ThunkHook) &&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalWindowD1Thunk,(void*)windowD1ThunkHook);
    if(!ok)return InstallStage::Vtable;
    const Elf64_Dyn* dynamic=nullptr;size_t dynamicCount=0;
    for(size_t i=0;i<image.count;++i) if(image.phdr[i].p_type==PT_DYNAMIC) {
        dynamic=reinterpret_cast<const Elf64_Dyn*>(image.base+image.phdr[i].p_vaddr);
        dynamicCount=image.phdr[i].p_memsz/sizeof(Elf64_Dyn);
    }
    const Elf64_Sym* symbols=nullptr;const char* strings=nullptr;const Elf64_Rela* relocs=nullptr;
    size_t relocSize=0;bool rela=false;
    for(size_t i=0;dynamic&&i<dynamicCount&&dynamic[i].d_tag!=DT_NULL;++i) {
        const auto& d=dynamic[i];
        if(d.d_tag==DT_SYMTAB)symbols=reinterpret_cast<const Elf64_Sym*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_STRTAB)strings=reinterpret_cast<const char*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_JMPREL)relocs=reinterpret_cast<const Elf64_Rela*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_PLTRELSZ)relocSize=d.d_un.d_val;
        if(d.d_tag==DT_PLTREL)rela=d.d_un.d_val==DT_RELA;
    }
    int found=0;
    if(!bitmap126 && symbols&&strings&&relocs&&rela&&relocSize%sizeof(Elf64_Rela)==0) {
        for(size_t i=0;i<relocSize/sizeof(Elf64_Rela);++i) if(ELF64_R_TYPE(relocs[i].r_info)==R_AARCH64_JUMP_SLOT&&
            std::strcmp(strings+symbols[ELF64_R_SYM(relocs[i].r_info)].st_name,kInit)==0) {
            ok=collect(image,changes,reinterpret_cast<void**>(image.base+relocs[i].r_offset),(void*)originalInit,(void*)initHook)&&ok;
            ++found;
        }
    }
    if(!ok || (!bitmap126 && found==0))return InstallStage::Relocation;
    callbackMethod=env->GetStaticMethodID(cls,bitmap126?"process126":"process",bitmap126?"(IIIFFFFII)J":"(IIIFFFFIIZ)I");
    if(callbackMethod&&!env->ExceptionCheck())callbackClass=static_cast<jclass>(env->NewGlobalRef(cls));
    if(!callbackMethod||!callbackClass||env->ExceptionCheck()) {
        if(callbackClass)env->DeleteGlobalRef(callbackClass);
        callbackClass=nullptr;callbackMethod=nullptr;
        if(env->ExceptionCheck())env->ExceptionClear();
        return InstallStage::Callback;
    }
    size_t applied=0;
    for(;applied<changes.size();++applied) if(!change(changes[applied],false)) {
        // A failed protection restore can follow an already-written slot.
        // Include this slot in rollback even if the first mprotect failed.
        ++applied;
        bool restored=true;
        while(applied)restored=change(changes[--applied],true)&&restored;
        env->DeleteGlobalRef(callbackClass);
        callbackClass=nullptr;callbackMethod=nullptr;
        return restored?InstallStage::Protection:InstallStage::Rollback;
    }
    installed=true;
    return InstallStage::Installed;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeInstall(JNIEnv* env,jclass cls,jstring library) {
    if(!library)return report(InstallStage::Argument);
    const char* chars=env->GetStringUTFChars(library,nullptr);
    if(!chars) {
        if(env->ExceptionCheck())env->ExceptionClear();
        return report(InstallStage::Argument);
    }
    try {
        std::string path(chars);
        env->ReleaseStringUTFChars(library,chars);chars=nullptr;
        return report(path.empty()?InstallStage::Argument:install(env,cls,path));
    } catch(const std::bad_alloc&) {
        // The string copy and Change-vector construction happen before any hook
        // is applied. LoadedLibrary closes the NOLOAD handle during unwinding.
        if(chars)env->ReleaseStringUTFChars(library,chars);
        if(env->ExceptionCheck())env->ExceptionClear();
        return report(InstallStage::Symbols);
    } catch(...) {
        // Never propagate a C++ exception through the JNI boundary.
        if(chars)env->ReleaseStringUTFChars(library,chars);
        if(env->ExceptionCheck())env->ExceptionClear();
        return report(InstallStage::Symbols);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeBeginFrame(JNIEnv* env,jclass,jint w,jint h) {
    frameEnv=env;surfaceWidth=w;surfaceHeight=h;used=false;frameSprite=nullptr;
    std::lock_guard<std::mutex> lock(identityMutex);
    bool ownerMatches=identityThreadMatches();
    ambiguous=!ownerMatches||liveSprites.size()>1;
    windowSprite=ownerMatches && liveSprites.size()==1 ? liveSprites.front().sprite : nullptr;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativePrepareCarrier(JNIEnv*,jclass,jint width,jint height) {
    // Called only by process126 while the outer GlState guard is active, before
    // pipeline target allocation. Drop old-size storage before budgeting the new
    // complete chain, rather than overlapping old and new carrier allocations.
    if(!bitmap126 || !frameEnv || !currentGlState || leasedSprite || carrierAbandoned.load() ||
        width<=0 || height<=0 || width>16384 || height>16384)return JNI_FALSE;
    if(!glStageSucceeded("carrier_prepare_entry"))return JNI_FALSE;
    if(carrier && (carrierWidth!=width || carrierHeight!=height)) {
        dropCarrier();
        if(!glStageSucceeded("carrier_prepare_drop"))return JNI_FALSE;
    }
    if(carrierRetryFrames) { --carrierRetryFrames;return JNI_FALSE; }
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeUploadCarrier(JNIEnv*,jclass,jint texture,jint width,jint height) {
    // Allocation happens only after the complete pipeline has accepted its
    // reservedBytes budget. No oversized carrier is allocated for a rejected plan.
    if(!bitmap126 || !frameEnv || !currentGlState || leasedSprite || carrierAbandoned.load() ||
        !texture || width<=0 || height<=0 || width>16384 || height>16384)return JNI_FALSE;
    if(copyToCarrier(static_cast<unsigned int>(texture),width,height))return JNI_TRUE;
    carrierRetryFrames=60;return JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeEndFrame(JNIEnv*,jclass) {
    // Cocos nativeRender has flushed all queued commands before this callback.
    if(bitmap126)restoreLease();
    frameEnv=nullptr;drawingSprite=nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeResetContext(JNIEnv*,jclass,jboolean release) {
    if(bitmap126) {
        carrierRetryFrames=0;
        if(release) {
            GlState state;
            if(state.valid) { restoreLease();dropCarrier(); }
        } else if(carrier || readFbo || drawFbo || leasedSprite) {
            carrierAbandoned.store(true);
            // Pure Sprite setters can restore an interrupted lease: the
            // retained RenderTexture still owns its Texture2D, so this cannot
            // destroy it. Keep the two extra lease refs as part of this single
            // bounded abandonment rather than running old-context destructors.
            restoreLease(false);
            carrier=carrierTexture=leasedSprite=leasedSource=nullptr;
            carrierWidth=carrierHeight=0;readFbo=drawFbo=0;
        }
    }
    frameEnv=nullptr;windowSprite=drawingSprite=frameSprite=nullptr;used=ambiguous=false;updateDepth=0;updateWindow=nullptr;
    // GL resource resets do not destroy engine Sprite objects. Retain their identity
    // set and any tracking failure across pause/resume; only destructors remove entries.
    if(installed) {
        { std::lock_guard<std::mutex> lock(identityMutex);identityThreadMatches(); }
        syncTextureCache();
    }
}
