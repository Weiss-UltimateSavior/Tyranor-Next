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

Destroy originalD0=nullptr,originalD1=nullptr,originalD0Thunk=nullptr,originalD1Thunk=nullptr;
TextureCache bindTextureCache=nullptr,activeTextureCache=nullptr;
void syncTextureCache() {
    if(!bindTextureCache || !activeTextureCache)return;
    GLint saved=GL_TEXTURE0;glGetIntegerv(GL_ACTIVE_TEXTURE,&saved);
    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,0);
    bindTextureCache(0);
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
thread_local void* windowSprite=nullptr;
// Only identity metadata is shared. The engine still owns object lifetimes;
// stage discovery and rendering must stay on one owner thread. Destructors on
// any thread remove tracked identities, but an off-owner tracked destruction
// makes reconstruction unsafe. Never call engine/GL/JNI code under this mutex.
constexpr size_t kMaxLiveSprites=1024;
std::mutex identityMutex;
std::vector<void*> liveSprites;
auto trackedSprite(void* sprite) {
    return std::find(liveSprites.begin(),liveSprites.end(),sprite);
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
thread_local bool drawBufferUpdated=true;

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
    if(windowSprite==top) { windowSprite=nullptr; drawBufferUpdated=true; }
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
    drawBufferUpdated=true;
    ++updateDepth;forward(self,texture);--updateDepth;
}
void updateHook(void* self,void* texture) { runUpdate(self,texture,originalUpdate); }
void updateThunkHook(void* self,void* texture) { runUpdate(self,texture,originalUpdateThunk); }
void remember(const void* self) {
    if (!updateDepth) return;
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
        else try { liveSprites.push_back(top); }
        catch(const std::bad_alloc&) {
            // An untracked live object cannot safely be assumed destroyed on reset.
            identityTrackingFailed.store(true);
        }
    }
    if(identityTrackingFailed.load() || liveSprites.size()!=1 || (frameSprite && frameSprite!=top)) ambiguous=true;
    frameSprite=top; windowSprite=top;
}
void* getterHook(const void* self) { remember(self); return originalGetter(self); }
void* getterThunkHook(const void* self) { remember(self); return originalGetterThunk(self); }
void drawHook(void* self,void* renderer,const void* matrix,unsigned int flags) {
    void* previous=drawingSprite;
    bool eligible=false;
    if(self==windowSprite && !ambiguous) {
        std::lock_guard<std::mutex> lock(identityMutex);
        eligible=!identityTrackingFailed.load() && identityOwner==std::this_thread::get_id()
            && liveSprites.size()==1 && liveSprites.front()==self;
    }
    drawingSprite=eligible?self:nullptr;
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
            bool frameDirty=drawBufferUpdated;
            drawBufferUpdated=false;
            used=true;
            output=static_cast<unsigned int>(frameEnv->CallStaticIntMethod(callbackClass,callbackMethod,
                static_cast<jint>(texture),sw,sh,u0,v0,u1,v1,surfaceWidth,surfaceHeight,
                origin==1?JNI_TRUE:JNI_FALSE,frameDirty?JNI_TRUE:JNI_FALSE));
            if(frameEnv->ExceptionCheck()) { frameEnv->ExceptionClear(); output=0; }
            if(output==0) drawBufferUpdated=true;
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
        image.path.size()>=14 && image.path.compare(image.path.size()-14,14,"/libgame134.so")==0?"97c91143725ba2cf99471c0aed309cf66703fc79":nullptr;
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
    originalUpdate=reinterpret_cast<Update>(resolve("_ZN14TVPWindowLayer16UpdateDrawBufferEP13iTVPTexture2D"));
    originalUpdateThunk=reinterpret_cast<Update>(resolve("_ZThn1040_N14TVPWindowLayer16UpdateDrawBufferEP13iTVPTexture2D"));
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

    std::vector<Change> changes;
    const size_t windowTableBytes=2360;
    bool ok=tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalUpdate,(void*)updateHook)&&
        tableChanges(image,handle,changes,"_ZTV14TVPWindowLayer",windowTableBytes,(void*)originalUpdateThunk,(void*)updateThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalGetter,(void*)getterHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalGetterThunk,(void*)getterThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalDraw,(void*)drawHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD0,(void*)d0Hook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD1,(void*)d1Hook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD0Thunk,(void*)d0ThunkHook)&&
        tableChanges(image,handle,changes,"_ZTVN7cocos2d6SpriteE",1632,(void*)originalD1Thunk,(void*)d1ThunkHook);
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
    if(symbols&&strings&&relocs&&rela&&relocSize%sizeof(Elf64_Rela)==0) {
        for(size_t i=0;i<relocSize/sizeof(Elf64_Rela);++i) if(ELF64_R_TYPE(relocs[i].r_info)==R_AARCH64_JUMP_SLOT&&
            std::strcmp(strings+symbols[ELF64_R_SYM(relocs[i].r_info)].st_name,kInit)==0) {
            ok=collect(image,changes,reinterpret_cast<void**>(image.base+relocs[i].r_offset),(void*)originalInit,(void*)initHook)&&ok;
            ++found;
        }
    }
    if(!ok || found==0)return InstallStage::Relocation;
    callbackMethod=env->GetStaticMethodID(cls,"process","(IIIFFFFIIZZ)I");
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
    windowSprite=ownerMatches && liveSprites.size()==1 ? liveSprites.front() : nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeEndFrame(JNIEnv*,jclass) {
    // Cocos nativeRender has flushed all queued commands before this callback.
    frameEnv=nullptr;drawingSprite=nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_core_gl_KirikiroidRawFrameBridge_nativeResetContext(JNIEnv*,jclass) {
    frameEnv=nullptr;windowSprite=drawingSprite=frameSprite=nullptr;used=ambiguous=false;updateDepth=0;drawBufferUpdated=true;
    // GL resource resets do not destroy engine Sprite objects. Retain their identity
    // set and any tracking failure across pause/resume; only destructors remove entries.
    if(installed) {
        { std::lock_guard<std::mutex> lock(identityMutex);identityThreadMatches(); }
        syncTextureCache();
    }
}
