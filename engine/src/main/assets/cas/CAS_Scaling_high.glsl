//!DESC CAS_Scaling_high
//!HOOK MAIN
//!BIND MAIN
#define A_GPU 1
#define A_GLSL 1
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//                                              [A] SHADER PORTABILITY 1.20190530
//
//==============================================================================================================================
// LICENSE
// =======
// Copyright (c) 2017-2019 Advanced Micro Devices, Inc. All rights reserved.
// Copyright (c) <2014> <Michal Drobot>
// -------
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
// files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
// modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
// -------
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
// -------
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
// ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//------------------------------------------------------------------------------------------------------------------------------
// ABOUT
// =====
// Common central point for high-level shading language and C portability for various shader headers.
//------------------------------------------------------------------------------------------------------------------------------
// DEFINES
// =======
// A_CPU ..... Include the CPU related code.
// A_GPU ..... Include the GPU related code.
// A_GLSL .... Using GLSL.
// A_HLSL .... Using HLSL.
// A_GCC ..... Using a GCC compatible compiler (else assume MSVC compatible compiler by default).
// =======
// A_BYTE .... Support 8-bit integer.
// A_HALF .... Support 16-bit integer and floating point.
// A_LONG .... Support 64-bit integer.
// A_DUBL .... Support 64-bit floating point.
// =======
// A_WAVE .... Support wave-wide operations.
//------------------------------------------------------------------------------------------------------------------------------
// To get #include "ffx_a.h" working in GLSL use '#extension GL_GOOGLE_include_directive:require'.
//------------------------------------------------------------------------------------------------------------------------------
// SIMPLIFIED TYPE SYSTEM
// ======================
//  - All ints will be unsigned with exception of when signed is required.
//  - Type naming simplified and shortened "A<type><#components>",
//     - H = 16-bit float (half)
//     - F = 32-bit float (float)
//     - D = 64-bit float (double)
//     - P = 1-bit integer (predicate, not using bool because 'B' is used for byte)
//     - B = 8-bit integer (byte)
//     - W = 16-bit integer (word)
//     - U = 32-bit integer (unsigned)
//     - L = 64-bit integer (long)
//  - Using "AS<type><#components>" for signed when required.
//------------------------------------------------------------------------------------------------------------------------------
// TODO
// ====
//  - Make sure 'ALerp*(a,b,m)' does 'b*m+(-a*m+a)' (2 ops).
//  - Add subgroup ops.
//------------------------------------------------------------------------------------------------------------------------------
// CHANGE LOG
// ==========
// 20190531 - Fixed changed to llabs() because long is int on Windows.
// 20190530 - Updated for new CPU/GPU portability.
// 20190528 - Fix AU1_AH2_x() on HLSL (had incorrectly swapped x and y), fixed asuint() cases.
// 20190527 - Added min3/max3 for low precision for HLSL.
// 20190526 - Updated with half approximations, added ARsq*(), and ASat*() for CPU.
// 20190519 - Added more approximations.
// 20190514 - Added long conversions.
// 20190513 - Added the real BFI moved the other one to ABfiM().
// 20190507 - Added extra remap useful for 2D reductions.
// 20190507 - Started adding wave ops, add parabolic sin/cos.
// 20190505 - Added ASigned*() and friends, setup more auto-typecast, GLSL extensions, etc.
// 20190504 - Added min3/max3 for 32-bit integers.
// 20190503 - Added type reinterpretation for half.
// 20190416 - Added min3/max3 for half.
// 20190405 - Misc bug fixing.
// 20190404 - Cleaned up color conversion code. Switched "splat" to shorter naming "type_". Misc bug fixing.
//==============================================================================================================================
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                           COMMON
//==============================================================================================================================
#define A_2PI 6.28318530718
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//
//                                                             CPU
//
//
//==============================================================================================================================
// Requires standard C types: stdint.h
// Requires a collection of standard math intrinsics.
//  - Requires VS2013 when not using GCC to get exp2() and log2().
//  - https://blogs.msdn.microsoft.com/vcblog/2013/07/19/c99-library-support-in-visual-studio-2013/
//------------------------------------------------------------------------------------------------------------------------------
// This provides a minimum subset of functionality compared to the GPU parts.
//==============================================================================================================================
#ifdef A_CPU
 // Supporting user defined overrides.
 #ifndef A_RESTRICT
  #define A_RESTRICT __restrict
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 #ifndef A_STATIC
  #define A_STATIC static
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 // Same types across CPU and GPU.
 // Predicate uses 32-bit integer (C friendly bool).
 typedef uint32_t AP1;
 typedef float AF1;
 typedef double AD1;
 typedef uint8_t AB1;
 typedef uint16_t AW1;
 typedef uint32_t AU1;
 typedef uint64_t AL1;
 typedef int8_t ASB1;
 typedef int16_t ASW1;
 typedef int32_t ASU1;
 typedef int64_t ASL1;
//------------------------------------------------------------------------------------------------------------------------------
 #define AD1_(a) ((AD1)(a))
 #define AF1_(a) ((AF1)(a))
 #define AL1_(a) ((AL1)(a))
 #define AU1_(a) ((AU1)(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define ASL1_(a) ((ASL1)(a))
 #define ASU1_(a) ((ASU1)(a))
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AU1 AU1_AF1(AF1 a){union{AF1 f;AU1 u;}bits;bits.f=a;return bits.u;}
//------------------------------------------------------------------------------------------------------------------------------
 #define A_TRUE 1
 #define A_FALSE 0
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//                                                       CPU/GPU PORTING
//
//------------------------------------------------------------------------------------------------------------------------------
// Hackary to get CPU and GPU to share all setup code, without duplicate code paths.
// Unfortunately this is the level of "ugly" that is required since the languages are very different.
// This uses a lower-case prefix for special vector constructs.
//  - In C restrict pointers are used.
//  - In the shading language, in/inout/out arguments are used.
// This depends on the ability to access a vector value in both languages via array syntax (aka color[2]).
//==============================================================================================================================
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                     VECTOR ARGUMENT/RETURN/INITIALIZATION PORTABILITY
//==============================================================================================================================
 #define retAD2 AD1 *A_RESTRICT
 #define retAD3 AD1 *A_RESTRICT
 #define retAD4 AD1 *A_RESTRICT
 #define retAF2 AF1 *A_RESTRICT
 #define retAF3 AF1 *A_RESTRICT
 #define retAF4 AF1 *A_RESTRICT
 #define retAL2 AL1 *A_RESTRICT
 #define retAL3 AL1 *A_RESTRICT
 #define retAL4 AL1 *A_RESTRICT
 #define retAU2 AU1 *A_RESTRICT
 #define retAU3 AU1 *A_RESTRICT
 #define retAU4 AU1 *A_RESTRICT
//------------------------------------------------------------------------------------------------------------------------------
 #define inAD2 AD1 *A_RESTRICT
 #define inAD3 AD1 *A_RESTRICT
 #define inAD4 AD1 *A_RESTRICT
 #define inAF2 AF1 *A_RESTRICT
 #define inAF3 AF1 *A_RESTRICT
 #define inAF4 AF1 *A_RESTRICT
 #define inAL2 AL1 *A_RESTRICT
 #define inAL3 AL1 *A_RESTRICT
 #define inAL4 AL1 *A_RESTRICT
 #define inAU2 AU1 *A_RESTRICT
 #define inAU3 AU1 *A_RESTRICT
 #define inAU4 AU1 *A_RESTRICT
//------------------------------------------------------------------------------------------------------------------------------
 #define inoutAD2 AD1 *A_RESTRICT
 #define inoutAD3 AD1 *A_RESTRICT
 #define inoutAD4 AD1 *A_RESTRICT
 #define inoutAF2 AF1 *A_RESTRICT
 #define inoutAF3 AF1 *A_RESTRICT
 #define inoutAF4 AF1 *A_RESTRICT
 #define inoutAL2 AL1 *A_RESTRICT
 #define inoutAL3 AL1 *A_RESTRICT
 #define inoutAL4 AL1 *A_RESTRICT
 #define inoutAU2 AU1 *A_RESTRICT
 #define inoutAU3 AU1 *A_RESTRICT
 #define inoutAU4 AU1 *A_RESTRICT
//------------------------------------------------------------------------------------------------------------------------------
 #define outAD2 AD1 *A_RESTRICT
 #define outAD3 AD1 *A_RESTRICT
 #define outAD4 AD1 *A_RESTRICT
 #define outAF2 AF1 *A_RESTRICT
 #define outAF3 AF1 *A_RESTRICT
 #define outAF4 AF1 *A_RESTRICT
 #define outAL2 AL1 *A_RESTRICT
 #define outAL3 AL1 *A_RESTRICT
 #define outAL4 AL1 *A_RESTRICT
 #define outAU2 AU1 *A_RESTRICT
 #define outAU3 AU1 *A_RESTRICT
 #define outAU4 AU1 *A_RESTRICT
//------------------------------------------------------------------------------------------------------------------------------
 #define varAD2(x) AD1 x[2]
 #define varAD3(x) AD1 x[3]
 #define varAD4(x) AD1 x[4]
 #define varAF2(x) AF1 x[2]
 #define varAF3(x) AF1 x[3]
 #define varAF4(x) AF1 x[4]
 #define varAL2(x) AL1 x[2]
 #define varAL3(x) AL1 x[3]
 #define varAL4(x) AL1 x[4]
 #define varAU2(x) AU1 x[2]
 #define varAU3(x) AU1 x[3]
 #define varAU4(x) AU1 x[4]
//------------------------------------------------------------------------------------------------------------------------------
 #define initAD2(x,y) {x,y}
 #define initAD3(x,y,z) {x,y,z}
 #define initAD4(x,y,z,w) {x,y,z,w}
 #define initAF2(x,y) {x,y}
 #define initAF3(x,y,z) {x,y,z}
 #define initAF4(x,y,z,w) {x,y,z,w}
 #define initAL2(x,y) {x,y}
 #define initAL3(x,y,z) {x,y,z}
 #define initAL4(x,y,z,w) {x,y,z,w}
 #define initAU2(x,y) {x,y}
 #define initAU3(x,y,z) {x,y,z}
 #define initAU4(x,y,z,w) {x,y,z,w}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                     SCALAR RETURN OPS
//------------------------------------------------------------------------------------------------------------------------------
// TODO
// ====
//  - Replace transcendentals with manual versions. 
//==============================================================================================================================
 #ifdef A_GCC
  A_STATIC AD1 AAbsD1(AD1 a){return __builtin_fabs(a);}
  A_STATIC AF1 AAbsF1(AF1 a){return __builtin_fabsf(a);}
  A_STATIC AU1 AAbsSU1(AU1 a){return AU1_(__builtin_abs(ASU1_(a)));}
  A_STATIC AL1 AAbsSL1(AL1 a){return AL1_(__builtin_labs(ASL1_(a)));}
 #else
  A_STATIC AD1 AAbsD1(AD1 a){return fabs(a);}
  A_STATIC AF1 AAbsF1(AF1 a){return fabsf(a);}
  A_STATIC AU1 AAbsSU1(AU1 a){return AU1_(abs(ASU1_(a)));}
  A_STATIC AL1 AAbsSL1(AL1 a){return AL1_(llabs(ASL1_(a)));}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 ACosD1(AD1 a){return __builtin_cos(a);}
  A_STATIC AF1 ACosF1(AF1 a){return __builtin_cosf(a);}
 #else
  A_STATIC AD1 ACosD1(AD1 a){return cos(a);}
  A_STATIC AF1 ACosF1(AF1 a){return cosf(a);}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 ADotD2(inAD2 a,inAD2 b){return a[0]*b[0]+a[1]*b[1];}
 A_STATIC AD1 ADotD3(inAD3 a,inAD3 b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
 A_STATIC AD1 ADotD4(inAD4 a,inAD4 b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3];}
 A_STATIC AF1 ADotF2(inAF2 a,inAF2 b){return a[0]*b[0]+a[1]*b[1];}
 A_STATIC AF1 ADotF3(inAF3 a,inAF3 b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
 A_STATIC AF1 ADotF4(inAF4 a,inAF4 b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3];}
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 AExp2D1(AD1 a){return __builtin_exp2(a);}
  A_STATIC AF1 AExp2F1(AF1 a){return __builtin_exp2f(a);}
 #else
  A_STATIC AD1 AExp2D1(AD1 a){return exp2(a);}
  A_STATIC AF1 AExp2F1(AF1 a){return exp2f(a);}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 AFloorD1(AD1 a){return __builtin_floor(a);}
  A_STATIC AF1 AFloorF1(AF1 a){return __builtin_floorf(a);}
 #else
  A_STATIC AD1 AFloorD1(AD1 a){return floor(a);}
  A_STATIC AF1 AFloorF1(AF1 a){return floorf(a);}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 ALerpD1(AD1 a,AD1 b,AD1 c){return b*c+(-a*c+a);}
 A_STATIC AF1 ALerpF1(AF1 a,AF1 b,AF1 c){return b*c+(-a*c+a);}
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 ALog2D1(AD1 a){return __builtin_log2(a);}
  A_STATIC AF1 ALog2F1(AF1 a){return __builtin_log2f(a);}
 #else
  A_STATIC AD1 ALog2D1(AD1 a){return log2(a);}
  A_STATIC AF1 ALog2F1(AF1 a){return log2f(a);}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 AMaxD1(AD1 a,AD1 b){return a>b?a:b;}
 A_STATIC AF1 AMaxF1(AF1 a,AF1 b){return a>b?a:b;}
 A_STATIC AL1 AMaxL1(AL1 a,AL1 b){return a>b?a:b;}
 A_STATIC AU1 AMaxU1(AU1 a,AU1 b){return a>b?a:b;}
