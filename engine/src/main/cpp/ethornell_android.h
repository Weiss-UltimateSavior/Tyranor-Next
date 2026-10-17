#ifndef ETHORNELL_ANDROID_H
#define ETHORNELL_ANDROID_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define ETHORNELL_ANDROID_ABI_VERSION 1u
/* All calls except abi_version/build_info/init_context/last_error run on the
 * creating engine thread. Strings are borrowed for the duration of the call,
 * standard UTF-8 (not JNI Modified UTF-8), and cannot contain embedded NUL. */
typedef struct { const uint8_t *ptr; uint32_t len; uint32_t reserved; } EthornellStringV1;
typedef struct {
    uint32_t struct_size, abi_version;
    EthornellStringV1 resource_root, user_root, font_path;
    uint32_t text_encoding; /* 0=CP932/SJIS, 1=CP936/GBK */
    uint32_t font_face_index; /* TTC index; applies to CPU and GPU */
    uint32_t backend; /* 0=Vulkan then GLES, 1=Vulkan only, 2=GLES only */
    uint32_t flags; /* bit0 strict native calls; bit1 trace; bit2 explicit silent mode */
    uint32_t reserved[4];
} EthornellConfigV1;
typedef struct {
    uint32_t struct_size, kind; /* 1=move,2=button-down,3=button-up,4=key-down,5=key-up,6=wheel,7=cancel */
    float x, y; /* Surface physical pixels; Rust performs aspect-fit conversion */
    int32_t code; /* button: Windows descriptor 1/2/4/5/6; key: Windows VK, NOT Android keyCode */
    float value; /* signed wheel delta */
    uint32_t reserved[2];
} EthornellInputV1;
typedef struct {
    uint32_t struct_size, kind; /* 1=diagnostic,2=confirmation request,3=closed,4=title */
    uint64_t request_id;
    int32_t code;
    uint32_t text_len; /* bytes present, excludes NUL; UTF-8 boundary preserved */
    uint8_t text[2048];
} EthornellEventV1;
typedef struct {
    uint32_t struct_size;
    int32_t result; /* VM integer result, once only */
    EthornellStringV1 text; /* reserved, must be empty in v1 */
} EthornellResponseV1;
/* Status: 0=OK, 1=no event/not yet due, negatives are errors. */
enum { ETHORNELL_E_ARGUMENT=-1, ETHORNELL_E_HANDLE=-2, ETHORNELL_E_ABI=-3,
       ETHORNELL_E_RESOURCE=-4, ETHORNELL_E_POLICY=-5, ETHORNELL_E_FONT=-6,
       ETHORNELL_E_GPU=-7, ETHORNELL_E_AUDIO=-8, ETHORNELL_E_VM=-9,
       ETHORNELL_E_UNSUPPORTED=-10, ETHORNELL_E_PANIC=-11,
       ETHORNELL_E_STATE=-12, ETHORNELL_E_THREAD=-13 };
uint32_t ethornell_android_abi_version(void);
/* Returns required bytes INCLUDING terminating NUL; copies only when capacity
 * suffices. NULL/0 is a size query. last_error is thread-local, redacted. */
int32_t ethornell_android_build_info(char *out, uint32_t capacity);
int32_t ethornell_android_last_error(char *out, uint32_t capacity);
/* JNI retains Application GlobalRef and JavaVM for this isolated process's lifetime.
 * Initialize once before create; identical repeated initialization is allowed. */
int32_t ethornell_android_init_context(void *java_vm, void *global_app_context);
int32_t ethornell_android_create(const EthornellConfigV1 *config, uint64_t *out_handle);
/* native_window is a BORROWED ANativeWindow. JNI retains its reference until
 * detach/destroy ACK. Failed attach leaves no borrow of the incoming window.
 * Generation must increase; detach acknowledges stale generations as no-op. */
int32_t ethornell_android_set_surface(uint64_t handle, void *native_window, uint32_t width_px, uint32_t height_px, uint64_t generation);
int32_t ethornell_android_detach_surface(uint64_t handle, uint64_t generation);
int32_t ethornell_android_step(uint64_t handle, uint64_t monotonic_time_ns);
int32_t ethornell_android_input(uint64_t handle, const EthornellInputV1 *input);
/* Complete mask, not incremental: bit0 Activity paused, bit1 focus, bit2 user,
 * bit3 host dialog. No Surface and pending request suspend independently. */
int32_t ethornell_android_set_paused(uint64_t handle, uint32_t reason_mask);
int32_t ethornell_android_request_close(uint64_t handle);
int32_t ethornell_android_poll_event(uint64_t handle, EthornellEventV1 *out_event);
int32_t ethornell_android_complete_request(uint64_t handle, uint64_t request_id, const EthornellResponseV1 *response);
int32_t ethornell_android_destroy(uint64_t handle);
#ifdef __cplusplus
}
#endif
#endif
