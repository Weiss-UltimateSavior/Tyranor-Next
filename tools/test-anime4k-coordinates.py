#!/usr/bin/env python3
"""CPU coordinate and tiered-FP16 (2xFP16) numerical regression for the six unmodified Anime4K Restore exports."""
import hashlib
import math
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]


def fp16(x):
    """IEEE-754 binary16 round-to-nearest-even matching GLSL ES 3.0 mediump / GL_RGBA16F."""
    return struct.unpack('<e', struct.pack('<e', x))[0]


def parse(text):
    """Decode the real GLSL mat4, CReLU macros, offsets, biases and residual adds."""
    result = []
    for block in text.split('//!DESC ')[1:]:
        macros = {}
        for index, body in re.findall(r'#define go_(\d+)\(x_off, y_off\) ([^\n]+)', block):
            name = re.search(r'(\w+)_texOff', body)[1]
            mode = -1 if 'max(-' in body else 1 if 'max(' in body else 0
            macros[index] = (name, mode)
        terms = []
        for matrix, macro, dx, dy in re.findall(r'mat4\(([^)]+)\)\s*\*\s*go_(\d+)\(([-.\d]+),\s*([-.\d]+)\)', block):
            weights = list(map(float,matrix.split(',')))
            assert len(weights) == 16
            terms.append((weights,macros[macro],int(float(dx)),int(float(dy))))
        for index, body in re.findall(r'#define g_(\d+) ([^\n]+)', block):
            name = re.search(r'(\w+)_tex\(',body)[1]
            mode = -1 if 'max(-' in body else 1 if 'max(' in body else 0
            macros[index] = (name,mode)
        for matrix, macro in re.findall(r'mat4\(([^)]+)\)\s*\*\s*g_(\d+)\b',block):
            weights=list(map(float,matrix.split(',')))
            assert len(weights)==16
            terms.append((weights,macros[macro],0,0))
        bias = re.search(r'result \+= vec4\(([^)]+)\);', block)
        assert terms and bias
        bias = list(map(float,bias[1].split(',')))
        save = re.search(r'//!SAVE (\w+)',block)[1]
        residual = 'return result + MAIN_tex(MAIN_pos);' in block
        assert len(re.findall(r'\bmat4\(',block)) == len(terms)
        assert 'return result;' in block or residual
        result.append((save,terms,bias,residual))
    assert result[-1][0] == 'MAIN'
    return result


def run(passes,image,dy_sign=1):
    """Evaluate all layers at texel centers with linear sampler's clamp-to-edge."""
    h,w=len(image),len(image[0]); textures={'MAIN':image}
    for save,terms,bias,residual in passes:
        output=[]
        for y in range(h):
            row=[]
            for x in range(w):
                value=bias.copy()
                for matrix,(name,mode),dx,dy in terms:
                    pixel=textures[name][min(h-1,max(0,y+dy*dy_sign))][min(w-1,max(0,x+dx))]
                    if mode: pixel=[max(mode*v,0) for v in pixel]
                    for out in range(4):
                        value[out]+=sum(matrix[ch*4+out]*pixel[ch] for ch in range(4))
                if residual: value=[a+b for a,b in zip(value,textures['MAIN'][y][x])]
                row.append(value)
            output.append(row)
        textures[save]=output
    return textures['MAIN']


def run_2xfp16(passes, image):
    """Evaluate a CPU IEEE binary16 simulation model (intermediate storage + conv ALU).

    Models runtime precision boundaries on CPU rather than guaranteeing GPU driver
    per-op mediump rounding: raw MAIN remains full precision (`highp` sampler),
    non-final GL_RGBA16F intermediates and convolution ALU use binary16, and final
    residual addition stays high precision before final 8-bit quantization.
    """
    h, w = len(image), len(image[0])
    textures = {'MAIN': [[px[:] for px in row] for row in image]}
    for save, terms, bias, residual in passes:
        output = []
        bias16 = [fp16(v) for v in bias]
        terms16 = [([fp16(m) for m in matrix], info, dx, dy) for matrix, info, dx, dy in terms]
        for y in range(h):
            row = []
            for x in range(w):
                value = [fp16(0.0)] * 4
                for matrix, (name, mode), dx, dy in terms16:
                    src_px = textures[name][min(h - 1, max(0, y + dy))][min(w - 1, max(0, x + dx))]
                    pixel = [fp16(v) for v in src_px]
                    if mode:
                        pixel = [max(fp16(mode * v), fp16(0.0)) for v in pixel]
                    for out in range(4):
                        term = fp16(0.0)
                        for ch in range(4):
                            term = fp16(term + fp16(matrix[ch * 4 + out] * pixel[ch]))
                        value[out] = fp16(value[out] + term)
                value = [fp16(value[out] + bias16[out]) for out in range(4)]
                if residual:
                    main_px = image[y][x]
                    value = [value[out] + main_px[out] for out in range(4)]
                row.append(value)
            output.append(row)
        textures[save] = output
    return textures['MAIN']