//------------------------------------------------------------------------------------------------------------------------------
 // These follow the convention that A integer types don't have signage, until they are operated on. 
 A_STATIC AL1 AMaxSL1(AL1 a,AL1 b){return (ASL1_(a)>ASL1_(b))?a:b;}
 A_STATIC AU1 AMaxSU1(AU1 a,AU1 b){return (ASU1_(a)>ASU1_(b))?a:b;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 AMinD1(AD1 a,AD1 b){return a<b?a:b;}
 A_STATIC AF1 AMinF1(AF1 a,AF1 b){return a<b?a:b;}
 A_STATIC AL1 AMinL1(AL1 a,AL1 b){return a<b?a:b;}
 A_STATIC AU1 AMinU1(AU1 a,AU1 b){return a<b?a:b;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AL1 AMinSL1(AL1 a,AL1 b){return (ASL1_(a)<ASL1_(b))?a:b;}
 A_STATIC AU1 AMinSU1(AU1 a,AU1 b){return (ASU1_(a)<ASU1_(b))?a:b;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 ARcpD1(AD1 a){return 1.0/a;}
 A_STATIC AF1 ARcpF1(AF1 a){return 1.0f/a;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AL1 AShrSL1(AL1 a,AL1 b){return AL1_(ASL1_(a)>>ASL1_(b));}
 A_STATIC AU1 AShrSU1(AU1 a,AU1 b){return AU1_(ASU1_(a)>>ASU1_(b));}
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 ASinD1(AD1 a){return __builtin_sin(a);}
  A_STATIC AF1 ASinF1(AF1 a){return __builtin_sinf(a);}
 #else
  A_STATIC AD1 ASinD1(AD1 a){return sin(a);}
  A_STATIC AF1 ASinF1(AF1 a){return sinf(a);}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_GCC
  A_STATIC AD1 ASqrtD1(AD1 a){return __builtin_sqrt(a);}
  A_STATIC AF1 ASqrtF1(AF1 a){return __builtin_sqrtf(a);}
 #else
  A_STATIC AD1 ASqrtD1(AD1 a){return sqrt(a);}
  A_STATIC AF1 ASqrtF1(AF1 a){return sqrtf(a);}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                               SCALAR RETURN OPS - DEPENDENT
//==============================================================================================================================
 A_STATIC AD1 AFractD1(AD1 a){return a-AFloorD1(a);}
 A_STATIC AF1 AFractF1(AF1 a){return a-AFloorF1(a);}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 APowD1(AD1 a,AD1 b){return AExp2D1(b*ALog2D1(a));}
 A_STATIC AF1 APowF1(AF1 a,AF1 b){return AExp2F1(b*ALog2F1(a));}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 ARsqD1(AD1 a){return ARcpD1(ASqrtD1(a));}
 A_STATIC AF1 ARsqF1(AF1 a){return ARcpF1(ASqrtF1(a));}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC AD1 ASatD1(AD1 a){return AMinD1(1.0,AMaxD1(0.0,a));}
 A_STATIC AF1 ASatF1(AF1 a){return AMinF1(1.0f,AMaxF1(0.0f,a));}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         VECTOR OPS
//------------------------------------------------------------------------------------------------------------------------------
// These are added as needed for production or prototyping, so not necessarily a complete set.
// They follow a convention of taking in a destination and also returning the destination value to increase utility.
//==============================================================================================================================
 A_STATIC retAD2 opAAbsD2(outAD2 d,inAD2 a){d[0]=AAbsD1(a[0]);d[1]=AAbsD1(a[1]);return d;}
 A_STATIC retAD3 opAAbsD3(outAD3 d,inAD3 a){d[0]=AAbsD1(a[0]);d[1]=AAbsD1(a[1]);d[2]=AAbsD1(a[2]);return d;}
 A_STATIC retAD4 opAAbsD4(outAD4 d,inAD4 a){d[0]=AAbsD1(a[0]);d[1]=AAbsD1(a[1]);d[2]=AAbsD1(a[2]);d[3]=AAbsD1(a[3]);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAAbsF2(outAF2 d,inAF2 a){d[0]=AAbsF1(a[0]);d[1]=AAbsF1(a[1]);return d;}
 A_STATIC retAF3 opAAbsF3(outAF3 d,inAF3 a){d[0]=AAbsF1(a[0]);d[1]=AAbsF1(a[1]);d[2]=AAbsF1(a[2]);return d;}
 A_STATIC retAF4 opAAbsF4(outAF4 d,inAF4 a){d[0]=AAbsF1(a[0]);d[1]=AAbsF1(a[1]);d[2]=AAbsF1(a[2]);d[3]=AAbsF1(a[3]);return d;}
//==============================================================================================================================
 A_STATIC retAD2 opAAddD2(outAD2 d,inAD2 a,inAD2 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];return d;}
 A_STATIC retAD3 opAAddD3(outAD3 d,inAD3 a,inAD3 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];d[2]=a[2]+b[2];return d;}
 A_STATIC retAD4 opAAddD4(outAD4 d,inAD4 a,inAD4 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];d[2]=a[2]+b[2];d[3]=a[3]+b[3];return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAAddF2(outAF2 d,inAF2 a,inAF2 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];return d;}
 A_STATIC retAF3 opAAddF3(outAF3 d,inAF3 a,inAF3 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];d[2]=a[2]+b[2];return d;}
 A_STATIC retAF4 opAAddF4(outAF4 d,inAF4 a,inAF4 b){d[0]=a[0]+b[0];d[1]=a[1]+b[1];d[2]=a[2]+b[2];d[3]=a[3]+b[3];return d;}
//==============================================================================================================================
 A_STATIC retAD2 opACpyD2(outAD2 d,inAD2 a){d[0]=a[0];d[1]=a[1];return d;}
 A_STATIC retAD3 opACpyD3(outAD3 d,inAD3 a){d[0]=a[0];d[1]=a[1];d[2]=a[2];return d;}
 A_STATIC retAD4 opACpyD4(outAD4 d,inAD4 a){d[0]=a[0];d[1]=a[1];d[2]=a[2];d[3]=a[3];return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opACpyF2(outAF2 d,inAF2 a){d[0]=a[0];d[1]=a[1];return d;}
 A_STATIC retAF3 opACpyF3(outAF3 d,inAF3 a){d[0]=a[0];d[1]=a[1];d[2]=a[2];return d;}
 A_STATIC retAF4 opACpyF4(outAF4 d,inAF4 a){d[0]=a[0];d[1]=a[1];d[2]=a[2];d[3]=a[3];return d;}
//==============================================================================================================================
 A_STATIC retAD2 opALerpD2(outAD2 d,inAD2 a,inAD2 b,inAD2 c){d[0]=ALerpD1(a[0],b[0],c[0]);d[1]=ALerpD1(a[1],b[1],c[1]);return d;}
 A_STATIC retAD3 opALerpD3(outAD3 d,inAD3 a,inAD3 b,inAD3 c){d[0]=ALerpD1(a[0],b[0],c[0]);d[1]=ALerpD1(a[1],b[1],c[1]);d[2]=ALerpD1(a[2],b[2],c[2]);return d;}
 A_STATIC retAD4 opALerpD4(outAD4 d,inAD4 a,inAD4 b,inAD4 c){d[0]=ALerpD1(a[0],b[0],c[0]);d[1]=ALerpD1(a[1],b[1],c[1]);d[2]=ALerpD1(a[2],b[2],c[2]);d[3]=ALerpD1(a[3],b[3],c[3]);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opALerpF2(outAF2 d,inAF2 a,inAF2 b,inAF2 c){d[0]=ALerpF1(a[0],b[0],c[0]);d[1]=ALerpF1(a[1],b[1],c[1]);return d;}
 A_STATIC retAF3 opALerpF3(outAF3 d,inAF3 a,inAF3 b,inAF3 c){d[0]=ALerpF1(a[0],b[0],c[0]);d[1]=ALerpF1(a[1],b[1],c[1]);d[2]=ALerpF1(a[2],b[2],c[2]);return d;}
 A_STATIC retAF4 opALerpF4(outAF4 d,inAF4 a,inAF4 b,inAF4 c){d[0]=ALerpF1(a[0],b[0],c[0]);d[1]=ALerpF1(a[1],b[1],c[1]);d[2]=ALerpF1(a[2],b[2],c[2]);d[3]=ALerpF1(a[3],b[3],c[3]);return d;}
//==============================================================================================================================
 A_STATIC retAD2 opALerpOneD2(outAD2 d,inAD2 a,inAD2 b,AD1 c){d[0]=ALerpD1(a[0],b[0],c);d[1]=ALerpD1(a[1],b[1],c);return d;}
 A_STATIC retAD3 opALerpOneD3(outAD3 d,inAD3 a,inAD3 b,AD1 c){d[0]=ALerpD1(a[0],b[0],c);d[1]=ALerpD1(a[1],b[1],c);d[2]=ALerpD1(a[2],b[2],c);return d;}
 A_STATIC retAD4 opALerpOneD4(outAD4 d,inAD4 a,inAD4 b,AD1 c){d[0]=ALerpD1(a[0],b[0],c);d[1]=ALerpD1(a[1],b[1],c);d[2]=ALerpD1(a[2],b[2],c);d[3]=ALerpD1(a[3],b[3],c);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opALerpOneF2(outAF2 d,inAF2 a,inAF2 b,AF1 c){d[0]=ALerpF1(a[0],b[0],c);d[1]=ALerpF1(a[1],b[1],c);return d;}
 A_STATIC retAF3 opALerpOneF3(outAF3 d,inAF3 a,inAF3 b,AF1 c){d[0]=ALerpF1(a[0],b[0],c);d[1]=ALerpF1(a[1],b[1],c);d[2]=ALerpF1(a[2],b[2],c);return d;}
 A_STATIC retAF4 opALerpOneF4(outAF4 d,inAF4 a,inAF4 b,AF1 c){d[0]=ALerpF1(a[0],b[0],c);d[1]=ALerpF1(a[1],b[1],c);d[2]=ALerpF1(a[2],b[2],c);d[3]=ALerpF1(a[3],b[3],c);return d;}
//==============================================================================================================================
 A_STATIC retAD2 opAMaxD2(outAD2 d,inAD2 a,inAD2 b){d[0]=AMaxD1(a[0],b[0]);d[1]=AMaxD1(a[1],b[1]);return d;}
 A_STATIC retAD3 opAMaxD3(outAD3 d,inAD3 a,inAD3 b){d[0]=AMaxD1(a[0],b[0]);d[1]=AMaxD1(a[1],b[1]);d[2]=AMaxD1(a[2],b[2]);return d;}
 A_STATIC retAD4 opAMaxD4(outAD4 d,inAD4 a,inAD4 b){d[0]=AMaxD1(a[0],b[0]);d[1]=AMaxD1(a[1],b[1]);d[2]=AMaxD1(a[2],b[2]);d[3]=AMaxD1(a[3],b[3]);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAMaxF2(outAF2 d,inAF2 a,inAF2 b){d[0]=AMaxF1(a[0],b[0]);d[1]=AMaxF1(a[1],b[1]);return d;}
 A_STATIC retAF3 opAMaxF3(outAF3 d,inAF3 a,inAF3 b){d[0]=AMaxF1(a[0],b[0]);d[1]=AMaxF1(a[1],b[1]);d[2]=AMaxF1(a[2],b[2]);return d;}
 A_STATIC retAF4 opAMaxF4(outAF4 d,inAF4 a,inAF4 b){d[0]=AMaxF1(a[0],b[0]);d[1]=AMaxF1(a[1],b[1]);d[2]=AMaxF1(a[2],b[2]);d[3]=AMaxF1(a[3],b[3]);return d;}
//==============================================================================================================================
 A_STATIC retAD2 opAMinD2(outAD2 d,inAD2 a,inAD2 b){d[0]=AMinD1(a[0],b[0]);d[1]=AMinD1(a[1],b[1]);return d;}
 A_STATIC retAD3 opAMinD3(outAD3 d,inAD3 a,inAD3 b){d[0]=AMinD1(a[0],b[0]);d[1]=AMinD1(a[1],b[1]);d[2]=AMinD1(a[2],b[2]);return d;}
 A_STATIC retAD4 opAMinD4(outAD4 d,inAD4 a,inAD4 b){d[0]=AMinD1(a[0],b[0]);d[1]=AMinD1(a[1],b[1]);d[2]=AMinD1(a[2],b[2]);d[3]=AMinD1(a[3],b[3]);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAMinF2(outAF2 d,inAF2 a,inAF2 b){d[0]=AMinF1(a[0],b[0]);d[1]=AMinF1(a[1],b[1]);return d;}
 A_STATIC retAF3 opAMinF3(outAF3 d,inAF3 a,inAF3 b){d[0]=AMinF1(a[0],b[0]);d[1]=AMinF1(a[1],b[1]);d[2]=AMinF1(a[2],b[2]);return d;}
 A_STATIC retAF4 opAMinF4(outAF4 d,inAF4 a,inAF4 b){d[0]=AMinF1(a[0],b[0]);d[1]=AMinF1(a[1],b[1]);d[2]=AMinF1(a[2],b[2]);d[3]=AMinF1(a[3],b[3]);return d;}
//==============================================================================================================================
 A_STATIC retAD2 opAMulD2(outAD2 d,inAD2 a,inAD2 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];return d;}
 A_STATIC retAD3 opAMulD3(outAD3 d,inAD3 a,inAD3 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];d[2]=a[2]*b[2];return d;}
 A_STATIC retAD4 opAMulD4(outAD4 d,inAD4 a,inAD4 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];d[2]=a[2]*b[2];d[3]=a[3]*b[3];return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAMulF2(outAF2 d,inAF2 a,inAF2 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];return d;}
 A_STATIC retAF3 opAMulF3(outAF3 d,inAF3 a,inAF3 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];d[2]=a[2]*b[2];return d;}
 A_STATIC retAF4 opAMulF4(outAF4 d,inAF4 a,inAF4 b){d[0]=a[0]*b[0];d[1]=a[1]*b[1];d[2]=a[2]*b[2];d[3]=a[3]*b[3];return d;}
//==============================================================================================================================
 A_STATIC retAD2 opAMulOneD2(outAD2 d,inAD2 a,AD1 b){d[0]=a[0]*b;d[1]=a[1]*b;return d;}
 A_STATIC retAD3 opAMulOneD3(outAD3 d,inAD3 a,AD1 b){d[0]=a[0]*b;d[1]=a[1]*b;d[2]=a[2]*b;return d;}
 A_STATIC retAD4 opAMulOneD4(outAD4 d,inAD4 a,AD1 b){d[0]=a[0]*b;d[1]=a[1]*b;d[2]=a[2]*b;d[3]=a[3]*b;return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opAMulOneF2(outAF2 d,inAF2 a,AF1 b){d[0]=a[0]*b;d[1]=a[1]*b;return d;}
 A_STATIC retAF3 opAMulOneF3(outAF3 d,inAF3 a,AF1 b){d[0]=a[0]*b;d[1]=a[1]*b;d[2]=a[2]*b;return d;}
 A_STATIC retAF4 opAMulOneF4(outAF4 d,inAF4 a,AF1 b){d[0]=a[0]*b;d[1]=a[1]*b;d[2]=a[2]*b;d[3]=a[3]*b;return d;}
//==============================================================================================================================
 A_STATIC retAD2 opANegD2(outAD2 d,inAD2 a){d[0]=-a[0];d[1]=-a[1];return d;}
 A_STATIC retAD3 opANegD3(outAD3 d,inAD3 a){d[0]=-a[0];d[1]=-a[1];d[2]=-a[2];return d;}
 A_STATIC retAD4 opANegD4(outAD4 d,inAD4 a){d[0]=-a[0];d[1]=-a[1];d[2]=-a[2];d[3]=-a[3];return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opANegF2(outAF2 d,inAF2 a){d[0]=-a[0];d[1]=-a[1];return d;}
 A_STATIC retAF3 opANegF3(outAF3 d,inAF3 a){d[0]=-a[0];d[1]=-a[1];d[2]=-a[2];return d;}
 A_STATIC retAF4 opANegF4(outAF4 d,inAF4 a){d[0]=-a[0];d[1]=-a[1];d[2]=-a[2];d[3]=-a[3];return d;}
//==============================================================================================================================
 A_STATIC retAD2 opARcpD2(outAD2 d,inAD2 a){d[0]=ARcpD1(a[0]);d[1]=ARcpD1(a[1]);return d;}
 A_STATIC retAD3 opARcpD3(outAD3 d,inAD3 a){d[0]=ARcpD1(a[0]);d[1]=ARcpD1(a[1]);d[2]=ARcpD1(a[2]);return d;}
 A_STATIC retAD4 opARcpD4(outAD4 d,inAD4 a){d[0]=ARcpD1(a[0]);d[1]=ARcpD1(a[1]);d[2]=ARcpD1(a[2]);d[3]=ARcpD1(a[3]);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 A_STATIC retAF2 opARcpF2(outAF2 d,inAF2 a){d[0]=ARcpF1(a[0]);d[1]=ARcpF1(a[1]);return d;}
 A_STATIC retAF3 opARcpF3(outAF3 d,inAF3 a){d[0]=ARcpF1(a[0]);d[1]=ARcpF1(a[1]);d[2]=ARcpF1(a[2]);return d;}
 A_STATIC retAF4 opARcpF4(outAF4 d,inAF4 a){d[0]=ARcpF1(a[0]);d[1]=ARcpF1(a[1]);d[2]=ARcpF1(a[2]);d[3]=ARcpF1(a[3]);return d;}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                     HALF FLOAT PACKING
//==============================================================================================================================
 // Convert float to half (in lower 16-bits of output).
 // Same fast technique as documented here: ftp://ftp.fox-toolkit.org/pub/fasthalffloatconversion.pdf
 // Supports denormals.
 // Conversion rules are to make computations possibly "safer" on the GPU,
 //  -INF & -NaN -> -65504
 //  +INF & +NaN -> +65504
 A_STATIC AU1 AU1_AH1_AF1(AF1 f){
  static AW1 base[512]={
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
   0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0001,0x0002,0x0004,0x0008,0x0010,0x0020,0x0040,0x0080,0x0100,
   0x0200,0x0400,0x0800,0x0c00,0x1000,0x1400,0x1800,0x1c00,0x2000,0x2400,0x2800,0x2c00,0x3000,0x3400,0x3800,0x3c00,
   0x4000,0x4400,0x4800,0x4c00,0x5000,0x5400,0x5800,0x5c00,0x6000,0x6400,0x6800,0x6c00,0x7000,0x7400,0x7800,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,0x7bff,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,
   0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8000,0x8001,0x8002,0x8004,0x8008,0x8010,0x8020,0x8040,0x8080,0x8100,
   0x8200,0x8400,0x8800,0x8c00,0x9000,0x9400,0x9800,0x9c00,0xa000,0xa400,0xa800,0xac00,0xb000,0xb400,0xb800,0xbc00,
   0xc000,0xc400,0xc800,0xcc00,0xd000,0xd400,0xd800,0xdc00,0xe000,0xe400,0xe800,0xec00,0xf000,0xf400,0xf800,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,
   0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff,0xfbff};
  static AB1 shift[512]={
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x17,0x16,0x15,0x14,0x13,0x12,0x11,0x10,0x0f,
   0x0e,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,
   0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x17,0x16,0x15,0x14,0x13,0x12,0x11,0x10,0x0f,
   0x0e,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,
   0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x0d,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,
   0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18};
  union{AF1 f;AU1 u;}bits;bits.f=f;AU1 u=bits.u;AU1 i=u>>23;return (AU1)(base[i])+((u&0x7fffff)>>shift[i]);}
//------------------------------------------------------------------------------------------------------------------------------
 // Used to output packed constant.
 A_STATIC AU1 AU1_AH2_AF2(inAF2 a){return AU1_AH1_AF1(a[0])+(AU1_AH1_AF1(a[1])<<16);}
#endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//
//                                                            GLSL
//
//
//==============================================================================================================================
#if defined(A_GLSL) && defined(A_GPU)
 #ifndef A_SKIP_EXT
  #ifdef A_HALF
   #extension GL_EXT_shader_16bit_storage:require
   #extension GL_EXT_shader_explicit_arithmetic_types:require 
  #endif
//------------------------------------------------------------------------------------------------------------------------------
  #ifdef A_LONG
   #extension GL_ARB_gpu_shader_int64:require
   // TODO: Fixme to more portable extension!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
   #extension GL_NV_shader_atomic_int64:require
  #endif
//------------------------------------------------------------------------------------------------------------------------------
  #ifdef A_WAVE
   #extension GL_KHR_shader_subgroup_arithmetic:require
   #extension GL_KHR_shader_subgroup_ballot:require
   #extension GL_KHR_shader_subgroup_quad:require
   #extension GL_KHR_shader_subgroup_shuffle:require
  #endif
 #endif
//==============================================================================================================================
 #define AP1 bool
 #define AP2 bvec2
 #define AP3 bvec3
 #define AP4 bvec4
//------------------------------------------------------------------------------------------------------------------------------
 #define AF1 float
 #define AF2 vec2
 #define AF3 vec3
 #define AF4 vec4
//------------------------------------------------------------------------------------------------------------------------------
 #define AU1 uint
 #define AU2 uvec2
 #define AU3 uvec3
 #define AU4 uvec4
//------------------------------------------------------------------------------------------------------------------------------
 #define ASU1 int
 #define ASU2 ivec2
 #define ASU3 ivec3
 #define ASU4 ivec4
//==============================================================================================================================
 #define AF1_AU1(x) uintBitsToFloat(AU1(x))
 #define AF2_AU2(x) uintBitsToFloat(AU2(x))
 #define AF3_AU3(x) uintBitsToFloat(AU3(x))
 #define AF4_AU4(x) uintBitsToFloat(AU4(x))
//------------------------------------------------------------------------------------------------------------------------------
 #define AU1_AF1(x) floatBitsToUint(AF1(x))
 #define AU2_AF2(x) floatBitsToUint(AF2(x))
 #define AU3_AF3(x) floatBitsToUint(AF3(x))
 #define AU4_AF4(x) floatBitsToUint(AF4(x))
//------------------------------------------------------------------------------------------------------------------------------
 #define AU1_AH2_AF2 packHalf2x16
 #define AU1_AW2Unorm_AF2 packUnorm2x16
 #define AU1_AB4Unorm_AF4 packUnorm4x8
//------------------------------------------------------------------------------------------------------------------------------
 #define AF2_AH2_AU1 unpackHalf2x16
 #define AF2_AW2Unorm_AU1 unpackUnorm2x16
 #define AF4_AB4Unorm_AU1 unpackUnorm4x8
//==============================================================================================================================
 AF1 AF1_x(AF1 a){return AF1(a);}
 AF2 AF2_x(AF1 a){return AF2(a,a);}
 AF3 AF3_x(AF1 a){return AF3(a,a,a);}
 AF4 AF4_x(AF1 a){return AF4(a,a,a,a);}
 #define AF1_(a) AF1_x(AF1(a))
 #define AF2_(a) AF2_x(AF1(a))
 #define AF3_(a) AF3_x(AF1(a))
 #define AF4_(a) AF4_x(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AU1_x(AU1 a){return AU1(a);}
 AU2 AU2_x(AU1 a){return AU2(a,a);}
 AU3 AU3_x(AU1 a){return AU3(a,a,a);}
 AU4 AU4_x(AU1 a){return AU4(a,a,a,a);}
 #define AU1_(a) AU1_x(AU1(a))
 #define AU2_(a) AU2_x(AU1(a))
 #define AU3_(a) AU3_x(AU1(a))
 #define AU4_(a) AU4_x(AU1(a))
//==============================================================================================================================
 AU1 AAbsSU1(AU1 a){return AU1(abs(ASU1(a)));}
 AU2 AAbsSU2(AU2 a){return AU2(abs(ASU2(a)));}
 AU3 AAbsSU3(AU3 a){return AU3(abs(ASU3(a)));}
 AU4 AAbsSU4(AU4 a){return AU4(abs(ASU4(a)));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 ABfe(AU1 src,AU1 off,AU1 bits){return ((src >> off) & (bits >= 32u ? 0xffffffffu : ((1u << bits)-1u)));}
 AU1 ABfi(AU1 src,AU1 ins,AU1 mask){return (ins&mask)|(src&(~mask));}
 // Proxy for V_BFI_B32 where the 'mask' is set as 'bits', 'mask=(1<<bits)-1', and 'bits' needs to be an immediate.
 AU1 ABfiM(AU1 src,AU1 ins,AU1 bits){return ABfi(src,ins,bits >= 32u ? 0xffffffffu : ((1u << bits)-1u));}
//------------------------------------------------------------------------------------------------------------------------------
 // V_FRACT_F32 (note DX frac() is different).
 AF1 AFractF1(AF1 x){return fract(x);}
 AF2 AFractF2(AF2 x){return fract(x);}
 AF3 AFractF3(AF3 x){return fract(x);}
 AF4 AFractF4(AF4 x){return fract(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ALerpF1(AF1 x,AF1 y,AF1 a){return mix(x,y,a);}
 AF2 ALerpF2(AF2 x,AF2 y,AF2 a){return mix(x,y,a);}
 AF3 ALerpF3(AF3 x,AF3 y,AF3 a){return mix(x,y,a);}
 AF4 ALerpF4(AF4 x,AF4 y,AF4 a){return mix(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
 // V_MAX3_F32.
 AF1 AMax3F1(AF1 x,AF1 y,AF1 z){return max(x,max(y,z));}
 AF2 AMax3F2(AF2 x,AF2 y,AF2 z){return max(x,max(y,z));}
 AF3 AMax3F3(AF3 x,AF3 y,AF3 z){return max(x,max(y,z));}
 AF4 AMax3F4(AF4 x,AF4 y,AF4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMax3SU1(AU1 x,AU1 y,AU1 z){return AU1(max(ASU1(x),max(ASU1(y),ASU1(z))));}
 AU2 AMax3SU2(AU2 x,AU2 y,AU2 z){return AU2(max(ASU2(x),max(ASU2(y),ASU2(z))));}
 AU3 AMax3SU3(AU3 x,AU3 y,AU3 z){return AU3(max(ASU3(x),max(ASU3(y),ASU3(z))));}
 AU4 AMax3SU4(AU4 x,AU4 y,AU4 z){return AU4(max(ASU4(x),max(ASU4(y),ASU4(z))));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMax3U1(AU1 x,AU1 y,AU1 z){return max(x,max(y,z));}
 AU2 AMax3U2(AU2 x,AU2 y,AU2 z){return max(x,max(y,z));}
 AU3 AMax3U3(AU3 x,AU3 y,AU3 z){return max(x,max(y,z));}
 AU4 AMax3U4(AU4 x,AU4 y,AU4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMaxSU1(AU1 a,AU1 b){return AU1(max(ASU1(a),ASU1(b)));}
 AU2 AMaxSU2(AU2 a,AU2 b){return AU2(max(ASU2(a),ASU2(b)));}
 AU3 AMaxSU3(AU3 a,AU3 b){return AU3(max(ASU3(a),ASU3(b)));}
 AU4 AMaxSU4(AU4 a,AU4 b){return AU4(max(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
 // Clamp has an easier pattern match for med3 when some ordering is known.
 // V_MED3_F32.
 AF1 AMed3F1(AF1 x,AF1 y,AF1 z){return max(min(x,y),min(max(x,y),z));}
 AF2 AMed3F2(AF2 x,AF2 y,AF2 z){return max(min(x,y),min(max(x,y),z));}
 AF3 AMed3F3(AF3 x,AF3 y,AF3 z){return max(min(x,y),min(max(x,y),z));}
 AF4 AMed3F4(AF4 x,AF4 y,AF4 z){return max(min(x,y),min(max(x,y),z));}
//------------------------------------------------------------------------------------------------------------------------------
 // V_MIN3_F32.
 AF1 AMin3F1(AF1 x,AF1 y,AF1 z){return min(x,min(y,z));}
 AF2 AMin3F2(AF2 x,AF2 y,AF2 z){return min(x,min(y,z));}
 AF3 AMin3F3(AF3 x,AF3 y,AF3 z){return min(x,min(y,z));}
 AF4 AMin3F4(AF4 x,AF4 y,AF4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMin3SU1(AU1 x,AU1 y,AU1 z){return AU1(min(ASU1(x),min(ASU1(y),ASU1(z))));}
 AU2 AMin3SU2(AU2 x,AU2 y,AU2 z){return AU2(min(ASU2(x),min(ASU2(y),ASU2(z))));}
 AU3 AMin3SU3(AU3 x,AU3 y,AU3 z){return AU3(min(ASU3(x),min(ASU3(y),ASU3(z))));}
 AU4 AMin3SU4(AU4 x,AU4 y,AU4 z){return AU4(min(ASU4(x),min(ASU4(y),ASU4(z))));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMin3U1(AU1 x,AU1 y,AU1 z){return min(x,min(y,z));}
 AU2 AMin3U2(AU2 x,AU2 y,AU2 z){return min(x,min(y,z));}
 AU3 AMin3U3(AU3 x,AU3 y,AU3 z){return min(x,min(y,z));}
 AU4 AMin3U4(AU4 x,AU4 y,AU4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMinSU1(AU1 a,AU1 b){return AU1(min(ASU1(a),ASU1(b)));}
 AU2 AMinSU2(AU2 a,AU2 b){return AU2(min(ASU2(a),ASU2(b)));}
 AU3 AMinSU3(AU3 a,AU3 b){return AU3(min(ASU3(a),ASU3(b)));}
 AU4 AMinSU4(AU4 a,AU4 b){return AU4(min(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
 // Normalized trig. Valid input domain is {-256 to +256}. No GLSL compiler intrinsic exists to map to this currently.
 // V_COS_F32.
 AF1 ANCosF1(AF1 x){return cos(x*AF1_(A_2PI));}
 AF2 ANCosF2(AF2 x){return cos(x*AF2_(A_2PI));}
 AF3 ANCosF3(AF3 x){return cos(x*AF3_(A_2PI));}
 AF4 ANCosF4(AF4 x){return cos(x*AF4_(A_2PI));}
//------------------------------------------------------------------------------------------------------------------------------
 // Normalized trig. Valid input domain is {-256 to +256}. No GLSL compiler intrinsic exists to map to this currently.
 // V_SIN_F32.
 AF1 ANSinF1(AF1 x){return sin(x*AF1_(A_2PI));}
 AF2 ANSinF2(AF2 x){return sin(x*AF2_(A_2PI));}
 AF3 ANSinF3(AF3 x){return sin(x*AF3_(A_2PI));}
 AF4 ANSinF4(AF4 x){return sin(x*AF4_(A_2PI));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ARcpF1(AF1 x){return AF1_(1.0)/x;}
 AF2 ARcpF2(AF2 x){return AF2_(1.0)/x;}
 AF3 ARcpF3(AF3 x){return AF3_(1.0)/x;}
 AF4 ARcpF4(AF4 x){return AF4_(1.0)/x;}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ARsqF1(AF1 x){return AF1_(1.0)/sqrt(x);}
 AF2 ARsqF2(AF2 x){return AF2_(1.0)/sqrt(x);}
 AF3 ARsqF3(AF3 x){return AF3_(1.0)/sqrt(x);}
 AF4 ARsqF4(AF4 x){return AF4_(1.0)/sqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ASatF1(AF1 x){return clamp(x,AF1_(0.0),AF1_(1.0));}
 AF2 ASatF2(AF2 x){return clamp(x,AF2_(0.0),AF2_(1.0));}
 AF3 ASatF3(AF3 x){return clamp(x,AF3_(0.0),AF3_(1.0));}
 AF4 ASatF4(AF4 x){return clamp(x,AF4_(0.0),AF4_(1.0));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AShrSU1(AU1 a,AU1 b){return AU1(ASU1(a)>>ASU1(b));}
 AU2 AShrSU2(AU2 a,AU2 b){return AU2(ASU2(a)>>ASU2(b));}
 AU3 AShrSU3(AU3 a,AU3 b){return AU3(ASU3(a)>>ASU3(b));}
 AU4 AShrSU4(AU4 a,AU4 b){return AU4(ASU4(a)>>ASU4(b));}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                          GLSL BYTE
//==============================================================================================================================
 #ifdef A_BYTE
  #define AB1 uint8_t
  #define AB2 u8vec2
  #define AB3 u8vec3
  #define AB4 u8vec4
//------------------------------------------------------------------------------------------------------------------------------
  #define ASB1 int8_t
  #define ASB2 i8vec2
  #define ASB3 i8vec3
  #define ASB4 i8vec4
//------------------------------------------------------------------------------------------------------------------------------
  AB1 AB1_x(AB1 a){return AB1(a);}
  AB2 AB2_x(AB1 a){return AB2(a,a);}
  AB3 AB3_x(AB1 a){return AB3(a,a,a);}
  AB4 AB4_x(AB1 a){return AB4(a,a,a,a);}
  #define AB1_(a) AB1_x(AB1(a))
  #define AB2_(a) AB2_x(AB1(a))
  #define AB3_(a) AB3_x(AB1(a))
  #define AB4_(a) AB4_x(AB1(a))
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                          GLSL HALF
//==============================================================================================================================
 #ifdef A_HALF
  #define AH1 float16_t
  #define AH2 f16vec2
  #define AH3 f16vec3
  #define AH4 f16vec4
//------------------------------------------------------------------------------------------------------------------------------
  #define AW1 uint16_t
  #define AW2 u16vec2
  #define AW3 u16vec3
  #define AW4 u16vec4
//------------------------------------------------------------------------------------------------------------------------------
  #define ASW1 int16_t
  #define ASW2 i16vec2
  #define ASW3 i16vec3
  #define ASW4 i16vec4
//==============================================================================================================================
  #define AH2_AU1(x) unpackFloat2x16(AU1(x))
  AH4 AH4_AU2_x(AU2 x){return AH4(unpackFloat2x16(x.x),unpackFloat2x16(x.y));}
  #define AH4_AU2(x) AH4_AU2_x(AU2(x))
  #define AW2_AU1(x) unpackUint2x16(AU1(x))
  #define AW4_AU2(x) unpackUint4x16(pack64(AU2(x)))
//------------------------------------------------------------------------------------------------------------------------------
  #define AU1_AH2(x) packFloat2x16(AH2(x))
  AU2 AU2_AH4_x(AH4 x){return AU2(packFloat2x16(x.xy),packFloat2x16(x.zw));}
  #define AU2_AH4(x) AU2_AH4_x(AH4(x))
  #define AU1_AW2(x) packUint2x16(AW2(x))
  #define AU2_AW4(x) unpack32(packUint4x16(AW4(x)))
//==============================================================================================================================
  #define AW1_AH1(x) halfBitsToUint16(AH1(x))
  #define AW2_AH2(x) halfBitsToUint16(AH2(x))
  #define AW3_AH3(x) halfBitsToUint16(AH3(x))
  #define AW4_AH4(x) halfBitsToUint16(AH4(x))
//------------------------------------------------------------------------------------------------------------------------------
  #define AH1_AW1(x) uint16BitsToHalf(AW1(x))
  #define AH2_AW2(x) uint16BitsToHalf(AW2(x))
  #define AH3_AW3(x) uint16BitsToHalf(AW3(x))
  #define AH4_AW4(x) uint16BitsToHalf(AW4(x))
//==============================================================================================================================
  AH1 AH1_x(AH1 a){return AH1(a);}
  AH2 AH2_x(AH1 a){return AH2(a,a);}
  AH3 AH3_x(AH1 a){return AH3(a,a,a);}
  AH4 AH4_x(AH1 a){return AH4(a,a,a,a);}
  #define AH1_(a) AH1_x(AH1(a))
  #define AH2_(a) AH2_x(AH1(a))
  #define AH3_(a) AH3_x(AH1(a))
  #define AH4_(a) AH4_x(AH1(a))
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AW1_x(AW1 a){return AW1(a);}
  AW2 AW2_x(AW1 a){return AW2(a,a);}
  AW3 AW3_x(AW1 a){return AW3(a,a,a);}
  AW4 AW4_x(AW1 a){return AW4(a,a,a,a);}
  #define AW1_(a) AW1_x(AW1(a))
  #define AW2_(a) AW2_x(AW1(a))
  #define AW3_(a) AW3_x(AW1(a))
  #define AW4_(a) AW4_x(AW1(a))
//==============================================================================================================================
  AW1 AAbsSW1(AW1 a){return AW1(abs(ASW1(a)));}
  AW2 AAbsSW2(AW2 a){return AW2(abs(ASW2(a)));}
  AW3 AAbsSW3(AW3 a){return AW3(abs(ASW3(a)));}
  AW4 AAbsSW4(AW4 a){return AW4(abs(ASW4(a)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 AFractH1(AH1 x){return fract(x);}
  AH2 AFractH2(AH2 x){return fract(x);}
  AH3 AFractH3(AH3 x){return fract(x);}
  AH4 AFractH4(AH4 x){return fract(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ALerpH1(AH1 x,AH1 y,AH1 a){return mix(x,y,a);}
  AH2 ALerpH2(AH2 x,AH2 y,AH2 a){return mix(x,y,a);}
  AH3 ALerpH3(AH3 x,AH3 y,AH3 a){return mix(x,y,a);}
  AH4 ALerpH4(AH4 x,AH4 y,AH4 a){return mix(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
  // No packed version of max3.
  AH1 AMax3H1(AH1 x,AH1 y,AH1 z){return max(x,max(y,z));}
  AH2 AMax3H2(AH2 x,AH2 y,AH2 z){return max(x,max(y,z));}
  AH3 AMax3H3(AH3 x,AH3 y,AH3 z){return max(x,max(y,z));}
  AH4 AMax3H4(AH4 x,AH4 y,AH4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AMaxSW1(AW1 a,AW1 b){return AW1(max(ASU1(a),ASU1(b)));}
  AW2 AMaxSW2(AW2 a,AW2 b){return AW2(max(ASU2(a),ASU2(b)));}
  AW3 AMaxSW3(AW3 a,AW3 b){return AW3(max(ASU3(a),ASU3(b)));}
  AW4 AMaxSW4(AW4 a,AW4 b){return AW4(max(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
  // No packed version of min3.
  AH1 AMin3H1(AH1 x,AH1 y,AH1 z){return min(x,min(y,z));}
  AH2 AMin3H2(AH2 x,AH2 y,AH2 z){return min(x,min(y,z));}
  AH3 AMin3H3(AH3 x,AH3 y,AH3 z){return min(x,min(y,z));}
  AH4 AMin3H4(AH4 x,AH4 y,AH4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AMinSW1(AW1 a,AW1 b){return AW1(min(ASU1(a),ASU1(b)));}
  AW2 AMinSW2(AW2 a,AW2 b){return AW2(min(ASU2(a),ASU2(b)));}
  AW3 AMinSW3(AW3 a,AW3 b){return AW3(min(ASU3(a),ASU3(b)));}
  AW4 AMinSW4(AW4 a,AW4 b){return AW4(min(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ARcpH1(AH1 x){return AH1_(1.0)/x;}
  AH2 ARcpH2(AH2 x){return AH2_(1.0)/x;}
  AH3 ARcpH3(AH3 x){return AH3_(1.0)/x;}
  AH4 ARcpH4(AH4 x){return AH4_(1.0)/x;}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ARsqH1(AH1 x){return AH1_(1.0)/sqrt(x);}
  AH2 ARsqH2(AH2 x){return AH2_(1.0)/sqrt(x);}
  AH3 ARsqH3(AH3 x){return AH3_(1.0)/sqrt(x);}
  AH4 ARsqH4(AH4 x){return AH4_(1.0)/sqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ASatH1(AH1 x){return clamp(x,AH1_(0.0),AH1_(1.0));}
  AH2 ASatH2(AH2 x){return clamp(x,AH2_(0.0),AH2_(1.0));}
  AH3 ASatH3(AH3 x){return clamp(x,AH3_(0.0),AH3_(1.0));}
  AH4 ASatH4(AH4 x){return clamp(x,AH4_(0.0),AH4_(1.0));}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AShrSW1(AW1 a,AW1 b){return AW1(ASW1(a)>>ASW1(b));}
  AW2 AShrSW2(AW2 a,AW2 b){return AW2(ASW2(a)>>ASW2(b));}
  AW3 AShrSW3(AW3 a,AW3 b){return AW3(ASW3(a)>>ASW3(b));}
  AW4 AShrSW4(AW4 a,AW4 b){return AW4(ASW4(a)>>ASW4(b));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         GLSL DOUBLE
//==============================================================================================================================
 #ifdef A_DUBL
  #define AD1 double
  #define AD2 dvec2
  #define AD3 dvec3
  #define AD4 dvec4
//------------------------------------------------------------------------------------------------------------------------------
  AD1 AD1_x(AD1 a){return AD1(a);}
  AD2 AD2_x(AD1 a){return AD2(a,a);}
  AD3 AD3_x(AD1 a){return AD3(a,a,a);}
  AD4 AD4_x(AD1 a){return AD4(a,a,a,a);}
  #define AD1_(a) AD1_x(AD1(a))
  #define AD2_(a) AD2_x(AD1(a))
  #define AD3_(a) AD3_x(AD1(a))
  #define AD4_(a) AD4_x(AD1(a))
//==============================================================================================================================
  AD1 AFractD1(AD1 x){return fract(x);}
  AD2 AFractD2(AD2 x){return fract(x);}
  AD3 AFractD3(AD3 x){return fract(x);}
  AD4 AFractD4(AD4 x){return fract(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ALerpD1(AD1 x,AD1 y,AD1 a){return mix(x,y,a);}
  AD2 ALerpD2(AD2 x,AD2 y,AD2 a){return mix(x,y,a);}
  AD3 ALerpD3(AD3 x,AD3 y,AD3 a){return mix(x,y,a);}
  AD4 ALerpD4(AD4 x,AD4 y,AD4 a){return mix(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ARcpD1(AD1 x){return AD1_(1.0)/x;}
  AD2 ARcpD2(AD2 x){return AD2_(1.0)/x;}
  AD3 ARcpD3(AD3 x){return AD3_(1.0)/x;}
  AD4 ARcpD4(AD4 x){return AD4_(1.0)/x;}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ARsqD1(AD1 x){return AD1_(1.0)/sqrt(x);}
  AD2 ARsqD2(AD2 x){return AD2_(1.0)/sqrt(x);}
  AD3 ARsqD3(AD3 x){return AD3_(1.0)/sqrt(x);}
  AD4 ARsqD4(AD4 x){return AD4_(1.0)/sqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ASatD1(AD1 x){return clamp(x,AD1_(0.0),AD1_(1.0));}
  AD2 ASatD2(AD2 x){return clamp(x,AD2_(0.0),AD2_(1.0));}
  AD3 ASatD3(AD3 x){return clamp(x,AD3_(0.0),AD3_(1.0));}
  AD4 ASatD4(AD4 x){return clamp(x,AD4_(0.0),AD4_(1.0));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         GLSL LONG
//==============================================================================================================================
 #ifdef A_LONG
  #define AL1 uint64_t
  #define AL2 u64vec2
  #define AL3 u64vec3
  #define AL4 u64vec4
//------------------------------------------------------------------------------------------------------------------------------
  #define ASL1 int64_t
  #define ASL2 i64vec2
  #define ASL3 i64vec3
  #define ASL4 i64vec4
//------------------------------------------------------------------------------------------------------------------------------
  #define AL1_AU2(x) packUint2x32(AU2(x))
  #define AU2_AL1(x) unpackUint2x32(AL1(x))
//------------------------------------------------------------------------------------------------------------------------------
  AL1 AL1_x(AL1 a){return AL1(a);}
  AL2 AL2_x(AL1 a){return AL2(a,a);}
  AL3 AL3_x(AL1 a){return AL3(a,a,a);}
  AL4 AL4_x(AL1 a){return AL4(a,a,a,a);}
  #define AL1_(a) AL1_x(AL1(a))
  #define AL2_(a) AL2_x(AL1(a))
  #define AL3_(a) AL3_x(AL1(a))
  #define AL4_(a) AL4_x(AL1(a))
//==============================================================================================================================
  AL1 AAbsSL1(AL1 a){return AL1(abs(ASL1(a)));}
  AL2 AAbsSL2(AL2 a){return AL2(abs(ASL2(a)));}
  AL3 AAbsSL3(AL3 a){return AL3(abs(ASL3(a)));}
  AL4 AAbsSL4(AL4 a){return AL4(abs(ASL4(a)));}
//------------------------------------------------------------------------------------------------------------------------------
  AL1 AMaxSL1(AL1 a,AL1 b){return AL1(max(ASU1(a),ASU1(b)));}
  AL2 AMaxSL2(AL2 a,AL2 b){return AL2(max(ASU2(a),ASU2(b)));}
  AL3 AMaxSL3(AL3 a,AL3 b){return AL3(max(ASU3(a),ASU3(b)));}
  AL4 AMaxSL4(AL4 a,AL4 b){return AL4(max(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
  AL1 AMinSL1(AL1 a,AL1 b){return AL1(min(ASU1(a),ASU1(b)));}
  AL2 AMinSL2(AL2 a,AL2 b){return AL2(min(ASU2(a),ASU2(b)));}
  AL3 AMinSL3(AL3 a,AL3 b){return AL3(min(ASU3(a),ASU3(b)));}
  AL4 AMinSL4(AL4 a,AL4 b){return AL4(min(ASU4(a),ASU4(b)));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                      WAVE OPERATIONS
//==============================================================================================================================
 #ifdef A_WAVE
  AF1 AWaveAdd(AF1 v){return subgroupAdd(v);}
  AF2 AWaveAdd(AF2 v){return subgroupAdd(v);}
  AF3 AWaveAdd(AF3 v){return subgroupAdd(v);}
  AF4 AWaveAdd(AF4 v){return subgroupAdd(v);}
 #endif
//==============================================================================================================================
#endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//
//                                                            HLSL
//
//
//==============================================================================================================================
#if defined(A_HLSL) && defined(A_GPU)
 #define AP1 bool
 #define AP2 bool2
 #define AP3 bool3
 #define AP4 bool4
//------------------------------------------------------------------------------------------------------------------------------
 #define AF1 float
 #define AF2 float2
 #define AF3 float3
 #define AF4 float4
//------------------------------------------------------------------------------------------------------------------------------
 #define AU1 uint
 #define AU2 uint2
 #define AU3 uint3
 #define AU4 uint4
//------------------------------------------------------------------------------------------------------------------------------
 #define ASU1 int
 #define ASU2 int2
 #define ASU3 int3
 #define ASU4 int4
//==============================================================================================================================
 #define AF1_AU1(x) asfloat(AU1(x))
 #define AF2_AU2(x) asfloat(AU2(x))
 #define AF3_AU3(x) asfloat(AU3(x))
 #define AF4_AU4(x) asfloat(AU4(x))
//------------------------------------------------------------------------------------------------------------------------------
 #define AU1_AF1(x) asuint(AF1(x))
 #define AU2_AF2(x) asuint(AF2(x))
 #define AU3_AF3(x) asuint(AF3(x))
 #define AU4_AF4(x) asuint(AF4(x))
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AU1_AH2_AF2_x(AF2 a){return f32tof16(a.x)|(f32tof16(a.y)<<16);}
 #define AU1_AH2_AF2(a) AU1_AH2_AF2_x(AF2(a)) 
 #define AU1_AB4Unorm_AF4(x) D3DCOLORtoUBYTE4(AF4(x))
//------------------------------------------------------------------------------------------------------------------------------
 AF2 AF2_AH2_AU1_x(AU1 x){return AF2(f16tof32(x&0xFFFF),f16tof32(x>>16));}
 #define AF2_AH2_AU1(x) AF2_AH2_AU1_x(AU1(x))
//==============================================================================================================================
 AF1 AF1_x(AF1 a){return AF1(a);}
 AF2 AF2_x(AF1 a){return AF2(a,a);}
 AF3 AF3_x(AF1 a){return AF3(a,a,a);}
 AF4 AF4_x(AF1 a){return AF4(a,a,a,a);}
 #define AF1_(a) AF1_x(AF1(a))
 #define AF2_(a) AF2_x(AF1(a))
 #define AF3_(a) AF3_x(AF1(a))
 #define AF4_(a) AF4_x(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AU1_x(AU1 a){return AU1(a);}
 AU2 AU2_x(AU1 a){return AU2(a,a);}
 AU3 AU3_x(AU1 a){return AU3(a,a,a);}
 AU4 AU4_x(AU1 a){return AU4(a,a,a,a);}
 #define AU1_(a) AU1_x(AU1(a))
 #define AU2_(a) AU2_x(AU1(a))
 #define AU3_(a) AU3_x(AU1(a))
 #define AU4_(a) AU4_x(AU1(a))
//==============================================================================================================================
 AU1 AAbsSU1(AU1 a){return AU1(abs(ASU1(a)));}
 AU2 AAbsSU2(AU2 a){return AU2(abs(ASU2(a)));}
 AU3 AAbsSU3(AU3 a){return AU3(abs(ASU3(a)));}
 AU4 AAbsSU4(AU4 a){return AU4(abs(ASU4(a)));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 ABfe(AU1 src,AU1 off,AU1 bits){AU1 mask=(1<<bits)-1;return (src>>off)&mask;}
 AU1 ABfi(AU1 src,AU1 ins,AU1 mask){return (ins&mask)|(src&(~mask));}
 AU1 ABfiM(AU1 src,AU1 ins,AU1 bits){AU1 mask=(1<<bits)-1;return (ins&mask)|(src&(~mask));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AFractF1(AF1 x){return x-floor(x);}
 AF2 AFractF2(AF2 x){return x-floor(x);}
 AF3 AFractF3(AF3 x){return x-floor(x);}
 AF4 AFractF4(AF4 x){return x-floor(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ALerpF1(AF1 x,AF1 y,AF1 a){return lerp(x,y,a);}
 AF2 ALerpF2(AF2 x,AF2 y,AF2 a){return lerp(x,y,a);}
 AF3 ALerpF3(AF3 x,AF3 y,AF3 a){return lerp(x,y,a);}
 AF4 ALerpF4(AF4 x,AF4 y,AF4 a){return lerp(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AMax3F1(AF1 x,AF1 y,AF1 z){return max(x,max(y,z));}
 AF2 AMax3F2(AF2 x,AF2 y,AF2 z){return max(x,max(y,z));}
 AF3 AMax3F3(AF3 x,AF3 y,AF3 z){return max(x,max(y,z));}
 AF4 AMax3F4(AF4 x,AF4 y,AF4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMax3SU1(AU1 x,AU1 y,AU1 z){return AU1(max(ASU1(x),max(ASU1(y),ASU1(z))));}
 AU2 AMax3SU2(AU2 x,AU2 y,AU2 z){return AU2(max(ASU2(x),max(ASU2(y),ASU2(z))));}
 AU3 AMax3SU3(AU3 x,AU3 y,AU3 z){return AU3(max(ASU3(x),max(ASU3(y),ASU3(z))));}
 AU4 AMax3SU4(AU4 x,AU4 y,AU4 z){return AU4(max(ASU4(x),max(ASU4(y),ASU4(z))));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMax3U1(AU1 x,AU1 y,AU1 z){return max(x,max(y,z));}
 AU2 AMax3U2(AU2 x,AU2 y,AU2 z){return max(x,max(y,z));}
 AU3 AMax3U3(AU3 x,AU3 y,AU3 z){return max(x,max(y,z));}
 AU4 AMax3U4(AU4 x,AU4 y,AU4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMaxSU1(AU1 a,AU1 b){return AU1(max(ASU1(a),ASU1(b)));}
 AU2 AMaxSU2(AU2 a,AU2 b){return AU2(max(ASU2(a),ASU2(b)));}
 AU3 AMaxSU3(AU3 a,AU3 b){return AU3(max(ASU3(a),ASU3(b)));}
 AU4 AMaxSU4(AU4 a,AU4 b){return AU4(max(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AMed3F1(AF1 x,AF1 y,AF1 z){return max(min(x,y),min(max(x,y),z));}
 AF2 AMed3F2(AF2 x,AF2 y,AF2 z){return max(min(x,y),min(max(x,y),z));}
 AF3 AMed3F3(AF3 x,AF3 y,AF3 z){return max(min(x,y),min(max(x,y),z));}
 AF4 AMed3F4(AF4 x,AF4 y,AF4 z){return max(min(x,y),min(max(x,y),z));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AMin3F1(AF1 x,AF1 y,AF1 z){return min(x,min(y,z));}
 AF2 AMin3F2(AF2 x,AF2 y,AF2 z){return min(x,min(y,z));}
 AF3 AMin3F3(AF3 x,AF3 y,AF3 z){return min(x,min(y,z));}
 AF4 AMin3F4(AF4 x,AF4 y,AF4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMin3SU1(AU1 x,AU1 y,AU1 z){return AU1(min(ASU1(x),min(ASU1(y),ASU1(z))));}
 AU2 AMin3SU2(AU2 x,AU2 y,AU2 z){return AU2(min(ASU2(x),min(ASU2(y),ASU2(z))));}
 AU3 AMin3SU3(AU3 x,AU3 y,AU3 z){return AU3(min(ASU3(x),min(ASU3(y),ASU3(z))));}
 AU4 AMin3SU4(AU4 x,AU4 y,AU4 z){return AU4(min(ASU4(x),min(ASU4(y),ASU4(z))));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMin3U1(AU1 x,AU1 y,AU1 z){return min(x,min(y,z));}
 AU2 AMin3U2(AU2 x,AU2 y,AU2 z){return min(x,min(y,z));}
 AU3 AMin3U3(AU3 x,AU3 y,AU3 z){return min(x,min(y,z));}
 AU4 AMin3U4(AU4 x,AU4 y,AU4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AMinSU1(AU1 a,AU1 b){return AU1(min(ASU1(a),ASU1(b)));}
 AU2 AMinSU2(AU2 a,AU2 b){return AU2(min(ASU2(a),ASU2(b)));}
 AU3 AMinSU3(AU3 a,AU3 b){return AU3(min(ASU3(a),ASU3(b)));}
 AU4 AMinSU4(AU4 a,AU4 b){return AU4(min(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ANCosF1(AF1 x){return cos(x*AF1_(A_2PI));}
 AF2 ANCosF2(AF2 x){return cos(x*AF2_(A_2PI));}
 AF3 ANCosF3(AF3 x){return cos(x*AF3_(A_2PI));}
 AF4 ANCosF4(AF4 x){return cos(x*AF4_(A_2PI));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ANSinF1(AF1 x){return sin(x*AF1_(A_2PI));}
 AF2 ANSinF2(AF2 x){return sin(x*AF2_(A_2PI));}
 AF3 ANSinF3(AF3 x){return sin(x*AF3_(A_2PI));}
 AF4 ANSinF4(AF4 x){return sin(x*AF4_(A_2PI));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ARcpF1(AF1 x){return rcp(x);}
 AF2 ARcpF2(AF2 x){return rcp(x);}
 AF3 ARcpF3(AF3 x){return rcp(x);}
 AF4 ARcpF4(AF4 x){return rcp(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ARsqF1(AF1 x){return rsqrt(x);}
 AF2 ARsqF2(AF2 x){return rsqrt(x);}
 AF3 ARsqF3(AF3 x){return rsqrt(x);}
 AF4 ARsqF4(AF4 x){return rsqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 ASatF1(AF1 x){return saturate(x);}
 AF2 ASatF2(AF2 x){return saturate(x);}
 AF3 ASatF3(AF3 x){return saturate(x);}
 AF4 ASatF4(AF4 x){return saturate(x);}
//------------------------------------------------------------------------------------------------------------------------------
 AU1 AShrSU1(AU1 a,AU1 b){return AU1(ASU1(a)>>ASU1(b));}
 AU2 AShrSU2(AU2 a,AU2 b){return AU2(ASU2(a)>>ASU2(b));}
 AU3 AShrSU3(AU3 a,AU3 b){return AU3(ASU3(a)>>ASU3(b));}
 AU4 AShrSU4(AU4 a,AU4 b){return AU4(ASU4(a)>>ASU4(b));}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                          HLSL BYTE
//==============================================================================================================================
 #ifdef A_BYTE
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                          HLSL HALF
//==============================================================================================================================
 #ifdef A_HALF
  #define AH1 min16float
  #define AH2 min16float2
  #define AH3 min16float3
  #define AH4 min16float4
//------------------------------------------------------------------------------------------------------------------------------
  #define AW1 min16uint
  #define AW2 min16uint2
  #define AW3 min16uint3
  #define AW4 min16uint4
//------------------------------------------------------------------------------------------------------------------------------
  #define ASW1 min16int
  #define ASW2 min16int2
  #define ASW3 min16int3
  #define ASW4 min16int4
//==============================================================================================================================
  // Need to use manual unpack to get optimal execution (don't use packed types in buffers directly).
  // Unpack requires this pattern: https://gpuopen.com/first-steps-implementing-fp16/
  AH2 AH2_AU1_x(AU1 x){AF2 t=f16tof32(AU2(x&0xFFFF,x>>16));return AH2(t);}
  AH4 AH4_AU2_x(AU2 x){return AH4(AH2_AU1_x(x.x),AH2_AU1_x(x.y));}
  AW2 AW2_AU1_x(AU1 x){AU2 t=AU2(x&0xFFFF,x>>16);return AW2(t);}
  AW4 AW4_AU2_x(AU2 x){return AW4(AW2_AU1_x(x.x),AW2_AU1_x(x.y));}
  #define AH2_AU1(x) AH2_AU1_x(AU1(x))
  #define AH4_AU2(x) AH4_AU2_x(AU2(x))
  #define AW2_AU1(x) AW2_AU1_x(AU1(x))
  #define AW4_AU2(x) AW4_AU2_x(AU2(x))
//------------------------------------------------------------------------------------------------------------------------------
  AU1 AU1_AH2_x(AH2 x){return f32tof16(x.x)+(f32tof16(x.y)<<16);}
  AU2 AU2_AH4_x(AH4 x){return AU2(AU1_AH2_x(x.xy),AU1_AH2_x(x.zw));}
  AU1 AU1_AW2_x(AW2 x){return AU1(x.x)+(AU1(x.y)<<16);}
  AU2 AU2_AW4_x(AW4 x){return AU2(AU1_AW2_x(x.xy),AU1_AW2_x(x.zw));}
  #define AU1_AH2(x) AU1_AH2_x(AH2(x))
  #define AU2_AH4(x) AU2_AH4_x(AH4(x))
  #define AU1_AW2(x) AU1_AW2_x(AW2(x))
  #define AU2_AW4(x) AU2_AW4_x(AW4(x))
//==============================================================================================================================
  // TODO: These are broken!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  #define AW1_AH1(x) AW1(asuint(AF1(x)))
  #define AW2_AH2(x) AW2(asuint(AF2(x)))
  #define AW3_AH3(x) AW3(asuint(AF3(x)))
  #define AW4_AH4(x) AW4(asuint(AF4(x)))
//------------------------------------------------------------------------------------------------------------------------------
  // TODO: These are broken!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  #define AH1_AW1(x) AH1(asfloat(AU1(x)))
  #define AH2_AW2(x) AH2(asfloat(AU2(x)))
  #define AH3_AW3(x) AH3(asfloat(AU3(x)))
  #define AH4_AW4(x) AH4(asfloat(AU4(x)))
//==============================================================================================================================
  AH1 AH1_x(AH1 a){return AH1(a);}
  AH2 AH2_x(AH1 a){return AH2(a,a);}
  AH3 AH3_x(AH1 a){return AH3(a,a,a);}
  AH4 AH4_x(AH1 a){return AH4(a,a,a,a);}
  #define AH1_(a) AH1_x(AH1(a))
  #define AH2_(a) AH2_x(AH1(a))
  #define AH3_(a) AH3_x(AH1(a))
  #define AH4_(a) AH4_x(AH1(a))
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AW1_x(AW1 a){return AW1(a);}
  AW2 AW2_x(AW1 a){return AW2(a,a);}
  AW3 AW3_x(AW1 a){return AW3(a,a,a);}
  AW4 AW4_x(AW1 a){return AW4(a,a,a,a);}
  #define AW1_(a) AW1_x(AW1(a))
  #define AW2_(a) AW2_x(AW1(a))
  #define AW3_(a) AW3_x(AW1(a))
  #define AW4_(a) AW4_x(AW1(a))
//==============================================================================================================================
  AW1 AAbsSW1(AW1 a){return AW1(abs(ASW1(a)));}
  AW2 AAbsSW2(AW2 a){return AW2(abs(ASW2(a)));}
  AW3 AAbsSW3(AW3 a){return AW3(abs(ASW3(a)));}
  AW4 AAbsSW4(AW4 a){return AW4(abs(ASW4(a)));}
//------------------------------------------------------------------------------------------------------------------------------
 // V_FRACT_F16 (note DX frac() is different).
  AH1 AFractH1(AH1 x){return x-floor(x);}
  AH2 AFractH2(AH2 x){return x-floor(x);}
  AH3 AFractH3(AH3 x){return x-floor(x);}
  AH4 AFractH4(AH4 x){return x-floor(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ALerpH1(AH1 x,AH1 y,AH1 a){return lerp(x,y,a);}
  AH2 ALerpH2(AH2 x,AH2 y,AH2 a){return lerp(x,y,a);}
  AH3 ALerpH3(AH3 x,AH3 y,AH3 a){return lerp(x,y,a);}
  AH4 ALerpH4(AH4 x,AH4 y,AH4 a){return lerp(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 AMax3H1(AH1 x,AH1 y,AH1 z){return max(x,max(y,z));}
  AH2 AMax3H2(AH2 x,AH2 y,AH2 z){return max(x,max(y,z));}
  AH3 AMax3H3(AH3 x,AH3 y,AH3 z){return max(x,max(y,z));}
  AH4 AMax3H4(AH4 x,AH4 y,AH4 z){return max(x,max(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AMaxSW1(AW1 a,AW1 b){return AW1(max(ASU1(a),ASU1(b)));}
  AW2 AMaxSW2(AW2 a,AW2 b){return AW2(max(ASU2(a),ASU2(b)));}
  AW3 AMaxSW3(AW3 a,AW3 b){return AW3(max(ASU3(a),ASU3(b)));}
  AW4 AMaxSW4(AW4 a,AW4 b){return AW4(max(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 AMin3H1(AH1 x,AH1 y,AH1 z){return min(x,min(y,z));}
  AH2 AMin3H2(AH2 x,AH2 y,AH2 z){return min(x,min(y,z));}
  AH3 AMin3H3(AH3 x,AH3 y,AH3 z){return min(x,min(y,z));}
  AH4 AMin3H4(AH4 x,AH4 y,AH4 z){return min(x,min(y,z));}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AMinSW1(AW1 a,AW1 b){return AW1(min(ASU1(a),ASU1(b)));}
  AW2 AMinSW2(AW2 a,AW2 b){return AW2(min(ASU2(a),ASU2(b)));}
  AW3 AMinSW3(AW3 a,AW3 b){return AW3(min(ASU3(a),ASU3(b)));}
  AW4 AMinSW4(AW4 a,AW4 b){return AW4(min(ASU4(a),ASU4(b)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ARcpH1(AH1 x){return rcp(x);}
  AH2 ARcpH2(AH2 x){return rcp(x);}
  AH3 ARcpH3(AH3 x){return rcp(x);}
  AH4 ARcpH4(AH4 x){return rcp(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ARsqH1(AH1 x){return rsqrt(x);}
  AH2 ARsqH2(AH2 x){return rsqrt(x);}
  AH3 ARsqH3(AH3 x){return rsqrt(x);}
  AH4 ARsqH4(AH4 x){return rsqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ASatH1(AH1 x){return saturate(x);}
  AH2 ASatH2(AH2 x){return saturate(x);}
  AH3 ASatH3(AH3 x){return saturate(x);}
  AH4 ASatH4(AH4 x){return saturate(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AW1 AShrSW1(AW1 a,AW1 b){return AW1(ASW1(a)>>ASW1(b));}
  AW2 AShrSW2(AW2 a,AW2 b){return AW2(ASW2(a)>>ASW2(b));}
  AW3 AShrSW3(AW3 a,AW3 b){return AW3(ASW3(a)>>ASW3(b));}
  AW4 AShrSW4(AW4 a,AW4 b){return AW4(ASW4(a)>>ASW4(b));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         HLSL DOUBLE
//==============================================================================================================================
 #ifdef A_DUBL
  #define AD1 double
  #define AD2 double2
  #define AD3 double3
  #define AD4 double4
//------------------------------------------------------------------------------------------------------------------------------
  AD1 AD1_x(AD1 a){return AD1(a);}
  AD2 AD2_x(AD1 a){return AD2(a,a);}
  AD3 AD3_x(AD1 a){return AD3(a,a,a);}
  AD4 AD4_x(AD1 a){return AD4(a,a,a,a);}
  #define AD1_(a) AD1_x(AD1(a))
  #define AD2_(a) AD2_x(AD1(a))
  #define AD3_(a) AD3_x(AD1(a))
  #define AD4_(a) AD4_x(AD1(a))
//==============================================================================================================================
  AD1 AFractD1(AD1 a){return a-floor(a);}
  AD2 AFractD2(AD2 a){return a-floor(a);}
  AD3 AFractD3(AD3 a){return a-floor(a);}
  AD4 AFractD4(AD4 a){return a-floor(a);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ALerpD1(AD1 x,AD1 y,AD1 a){return lerp(x,y,a);}
  AD2 ALerpD2(AD2 x,AD2 y,AD2 a){return lerp(x,y,a);}
  AD3 ALerpD3(AD3 x,AD3 y,AD3 a){return lerp(x,y,a);}
  AD4 ALerpD4(AD4 x,AD4 y,AD4 a){return lerp(x,y,a);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ARcpD1(AD1 x){return rcp(x);}
  AD2 ARcpD2(AD2 x){return rcp(x);}
  AD3 ARcpD3(AD3 x){return rcp(x);}
  AD4 ARcpD4(AD4 x){return rcp(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ARsqD1(AD1 x){return rsqrt(x);}
  AD2 ARsqD2(AD2 x){return rsqrt(x);}
  AD3 ARsqD3(AD3 x){return rsqrt(x);}
  AD4 ARsqD4(AD4 x){return rsqrt(x);}
//------------------------------------------------------------------------------------------------------------------------------
  AD1 ASatD1(AD1 x){return saturate(x);}
  AD2 ASatD2(AD2 x){return saturate(x);}
  AD3 ASatD3(AD3 x){return saturate(x);}
  AD4 ASatD4(AD4 x){return saturate(x);}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         HLSL LONG
//==============================================================================================================================
 #ifdef A_LONG
 #endif
//==============================================================================================================================
#endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//
//                                                          GPU COMMON
//
//
//==============================================================================================================================
#ifdef A_GPU
 // Negative and positive infinity.
 #define A_INFN_F AF1_AU1(0x7f800000u)
 #define A_INFP_F AF1_AU1(0xff800000u)
//------------------------------------------------------------------------------------------------------------------------------
 // Copy sign from 's' to positive 'd'.
 AF1 ACpySgnF1(AF1 d,AF1 s){return AF1_AU1(AU1_AF1(d)|(AU1_AF1(s)&AU1_(0x80000000u)));}
 AF2 ACpySgnF2(AF2 d,AF2 s){return AF2_AU2(AU2_AF2(d)|(AU2_AF2(s)&AU2_(0x80000000u)));}
 AF3 ACpySgnF3(AF3 d,AF3 s){return AF3_AU3(AU3_AF3(d)|(AU3_AF3(s)&AU3_(0x80000000u)));}
 AF4 ACpySgnF4(AF4 d,AF4 s){return AF4_AU4(AU4_AF4(d)|(AU4_AF4(s)&AU4_(0x80000000u)));}
//------------------------------------------------------------------------------------------------------------------------------
 // Single operation to return (useful to create a mask to use in lerp for branch free logic),
 //  m=NaN := 0
 //  m>=0  := 0
 //  m<0   := 1
 // Uses the following useful floating point logic,
 //  saturate(+a*(-INF)==-INF) := 0
 //  saturate( 0*(-INF)== NaN) := 0
 //  saturate(-a*(-INF)==+INF) := 1
 AF1 ASignedF1(AF1 m){return ASatF1(m*AF1_(A_INFN_F));}
 AF2 ASignedF2(AF2 m){return ASatF2(m*AF2_(A_INFN_F));}
 AF3 ASignedF3(AF3 m){return ASatF3(m*AF3_(A_INFN_F));}
 AF4 ASignedF4(AF4 m){return ASatF4(m*AF4_(A_INFN_F));}
//==============================================================================================================================
 #ifdef A_HALF
  #define A_INFN_H AH1_AW1(0x7c00u)
  #define A_INFP_H AH1_AW1(0xfc00u)
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ACpySgnH1(AH1 d,AH1 s){return AH1_AW1(AW1_AH1(d)|(AW1_AH1(s)&AW1_(0x8000u)));}
  AH2 ACpySgnH2(AH2 d,AH2 s){return AH2_AW2(AW2_AH2(d)|(AW2_AH2(s)&AW2_(0x8000u)));}
  AH3 ACpySgnH3(AH3 d,AH3 s){return AH3_AW3(AW3_AH3(d)|(AW3_AH3(s)&AW3_(0x8000u)));}
  AH4 ACpySgnH4(AH4 d,AH4 s){return AH4_AW4(AW4_AH4(d)|(AW4_AH4(s)&AW4_(0x8000u)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH1 ASignedH1(AH1 m){return ASatH1(m*AH1_(A_INFN_H));}
  AH2 ASignedH2(AH2 m){return ASatH2(m*AH2_(A_INFN_H));}
  AH3 ASignedH3(AH3 m){return ASatH3(m*AH3_(A_INFN_H));}
  AH4 ASignedH4(AH4 m){return ASatH4(m*AH4_(A_INFN_H));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                     HALF APPROXIMATIONS
//------------------------------------------------------------------------------------------------------------------------------
// These support only positive inputs.
// Did not see value yet in specialization for range.
// Using quick testing, ended up mostly getting the same "best" approximation for various ranges.
// With hardware that can co-execute transcendentals, the value in approximations could be less than expected.
// However from a latency perspective, if execution of a transcendental is 4 clk, with no packed support, -> 8 clk total.
// And co-execution would require a compiler interleaving a lot of independent work for packed usage.
//------------------------------------------------------------------------------------------------------------------------------
// The one Newton Raphson iteration form of rsq() was skipped (requires 6 ops total).
// Same with sqrt(), as this could be x*rsq() (7 ops).
//------------------------------------------------------------------------------------------------------------------------------
// IDEAS
// =====
//  - Polaris hardware has 16-bit support, but non-double rate.
//    Could be possible still get part double rate for some of this logic, 
//    by clearing out the lower half's sign when necessary and using 32-bit ops...
//==============================================================================================================================
 #ifdef A_HALF
  // Minimize squared error across full positive range, 2 ops.
  // The 0x1de2 based approximation maps {0 to 1} input maps to < 1 output.
  AH1 APrxLoSqrtH1(AH1 a){return AH1_AW1((AW1_AH1(a)>>AW1_(1))+AW1_(0x1de2));}
  AH2 APrxLoSqrtH2(AH2 a){return AH2_AW2((AW2_AH2(a)>>AW2_(1))+AW2_(0x1de2));}
//------------------------------------------------------------------------------------------------------------------------------
  // Lower precision estimation, 1 op.
  // Minimize squared error across {smallest normal to 16384.0}.
  AH1 APrxLoRcpH1(AH1 a){return AH1_AW1(AW1_(0x7784)-AW1_AH1(a));}
  AH2 APrxLoRcpH2(AH2 a){return AH2_AW2(AW2_(0x7784)-AW2_AH2(a));}
//------------------------------------------------------------------------------------------------------------------------------
  // Medium precision estimation, one Newton Raphson iteration, 3 ops.
  AH1 APrxMedRcpH1(AH1 a){AH1 b=AH1_AW1(AW1_(0x778d)-AW1_AH1(a));return b*(-b*a+AH1_(2.0));}
  AH2 APrxMedRcpH2(AH2 a){AH2 b=AH2_AW2(AW2_(0x778d)-AW2_AH2(a));return b*(-b*a+AH2_(2.0));}
//------------------------------------------------------------------------------------------------------------------------------
  // Minimize squared error across {smallest normal to 16384.0}, 2 ops.
  AH1 APrxLoRsqH1(AH1 a){return AH1_AW1(AW1_(0x59a3)-(AW1_AH1(a)>>AW1_(1)));}
  AH2 APrxLoRsqH2(AH2 a){return AH2_AW2(AW2_(0x59a3)-(AW2_AH2(a)>>AW2_(1)));}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                    FLOAT APPROXIMATIONS
//------------------------------------------------------------------------------------------------------------------------------
// Michal Drobot has an excellent presentation on these: "Low Level Optimizations For GCN",
//  - Idea dates back to SGI, then to Quake 3, etc.
//  - https://michaldrobot.files.wordpress.com/2014/05/gcn_alu_opt_digitaldragons2014.pdf
//     - sqrt(x)=rsqrt(x)*x
//     - rcp(x)=rsqrt(x)*rsqrt(x) for positive x
//  - https://github.com/michaldrobot/ShaderFastLibs/blob/master/ShaderFastMathLib.h
//------------------------------------------------------------------------------------------------------------------------------
// These below are from perhaps less complete searching for optimal.
// Used FP16 normal range for testing with +4096 32-bit step size for sampling error.
// So these match up well with the half approximations.
//==============================================================================================================================
 AF1 APrxLoSqrtF1(AF1 a){return AF1_AU1((AU1_AF1(a)>>AU1_(1))+AU1_(0x1fbc4639));}
 AF1 APrxLoRcpF1(AF1 a){return AF1_AU1(AU1_(0x7ef07ebb)-AU1_AF1(a));}
 AF1 APrxMedRcpF1(AF1 a){AF1 b=AF1_AU1(AU1_(0x7ef19fff)-AU1_AF1(a));return b*(-b*a+AF1_(2.0));}
 AF1 APrxLoRsqF1(AF1 a){return AF1_AU1(AU1_(0x5f347d74)-(AU1_AF1(a)>>AU1_(1)));}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                    PARABOLIC SIN & COS
//------------------------------------------------------------------------------------------------------------------------------
// Approximate answers to transcendental questions.
//------------------------------------------------------------------------------------------------------------------------------
// TODO
// ====
//  - Verify packed math ABS is correctly doing an AND.
//==============================================================================================================================
 // Valid input range is {-1 to 1} representing {0 to 2 pi}.
 // Output range is {-1/4 to -1/4} representing {-1 to 1}.
 AF1 APSinF1(AF1 x){return x*abs(x)-x;} // MAD.
 AF1 APCosF1(AF1 x){x=AFractF1(x*AF1_(0.5)+AF1_(0.75));x=x*AF1_(2.0)-AF1_(1.0);return APSinF1(x);} // 3x MAD, FRACT 
//------------------------------------------------------------------------------------------------------------------------------
 #ifdef A_HALF
  // For a packed {sin,cos} pair,
  //  - Native takes 16 clocks and 4 issue slots (no packed transcendentals).
  //  - Parabolic takes 8 clocks and 8 issue slots (only fract is non-packed).
  AH2 APSinH2(AH2 x){return x*abs(x)-x;} // AND,FMA
  AH2 APCosH2(AH2 x){x=AFractH2(x*AH2_(0.5)+AH2_(0.75));x=x*AH2_(2.0)-AH2_(1.0);return APSinH2(x);} // 3x FMA, 2xFRACT, AND 
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                      COLOR CONVERSIONS
//------------------------------------------------------------------------------------------------------------------------------
// These are all linear to/from some other space (where 'linear' has been shortened out of the function name).
// So 'ToGamma' is 'LinearToGamma', and 'FromGamma' is 'LinearFromGamma'.
// These are branch free implementations.
// The AToSrgbF1() function is useful for stores for compute shaders for GPUs without hardware linear->sRGB store conversion.
//------------------------------------------------------------------------------------------------------------------------------
// TRANSFER FUNCTIONS
// ==================
// 709 ..... Rec709 used for some HDTVs
// Gamma ... Typically 2.2 for some PC displays, or 2.4-2.5 for CRTs, or 2.2 FreeSync2 native
// Pq ...... PQ native for HDR10
// Srgb .... The sRGB output, typical of PC displays, useful for 10-bit output, or storing to 8-bit UNORM without SRGB type
// Two ..... Gamma 2.0, fastest conversion (useful for intermediate pass approximations)
//------------------------------------------------------------------------------------------------------------------------------
// FOR PQ
// ======
// Both input and output is {0.0-1.0}, and where output 1.0 represents 10000.0 cd/m^2.
// All constants are only specified to FP32 precision.
// External PQ source reference,
//  - https://github.com/ampas/aces-dev/blob/master/transforms/ctl/utilities/ACESlib.Utilities_Color.a1.0.1.ctl
//------------------------------------------------------------------------------------------------------------------------------
// PACKED VERSIONS
// ===============
// These are the A*H2() functions.
// There is no PQ functions as FP16 seemed to not have enough precision for the conversion.
// The remaining functions are "good enough" for 8-bit, and maybe 10-bit if not concerned about a few 1-bit errors.
// Precision is lowest in the 709 conversion, higher in sRGB, higher still in Two and Gamma (when using 2.2 at least).
//------------------------------------------------------------------------------------------------------------------------------
// NOTES
// =====
// Could be faster for PQ conversions to be in ALU or a texture lookup depending on usage case.
//==============================================================================================================================
 AF1 ATo709F1(AF1 c){return max(min(c*AF1_(4.5),AF1_(0.018)),AF1_(1.099)*pow(c,AF1_(0.45))-AF1_(0.099));}
//------------------------------------------------------------------------------------------------------------------------------
 // Note 'rcpX' is '1/x', where the 'x' is what would be used in AFromGamma().
 AF1 AToGammaF1(AF1 c,AF1 rcpX){return pow(c,rcpX);} 
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AToPqF1(AF1 x){AF1 p=pow(x,AF1_(0.159302));
  return pow((AF1_(0.835938)+AF1_(18.8516)*p)/(AF1_(1.0)+AF1_(18.6875)*p),AF1_(78.8438));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AToSrgbF1(AF1 c){return max(min(c*AF1_(12.92),AF1_(0.0031308)),AF1_(1.055)*pow(c,AF1_(0.41666))-AF1_(0.055));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AToTwoF1(AF1 c){return sqrt(c);}
//==============================================================================================================================
 AF1 AFrom709F1(AF1 c){return max(min(c*AF1_(1.0/4.5),AF1_(0.081)),
  pow((c+AF1_(0.099))*(AF1_(1.0)/(AF1_(1.099))),AF1_(1.0/0.45)));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AFromGammaF1(AF1 c,AF1 x){return pow(c,x);} 
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AFromPqF1(AF1 x){AF1 p=pow(x,AF1_(0.0126833));
  return pow(ASatF1(p-AF1_(0.835938))/(AF1_(18.8516)-AF1_(18.6875)*p),AF1_(6.27739));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AFromSrgbF1(AF1 c){return max(min(c*AF1_(1.0/12.92),AF1_(0.04045)),
  pow((c+AF1_(0.055))*(AF1_(1.0)/AF1_(1.055)),AF1_(2.4)));}
//------------------------------------------------------------------------------------------------------------------------------
 AF1 AFromTwoF1(AF1 c){return c*c;}
//==============================================================================================================================
 #ifdef A_HALF
  AH2 ATo709H2(AH2 c){return max(min(c*AH2_(4.5),AH2_(0.018)),AH2_(1.099)*pow(c,AH2_(0.45))-AH2_(0.099));}
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AToGammaH2(AH2 c,AH1 rcpX){return pow(c,AH2_(rcpX));} 
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AToSrgbH2(AH2 c){return max(min(c*AH2_(12.92),AH2_(0.0031308)),AH2_(1.055)*pow(c,AH2_(0.41666))-AH2_(0.055));}
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AToTwoH2(AH2 c){return sqrt(c);}
 #endif
//==============================================================================================================================
 #ifdef A_HALF
  AH2 AFrom709H2(AH2 c){return max(min(c*AH2_(1.0/4.5),AH2_(0.081)),
   pow((c+AH2_(0.099))*(AH2_(1.0)/(AH2_(1.099))),AH2_(1.0/0.45)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AFromGammaH2(AH2 c,AH1 x){return pow(c,AH2_(x));}
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AFromSrgbH2(AH2 c){return max(min(c*AH2_(1.0/12.92),AH2_(0.04045)),
   pow((c+AH2_(0.055))*(AH2_(1.0)/AH2_(1.055)),AH2_(2.4)));}
//------------------------------------------------------------------------------------------------------------------------------
  AH2 AFromTwoH2(AH2 c){return c*c;}
 #endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                          CS REMAP
//==============================================================================================================================
 // Simple remap 64x1 to 8x8 with rotated 2x2 pixel quads in quad linear.
 //  543210
 //  ======
 //  ..xxx.
 //  yy...y
 AU2 ARmp8x8(AU1 a){return AU2(ABfe(a,1u,3u),ABfiM(ABfe(a,3u,3u),a,1u));}
//==============================================================================================================================
 // More complex remap 64x1 to 8x8 which is necessary for 2D wave reductions.
 //  543210
 //  ======
 //  .xx..x
 //  y..yy.
 // Details,
 //  LANE TO 8x8 MAPPING
 //  ===================
 //  00 01 08 09 10 11 18 19 
 //  02 03 0a 0b 12 13 1a 1b
 //  04 05 0c 0d 14 15 1c 1d
 //  06 07 0e 0f 16 17 1e 1f 
 //  20 21 28 29 30 31 38 39 
 //  22 23 2a 2b 32 33 3a 3b
 //  24 25 2c 2d 34 35 3c 3d
 //  26 27 2e 2f 36 37 3e 3f 
 AU2 ARmpRed8x8(AU1 a){return AU2(ABfiM(ABfe(a,2u,3u),a,1u),ABfiM(ABfe(a,3u,3u),ABfe(a,1u,2u),2u));}
#endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//                                                          REFERENCE
//
//------------------------------------------------------------------------------------------------------------------------------
// IEEE FLOAT RULES
// ================
//  - saturate(NaN)=0, saturate(-INF)=0, saturate(+INF)=1
//  - {+/-}0 * {+/-}INF = NaN
//  - -INF + (+INF) = NaN
//  - {+/-}0 / {+/-}0 = NaN
//  - {+/-}INF / {+/-}INF = NaN
//  - a<(-0) := sqrt(a) = NaN (a=-0.0 won't NaN)
//  - 0 == -0
//  - 4/0 = +INF
//  - 4/-0 = -INF
//  - 4+INF = +INF
//  - 4-INF = -INF
//  - 4*(+INF) = +INF
//  - 4*(-INF) = -INF
//  - -4*(+INF) = -INF
//  - sqrt(+INF) = +INF
//------------------------------------------------------------------------------------------------------------------------------
// FP16 ENCODING
// =============
// fedcba9876543210
// ----------------
// ......mmmmmmmmmm  10-bit mantissa (encodes 11-bit 0.5 to 1.0 except for denormals)
// .eeeee..........  5-bit exponent
// .00000..........  denormals
// .00001..........  -14 exponent
// .11110..........   15 exponent
// .111110000000000  infinity
// .11111nnnnnnnnnn  NaN with n!=0
// s...............  sign
//------------------------------------------------------------------------------------------------------------------------------
// FP16/INT16 ALIASING DENORMAL
// ============================
// 11-bit unsigned integers alias with half float denormal/normal values,
//     1 = 2^(-24) = 1/16777216 ....................... first denormal value
//     2 = 2^(-23)
//   ...
//  1023 = 2^(-14)*(1-2^(-10)) = 2^(-14)*(1-1/1024) ... last denormal value
//  1024 = 2^(-14) = 1/16384 .......................... first normal value that still maps to integers
//  2047 .............................................. last normal value that still maps to integers 
// Scaling limits,
//  2^15 = 32768 ...................................... largest power of 2 scaling
// Largest pow2 conversion mapping is at *32768,
//     1 : 2^(-9) = 1/128
//  1024 : 8
//  2047 : a little less than 16
//==============================================================================================================================
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//
//                                                     GPU/CPU PORTABILITY
//
//
//------------------------------------------------------------------------------------------------------------------------------
// This is the GPU implementation.
// See the CPU implementation for docs.
//==============================================================================================================================
#ifdef A_GPU
 #define A_TRUE true
 #define A_FALSE false
 #define A_STATIC
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                     VECTOR ARGUMENT/RETURN/INITIALIZATION PORTABILITY
//==============================================================================================================================
 #define retAD2 AD2
 #define retAD3 AD3
 #define retAD4 AD4
 #define retAF2 AF2
 #define retAF3 AF3
 #define retAF4 AF4
 #define retAL2 AL2
 #define retAL3 AL3
 #define retAL4 AL4
 #define retAU2 AU2
 #define retAU3 AU3
 #define retAU4 AU4
//------------------------------------------------------------------------------------------------------------------------------
 #define inAD2 in AD2
 #define inAD3 in AD3
 #define inAD4 in AD4
 #define inAF2 in AF2
 #define inAF3 in AF3
 #define inAF4 in AF4
 #define inAL2 in AL2
 #define inAL3 in AL3
 #define inAL4 in AL4
 #define inAU2 in AU2
 #define inAU3 in AU3
 #define inAU4 in AU4
//------------------------------------------------------------------------------------------------------------------------------
 #define inoutAD2 inout AD2
 #define inoutAD3 inout AD3
 #define inoutAD4 inout AD4
 #define inoutAF2 inout AF2
 #define inoutAF3 inout AF3
 #define inoutAF4 inout AF4
 #define inoutAL2 inout AL2
 #define inoutAL3 inout AL3
 #define inoutAL4 inout AL4
 #define inoutAU2 inout AU2
 #define inoutAU3 inout AU3
 #define inoutAU4 inout AU4
//------------------------------------------------------------------------------------------------------------------------------
 #define outAD2 out AD2
 #define outAD3 out AD3
 #define outAD4 out AD4
 #define outAF2 out AF2
 #define outAF3 out AF3
 #define outAF4 out AF4
 #define outAL2 out AL2
 #define outAL3 out AL3
 #define outAL4 out AL4
 #define outAU2 out AU2
 #define outAU3 out AU3
 #define outAU4 out AU4
//------------------------------------------------------------------------------------------------------------------------------
 #define varAD2(x) AD2 x
 #define varAD3(x) AD3 x
 #define varAD4(x) AD4 x
 #define varAF2(x) AF2 x
 #define varAF3(x) AF3 x
 #define varAF4(x) AF4 x
 #define varAL2(x) AL2 x
 #define varAL3(x) AL3 x
 #define varAL4(x) AL4 x
 #define varAU2(x) AU2 x
 #define varAU3(x) AU3 x
 #define varAU4(x) AU4 x
//------------------------------------------------------------------------------------------------------------------------------
 #define initAD2(x,y) AD2(x,y)
 #define initAD3(x,y,z) AD3(x,y,z)
 #define initAD4(x,y,z,w) AD4(x,y,z,w)
 #define initAF2(x,y) AF2(x,y)
 #define initAF3(x,y,z) AF3(x,y,z)
 #define initAF4(x,y,z,w) AF4(x,y,z,w)
 #define initAL2(x,y) AL2(x,y)
 #define initAL3(x,y,z) AL3(x,y,z)
 #define initAL4(x,y,z,w) AL4(x,y,z,w)
 #define initAU2(x,y) AU2(x,y)
 #define initAU3(x,y,z) AU3(x,y,z)
 #define initAU4(x,y,z,w) AU4(x,y,z,w)
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                     SCALAR RETURN OPS
//==============================================================================================================================
 #define AAbsD1(a) abs(AD1(a))
 #define AAbsF1(a) abs(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define ACosD1(a) cos(AD1(a))
 #define ACosF1(a) cos(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define ADotD2(a,b) dot(AD2(a),AD2(b))
 #define ADotD3(a,b) dot(AD3(a),AD3(b))
 #define ADotD4(a,b) dot(AD4(a),AD4(b))
 #define ADotF2(a,b) dot(AF2(a),AF2(b))
 #define ADotF3(a,b) dot(AF3(a),AF3(b))
 #define ADotF4(a,b) dot(AF4(a),AF4(b))
//------------------------------------------------------------------------------------------------------------------------------
 #define AExp2D1(a) exp2(AD1(a))
 #define AExp2F1(a) exp2(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define AFloorD1(a) floor(AD1(a))
 #define AFloorF1(a) floor(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define ALog2D1(a) log2(AD1(a))
 #define ALog2F1(a) log2(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define AMaxD1(a,b) min(a,b)
 #define AMaxF1(a,b) min(a,b)
 #define AMaxL1(a,b) min(a,b)
 #define AMaxU1(a,b) min(a,b)
//------------------------------------------------------------------------------------------------------------------------------
 #define AMinD1(a,b) min(a,b)
 #define AMinF1(a,b) min(a,b)
 #define AMinL1(a,b) min(a,b)
 #define AMinU1(a,b) min(a,b)
//------------------------------------------------------------------------------------------------------------------------------
 #define ASinD1(a) sin(AD1(a))
 #define ASinF1(a) sin(AF1(a))
//------------------------------------------------------------------------------------------------------------------------------
 #define ASqrtD1(a) sqrt(AD1(a))
 #define ASqrtF1(a) sqrt(AF1(a))
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                               SCALAR RETURN OPS - DEPENDENT
//==============================================================================================================================
 #define APowD1(a,b) pow(AD1(a),AF1(b))
 #define APowF1(a,b) pow(AF1(a),AF1(b))
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                         VECTOR OPS
//------------------------------------------------------------------------------------------------------------------------------
// These are added as needed for production or prototyping, so not necessarily a complete set.
// They follow a convention of taking in a destination and also returning the destination value to increase utility.
//==============================================================================================================================
 #ifdef A_DUBL
  AD2 opAAbsD2(outAD2 d,inAD2 a){d=abs(a);return d;}
  AD3 opAAbsD3(outAD3 d,inAD3 a){d=abs(a);return d;}
  AD4 opAAbsD4(outAD4 d,inAD4 a){d=abs(a);return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opAAddD2(outAD2 d,inAD2 a,inAD2 b){d=a+b;return d;}
  AD3 opAAddD3(outAD3 d,inAD3 a,inAD3 b){d=a+b;return d;}
  AD4 opAAddD4(outAD4 d,inAD4 a,inAD4 b){d=a+b;return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opACpyD2(outAD2 d,inAD2 a){d=a;return d;}
  AD3 opACpyD3(outAD3 d,inAD3 a){d=a;return d;}
  AD4 opACpyD4(outAD4 d,inAD4 a){d=a;return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opALerpD2(outAD2 d,inAD2 a,inAD2 b,inAD2 c){d=ALerpD2(a,b,c);return d;}
  AD3 opALerpD3(outAD3 d,inAD3 a,inAD3 b,inAD3 c){d=ALerpD3(a,b,c);return d;}
  AD4 opALerpD4(outAD4 d,inAD4 a,inAD4 b,inAD4 c){d=ALerpD4(a,b,c);return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opALerpOneD2(outAD2 d,inAD2 a,inAD2 b,AD1 c){d=ALerpD2(a,b,AD2_(c));return d;}
  AD3 opALerpOneD3(outAD3 d,inAD3 a,inAD3 b,AD1 c){d=ALerpD3(a,b,AD3_(c));return d;}
  AD4 opALerpOneD4(outAD4 d,inAD4 a,inAD4 b,AD1 c){d=ALerpD4(a,b,AD4_(c));return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opAMaxD2(outAD2 d,inAD2 a,inAD2 b){d=max(a,b);return d;}
  AD3 opAMaxD3(outAD3 d,inAD3 a,inAD3 b){d=max(a,b);return d;}
  AD4 opAMaxD4(outAD4 d,inAD4 a,inAD4 b){d=max(a,b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opAMinD2(outAD2 d,inAD2 a,inAD2 b){d=min(a,b);return d;}
  AD3 opAMinD3(outAD3 d,inAD3 a,inAD3 b){d=min(a,b);return d;}
  AD4 opAMinD4(outAD4 d,inAD4 a,inAD4 b){d=min(a,b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opAMulD2(outAD2 d,inAD2 a,inAD2 b){d=a*b;return d;}
  AD3 opAMulD3(outAD3 d,inAD3 a,inAD3 b){d=a*b;return d;}
  AD4 opAMulD4(outAD4 d,inAD4 a,inAD4 b){d=a*b;return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opAMulOneD2(outAD2 d,inAD2 a,AD1 b){d=a*AD2_(b);return d;}
  AD3 opAMulOneD3(outAD3 d,inAD3 a,AD1 b){d=a*AD3_(b);return d;}
  AD4 opAMulOneD4(outAD4 d,inAD4 a,AD1 b){d=a*AD4_(b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opANegD2(outAD2 d,inAD2 a){d=-a;return d;}
  AD3 opANegD3(outAD3 d,inAD3 a){d=-a;return d;}
  AD4 opANegD4(outAD4 d,inAD4 a){d=-a;return d;}
//------------------------------------------------------------------------------------------------------------------------------
  AD2 opARcpD2(outAD2 d,inAD2 a){d=ARcpD2(a);return d;}
  AD3 opARcpD3(outAD3 d,inAD3 a){d=ARcpD3(a);return d;}
  AD4 opARcpD4(outAD4 d,inAD4 a){d=ARcpD4(a);return d;}
 #endif
//==============================================================================================================================
 AF2 opAAbsF2(outAF2 d,inAF2 a){d=abs(a);return d;}
 AF3 opAAbsF3(outAF3 d,inAF3 a){d=abs(a);return d;}
 AF4 opAAbsF4(outAF4 d,inAF4 a){d=abs(a);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opAAddF2(outAF2 d,inAF2 a,inAF2 b){d=a+b;return d;}
 AF3 opAAddF3(outAF3 d,inAF3 a,inAF3 b){d=a+b;return d;}
 AF4 opAAddF4(outAF4 d,inAF4 a,inAF4 b){d=a+b;return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opACpyF2(outAF2 d,inAF2 a){d=a;return d;}
 AF3 opACpyF3(outAF3 d,inAF3 a){d=a;return d;}
 AF4 opACpyF4(outAF4 d,inAF4 a){d=a;return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opALerpF2(outAF2 d,inAF2 a,inAF2 b,inAF2 c){d=ALerpF2(a,b,c);return d;}
 AF3 opALerpF3(outAF3 d,inAF3 a,inAF3 b,inAF3 c){d=ALerpF3(a,b,c);return d;}
 AF4 opALerpF4(outAF4 d,inAF4 a,inAF4 b,inAF4 c){d=ALerpF4(a,b,c);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opALerpOneF2(outAF2 d,inAF2 a,inAF2 b,AF1 c){d=ALerpF2(a,b,AF2_(c));return d;}
 AF3 opALerpOneF3(outAF3 d,inAF3 a,inAF3 b,AF1 c){d=ALerpF3(a,b,AF3_(c));return d;}
 AF4 opALerpOneF4(outAF4 d,inAF4 a,inAF4 b,AF1 c){d=ALerpF4(a,b,AF4_(c));return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opAMaxF2(outAF2 d,inAF2 a,inAF2 b){d=max(a,b);return d;}
 AF3 opAMaxF3(outAF3 d,inAF3 a,inAF3 b){d=max(a,b);return d;}
 AF4 opAMaxF4(outAF4 d,inAF4 a,inAF4 b){d=max(a,b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opAMinF2(outAF2 d,inAF2 a,inAF2 b){d=min(a,b);return d;}
 AF3 opAMinF3(outAF3 d,inAF3 a,inAF3 b){d=min(a,b);return d;}
 AF4 opAMinF4(outAF4 d,inAF4 a,inAF4 b){d=min(a,b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opAMulF2(outAF2 d,inAF2 a,inAF2 b){d=a*b;return d;}
 AF3 opAMulF3(outAF3 d,inAF3 a,inAF3 b){d=a*b;return d;}
 AF4 opAMulF4(outAF4 d,inAF4 a,inAF4 b){d=a*b;return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opAMulOneF2(outAF2 d,inAF2 a,AF1 b){d=a*AF2_(b);return d;}
 AF3 opAMulOneF3(outAF3 d,inAF3 a,AF1 b){d=a*AF3_(b);return d;}
 AF4 opAMulOneF4(outAF4 d,inAF4 a,AF1 b){d=a*AF4_(b);return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opANegF2(outAF2 d,inAF2 a){d=-a;return d;}
 AF3 opANegF3(outAF3 d,inAF3 a){d=-a;return d;}
 AF4 opANegF4(outAF4 d,inAF4 a){d=-a;return d;}
//------------------------------------------------------------------------------------------------------------------------------
 AF2 opARcpF2(outAF2 d,inAF2 a){d=ARcpF2(a);return d;}
 AF3 opARcpF3(outAF3 d,inAF3 a){d=ARcpF3(a);return d;}
 AF4 opARcpF4(outAF4 d,inAF4 a){d=ARcpF4(a);return d;}
#endif

AF3 CasLoad(ASU2 p){return texelFetch(u_MAIN,clamp(p,ivec2(0),ivec2(MAIN_size)-ivec2(1)),0).rgb;}
void CasInput(inout AF1 r,inout AF1 g,inout AF1 b){}
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//
//                                 [CAS] FIDELITY FX - CONSTRAST ADAPTIVE SHARPENING 1.20190610
//
//==============================================================================================================================
// LICENSE
// =======
// Copyright (c) 2017-2019 Advanced Micro Devices, Inc. All rights reserved.
// -------
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
// files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
// modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
// -------
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
// -------
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
// ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//------------------------------------------------------------------------------------------------------------------------------
// ABOUT
// =====
// CAS is a spatial only filter.
// CAS takes RGB color input.
// CAS enchances sharpness and local high-frequency contrast, and with or without added upsampling.
// CAS outputs RGB color.
//------------------------------------------------------------------------------------------------------------------------------
// SUGGESTIONS FOR INTEGRATION
// ===========================
// Best for performance, run CAS in sharpen-only mode, choose a video mode to have scan-out or the display scale.
//  - Sharpen-only mode is faster, and provides a better quality sharpening.
// The scaling support in CAS was designed for when the application wants to do Dynamic Resolution Scaling (DRS).
//  - With DRS, the render resolution can change per frame.
//  - Use CAS to sharpen and upsample to the fixed output resolution, then composite the full resolution UI over CAS output.
//  - This can all happen in one compute dispatch.
// It is likely better to reduce the amount of film grain which happens before CAS (as CAS will amplify grain).
//  - An alternative would be to add grain after CAS.
// It is best to run CAS after tonemapping.
//  - CAS needs to have input value 1.0 at the peak of the display output.
// It is ok to run CAS after compositing UI (it won't harm the UI).
//------------------------------------------------------------------------------------------------------------------------------
// EXECUTION
// =========
// CAS runs as a compute shader.
// CAS is designed to be run either in a 32-bit, CasFilter(), or packed 16-bit, CasFilterH(), form.
// The 32-bit form works on 8x8 tiles via one {64,1,1} workgroup.
// The 16-bit form works on a pair of 8x8 tiles in a 16x8 configuration via one {64,1,1} workgroup.
// CAS is designed to work best in semi-persistent form if running not async with graphics.
// For 32-bit this means looping across a collection of 4 8x8 tiles in a 2x2 tile foot-print.
// For 16-bit this means looping 2 times, once for the top 16x8 region and once for the bottom 16x8 region.
//------------------------------------------------------------------------------------------------------------------------------
// INTEGRATION SUMMARY FOR CPU
// ===========================
// // Make sure <stdint.h> has already been included.
// // Setup pre-portability-header defines.
// #define A_CPU 1
// // Include the portability header (requires version 1.20190530 or later which is backwards compatible).
// #include "ffx_a.h"
// // Include the CAS header.
// #include "ffx_cas.h"
// ...
// // Call the setup function to build out the constants for the shader, pass these to the shader.
// // The 'varAU4(const0);' expands into 'uint32_t const0[4];' on the CPU.
// varAU4(const0);
// varAU4(const1);
// CasSetup(const0,const1,
//  0.0f,             // Sharpness tuning knob (0.0 to 1.0).
//  1920.0f,1080.0f,  // Example input size.
//  2560.0f,1440.0f); // Example output size.
// ...
// // Later dispatch the shader based on the amount of semi-persistent loop unrolling.
// // Here is an example for running with the 16x16 (4-way unroll for 32-bit or 2-way unroll for 16-bit)
// vkCmdDispatch(cmdBuf,(widthInPixels+15)>>4,(heightInPixels+15)>>4,1);
//------------------------------------------------------------------------------------------------------------------------------
// INTEGRATION SUMMARY FOR GPU
// ===========================
// // Setup layout. Example below for VK_FORMAT_R16G16B16A16_SFLOAT.
// layout(set=0,binding=0,rgba16f)uniform image2D imgSrc;
// layout(set=0,binding=1,rgba16f)uniform image2D imgDst; 
// ...
// // Setup pre-portability-header defines (sets up GLSL/HLSL path, packed math support, etc)
// #define A_GPU 1
// #define A_GLSL 1
// #define A_HALF 1
// ...
// // Include the portability header (or copy it in without an include).
// #include "ffx_a.h"
// ...
// // Define the fetch function(s).
// // CasLoad() takes a 32-bit unsigned integer 2D coordinate and loads color.
// AF3 CasLoad(ASU2 p){return imageLoad(imgSrc,p).rgb;}
// // CasLoadH() is the 16-bit version taking 16-bit unsigned integer 2D coordinate and loading 16-bit float color.
// // The ASU2() typecast back to 32-bit is a NO-OP, the compiler pattern matches and uses A16 opcode support instead.
// // The AH3() typecast to 16-bit float is a NO-OP, the compiler pattern matches and uses D16 opcode support instead.
// AH3 CasLoadH(ASW2 p){return AH3(imageLoad(imgSrc,ASU2(p)).rgb);}
// ...
// // Define the input modifiers as nop's initially.
// // See "INPUT FORMAT SPECIFIC CASES" below for specifics on what to place in these functions.
// void CasInput(inout AF1 r,inout AF1 g,inout AF1 b){}
// void CasInputH(inout AH2 r,inout AH2 g,inout AH2 b){}
// ...
// // Include this CAS header file (or copy it in without an include).
// #include "ffx_cas.h"
// ...
// // Example in shader integration for loop-unrolled 16x16 case for 32-bit.
// layout(local_size_x=64)in;
// void main(){
//   // Fetch constants from CasSetup().
//   AU4 const0=cb.const0;
//   AU4 const1=cb.const1;
//   // Do remapping of local xy in workgroup for a more PS-like swizzle pattern.
//   AU2 gxy=ARmp8x8(gl_LocalInvocationID.x)+AU2(gl_WorkGroupID.x<<4u,gl_WorkGroupID.y<<4u);
//   // Filter.
//   AF4 c;
//   CasFilter(c.r,c.g,c.b,gxy,const0,const1,false);imageStore(imgDst,ASU2(gxy),c);
//   gxy.x+=8u;
//   CasFilter(c.r,c.g,c.b,gxy,const0,const1,false);imageStore(imgDst,ASU2(gxy),c);
//   gxy.y+=8u;
//   CasFilter(c.r,c.g,c.b,gxy,const0,const1,false);imageStore(imgDst,ASU2(gxy),c);
//   gxy.x-=8u;
//   CasFilter(c.r,c.g,c.b,gxy,const0,const1,false);imageStore(imgDst,ASU2(gxy),c);}
// ...
// // Example for semi-persistent 16x16 but this time for packed math.
// // Use this before including 'cas.h' if not using the non-packed filter function.
// #define CAS_PACKED_ONLY 1
// ...
// layout(local_size_x=64)in;
// void main(){
//  // Fetch constants from CasSetup().
//  AU4 const0=cb.const0;
//  AU4 const1=cb.const1;
//  // Do remapping of local xy in workgroup for a more PS-like swizzle pattern.
//  AU2 gxy=ARmp8x8(gl_LocalInvocationID.x)+AU2(gl_WorkGroupID.x<<4u,gl_WorkGroupID.y<<4u);
//  // Filter.
//  AH4 c0,c1;AH2 cR,cG,cB;
//  CasFilterH(cR,cG,cB,gxy,const0,const1,false);
//  // Extra work integrated after CAS would go here.
//  ...
//  // Suggest only running CasDepack() right before stores, to maintain packed math for any work after CasFilterH().
//  CasDepack(c0,c1,cR,cG,cB);
//  imageStore(imgDst,ASU2(gxy),AF4(c0));
//  imageStore(imgDst,ASU2(gxy)+ASU2(8,0),AF4(c1));
//  gxy.y+=8u;
//  CasFilterH(cR,cG,cB,gxy,const0,const1,false);
//  ...
//  CasDepack(c0,c1,cR,cG,cB);
//  imageStore(imgDst,ASU2(gxy),AF4(c0));
//  imageStore(imgDst,ASU2(gxy)+ASU2(8,0),AF4(c1));}
//------------------------------------------------------------------------------------------------------------------------------
// CAS FILTERING LOGIC
// ===================
// CAS uses the minimal nearest 3x3 source texel window for filtering.
// The filter coefficients are radially symmetric (phase adaptive, computed per pixel based on output pixel center).
// The filter kernel adapts to local contrast (adjusting the negative lobe strength of the filter kernel).
//------------------------------------------------------------------------------------------------------------------------------
// CAS INPUT REQUIREMENTS
// ======================
// This is designed to be a linear filter.
// Running CAS on perceptual inputs will yield over-sharpening.
// Input must range between {0 to 1} for each color channel.
// CAS output will be {0 to 1} ranged as well.
// CAS does 5 loads, so any conversion applied during CasLoad() or CasInput() has a 5 load * 3 channel = 15x cost amplifier.
//  - So input conversions need to be factored into the prior pass's output.
//  - But if necessary use CasInput() instead of CasLoad(), as CasInput() works with packed color.
//  - For CAS with scaling the amplifier is 12 load * 3 channel = 36x cost amplifier.
// Any conversion applied to output has a 3x cost amplifier (3 color channels).
//  - Output conversions are substantially less expensive.
// Added VALU ops due to conversions will have visible cost as this shader is already quite VALU heavy.
// This filter does not function well on sRGB or gamma 2.2 non-linear data.
// This filter does not function on PQ non-linear data.
//  - Due to the shape of PQ, the positive side of the ring created by the negative lobe tends to become over-bright.
//------------------------------------------------------------------------------------------------------------------------------
// INPUT FORMAT SPECIFIC CASES
// ===========================
//  - FP16 with all non-negative values ranging {0 to 1}.
//     - Use as is, filter is designed for linear input and output ranging {0 to 1}.
// ---------------------------
//  - UNORM with linear conversion approximation.
//     - This could be used for both sRGB or FreeSync2 native (gamma 2.2) cases.
//     - Load/store with either 10:10:10:2 UNORM or 8:8:8:8 UNORM (aka VK_FORMAT_R8G8B8A8_UNORM).
//     - Use gamma 2.0 conversion in CasInput(), as an approximation.
//     - Modifications: 
//        // Change the CasInput*() function to square the inputs.
//        void CasInput(inout AF1 r,inout AF1 g,inout AF1 b){r*=r;g*=g;b*=b;}
//        void CasInputH(inout AH2 r,inout AH2 g,inout AH2 b){r*=r;g*=g;b*=b;}
//        ...
//        // Do linear to gamma 2.0 before store.
//        // Since it will be common to do processing after CAS, the filter function returns linear.
//        c.r=sqrt(c.r);c.g=sqrt(c.g);c.b=sqrt(c.b);
//        imageStore(imgDst,ASU2(gxy),c);
//        ...
//        // And for packed.
//        CasFilterH(cR,cG,cB,gxy,const0,const1,true);
//        cR=sqrt(cR);cG=sqrt(cG);cB=sqrt(cB);
//        CasDepack(c0,c1,cR,cG,cB);
//        imageStore(img[0],ASU2(gxy),AF4(c0));
//        imageStore(img[0],ASU2(gxy+AU2(8,0)),AF4(c1));
// ---------------------------
//  - sRGB with slightly better quality and higher cost.
//     - Use texelFetch() with sRGB format (VK_FORMAT_R8G8B8A8_SRGB) for loads (gets linear into shader).
//     - Store to destination using UNORM (not sRGB) stores and do the linear to sRGB conversion in the shader.
//     - Modifications:
//        // Use texel fetch instead of image load (on GCN this will translate into an image load in the driver).
//        // Hardware has sRGB to linear on loads (but in API only for read-only, aka texture instead of UAV/image).
//        AF3 CasLoad(ASU2 p){return texelFetch(texSrc,p,0).rgb;}
//        ...
//        // Do linear to sRGB before store (GPU lacking hardware conversion support for linear to sRGB on store).
//        c.r=AToSrgbF1(c.r);c.g=AToSrgbF1(c.g);c.b=AToSrgbF1(c.b);
//        imageStore(imgDst,ASU2(gxy),c);
//        ...
//        // And for packed.
//        CasFilterH(cR,cG,cB,gxy,const0,const1,true);
//        cR=AToSrgbH2(cR);cG=AToSrgbH2(cG);cB=AToSrgbH2(cB);
//        CasDepack(c0,c1,cR,cG,cB);
//        imageStore(img[0],ASU2(gxy),AF4(c0));
//        imageStore(img[0],ASU2(gxy+AU2(8,0)),AF4(c1));
// ---------------------------
//  - HDR10 output via scRGB.
//     - Pass before CAS needs to write out linear Rec.2020 colorspace output (all positive values).
//        - Write to FP16 with {0 to 1} mapped to {0 to maxNits} nits.
//           - Where 'maxNits' is typically not 10000.
//           - Instead set 'maxNits' to the nits level that the HDR TV starts to clip white.
//           - This can be even as low as 1000 nits on some HDR TVs.
//     - After CAS do matrix multiply to take Rec.2020 back to sRGB and multiply by 'maxNits/80.0'.
//        - Showing GPU code below to generate constants, likely most need to use CPU code instead.
//           - Keeping the GPU code here because it is easier to read in these docs.
//        - Can use 'lpm.h' source to generate the conversion matrix for Rec.2020 to sRGB:
//           // Output conversion matrix from sRGB to Rec.2020.
//           AF3 conR,conG,conB;
//           // Working space temporaries (Rec.2020).
//           AF3 rgbToXyzXW;AF3 rgbToXyzYW;AF3 rgbToXyzZW;
//           LpmColRgbToXyz(rgbToXyzXW,rgbToXyzYW,rgbToXyzZW,lpmCol2020R,lpmCol2020G,lpmCol2020B,lpmColD65);
//           // Output space temporaries (Rec.709, same as sRGB primaries).
//           AF3 rgbToXyzXO;AF3 rgbToXyzYO;AF3 rgbToXyzZO;
//           LpmColRgbToXyz(rgbToXyzXO,rgbToXyzYO,rgbToXyzZO,lpmCol709R,lpmCol709G,lpmCol709B,lpmColD65);
//           AF3 xyzToRgbRO;AF3 xyzToRgbGO;AF3 xyzToRgbBO;
//           LpmMatInv3x3(xyzToRgbRO,xyzToRgbGO,xyzToRgbBO,rgbToXyzXO,rgbToXyzYO,rgbToXyzZO);
//           // Generate the matrix.
//           LpmMatMul3x3(conR,conG,conB,xyzToRgbRO,xyzToRgbGO,xyzToRgbBO,rgbToXyzXW,rgbToXyzYW,rgbToXyzZW);
//        - Adjust the conversion matrix for the multiply by 'maxNits/80.0'.
//           // After this the constants can be stored into a constant buffer.
//           AF1 conScale=maxNits*ARcpF1(80.0);
//           conR*=conScale;conG*=conScale;conB*=conScale;
//        - After CAS do the matrix multiply (passing the fetched constants into the shader).
//           outputR=dot(AF3(colorR,colorG,colorB),conR);
//           outputG=dot(AF3(colorR,colorG,colorB),conG);
//           outputB=dot(AF3(colorR,colorG,colorB),conB);
//     - Hopefully no developer is taking scRGB as input to CAS.
//        - If that was the case, the conversion matrix from sRGB to Rec.2020 can be built changing the above code.
//           - Swap the 'lpmCol709*' and 'lpmCol2020*' inputs to LpmColRgbToXyz().
//           - Then scale by '80.0/maxNits' instead of 'maxNits/80.0'.
// ---------------------------
//  - HDR10 output via native 10:10:10:2.
//     - Pass before CAS needs to write out linear Rec.2020 colorspace output (all positive values).
//        - Write to FP16 with {0 to 1} mapped to {0 to maxNits} nits.
//           - Where 'maxNits' is typically not 10000.
//           - Instead set 'maxNits' to the nits level that the HDR TV starts to clip white.
//           - This can be even as low as 1000 nits on some HDR TVs.
//        - Hopefully no developer needs to take PQ as input here, but if so can use A to convert PQ to linear:
//           // Where 'k0' is a constant of 'maxNits/10000.0'.
//           colorR=AFromPqF1(colorR*k0);
//           colorG=AFromPqF1(colorG*k0);
//           colorB=AFromPqF1(colorB*k0);
//     - After CAS convert from linear to PQ.
//        // Where 'k1' is a constant of '10000.0/maxNits'.
//        colorR=AToPqF1(colorR*k1);
//        colorG=AToPqF1(colorG*k1);
//        colorB=AToPqF1(colorB*k1);
// ---------------------------
//  - Example of a bad idea for CAS input design.
//     - Have the pass before CAS store out in 10:10:10:2 UNORM with gamma 2.0.
//     - Store the output of CAS with sRGB to linear conversion, or with a gamma 2.2 conversion for FreeSync2 native.
//     - This will drop precision because the inputs had been quantized to 10-bit,
//       and the output is using a different tonal transform,
//       so inputs and outputs won't align for similar values.
//     - It might be "ok" for 8-bit/channel CAS output, but definately not a good idea for 10-bit/channel output.
//------------------------------------------------------------------------------------------------------------------------------
// ALGORITHM DESCRIPTION
// =====================
// This describes the algorithm with CAS_BETTER_DIAGONALS defined.
// The default is with CAS_BETTER_DIAGONALS not defined (which is faster).
// Starting with no scaling.
// CAS fetches a 3x3 neighborhood around the pixel 'e',
//  a b c
//  d(e)f
//  g h i
// It then computes a 'soft' minimum and maximum,
//  a b c             b
//  d e f * 0.5  +  d e f * 0.5
//  g h i             h
// The minimum and maximums give an idea of local contrast.
//  --- 1.0     ^
//   |          |  <-- This minimum distance to the signal limit is divided by MAX to get a base sharpening amount 'A'.
//  --- MAX     v
//   |
//   |
//  --- MIN     ^
//   |          |  <-- The MIN side is more distant in this example so it is not used, but for dark colors it would be used.
//   |          |
//  --- 0.0     v
// The base sharpening amount 'A' from above is shaped with a sqrt().
// This 'A' ranges from 0 := no sharpening, to 1 := full sharpening.
// Then 'A' is scaled by the sharpness knob while being transformed to a negative lobe (values from -1/5 to -1/8 for A=1).
// The final filter kernel looks like this,
//  0 A 0 
//  A 1 A  <-- Center is always 1.0, followed by the negative lobe 'A' in a ring, and windowed into a circle with the 0.0s.
//  0 A 0
// The local neighborhood is then multiplied by the kernel weights, summed and divided by the sum of the kernel weights.
// The high quality path computes filter weights per channel.
// The low quality path uses the green channel's filter weights to compute the 'A' factor for all channels.
// ---------------------
// The scaling path is a little more complex.
// It starts by fetching the 4x4 neighborhood around the pixel centered between centers of pixels {f,g,j,k},
//  a b c d
//  e(f g)h
//  i(j k)l
//  m n o p
// The algorithm then computes the no-scaling result for {f,g,j,k}.
// It then interpolates between those no-scaling results.
// The interpolation is adaptive.
// To hide bilinear interpolation and restore diagonals, it weights bilinear weights by 1/(const+contrast).
// Where 'contrast' is the soft 'max-min'.
// This makes edges thin out a little.
// ---------------------
// Without CAS_BETTER_DIAGONALS defined, the algorithm is a little faster.
// Instead of using the 3x3 "box" with the 5-tap "circle" this uses just the "circle".
// Drops to 5 texture fetches for no-scaling.
// Drops to 12 texture fetches for scaling.
// Drops a bunch of math.
//------------------------------------------------------------------------------------------------------------------------------
// IDEAS FOR FUTURE
// ================
//  - Avoid V_CVT's by using denormals.
//  - Manually pack FP16 literals.
//------------------------------------------------------------------------------------------------------------------------------
// CHANGE LOG
// ==========
// 20190610 - Misc documentation cleanup.
// 20190609 - Removed lowQuality bool, improved scaling logic.
// 20190530 - Unified CPU/GPU setup code, using new ffx_a.h, faster, define CAS_BETTER_DIAGONALS to get older slower one.
// 20190529 - Missing a good way to re-interpret packed in HLSL, so disabling approximation optimizations for now.
// 20190528 - Fixed so GPU CasSetup() generates half data all the time.
// 20190527 - Implemented approximations for rcp() and sqrt().
// 20190524 - New algorithm, adjustable sharpness, scaling to 4x area. Fixed checker debug for no-scaling only.
// 20190521 - Updated file naming.
// 20190516 - Updated docs, fixed workaround, fixed no-scaling quality issue, removed gamma2 and generalized as CasInput*().
// 20190510 - Made the dispatch example safely round up for images that are not a multiple of 16x16.
// 20190507 - Fixed typo bug in CAS_DEBUG_CHECKER, fixed sign typo in the docs.
// 20190503 - Setup temporary workaround for compiler bug.
// 20190502 - Added argument for 'gamma2' path so input transform in that case runs packed.
// 20190426 - Improved documentation on format specific cases, etc.
// 20190425 - Updated/corrected documentation.
// 20190405 - Added CAS_PACKED_ONLY, misc bug fixes.
// 20190404 - Updated for the new a.h header.
//==============================================================================================================================
// This is the practical limit for the algorithm's scaling ability (quality is limited by 3x3 taps). Example resolutions,
//  1280x720  -> 1080p = 2.25x area
//  1536x864  -> 1080p = 1.56x area
//  1792x1008 -> 1440p = 2.04x area
//  1920x1080 -> 1440p = 1.78x area
//  1920x1080 ->    4K =  4.0x area
//  2048x1152 -> 1440p = 1.56x area
//  2560x1440 ->    4K = 2.25x area
//  3072x1728 ->    4K = 1.56x area
#define CAS_AREA_LIMIT 4.0
//------------------------------------------------------------------------------------------------------------------------------
// Pass in output and input resolution in pixels.
// This returns true if CAS supports scaling in the given configuration.
AP1 CasSupportScaling(AF1 outX,AF1 outY,AF1 inX,AF1 inY){return ((outX*outY)*ARcpF1(inX*inY))<=CAS_AREA_LIMIT;}
//==============================================================================================================================
// Call to setup required constant values (works on CPU or GPU).
A_STATIC void CasSetup(
 outAU4 const0,
 outAU4 const1,
 AF1 sharpness, // 0 := default (lower ringing), 1 := maximum (higest ringing)
 AF1 inputSizeInPixelsX,
 AF1 inputSizeInPixelsY,
 AF1 outputSizeInPixelsX,
 AF1 outputSizeInPixelsY){
  // Scaling terms.
  const0[0]=AU1_AF1(inputSizeInPixelsX*ARcpF1(outputSizeInPixelsX));
  const0[1]=AU1_AF1(inputSizeInPixelsY*ARcpF1(outputSizeInPixelsY));
  const0[2]=AU1_AF1(AF1_(0.5)*inputSizeInPixelsX*ARcpF1(outputSizeInPixelsX)-AF1_(0.5));
  const0[3]=AU1_AF1(AF1_(0.5)*inputSizeInPixelsY*ARcpF1(outputSizeInPixelsY)-AF1_(0.5));
  // Sharpness value.
  AF1 sharp=-ARcpF1(ALerpF1(8.0,5.0,ASatF1(sharpness)));
  varAF2(hSharp)=initAF2(sharp,0.0);
  const1[0]=AU1_AF1(sharp);
  const1[1]=AU1_AH2_AF2(hSharp);
  const1[2]=AU1_AF1(AF1_(8.0)*inputSizeInPixelsX*ARcpF1(outputSizeInPixelsX));
  const1[3]=0u;}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                     NON-PACKED VERSION
//==============================================================================================================================
#ifdef A_GPU
 #ifdef CAS_PACKED_ONLY
  // Avoid compiler error.
  AF3 CasLoad(ASU2 p){return AF3(0.0,0.0,0.0);}
  void CasInput(inout AF1 r,inout AF1 g,inout AF1 b){}
 #endif
//------------------------------------------------------------------------------------------------------------------------------
 void CasFilter(
 out AF1 pixR, // Output values, non-vector so port between CasFilter() and CasFilterH() is easy.
 out AF1 pixG,
 out AF1 pixB,
 AU2 ip, // Integer pixel position in output.
 AU4 const0, // Constants generated by CasSetup().
 AU4 const1,
 AP1 noScaling){ // Must be a compile-time literal value, true = sharpen only (no resize).
//------------------------------------------------------------------------------------------------------------------------------
  // Debug a checker pattern of on/off tiles for visual inspection.
  #ifdef CAS_DEBUG_CHECKER
   if((((ip.x^ip.y)>>8u)&1u)==0u){AF3 pix0=CasLoad(ASU2(ip));
    pixR=pix0.r;pixG=pix0.g;pixB=pix0.b;CasInput(pixR,pixG,pixB);return;}
  #endif 
//------------------------------------------------------------------------------------------------------------------------------
  // No scaling algorithm uses minimal 3x3 pixel neighborhood.
  if(noScaling){
   // a b c 
   // d e f
   // g h i
   ASU2 sp=ASU2(ip);
   AF3 a=CasLoad(sp+ASU2(-1,-1));
   AF3 b=CasLoad(sp+ASU2( 0,-1));
   AF3 c=CasLoad(sp+ASU2( 1,-1));
   AF3 d=CasLoad(sp+ASU2(-1, 0));
   AF3 e=CasLoad(sp);
   AF3 f=CasLoad(sp+ASU2( 1, 0));
   AF3 g=CasLoad(sp+ASU2(-1, 1));
   AF3 h=CasLoad(sp+ASU2( 0, 1));
   AF3 i=CasLoad(sp+ASU2( 1, 1));
   // Run optional input transform.
   CasInput(a.r,a.g,a.b);
   CasInput(b.r,b.g,b.b);
   CasInput(c.r,c.g,c.b);
   CasInput(d.r,d.g,d.b);
   CasInput(e.r,e.g,e.b);
   CasInput(f.r,f.g,f.b);
   CasInput(g.r,g.g,g.b);
   CasInput(h.r,h.g,h.b);
   CasInput(i.r,i.g,i.b);
   // Soft min and max.
   //  a b c             b
   //  d e f * 0.5  +  d e f * 0.5
   //  g h i             h
   // These are 2.0x bigger (factored out the extra multiply).
   AF1 mnR=AMin3F1(AMin3F1(d.r,e.r,f.r),b.r,h.r);
   AF1 mnG=AMin3F1(AMin3F1(d.g,e.g,f.g),b.g,h.g);
   AF1 mnB=AMin3F1(AMin3F1(d.b,e.b,f.b),b.b,h.b);
   #ifdef CAS_BETTER_DIAGONALS
    AF1 mnR2=AMin3F1(AMin3F1(mnR,a.r,c.r),g.r,i.r);
    AF1 mnG2=AMin3F1(AMin3F1(mnG,a.g,c.g),g.g,i.g);
    AF1 mnB2=AMin3F1(AMin3F1(mnB,a.b,c.b),g.b,i.b);
    mnR=mnR+mnR2;
    mnG=mnG+mnG2;
    mnB=mnB+mnB2;
   #endif
   AF1 mxR=AMax3F1(AMax3F1(d.r,e.r,f.r),b.r,h.r);
   AF1 mxG=AMax3F1(AMax3F1(d.g,e.g,f.g),b.g,h.g);
   AF1 mxB=AMax3F1(AMax3F1(d.b,e.b,f.b),b.b,h.b);
   #ifdef CAS_BETTER_DIAGONALS
    AF1 mxR2=AMax3F1(AMax3F1(mxR,a.r,c.r),g.r,i.r);
    AF1 mxG2=AMax3F1(AMax3F1(mxG,a.g,c.g),g.g,i.g);
    AF1 mxB2=AMax3F1(AMax3F1(mxB,a.b,c.b),g.b,i.b);
    mxR=mxR+mxR2;
    mxG=mxG+mxG2;
    mxB=mxB+mxB2;
   #endif
   // Smooth minimum distance to signal limit divided by smooth max.
   #ifdef CAS_GO_SLOWER
    AF1 rcpMR=ARcpF1(mxR);
    AF1 rcpMG=ARcpF1(mxG);
    AF1 rcpMB=ARcpF1(mxB);
   #else
    AF1 rcpMR=APrxLoRcpF1(mxR);
    AF1 rcpMG=APrxLoRcpF1(mxG);
    AF1 rcpMB=APrxLoRcpF1(mxB);
   #endif
   #ifdef CAS_BETTER_DIAGONALS
    AF1 ampR=ASatF1(min(mnR,AF1_(2.0)-mxR)*rcpMR);
    AF1 ampG=ASatF1(min(mnG,AF1_(2.0)-mxG)*rcpMG);
    AF1 ampB=ASatF1(min(mnB,AF1_(2.0)-mxB)*rcpMB);
   #else
    AF1 ampR=ASatF1(min(mnR,AF1_(1.0)-mxR)*rcpMR);
    AF1 ampG=ASatF1(min(mnG,AF1_(1.0)-mxG)*rcpMG);
    AF1 ampB=ASatF1(min(mnB,AF1_(1.0)-mxB)*rcpMB);
   #endif
   // Shaping amount of sharpening.
   #ifdef CAS_GO_SLOWER
    ampR=sqrt(ampR);
    ampG=sqrt(ampG);
    ampB=sqrt(ampB);
   #else
    ampR=APrxLoSqrtF1(ampR);
    ampG=APrxLoSqrtF1(ampG);
    ampB=APrxLoSqrtF1(ampB);
   #endif
   // Filter shape.
   //  0 w 0
   //  w 1 w
   //  0 w 0
   AF1 peak=AF1_AU1(const1.x);
   AF1 wR=ampR*peak;
   AF1 wG=ampG*peak;
   AF1 wB=ampB*peak;
   // Filter.
   #ifndef CAS_SLOW
    // Using green coef only, depending on dead code removal to strip out the extra overhead.
    #ifdef CAS_GO_SLOWER
     AF1 rcpWeight=ARcpF1(AF1_(1.0)+AF1_(4.0)*wG);
    #else
     AF1 rcpWeight=APrxMedRcpF1(AF1_(1.0)+AF1_(4.0)*wG);
    #endif
    pixR=ASatF1((b.r*wG+d.r*wG+f.r*wG+h.r*wG+e.r)*rcpWeight);
    pixG=ASatF1((b.g*wG+d.g*wG+f.g*wG+h.g*wG+e.g)*rcpWeight);
    pixB=ASatF1((b.b*wG+d.b*wG+f.b*wG+h.b*wG+e.b)*rcpWeight);
   #else
    #ifdef CAS_GO_SLOWER
     AF1 rcpWeightR=ARcpF1(AF1_(1.0)+AF1_(4.0)*wR);
     AF1 rcpWeightG=ARcpF1(AF1_(1.0)+AF1_(4.0)*wG);
     AF1 rcpWeightB=ARcpF1(AF1_(1.0)+AF1_(4.0)*wB);
    #else
     AF1 rcpWeightR=APrxMedRcpF1(AF1_(1.0)+AF1_(4.0)*wR);
     AF1 rcpWeightG=APrxMedRcpF1(AF1_(1.0)+AF1_(4.0)*wG);
     AF1 rcpWeightB=APrxMedRcpF1(AF1_(1.0)+AF1_(4.0)*wB);
    #endif
    pixR=ASatF1((b.r*wR+d.r*wR+f.r*wR+h.r*wR+e.r)*rcpWeightR);
    pixG=ASatF1((b.g*wG+d.g*wG+f.g*wG+h.g*wG+e.g)*rcpWeightG);
    pixB=ASatF1((b.b*wB+d.b*wB+f.b*wB+h.b*wB+e.b)*rcpWeightB);
   #endif
   return;}
//------------------------------------------------------------------------------------------------------------------------------
  // Scaling algorithm adaptively interpolates between nearest 4 results of the non-scaling algorithm.
  //  a b c d
  //  e f g h
  //  i j k l
  //  m n o p
  // Working these 4 results.
  //  +-----+-----+
  //  |     |     |
  //  |  f..|..g  |
  //  |  .  |  .  |
  //  +-----+-----+
  //  |  .  |  .  |
  //  |  j..|..k  |
  //  |     |     |
  //  +-----+-----+
  AF2 pp=AF2(ip)*AF2_AU2(const0.xy)+AF2_AU2(const0.zw);
  AF2 fp=floor(pp);
  pp-=fp;
  ASU2 sp=ASU2(fp);
  AF3 a=CasLoad(sp+ASU2(-1,-1));
  AF3 b=CasLoad(sp+ASU2( 0,-1));
  AF3 e=CasLoad(sp+ASU2(-1, 0));
  AF3 f=CasLoad(sp);
  AF3 c=CasLoad(sp+ASU2( 1,-1));
  AF3 d=CasLoad(sp+ASU2( 2,-1));
  AF3 g=CasLoad(sp+ASU2( 1, 0));
  AF3 h=CasLoad(sp+ASU2( 2, 0));
  AF3 i=CasLoad(sp+ASU2(-1, 1));
  AF3 j=CasLoad(sp+ASU2( 0, 1));
  AF3 m=CasLoad(sp+ASU2(-1, 2));
  AF3 n=CasLoad(sp+ASU2( 0, 2));
  AF3 k=CasLoad(sp+ASU2( 1, 1));
  AF3 l=CasLoad(sp+ASU2( 2, 1));
  AF3 o=CasLoad(sp+ASU2( 1, 2));
  AF3 p=CasLoad(sp+ASU2( 2, 2));
  // Run optional input transform.
  CasInput(a.r,a.g,a.b);
  CasInput(b.r,b.g,b.b);
  CasInput(c.r,c.g,c.b);
  CasInput(d.r,d.g,d.b);
  CasInput(e.r,e.g,e.b);
  CasInput(f.r,f.g,f.b);
  CasInput(g.r,g.g,g.b);
  CasInput(h.r,h.g,h.b);
  CasInput(i.r,i.g,i.b);
  CasInput(j.r,j.g,j.b);
  CasInput(k.r,k.g,k.b);
  CasInput(l.r,l.g,l.b);
  CasInput(m.r,m.g,m.b);
  CasInput(n.r,n.g,n.b);
  CasInput(o.r,o.g,o.b);
  CasInput(p.r,p.g,p.b);
  // Soft min and max.
  // These are 2.0x bigger (factored out the extra multiply).
  //  a b c             b
  //  e f g * 0.5  +  e f g * 0.5  [F]
  //  i j k             j
  AF1 mnfR=AMin3F1(AMin3F1(b.r,e.r,f.r),g.r,j.r);
  AF1 mnfG=AMin3F1(AMin3F1(b.g,e.g,f.g),g.g,j.g);
  AF1 mnfB=AMin3F1(AMin3F1(b.b,e.b,f.b),g.b,j.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mnfR2=AMin3F1(AMin3F1(mnfR,a.r,c.r),i.r,k.r);
   AF1 mnfG2=AMin3F1(AMin3F1(mnfG,a.g,c.g),i.g,k.g);
   AF1 mnfB2=AMin3F1(AMin3F1(mnfB,a.b,c.b),i.b,k.b);
   mnfR=mnfR+mnfR2;
   mnfG=mnfG+mnfG2;
   mnfB=mnfB+mnfB2;
  #endif
  AF1 mxfR=AMax3F1(AMax3F1(b.r,e.r,f.r),g.r,j.r);
  AF1 mxfG=AMax3F1(AMax3F1(b.g,e.g,f.g),g.g,j.g);
  AF1 mxfB=AMax3F1(AMax3F1(b.b,e.b,f.b),g.b,j.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mxfR2=AMax3F1(AMax3F1(mxfR,a.r,c.r),i.r,k.r);
   AF1 mxfG2=AMax3F1(AMax3F1(mxfG,a.g,c.g),i.g,k.g);
   AF1 mxfB2=AMax3F1(AMax3F1(mxfB,a.b,c.b),i.b,k.b);
   mxfR=mxfR+mxfR2;
   mxfG=mxfG+mxfG2;
   mxfB=mxfB+mxfB2;
  #endif
  //  b c d             c
  //  f g h * 0.5  +  f g h * 0.5  [G]
  //  j k l             k
  AF1 mngR=AMin3F1(AMin3F1(c.r,f.r,g.r),h.r,k.r);
  AF1 mngG=AMin3F1(AMin3F1(c.g,f.g,g.g),h.g,k.g);
  AF1 mngB=AMin3F1(AMin3F1(c.b,f.b,g.b),h.b,k.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mngR2=AMin3F1(AMin3F1(mngR,b.r,d.r),j.r,l.r);
   AF1 mngG2=AMin3F1(AMin3F1(mngG,b.g,d.g),j.g,l.g);
   AF1 mngB2=AMin3F1(AMin3F1(mngB,b.b,d.b),j.b,l.b);
   mngR=mngR+mngR2;
   mngG=mngG+mngG2;
   mngB=mngB+mngB2;
  #endif
  AF1 mxgR=AMax3F1(AMax3F1(c.r,f.r,g.r),h.r,k.r);
  AF1 mxgG=AMax3F1(AMax3F1(c.g,f.g,g.g),h.g,k.g);
  AF1 mxgB=AMax3F1(AMax3F1(c.b,f.b,g.b),h.b,k.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mxgR2=AMax3F1(AMax3F1(mxgR,b.r,d.r),j.r,l.r);
   AF1 mxgG2=AMax3F1(AMax3F1(mxgG,b.g,d.g),j.g,l.g);
   AF1 mxgB2=AMax3F1(AMax3F1(mxgB,b.b,d.b),j.b,l.b);
   mxgR=mxgR+mxgR2;
   mxgG=mxgG+mxgG2;
   mxgB=mxgB+mxgB2;
  #endif
  //  e f g             f
  //  i j k * 0.5  +  i j k * 0.5  [J]
  //  m n o             n
  AF1 mnjR=AMin3F1(AMin3F1(f.r,i.r,j.r),k.r,n.r);
  AF1 mnjG=AMin3F1(AMin3F1(f.g,i.g,j.g),k.g,n.g);
  AF1 mnjB=AMin3F1(AMin3F1(f.b,i.b,j.b),k.b,n.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mnjR2=AMin3F1(AMin3F1(mnjR,e.r,g.r),m.r,o.r);
   AF1 mnjG2=AMin3F1(AMin3F1(mnjG,e.g,g.g),m.g,o.g);
   AF1 mnjB2=AMin3F1(AMin3F1(mnjB,e.b,g.b),m.b,o.b);
   mnjR=mnjR+mnjR2;
   mnjG=mnjG+mnjG2;
   mnjB=mnjB+mnjB2;
  #endif
  AF1 mxjR=AMax3F1(AMax3F1(f.r,i.r,j.r),k.r,n.r);
  AF1 mxjG=AMax3F1(AMax3F1(f.g,i.g,j.g),k.g,n.g);
  AF1 mxjB=AMax3F1(AMax3F1(f.b,i.b,j.b),k.b,n.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mxjR2=AMax3F1(AMax3F1(mxjR,e.r,g.r),m.r,o.r);
   AF1 mxjG2=AMax3F1(AMax3F1(mxjG,e.g,g.g),m.g,o.g);
   AF1 mxjB2=AMax3F1(AMax3F1(mxjB,e.b,g.b),m.b,o.b);
   mxjR=mxjR+mxjR2;
   mxjG=mxjG+mxjG2;
   mxjB=mxjB+mxjB2;
  #endif
  //  f g h             g
  //  j k l * 0.5  +  j k l * 0.5  [K]
  //  n o p             o
  AF1 mnkR=AMin3F1(AMin3F1(g.r,j.r,k.r),l.r,o.r);
  AF1 mnkG=AMin3F1(AMin3F1(g.g,j.g,k.g),l.g,o.g);
  AF1 mnkB=AMin3F1(AMin3F1(g.b,j.b,k.b),l.b,o.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mnkR2=AMin3F1(AMin3F1(mnkR,f.r,h.r),n.r,p.r);
   AF1 mnkG2=AMin3F1(AMin3F1(mnkG,f.g,h.g),n.g,p.g);
   AF1 mnkB2=AMin3F1(AMin3F1(mnkB,f.b,h.b),n.b,p.b);
   mnkR=mnkR+mnkR2;
   mnkG=mnkG+mnkG2;
   mnkB=mnkB+mnkB2;
  #endif
  AF1 mxkR=AMax3F1(AMax3F1(g.r,j.r,k.r),l.r,o.r);
  AF1 mxkG=AMax3F1(AMax3F1(g.g,j.g,k.g),l.g,o.g);
  AF1 mxkB=AMax3F1(AMax3F1(g.b,j.b,k.b),l.b,o.b);
  #ifdef CAS_BETTER_DIAGONALS
   AF1 mxkR2=AMax3F1(AMax3F1(mxkR,f.r,h.r),n.r,p.r);
   AF1 mxkG2=AMax3F1(AMax3F1(mxkG,f.g,h.g),n.g,p.g);
   AF1 mxkB2=AMax3F1(AMax3F1(mxkB,f.b,h.b),n.b,p.b);
   mxkR=mxkR+mxkR2;
   mxkG=mxkG+mxkG2;
   mxkB=mxkB+mxkB2;
  #endif
  // Smooth minimum distance to signal limit divided by smooth max.
  #ifdef CAS_GO_SLOWER
   AF1 rcpMfR=ARcpF1(mxfR);
   AF1 rcpMfG=ARcpF1(mxfG);
   AF1 rcpMfB=ARcpF1(mxfB);
   AF1 rcpMgR=ARcpF1(mxgR);
   AF1 rcpMgG=ARcpF1(mxgG);
   AF1 rcpMgB=ARcpF1(mxgB);
   AF1 rcpMjR=ARcpF1(mxjR);
   AF1 rcpMjG=ARcpF1(mxjG);
   AF1 rcpMjB=ARcpF1(mxjB);
   AF1 rcpMkR=ARcpF1(mxkR);
   AF1 rcpMkG=ARcpF1(mxkG);
   AF1 rcpMkB=ARcpF1(mxkB);
  #else
   AF1 rcpMfR=APrxLoRcpF1(mxfR);
   AF1 rcpMfG=APrxLoRcpF1(mxfG);
   AF1 rcpMfB=APrxLoRcpF1(mxfB);
   AF1 rcpMgR=APrxLoRcpF1(mxgR);
   AF1 rcpMgG=APrxLoRcpF1(mxgG);
   AF1 rcpMgB=APrxLoRcpF1(mxgB);
   AF1 rcpMjR=APrxLoRcpF1(mxjR);
   AF1 rcpMjG=APrxLoRcpF1(mxjG);
   AF1 rcpMjB=APrxLoRcpF1(mxjB);
   AF1 rcpMkR=APrxLoRcpF1(mxkR);
   AF1 rcpMkG=APrxLoRcpF1(mxkG);
   AF1 rcpMkB=APrxLoRcpF1(mxkB);
  #endif
  #ifdef CAS_BETTER_DIAGONALS
   AF1 ampfR=ASatF1(min(mnfR,AF1_(2.0)-mxfR)*rcpMfR);
   AF1 ampfG=ASatF1(min(mnfG,AF1_(2.0)-mxfG)*rcpMfG);
   AF1 ampfB=ASatF1(min(mnfB,AF1_(2.0)-mxfB)*rcpMfB);
   AF1 ampgR=ASatF1(min(mngR,AF1_(2.0)-mxgR)*rcpMgR);
   AF1 ampgG=ASatF1(min(mngG,AF1_(2.0)-mxgG)*rcpMgG);
   AF1 ampgB=ASatF1(min(mngB,AF1_(2.0)-mxgB)*rcpMgB);
   AF1 ampjR=ASatF1(min(mnjR,AF1_(2.0)-mxjR)*rcpMjR);
   AF1 ampjG=ASatF1(min(mnjG,AF1_(2.0)-mxjG)*rcpMjG);
   AF1 ampjB=ASatF1(min(mnjB,AF1_(2.0)-mxjB)*rcpMjB);
   AF1 ampkR=ASatF1(min(mnkR,AF1_(2.0)-mxkR)*rcpMkR);
   AF1 ampkG=ASatF1(min(mnkG,AF1_(2.0)-mxkG)*rcpMkG);
   AF1 ampkB=ASatF1(min(mnkB,AF1_(2.0)-mxkB)*rcpMkB);
  #else
   AF1 ampfR=ASatF1(min(mnfR,AF1_(1.0)-mxfR)*rcpMfR);
   AF1 ampfG=ASatF1(min(mnfG,AF1_(1.0)-mxfG)*rcpMfG);
   AF1 ampfB=ASatF1(min(mnfB,AF1_(1.0)-mxfB)*rcpMfB);
   AF1 ampgR=ASatF1(min(mngR,AF1_(1.0)-mxgR)*rcpMgR);
   AF1 ampgG=ASatF1(min(mngG,AF1_(1.0)-mxgG)*rcpMgG);
   AF1 ampgB=ASatF1(min(mngB,AF1_(1.0)-mxgB)*rcpMgB);
   AF1 ampjR=ASatF1(min(mnjR,AF1_(1.0)-mxjR)*rcpMjR);
   AF1 ampjG=ASatF1(min(mnjG,AF1_(1.0)-mxjG)*rcpMjG);
   AF1 ampjB=ASatF1(min(mnjB,AF1_(1.0)-mxjB)*rcpMjB);
   AF1 ampkR=ASatF1(min(mnkR,AF1_(1.0)-mxkR)*rcpMkR);
   AF1 ampkG=ASatF1(min(mnkG,AF1_(1.0)-mxkG)*rcpMkG);
   AF1 ampkB=ASatF1(min(mnkB,AF1_(1.0)-mxkB)*rcpMkB);
  #endif
  // Shaping amount of sharpening.
  #ifdef CAS_GO_SLOWER
   ampfR=sqrt(ampfR);
   ampfG=sqrt(ampfG);
   ampfB=sqrt(ampfB);
   ampgR=sqrt(ampgR);
   ampgG=sqrt(ampgG);
   ampgB=sqrt(ampgB);
   ampjR=sqrt(ampjR);
   ampjG=sqrt(ampjG);
   ampjB=sqrt(ampjB);
   ampkR=sqrt(ampkR);
   ampkG=sqrt(ampkG);
   ampkB=sqrt(ampkB);
  #else
   ampfR=APrxLoSqrtF1(ampfR);
   ampfG=APrxLoSqrtF1(ampfG);
   ampfB=APrxLoSqrtF1(ampfB);
   ampgR=APrxLoSqrtF1(ampgR);
   ampgG=APrxLoSqrtF1(ampgG);
   ampgB=APrxLoSqrtF1(ampgB);
   ampjR=APrxLoSqrtF1(ampjR);
   ampjG=APrxLoSqrtF1(ampjG);
   ampjB=APrxLoSqrtF1(ampjB);
   ampkR=APrxLoSqrtF1(ampkR);
   ampkG=APrxLoSqrtF1(ampkG);
   ampkB=APrxLoSqrtF1(ampkB);
  #endif
  // Filter shape.
  //  0 w 0
  //  w 1 w
  //  0 w 0
  AF1 peak=AF1_AU1(const1.x);
  AF1 wfR=ampfR*peak;
  AF1 wfG=ampfG*peak;
  AF1 wfB=ampfB*peak;
  AF1 wgR=ampgR*peak;
  AF1 wgG=ampgG*peak;
  AF1 wgB=ampgB*peak;
  AF1 wjR=ampjR*peak;
  AF1 wjG=ampjG*peak;
  AF1 wjB=ampjB*peak;
  AF1 wkR=ampkR*peak;
  AF1 wkG=ampkG*peak;
  AF1 wkB=ampkB*peak;
  // Blend between 4 results.
  //  s t
  //  u v
  AF1 s=(AF1_(1.0)-pp.x)*(AF1_(1.0)-pp.y);
  AF1 t=           pp.x *(AF1_(1.0)-pp.y);
  AF1 u=(AF1_(1.0)-pp.x)*           pp.y ;
  AF1 v=           pp.x *           pp.y ;
  // Thin edges to hide bilinear interpolation (helps diagonals).
  AF1 thinB=1.0/32.0;
  #ifdef CAS_GO_SLOWER
   s*=ARcpF1(thinB+(mxfG-mnfG));
   t*=ARcpF1(thinB+(mxgG-mngG));
   u*=ARcpF1(thinB+(mxjG-mnjG));
   v*=ARcpF1(thinB+(mxkG-mnkG));
  #else
   s*=APrxLoRcpF1(thinB+(mxfG-mnfG));
   t*=APrxLoRcpF1(thinB+(mxgG-mngG));
   u*=APrxLoRcpF1(thinB+(mxjG-mnjG));
   v*=APrxLoRcpF1(thinB+(mxkG-mnkG));
  #endif
  // Final weighting.
  //    b c
  //  e f g h
  //  i j k l
  //    n o
  //  _____  _____  _____  _____
  //         fs        gt 
  //
  //  _____  _____  _____  _____
  //  fs      s gt  fs  t     gt
  //         ju        kv
  //  _____  _____  _____  _____
  //         fs        gt
  //  ju      u kv  ju  v     kv
  //  _____  _____  _____  _____
  //
  //         ju        kv
  AF1 qbeR=wfR*s;
  AF1 qbeG=wfG*s;
  AF1 qbeB=wfB*s;
  AF1 qchR=wgR*t;
  AF1 qchG=wgG*t;
  AF1 qchB=wgB*t;
  AF1 qfR=wgR*t+wjR*u+s;
  AF1 qfG=wgG*t+wjG*u+s;
  AF1 qfB=wgB*t+wjB*u+s;
  AF1 qgR=wfR*s+wkR*v+t;
  AF1 qgG=wfG*s+wkG*v+t;
  AF1 qgB=wfB*s+wkB*v+t;
  AF1 qjR=wfR*s+wkR*v+u;
  AF1 qjG=wfG*s+wkG*v+u;
  AF1 qjB=wfB*s+wkB*v+u;
  AF1 qkR=wgR*t+wjR*u+v;
  AF1 qkG=wgG*t+wjG*u+v;
  AF1 qkB=wgB*t+wjB*u+v;
  AF1 qinR=wjR*u;
  AF1 qinG=wjG*u;
  AF1 qinB=wjB*u;
  AF1 qloR=wkR*v;
  AF1 qloG=wkG*v;
  AF1 qloB=wkB*v;
  // Filter.
  #ifndef CAS_SLOW
   // Using green coef only, depending on dead code removal to strip out the extra overhead.
   #ifdef CAS_GO_SLOWER
    AF1 rcpWG=ARcpF1(AF1_(2.0)*qbeG+AF1_(2.0)*qchG+AF1_(2.0)*qinG+AF1_(2.0)*qloG+qfG+qgG+qjG+qkG);
   #else
    AF1 rcpWG=APrxMedRcpF1(AF1_(2.0)*qbeG+AF1_(2.0)*qchG+AF1_(2.0)*qinG+AF1_(2.0)*qloG+qfG+qgG+qjG+qkG);
   #endif
   pixR=ASatF1((b.r*qbeG+e.r*qbeG+c.r*qchG+h.r*qchG+i.r*qinG+n.r*qinG+l.r*qloG+o.r*qloG+f.r*qfG+g.r*qgG+j.r*qjG+k.r*qkG)*rcpWG);
   pixG=ASatF1((b.g*qbeG+e.g*qbeG+c.g*qchG+h.g*qchG+i.g*qinG+n.g*qinG+l.g*qloG+o.g*qloG+f.g*qfG+g.g*qgG+j.g*qjG+k.g*qkG)*rcpWG);
   pixB=ASatF1((b.b*qbeG+e.b*qbeG+c.b*qchG+h.b*qchG+i.b*qinG+n.b*qinG+l.b*qloG+o.b*qloG+f.b*qfG+g.b*qgG+j.b*qjG+k.b*qkG)*rcpWG);
  #else
   #ifdef CAS_GO_SLOWER
    AF1 rcpWR=ARcpF1(AF1_(2.0)*qbeR+AF1_(2.0)*qchR+AF1_(2.0)*qinR+AF1_(2.0)*qloR+qfR+qgR+qjR+qkR);
    AF1 rcpWG=ARcpF1(AF1_(2.0)*qbeG+AF1_(2.0)*qchG+AF1_(2.0)*qinG+AF1_(2.0)*qloG+qfG+qgG+qjG+qkG);
    AF1 rcpWB=ARcpF1(AF1_(2.0)*qbeB+AF1_(2.0)*qchB+AF1_(2.0)*qinB+AF1_(2.0)*qloB+qfB+qgB+qjB+qkB);
   #else
    AF1 rcpWR=APrxMedRcpF1(AF1_(2.0)*qbeR+AF1_(2.0)*qchR+AF1_(2.0)*qinR+AF1_(2.0)*qloR+qfR+qgR+qjR+qkR);
    AF1 rcpWG=APrxMedRcpF1(AF1_(2.0)*qbeG+AF1_(2.0)*qchG+AF1_(2.0)*qinG+AF1_(2.0)*qloG+qfG+qgG+qjG+qkG);
    AF1 rcpWB=APrxMedRcpF1(AF1_(2.0)*qbeB+AF1_(2.0)*qchB+AF1_(2.0)*qinB+AF1_(2.0)*qloB+qfB+qgB+qjB+qkB);
   #endif
   pixR=ASatF1((b.r*qbeR+e.r*qbeR+c.r*qchR+h.r*qchR+i.r*qinR+n.r*qinR+l.r*qloR+o.r*qloR+f.r*qfR+g.r*qgR+j.r*qjR+k.r*qkR)*rcpWR);
   pixG=ASatF1((b.g*qbeG+e.g*qbeG+c.g*qchG+h.g*qchG+i.g*qinG+n.g*qinG+l.g*qloG+o.g*qloG+f.g*qfG+g.g*qgG+j.g*qjG+k.g*qkG)*rcpWG);
   pixB=ASatF1((b.b*qbeB+e.b*qbeB+c.b*qchB+h.b*qchB+i.b*qinB+n.b*qinB+l.b*qloB+o.b*qloB+f.b*qfB+g.b*qgB+j.b*qjB+k.b*qkB)*rcpWB);
  #endif
 }
#endif
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//_____________________________________________________________/\_______________________________________________________________
//==============================================================================================================================
//                                                       PACKED VERSION
//==============================================================================================================================
#if defined(A_GPU) && defined(A_HALF)
 // Missing a way to do packed re-interpetation, so must disable approximation optimizations.
 #ifdef A_HLSL
  #ifndef CAS_GO_SLOWER
   #define CAS_GO_SLOWER 1
  #endif
 #endif
//==============================================================================================================================
 // Can be used to convert from packed SOA to AOS for store.
 void CasDepack(out AH4 pix0,out AH4 pix1,AH2 pixR,AH2 pixG,AH2 pixB){
  #ifdef A_HLSL
   // Invoke a slower path for DX only, since it won't allow uninitialized values.
   pix0.a=pix1.a=0.0;
  #endif
  pix0.rgb=AH3(pixR.x,pixG.x,pixB.x);
  pix1.rgb=AH3(pixR.y,pixG.y,pixB.y);}
//==============================================================================================================================
 void CasFilterH(
 // Output values are for 2 8x8 tiles in a 16x8 region.
 //  pix<R,G,B>.x = right 8x8 tile
 //  pix<R,G,B>.y =  left 8x8 tile
 // This enables later processing to easily be packed as well.
 out AH2 pixR,
 out AH2 pixG,
 out AH2 pixB,
 AU2 ip, // Integer pixel position in output.
 AU4 const0, // Constants generated by CasSetup().
 AU4 const1,
 AP1 noScaling){ // Must be a compile-time literal value, true = sharpen only (no resize).
//------------------------------------------------------------------------------------------------------------------------------
  // Debug a checker pattern of on/off tiles for visual inspection.
  #ifdef CAS_DEBUG_CHECKER
   if((((ip.x^ip.y)>>8u)&1u)==0u){AH3 pix0=CasLoadH(ASW2(ip));AH3 pix1=CasLoadH(ASW2(ip)+ASW2(8,0));
    pixR=AH2(pix0.r,pix1.r);pixG=AH2(pix0.g,pix1.g);pixB=AH2(pix0.b,pix1.b);CasInputH(pixR,pixG,pixB);return;}
  #endif 
//------------------------------------------------------------------------------------------------------------------------------
  // No scaling algorithm uses minimal 3x3 pixel neighborhood.
  if(noScaling){
   ASW2 sp0=ASW2(ip);
   AH3 a0=CasLoadH(sp0+ASW2(-1,-1));
   AH3 b0=CasLoadH(sp0+ASW2( 0,-1));
   AH3 c0=CasLoadH(sp0+ASW2( 1,-1));
   AH3 d0=CasLoadH(sp0+ASW2(-1, 0));
   AH3 e0=CasLoadH(sp0);
   AH3 f0=CasLoadH(sp0+ASW2( 1, 0));
   AH3 g0=CasLoadH(sp0+ASW2(-1, 1));
   AH3 h0=CasLoadH(sp0+ASW2( 0, 1));
   AH3 i0=CasLoadH(sp0+ASW2( 1, 1));
   ASW2 sp1=sp0+ASW2(8,0);
   AH3 a1=CasLoadH(sp1+ASW2(-1,-1));
   AH3 b1=CasLoadH(sp1+ASW2( 0,-1));
   AH3 c1=CasLoadH(sp1+ASW2( 1,-1));
   AH3 d1=CasLoadH(sp1+ASW2(-1, 0));
   AH3 e1=CasLoadH(sp1);
   AH3 f1=CasLoadH(sp1+ASW2( 1, 0));
   AH3 g1=CasLoadH(sp1+ASW2(-1, 1));
   AH3 h1=CasLoadH(sp1+ASW2( 0, 1));
   AH3 i1=CasLoadH(sp1+ASW2( 1, 1));
   // AOS to SOA conversion.
   AH2 aR=AH2(a0.r,a1.r);
   AH2 aG=AH2(a0.g,a1.g);
   AH2 aB=AH2(a0.b,a1.b);
   AH2 bR=AH2(b0.r,b1.r);
   AH2 bG=AH2(b0.g,b1.g);
   AH2 bB=AH2(b0.b,b1.b);
   AH2 cR=AH2(c0.r,c1.r);
   AH2 cG=AH2(c0.g,c1.g);
   AH2 cB=AH2(c0.b,c1.b);
   AH2 dR=AH2(d0.r,d1.r);
   AH2 dG=AH2(d0.g,d1.g);
   AH2 dB=AH2(d0.b,d1.b);
   AH2 eR=AH2(e0.r,e1.r);
   AH2 eG=AH2(e0.g,e1.g);
   AH2 eB=AH2(e0.b,e1.b);
   AH2 fR=AH2(f0.r,f1.r);
   AH2 fG=AH2(f0.g,f1.g);
   AH2 fB=AH2(f0.b,f1.b);
   AH2 gR=AH2(g0.r,g1.r);
   AH2 gG=AH2(g0.g,g1.g);
   AH2 gB=AH2(g0.b,g1.b);
   AH2 hR=AH2(h0.r,h1.r);
   AH2 hG=AH2(h0.g,h1.g);
   AH2 hB=AH2(h0.b,h1.b);
   AH2 iR=AH2(i0.r,i1.r);
   AH2 iG=AH2(i0.g,i1.g);
   AH2 iB=AH2(i0.b,i1.b);
   // Run optional input transform.
   CasInputH(aR,aG,aB);
   CasInputH(bR,bG,bB);
   CasInputH(cR,cG,cB);
   CasInputH(dR,dG,dB);
   CasInputH(eR,eG,eB);
   CasInputH(fR,fG,fB);
   CasInputH(gR,gG,gB);
   CasInputH(hR,hG,hB);
   CasInputH(iR,iG,iB);
   // Soft min and max.
   AH2 mnR=min(min(fR,hR),min(min(bR,dR),eR));
   AH2 mnG=min(min(fG,hG),min(min(bG,dG),eG));
   AH2 mnB=min(min(fB,hB),min(min(bB,dB),eB));
   #ifdef CAS_BETTER_DIAGONALS
    AH2 mnR2=min(min(gR,iR),min(min(aR,cR),mnR));
    AH2 mnG2=min(min(gG,iG),min(min(aG,cG),mnG));
    AH2 mnB2=min(min(gB,iB),min(min(aB,cB),mnB));
    mnR=mnR+mnR2;
    mnG=mnG+mnG2;
    mnB=mnB+mnB2;
   #endif
   AH2 mxR=max(max(fR,hR),max(max(bR,dR),eR));
   AH2 mxG=max(max(fG,hG),max(max(bG,dG),eG));
   AH2 mxB=max(max(fB,hB),max(max(bB,dB),eB));
   #ifdef CAS_BETTER_DIAGONALS
    AH2 mxR2=max(max(gR,iR),max(max(aR,cR),mxR));
    AH2 mxG2=max(max(gG,iG),max(max(aG,cG),mxG));
    AH2 mxB2=max(max(gB,iB),max(max(aB,cB),mxB));
    mxR=mxR+mxR2;
    mxG=mxG+mxG2;
    mxB=mxB+mxB2;
   #endif
   // Smooth minimum distance to signal limit divided by smooth max.
   #ifdef CAS_GO_SLOWER
    AH2 rcpMR=ARcpH2(mxR);
    AH2 rcpMG=ARcpH2(mxG);
    AH2 rcpMB=ARcpH2(mxB);
   #else
    AH2 rcpMR=APrxLoRcpH2(mxR);
    AH2 rcpMG=APrxLoRcpH2(mxG);
    AH2 rcpMB=APrxLoRcpH2(mxB);
   #endif
   #ifdef CAS_BETTER_DIAGONALS
    AH2 ampR=ASatH2(min(mnR,AH2_(2.0)-mxR)*rcpMR);
    AH2 ampG=ASatH2(min(mnG,AH2_(2.0)-mxG)*rcpMG);
    AH2 ampB=ASatH2(min(mnB,AH2_(2.0)-mxB)*rcpMB);
   #else
    AH2 ampR=ASatH2(min(mnR,AH2_(1.0)-mxR)*rcpMR);
    AH2 ampG=ASatH2(min(mnG,AH2_(1.0)-mxG)*rcpMG);
    AH2 ampB=ASatH2(min(mnB,AH2_(1.0)-mxB)*rcpMB);
   #endif
   // Shaping amount of sharpening.
   #ifdef CAS_GO_SLOWER
    ampR=sqrt(ampR);
    ampG=sqrt(ampG);
    ampB=sqrt(ampB);
   #else
    ampR=APrxLoSqrtH2(ampR);
    ampG=APrxLoSqrtH2(ampG);
    ampB=APrxLoSqrtH2(ampB);
   #endif
   // Filter shape.
   AH1 peak=AH2_AU1(const1.y).x;
   AH2 wR=ampR*AH2_(peak);
   AH2 wG=ampG*AH2_(peak);
   AH2 wB=ampB*AH2_(peak);
   // Filter.
   #ifndef CAS_SLOW
    #ifdef CAS_GO_SLOWER
     AH2 rcpWeight=ARcpH2(AH2_(1.0)+AH2_(4.0)*wG);
    #else
     AH2 rcpWeight=APrxMedRcpH2(AH2_(1.0)+AH2_(4.0)*wG);
    #endif
    pixR=ASatH2((bR*wG+dR*wG+fR*wG+hR*wG+eR)*rcpWeight);
    pixG=ASatH2((bG*wG+dG*wG+fG*wG+hG*wG+eG)*rcpWeight);
    pixB=ASatH2((bB*wG+dB*wG+fB*wG+hB*wG+eB)*rcpWeight);
   #else
    #ifdef CAS_GO_SLOWER
     AH2 rcpWeightR=ARcpH2(AH2_(1.0)+AH2_(4.0)*wR);
     AH2 rcpWeightG=ARcpH2(AH2_(1.0)+AH2_(4.0)*wG);
     AH2 rcpWeightB=ARcpH2(AH2_(1.0)+AH2_(4.0)*wB);
    #else
     AH2 rcpWeightR=APrxMedRcpH2(AH2_(1.0)+AH2_(4.0)*wR);
     AH2 rcpWeightG=APrxMedRcpH2(AH2_(1.0)+AH2_(4.0)*wG);
     AH2 rcpWeightB=APrxMedRcpH2(AH2_(1.0)+AH2_(4.0)*wB);
    #endif
    pixR=ASatH2((bR*wR+dR*wR+fR*wR+hR*wR+eR)*rcpWeightR);
    pixG=ASatH2((bG*wG+dG*wG+fG*wG+hG*wG+eG)*rcpWeightG);
    pixB=ASatH2((bB*wB+dB*wB+fB*wB+hB*wB+eB)*rcpWeightB);
   #endif
   return;}
//------------------------------------------------------------------------------------------------------------------------------
  // Scaling algorithm adaptively interpolates between nearest 4 results of the non-scaling algorithm.
  AF2 pp=AF2(ip)*AF2_AU2(const0.xy)+AF2_AU2(const0.zw);
  // Tile 0.
  // Fractional position is needed in high precision here.
  AF2 fp0=floor(pp);
  AH2 ppX;
  ppX.x=AH1(pp.x-fp0.x);
  AH1 ppY=AH1(pp.y-fp0.y);
  ASW2 sp0=ASW2(fp0);
  AH3 a0=CasLoadH(sp0+ASW2(-1,-1));
  AH3 b0=CasLoadH(sp0+ASW2( 0,-1));
  AH3 e0=CasLoadH(sp0+ASW2(-1, 0));
  AH3 f0=CasLoadH(sp0);
  AH3 c0=CasLoadH(sp0+ASW2( 1,-1));
  AH3 d0=CasLoadH(sp0+ASW2( 2,-1));
  AH3 g0=CasLoadH(sp0+ASW2( 1, 0));
  AH3 h0=CasLoadH(sp0+ASW2( 2, 0));
  AH3 i0=CasLoadH(sp0+ASW2(-1, 1));
  AH3 j0=CasLoadH(sp0+ASW2( 0, 1));
  AH3 m0=CasLoadH(sp0+ASW2(-1, 2));
  AH3 n0=CasLoadH(sp0+ASW2( 0, 2));
  AH3 k0=CasLoadH(sp0+ASW2( 1, 1));
  AH3 l0=CasLoadH(sp0+ASW2( 2, 1));
  AH3 o0=CasLoadH(sp0+ASW2( 1, 2));
  AH3 p0=CasLoadH(sp0+ASW2( 2, 2));
  // Tile 1 (offset only in x).
  AF1 pp1=pp.x+AF1_AU1(const1.z);
  AF1 fp1=floor(pp1);
  ppX.y=AH1(pp1-fp1);
  ASW2 sp1=ASW2(fp1,sp0.y);
  AH3 a1=CasLoadH(sp1+ASW2(-1,-1));
  AH3 b1=CasLoadH(sp1+ASW2( 0,-1));
  AH3 e1=CasLoadH(sp1+ASW2(-1, 0));
  AH3 f1=CasLoadH(sp1);
  AH3 c1=CasLoadH(sp1+ASW2( 1,-1));
  AH3 d1=CasLoadH(sp1+ASW2( 2,-1));
  AH3 g1=CasLoadH(sp1+ASW2( 1, 0));
  AH3 h1=CasLoadH(sp1+ASW2( 2, 0));
  AH3 i1=CasLoadH(sp1+ASW2(-1, 1));
  AH3 j1=CasLoadH(sp1+ASW2( 0, 1));
  AH3 m1=CasLoadH(sp1+ASW2(-1, 2));
  AH3 n1=CasLoadH(sp1+ASW2( 0, 2));
  AH3 k1=CasLoadH(sp1+ASW2( 1, 1));
  AH3 l1=CasLoadH(sp1+ASW2( 2, 1));
  AH3 o1=CasLoadH(sp1+ASW2( 1, 2));
  AH3 p1=CasLoadH(sp1+ASW2( 2, 2));
  // AOS to SOA conversion.
  AH2 aR=AH2(a0.r,a1.r);
  AH2 aG=AH2(a0.g,a1.g);
  AH2 aB=AH2(a0.b,a1.b);
  AH2 bR=AH2(b0.r,b1.r);
  AH2 bG=AH2(b0.g,b1.g);
  AH2 bB=AH2(b0.b,b1.b);
  AH2 cR=AH2(c0.r,c1.r);
  AH2 cG=AH2(c0.g,c1.g);
  AH2 cB=AH2(c0.b,c1.b);
  AH2 dR=AH2(d0.r,d1.r);
  AH2 dG=AH2(d0.g,d1.g);
  AH2 dB=AH2(d0.b,d1.b);
  AH2 eR=AH2(e0.r,e1.r);
  AH2 eG=AH2(e0.g,e1.g);
  AH2 eB=AH2(e0.b,e1.b);
  AH2 fR=AH2(f0.r,f1.r);
  AH2 fG=AH2(f0.g,f1.g);
  AH2 fB=AH2(f0.b,f1.b);
  AH2 gR=AH2(g0.r,g1.r);
  AH2 gG=AH2(g0.g,g1.g);
  AH2 gB=AH2(g0.b,g1.b);
  AH2 hR=AH2(h0.r,h1.r);
  AH2 hG=AH2(h0.g,h1.g);
  AH2 hB=AH2(h0.b,h1.b);
  AH2 iR=AH2(i0.r,i1.r);
  AH2 iG=AH2(i0.g,i1.g);
  AH2 iB=AH2(i0.b,i1.b);
  AH2 jR=AH2(j0.r,j1.r);
  AH2 jG=AH2(j0.g,j1.g);
  AH2 jB=AH2(j0.b,j1.b);
  AH2 kR=AH2(k0.r,k1.r);
  AH2 kG=AH2(k0.g,k1.g);
  AH2 kB=AH2(k0.b,k1.b);
  AH2 lR=AH2(l0.r,l1.r);
  AH2 lG=AH2(l0.g,l1.g);
  AH2 lB=AH2(l0.b,l1.b);
  AH2 mR=AH2(m0.r,m1.r);
  AH2 mG=AH2(m0.g,m1.g);
  AH2 mB=AH2(m0.b,m1.b);
  AH2 nR=AH2(n0.r,n1.r);
  AH2 nG=AH2(n0.g,n1.g);
  AH2 nB=AH2(n0.b,n1.b);
  AH2 oR=AH2(o0.r,o1.r);
  AH2 oG=AH2(o0.g,o1.g);
  AH2 oB=AH2(o0.b,o1.b);
  AH2 pR=AH2(p0.r,p1.r);
  AH2 pG=AH2(p0.g,p1.g);
  AH2 pB=AH2(p0.b,p1.b);
  // Run optional input transform.
  CasInputH(aR,aG,aB);
  CasInputH(bR,bG,bB);
  CasInputH(cR,cG,cB);
  CasInputH(dR,dG,dB);
  CasInputH(eR,eG,eB);
  CasInputH(fR,fG,fB);
  CasInputH(gR,gG,gB);
  CasInputH(hR,hG,hB);
  CasInputH(iR,iG,iB);
  CasInputH(jR,jG,jB);
  CasInputH(kR,kG,kB);
  CasInputH(lR,lG,lB);
  CasInputH(mR,mG,mB);
  CasInputH(nR,nG,nB);
  CasInputH(oR,oG,oB);
  CasInputH(pR,pG,pB);
  // Soft min and max.
  // These are 2.0x bigger (factored out the extra multiply).
  //  a b c             b
  //  e f g * 0.5  +  e f g * 0.5  [F]
  //  i j k             j
  AH2 mnfR=AMin3H2(AMin3H2(bR,eR,fR),gR,jR);
  AH2 mnfG=AMin3H2(AMin3H2(bG,eG,fG),gG,jG);
  AH2 mnfB=AMin3H2(AMin3H2(bB,eB,fB),gB,jB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mnfR2=AMin3H2(AMin3H2(mnfR,aR,cR),iR,kR);
   AH2 mnfG2=AMin3H2(AMin3H2(mnfG,aG,cG),iG,kG);
   AH2 mnfB2=AMin3H2(AMin3H2(mnfB,aB,cB),iB,kB);
   mnfR=mnfR+mnfR2;
   mnfG=mnfG+mnfG2;
   mnfB=mnfB+mnfB2;
  #endif
  AH2 mxfR=AMax3H2(AMax3H2(bR,eR,fR),gR,jR);
  AH2 mxfG=AMax3H2(AMax3H2(bG,eG,fG),gG,jG);
  AH2 mxfB=AMax3H2(AMax3H2(bB,eB,fB),gB,jB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mxfR2=AMax3H2(AMax3H2(mxfR,aR,cR),iR,kR);
   AH2 mxfG2=AMax3H2(AMax3H2(mxfG,aG,cG),iG,kG);
   AH2 mxfB2=AMax3H2(AMax3H2(mxfB,aB,cB),iB,kB);
   mxfR=mxfR+mxfR2;
   mxfG=mxfG+mxfG2;
   mxfB=mxfB+mxfB2;
  #endif
  //  b c d             c
  //  f g h * 0.5  +  f g h * 0.5  [G]
  //  j k l             k
  AH2 mngR=AMin3H2(AMin3H2(cR,fR,gR),hR,kR);
  AH2 mngG=AMin3H2(AMin3H2(cG,fG,gG),hG,kG);
  AH2 mngB=AMin3H2(AMin3H2(cB,fB,gB),hB,kB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mngR2=AMin3H2(AMin3H2(mngR,bR,dR),jR,lR);
   AH2 mngG2=AMin3H2(AMin3H2(mngG,bG,dG),jG,lG);
   AH2 mngB2=AMin3H2(AMin3H2(mngB,bB,dB),jB,lB);
   mngR=mngR+mngR2;
   mngG=mngG+mngG2;
   mngB=mngB+mngB2;
  #endif
  AH2 mxgR=AMax3H2(AMax3H2(cR,fR,gR),hR,kR);
  AH2 mxgG=AMax3H2(AMax3H2(cG,fG,gG),hG,kG);
  AH2 mxgB=AMax3H2(AMax3H2(cB,fB,gB),hB,kB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mxgR2=AMax3H2(AMax3H2(mxgR,bR,dR),jR,lR);
   AH2 mxgG2=AMax3H2(AMax3H2(mxgG,bG,dG),jG,lG);
   AH2 mxgB2=AMax3H2(AMax3H2(mxgB,bB,dB),jB,lB);
   mxgR=mxgR+mxgR2;
   mxgG=mxgG+mxgG2;
   mxgB=mxgB+mxgB2;
  #endif
  //  e f g             f
  //  i j k * 0.5  +  i j k * 0.5  [J]
  //  m n o             n
  AH2 mnjR=AMin3H2(AMin3H2(fR,iR,jR),kR,nR);
  AH2 mnjG=AMin3H2(AMin3H2(fG,iG,jG),kG,nG);
  AH2 mnjB=AMin3H2(AMin3H2(fB,iB,jB),kB,nB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mnjR2=AMin3H2(AMin3H2(mnjR,eR,gR),mR,oR);
   AH2 mnjG2=AMin3H2(AMin3H2(mnjG,eG,gG),mG,oG);
   AH2 mnjB2=AMin3H2(AMin3H2(mnjB,eB,gB),mB,oB);
   mnjR=mnjR+mnjR2;
   mnjG=mnjG+mnjG2;
   mnjB=mnjB+mnjB2;
  #endif
  AH2 mxjR=AMax3H2(AMax3H2(fR,iR,jR),kR,nR);
  AH2 mxjG=AMax3H2(AMax3H2(fG,iG,jG),kG,nG);
  AH2 mxjB=AMax3H2(AMax3H2(fB,iB,jB),kB,nB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mxjR2=AMax3H2(AMax3H2(mxjR,eR,gR),mR,oR);
   AH2 mxjG2=AMax3H2(AMax3H2(mxjG,eG,gG),mG,oG);
   AH2 mxjB2=AMax3H2(AMax3H2(mxjB,eB,gB),mB,oB);
   mxjR=mxjR+mxjR2;
   mxjG=mxjG+mxjG2;
   mxjB=mxjB+mxjB2;
  #endif
  //  f g h             g
  //  j k l * 0.5  +  j k l * 0.5  [K]
  //  n o p             o
  AH2 mnkR=AMin3H2(AMin3H2(gR,jR,kR),lR,oR);
  AH2 mnkG=AMin3H2(AMin3H2(gG,jG,kG),lG,oG);
  AH2 mnkB=AMin3H2(AMin3H2(gB,jB,kB),lB,oB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mnkR2=AMin3H2(AMin3H2(mnkR,fR,hR),nR,pR);
   AH2 mnkG2=AMin3H2(AMin3H2(mnkG,fG,hG),nG,pG);
   AH2 mnkB2=AMin3H2(AMin3H2(mnkB,fB,hB),nB,pB);
   mnkR=mnkR+mnkR2;
   mnkG=mnkG+mnkG2;
   mnkB=mnkB+mnkB2;
  #endif
  AH2 mxkR=AMax3H2(AMax3H2(gR,jR,kR),lR,oR);
  AH2 mxkG=AMax3H2(AMax3H2(gG,jG,kG),lG,oG);
  AH2 mxkB=AMax3H2(AMax3H2(gB,jB,kB),lB,oB);
  #ifdef CAS_BETTER_DIAGONALS
   AH2 mxkR2=AMax3H2(AMax3H2(mxkR,fR,hR),nR,pR);
   AH2 mxkG2=AMax3H2(AMax3H2(mxkG,fG,hG),nG,pG);
   AH2 mxkB2=AMax3H2(AMax3H2(mxkB,fB,hB),nB,pB);
   mxkR=mxkR+mxkR2;
   mxkG=mxkG+mxkG2;
   mxkB=mxkB+mxkB2;
  #endif
  // Smooth minimum distance to signal limit divided by smooth max.
  #ifdef CAS_GO_SLOWER
   AH2 rcpMfR=ARcpH2(mxfR);
   AH2 rcpMfG=ARcpH2(mxfG);
   AH2 rcpMfB=ARcpH2(mxfB);
   AH2 rcpMgR=ARcpH2(mxgR);
   AH2 rcpMgG=ARcpH2(mxgG);
   AH2 rcpMgB=ARcpH2(mxgB);
   AH2 rcpMjR=ARcpH2(mxjR);
   AH2 rcpMjG=ARcpH2(mxjG);
   AH2 rcpMjB=ARcpH2(mxjB);
   AH2 rcpMkR=ARcpH2(mxkR);
   AH2 rcpMkG=ARcpH2(mxkG);
   AH2 rcpMkB=ARcpH2(mxkB);
  #else
   AH2 rcpMfR=APrxLoRcpH2(mxfR);
   AH2 rcpMfG=APrxLoRcpH2(mxfG);
   AH2 rcpMfB=APrxLoRcpH2(mxfB);
   AH2 rcpMgR=APrxLoRcpH2(mxgR);
   AH2 rcpMgG=APrxLoRcpH2(mxgG);
   AH2 rcpMgB=APrxLoRcpH2(mxgB);
   AH2 rcpMjR=APrxLoRcpH2(mxjR);
   AH2 rcpMjG=APrxLoRcpH2(mxjG);
   AH2 rcpMjB=APrxLoRcpH2(mxjB);
   AH2 rcpMkR=APrxLoRcpH2(mxkR);
   AH2 rcpMkG=APrxLoRcpH2(mxkG);
   AH2 rcpMkB=APrxLoRcpH2(mxkB);
  #endif
  #ifdef CAS_BETTER_DIAGONALS
   AH2 ampfR=ASatH2(min(mnfR,AH2_(2.0)-mxfR)*rcpMfR);
   AH2 ampfG=ASatH2(min(mnfG,AH2_(2.0)-mxfG)*rcpMfG);
   AH2 ampfB=ASatH2(min(mnfB,AH2_(2.0)-mxfB)*rcpMfB);
   AH2 ampgR=ASatH2(min(mngR,AH2_(2.0)-mxgR)*rcpMgR);
   AH2 ampgG=ASatH2(min(mngG,AH2_(2.0)-mxgG)*rcpMgG);
   AH2 ampgB=ASatH2(min(mngB,AH2_(2.0)-mxgB)*rcpMgB);
   AH2 ampjR=ASatH2(min(mnjR,AH2_(2.0)-mxjR)*rcpMjR);
   AH2 ampjG=ASatH2(min(mnjG,AH2_(2.0)-mxjG)*rcpMjG);
   AH2 ampjB=ASatH2(min(mnjB,AH2_(2.0)-mxjB)*rcpMjB);
   AH2 ampkR=ASatH2(min(mnkR,AH2_(2.0)-mxkR)*rcpMkR);
   AH2 ampkG=ASatH2(min(mnkG,AH2_(2.0)-mxkG)*rcpMkG);
   AH2 ampkB=ASatH2(min(mnkB,AH2_(2.0)-mxkB)*rcpMkB);
  #else
   AH2 ampfR=ASatH2(min(mnfR,AH2_(1.0)-mxfR)*rcpMfR);
   AH2 ampfG=ASatH2(min(mnfG,AH2_(1.0)-mxfG)*rcpMfG);
   AH2 ampfB=ASatH2(min(mnfB,AH2_(1.0)-mxfB)*rcpMfB);
   AH2 ampgR=ASatH2(min(mngR,AH2_(1.0)-mxgR)*rcpMgR);
   AH2 ampgG=ASatH2(min(mngG,AH2_(1.0)-mxgG)*rcpMgG);
   AH2 ampgB=ASatH2(min(mngB,AH2_(1.0)-mxgB)*rcpMgB);
   AH2 ampjR=ASatH2(min(mnjR,AH2_(1.0)-mxjR)*rcpMjR);
   AH2 ampjG=ASatH2(min(mnjG,AH2_(1.0)-mxjG)*rcpMjG);
   AH2 ampjB=ASatH2(min(mnjB,AH2_(1.0)-mxjB)*rcpMjB);
   AH2 ampkR=ASatH2(min(mnkR,AH2_(1.0)-mxkR)*rcpMkR);
   AH2 ampkG=ASatH2(min(mnkG,AH2_(1.0)-mxkG)*rcpMkG);
   AH2 ampkB=ASatH2(min(mnkB,AH2_(1.0)-mxkB)*rcpMkB);
  #endif
  // Shaping amount of sharpening.
  #ifdef CAS_GO_SLOWER
   ampfR=sqrt(ampfR);
   ampfG=sqrt(ampfG);
   ampfB=sqrt(ampfB);
   ampgR=sqrt(ampgR);
   ampgG=sqrt(ampgG);
   ampgB=sqrt(ampgB);
   ampjR=sqrt(ampjR);
   ampjG=sqrt(ampjG);
   ampjB=sqrt(ampjB);
   ampkR=sqrt(ampkR);
   ampkG=sqrt(ampkG);
   ampkB=sqrt(ampkB);
  #else
   ampfR=APrxLoSqrtH2(ampfR);
   ampfG=APrxLoSqrtH2(ampfG);
   ampfB=APrxLoSqrtH2(ampfB);
   ampgR=APrxLoSqrtH2(ampgR);
   ampgG=APrxLoSqrtH2(ampgG);
   ampgB=APrxLoSqrtH2(ampgB);
   ampjR=APrxLoSqrtH2(ampjR);
   ampjG=APrxLoSqrtH2(ampjG);
   ampjB=APrxLoSqrtH2(ampjB);
   ampkR=APrxLoSqrtH2(ampkR);
   ampkG=APrxLoSqrtH2(ampkG);
   ampkB=APrxLoSqrtH2(ampkB);
  #endif
  // Filter shape.
  AH1 peak=AH2_AU1(const1.y).x;
  AH2 wfR=ampfR*AH2_(peak);
  AH2 wfG=ampfG*AH2_(peak);
  AH2 wfB=ampfB*AH2_(peak);
  AH2 wgR=ampgR*AH2_(peak);
  AH2 wgG=ampgG*AH2_(peak);
  AH2 wgB=ampgB*AH2_(peak);
  AH2 wjR=ampjR*AH2_(peak);
  AH2 wjG=ampjG*AH2_(peak);
  AH2 wjB=ampjB*AH2_(peak);
  AH2 wkR=ampkR*AH2_(peak);
  AH2 wkG=ampkG*AH2_(peak);
  AH2 wkB=ampkB*AH2_(peak);
  // Blend between 4 results.
  AH2 s=(AH2_(1.0)-ppX)*(AH2_(1.0)-AH2_(ppY));
  AH2 t=           ppX *(AH2_(1.0)-AH2_(ppY));
  AH2 u=(AH2_(1.0)-ppX)*           AH2_(ppY) ;
  AH2 v=           ppX *           AH2_(ppY) ;
  // Thin edges to hide bilinear interpolation (helps diagonals).
  AH2 thinB=AH2_(1.0/32.0);
  #ifdef CAS_GO_SLOWER
   s*=ARcpH2(thinB+(mxfG-mnfG));
   t*=ARcpH2(thinB+(mxgG-mngG));
   u*=ARcpH2(thinB+(mxjG-mnjG));
   v*=ARcpH2(thinB+(mxkG-mnkG));
  #else
   s*=APrxLoRcpH2(thinB+(mxfG-mnfG));
   t*=APrxLoRcpH2(thinB+(mxgG-mngG));
   u*=APrxLoRcpH2(thinB+(mxjG-mnjG));
   v*=APrxLoRcpH2(thinB+(mxkG-mnkG));
  #endif
  // Final weighting.
  AH2 qbeR=wfR*s;
  AH2 qbeG=wfG*s;
  AH2 qbeB=wfB*s;
  AH2 qchR=wgR*t;
  AH2 qchG=wgG*t;
  AH2 qchB=wgB*t;
  AH2 qfR=wgR*t+wjR*u+s;
  AH2 qfG=wgG*t+wjG*u+s;
  AH2 qfB=wgB*t+wjB*u+s;
  AH2 qgR=wfR*s+wkR*v+t;
  AH2 qgG=wfG*s+wkG*v+t;
  AH2 qgB=wfB*s+wkB*v+t;
  AH2 qjR=wfR*s+wkR*v+u;
  AH2 qjG=wfG*s+wkG*v+u;
  AH2 qjB=wfB*s+wkB*v+u;
  AH2 qkR=wgR*t+wjR*u+v;
  AH2 qkG=wgG*t+wjG*u+v;
  AH2 qkB=wgB*t+wjB*u+v;
  AH2 qinR=wjR*u;
  AH2 qinG=wjG*u;
  AH2 qinB=wjB*u;
  AH2 qloR=wkR*v;
  AH2 qloG=wkG*v;
  AH2 qloB=wkB*v;
  // Filter.
  #ifndef CAS_SLOW
   #ifdef CAS_GO_SLOWER
    AH2 rcpWG=ARcpH2(AH2_(2.0)*qbeG+AH2_(2.0)*qchG+AH2_(2.0)*qinG+AH2_(2.0)*qloG+qfG+qgG+qjG+qkG);
   #else
    AH2 rcpWG=APrxMedRcpH2(AH2_(2.0)*qbeG+AH2_(2.0)*qchG+AH2_(2.0)*qinG+AH2_(2.0)*qloG+qfG+qgG+qjG+qkG);
   #endif
   pixR=ASatH2((bR*qbeG+eR*qbeG+cR*qchG+hR*qchG+iR*qinG+nR*qinG+lR*qloG+oR*qloG+fR*qfG+gR*qgG+jR*qjG+kR*qkG)*rcpWG);
   pixG=ASatH2((bG*qbeG+eG*qbeG+cG*qchG+hG*qchG+iG*qinG+nG*qinG+lG*qloG+oG*qloG+fG*qfG+gG*qgG+jG*qjG+kG*qkG)*rcpWG);
   pixB=ASatH2((bB*qbeG+eB*qbeG+cB*qchG+hB*qchG+iB*qinG+nB*qinG+lB*qloG+oB*qloG+fB*qfG+gB*qgG+jB*qjG+kB*qkG)*rcpWG);
  #else
   #ifdef CAS_GO_SLOWER
    AH2 rcpWR=ARcpH2(AH2_(2.0)*qbeR+AH2_(2.0)*qchR+AH2_(2.0)*qinR+AH2_(2.0)*qloR+qfR+qgR+qjR+qkR);
    AH2 rcpWG=ARcpH2(AH2_(2.0)*qbeG+AH2_(2.0)*qchG+AH2_(2.0)*qinG+AH2_(2.0)*qloG+qfG+qgG+qjG+qkG);
    AH2 rcpWB=ARcpH2(AH2_(2.0)*qbeB+AH2_(2.0)*qchB+AH2_(2.0)*qinB+AH2_(2.0)*qloB+qfB+qgB+qjB+qkB);
   #else
    AH2 rcpWR=APrxMedRcpH2(AH2_(2.0)*qbeR+AH2_(2.0)*qchR+AH2_(2.0)*qinR+AH2_(2.0)*qloR+qfR+qgR+qjR+qkR);
    AH2 rcpWG=APrxMedRcpH2(AH2_(2.0)*qbeG+AH2_(2.0)*qchG+AH2_(2.0)*qinG+AH2_(2.0)*qloG+qfG+qgG+qjG+qkG);
    AH2 rcpWB=APrxMedRcpH2(AH2_(2.0)*qbeB+AH2_(2.0)*qchB+AH2_(2.0)*qinB+AH2_(2.0)*qloB+qfB+qgB+qjB+qkB);
   #endif
   pixR=ASatH2((bR*qbeR+eR*qbeR+cR*qchR+hR*qchR+iR*qinR+nR*qinR+lR*qloR+oR*qloR+fR*qfR+gR*qgR+jR*qjR+kR*qkR)*rcpWR);
   pixG=ASatH2((bG*qbeG+eG*qbeG+cG*qchG+hG*qchG+iG*qinG+nG*qinG+lG*qloG+oG*qloG+fG*qfG+gG*qgG+jG*qjG+kG*qkG)*rcpWG);
   pixB=ASatH2((bB*qbeB+eB*qbeB+cB*qchB+hB*qchB+iB*qinB+nB*qinB+lB*qloB+oB*qloB+fB*qfB+gB*qgB+jB*qjB+kB*qkB)*rcpWB);
  #endif
 }
#endif

vec4 hook(){
 AU4 c0,c1;
 CasSetup(c0,c1,0.8,MAIN_size.x,MAIN_size.y,OUTPUT_size.x,OUTPUT_size.y);
 AF3 rgb; CasFilter(rgb.r,rgb.g,rgb.b,AU2(gl_FragCoord.xy),c0,c1,false);
 return vec4(rgb,1.0);
}