def metrics(reference,actual):
    errors=[abs(a-b) for ra,rb in zip(reference,actual) for pa,pb in zip(ra,rb) for a,b in zip(pa[:3],pb[:3])]
    return {'max_abs_rgb':max(errors),'mean_abs_rgb':sum(errors)/len(errors),
            'mean_abs_rgb_8bit_equivalent':255*sum(errors)/len(errors)}


def quantize_8bit(value: float) -> int:
    """Rounds clamped [0, 1] radiance matching JVM Math.round(clamped * 255.0)."""
    clamped = min(1.0, max(0.0, value))
    return math.floor(clamped * 255.0 + 0.5)


def fp16_chain_metrics(reference, actual):
    mse_sum = 0.0
    count = 0
    max_abs = 0.0
    max_lsb = 0
    for ra, rb in zip(reference, actual):
        for pa, pb in zip(ra, rb):
            for c in range(3):
                r = min(1.0, max(0.0, pa[c]))
                a = min(1.0, max(0.0, pb[c]))
                diff = abs(r - a)
                max_abs = max(max_abs, diff)
                mse_sum += diff * diff
                count += 1
                lsb = abs(quantize_8bit(r) - quantize_8bit(a))
                max_lsb = max(max_lsb, lsb)
    psnr = float('inf') if mse_sum == 0.0 else 10.0 * math.log10(1.0 / (mse_sum / count))
    return {'psnr_db': psnr, 'max_abs_clamped': max_abs, 'max_8bit_lsb': max_lsb}


def main():
    """Check both possible input orientations and verify 2xFP16 numerical bounds across all six Anime4K models."""
    # Verify half-integer boundary matches JVM Math.round (76.5 -> 77, 75.48 -> 75, delta = 2 LSB).
    assert abs(quantize_8bit(76.5 / 255.0) - quantize_8bit(75.48 / 255.0)) == 2
    patterns={
        'asymmetric_gradient':[[[((x*7+y*11+c*3)%41)/40 for c in range(3)]+[1.] for x in range(12)] for y in range(12)],
        'horizontal_edge':[[[.05 if y<4 else .85 if y<8 else .3, x/11, .1 if y<7 else .7,1.] for x in range(12)] for y in range(12)],
        'constant_control':[[[.2,.4,.6,1.] for x in range(12)] for y in range(12)]}
    for name in ('S','M','L','Soft_S','Soft_M','Soft_L'):
        path=ROOT/'engine/src/main/assets/anime4k'/('Anime4K_Restore_CNN_'+name+'.glsl')
        passes=parse(path.read_text(encoding='utf-8'))
        record={'shader':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'passes':len(passes),'patterns':{}}
        fp16_refs = []
        fp16_outs = []
        for pattern,image in patterns.items():
            reference=run(passes,image) # Un-adapted export on top-left image rows: the coordinate oracle.
            unchanged=run(passes,image[::-1])[::-1] # Current shader on bottom-left texture.
            adapted=run(passes,image[::-1],-1)[::-1] # Declared top-left shader via the host row-order adapter.
            tiered_fp16=run_2xfp16(passes,image)
            fp16_refs.extend(reference)
            fp16_outs.extend(tiered_fp16)
            result={'bottom_left_current':metrics(reference,unchanged),
                    'bottom_left_y_corrected':metrics(reference,adapted)}
            assert result['bottom_left_y_corrected']['max_abs_rgb'] < 1e-12
            if pattern=='constant_control':assert result['bottom_left_current']['max_abs_rgb'] < 1e-12
            else:assert result['bottom_left_current']['max_abs_rgb'] > 1e-6
            record['patterns'][pattern]=result
        fp16_stat = fp16_chain_metrics(fp16_refs, fp16_outs)
        assert fp16_stat['max_8bit_lsb'] <= 1, f"{name} 2xFP16 exceeded 1 LSB: {fp16_stat}"
        assert fp16_stat['psnr_db'] >= 60.0, f"{name} 2xFP16 PSNR below 60 dB: {fp16_stat}"
        p=record['patterns']['horizontal_edge']
        print(name, 'passes='+str(len(passes)),
              'edge current MAE(8bit)='+str(round(p['bottom_left_current']['mean_abs_rgb_8bit_equivalent'],6)),
              'corrected max='+str(p['bottom_left_y_corrected']['max_abs_rgb']),
              '2xFP16 PSNR='+str(round(fp16_stat['psnr_db'],2))+'dB',
              'max8bitLSB='+str(fp16_stat['max_8bit_lsb']))


if __name__=='__main__':main()
