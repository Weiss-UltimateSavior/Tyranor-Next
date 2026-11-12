#!/usr/bin/env python3
"""Compare pinned top-left GLSL math with adapted bottom-left math on asymmetric RGB.

This FP64 CPU evaluator checks exported coefficients, taps and PixelShuffle, not
device GLSL compilation, FP16 precision or agreement with training checkpoints.
"""
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def sample_bilinear(image, u, v):
    """Standard GLES bilinear texture sampling with clamp-to-edge."""
    height, width = len(image), len(image[0])
    u_texel = u * width - 0.5
    v_texel = v * height - 0.5
    x0 = int(math.floor(u_texel))
    y0 = int(math.floor(v_texel))
    x1 = x0 + 1
    y1 = y0 + 1
    fx = u_texel - x0
    fy = v_texel - y0
    cx0 = max(0, min(width - 1, x0))
    cx1 = max(0, min(width - 1, x1))
    cy0 = max(0, min(height - 1, y0))
    cy1 = max(0, min(height - 1, y1))
    c00 = image[cy0][cx0]
    c10 = image[cy0][cx1]
    c01 = image[cy1][cx0]
    c11 = image[cy1][cx1]
    return [
        (1.0 - fx) * (1.0 - fy) * c00[c] + fx * (1.0 - fy) * c10[c]
        + (1.0 - fx) * fy * c01[c] + fx * fy * c11[c]
        for c in range(len(c00))
    ]


def evaluate(text, image, flip_coordinates=False):
    """Evaluate exported convolution passes, residual shuffle and bilinear base combine."""
    height, width = len(image), len(image[0])
    textures = {"MAIN": image}
    passes = text.split("//!DESC ")[1:]
    for shader in passes[:-1]:
        save = re.search(r"//!SAVE (\w+)", shader)[1]
        inputs = dict(re.findall(r"vec4 sample_(\d+)\(vec2 p\) \{ return (\w+)_tex", shader))
        flip = ("vec2(p.x,-p.y)" in shader) != flip_coordinates
        bias = [float(x) for x in re.search(r"vec4 value=vec4\(([^)]+)\)", shader)[1].split(",")]
        terms = re.findall(r"mat4\(([^)]+)\)\*sample_(\d+)\(vec2\(([-.\d]+),([-.\d]+)\)\)", shader)
        assert terms and len(bias) == 4
        terms = [([float(v) for v in matrix.split(",")], inputs[index], int(float(dx)), int(float(dy)) * (-1 if flip else 1)) for matrix,index,dx,dy in terms]
        output = []
        for y in range(height):
            row = []
            for x in range(width):
                value = bias.copy()
                for matrix, name, dx, dy in terms:
                    sample = textures[name][min(height-1,max(0,y+dy))][min(width-1,max(0,x+dx))]
                    for out in range(4):
                        value[out] += sum(matrix[ch*4+out]*sample[ch] for ch in range(4))
                if "value=max(value,0.0)" in shader:
                    value = [max(v,0.0) for v in value]
                row.append(value)
            output.append(row)
        textures[save] = output
    final = passes[-1]
    residuals = re.findall(r"(vnsr_output_\d+)_tex\(center\)\[index\]", final)
    assert len(residuals) == 3 and "//!SAVE MAIN" in final
    assert re.search(r"return\s+vec4\(\s*MAIN_tex\(\s*MAIN_pos\s*\)\.rgb\s*\+\s*residual\s*,\s*1\.0\s*\);", final)
    flip_phase = ("int index=(1-phase.y)*2+phase.x;" in final) != flip_coordinates

    out_residuals = []
    out_final = []
    out_h, out_w = height * 2, width * 2
    for y in range(out_h):
        res_row = []
        final_row = []
        for x in range(out_w):
            idx = ((1 - y % 2) if flip_phase else y % 2) * 2 + x % 2
            res = [textures[name][y // 2][x // 2][idx] for name in residuals]
            res_row.append(res)

            # Fragment center in physical coordinate of output pass:
            # vPos = (x + 0.5) / out_w, (y + 0.5) / out_h
            # When flip_coordinates is True (bottom-left physical input, declared top-left shader):
            # u_FLIP_Y is 1; MAIN_pos.y = 1.0 - vPos.y;
            # MAIN_tex(MAIN_pos) invokes texture(u_MAIN, hookCoord(MAIN_pos)) = texture(u_MAIN, vPos).
            # So physical sampling of textures["MAIN"] occurs at vPos!
            u = (x + 0.5) / out_w
            v = (y + 0.5) / out_h
            base_sample = sample_bilinear(textures["MAIN"], u, v)
            combined_rgb = [base_sample[c] + res[c] for c in range(3)]
            final_row.append(combined_rgb + [1.0])
        out_residuals.append(res_row)
        out_final.append(final_row)

    return out_residuals, out_final


def main():
    """Check catalog invariants, residual outputs and full bilinear reconstructions."""
    filters = json.loads((ROOT/"engine/src/main/graphicsfilters/vnsr.json").read_text())["filters"]
    assert [f["id"] for f in filters] == ["vnsr_c08_x2", "vnsr_c12_x2", "vnsr_c16_x2"]
    image = [[[((x*3+y*7+c*11)%31)/31 for c in range(3)]+[1.0] for x in range(8)] for y in range(8)]
    for definition, count in zip(filters, (10,15,20)):
        name = Path(definition["asset"]).name
        original = (ROOT/"engine/patches/vnsr/sources"/name).read_text()
        adapted = (ROOT/"engine/src/main/assets"/definition["asset"]).read_text()
        # The generator retains upstream bytes, so the packaged asset must be byte-identical;
        # the flip equivalence below is therefore evaluated against the pinned source itself.
        assert adapted == original
        assert definition["nativeScale"] == definition["maxOutputScale"] == 2
        assert definition["haloPerPass"] == 1
        assert adapted.count("//!DESC ") == count
        offsets = re.findall(r"sample_\d+\(vec2\(([-.\d]+),([-.\d]+)\)\)", adapted)
        assert offsets and max(abs(float(v)) for pair in offsets for v in pair) == 1
        expected_residuals, expected_final = evaluate(original, image)
        assert definition["topLeftCoordinates"]
        actual_residuals, actual_final = evaluate(adapted, image[::-1], flip_coordinates=True)
        actual_residuals = actual_residuals[::-1]
        actual_final = actual_final[::-1]

        res_error = max(abs(a-b) for ra,rb in zip(expected_residuals,actual_residuals) for pa,pb in zip(ra,rb) for a,b in zip(pa,pb))
        assert res_error < 1e-10, (name, "residual", res_error)

        final_error = max(abs(a-b) for ra,rb in zip(expected_final,actual_final) for pa,pb in zip(ra,rb) for a,b in zip(pa,pb))
        assert final_error < 1e-10, (name, "final float", final_error)

        # 8-bit quantized [0, 255] check across all 4 channels (RGBA)
        quantized_expected = [[[round(max(0.0, min(1.0, c)) * 255) for c in pixel] for pixel in row] for row in expected_final]
        quantized_actual = [[[round(max(0.0, min(1.0, c)) * 255) for c in pixel] for pixel in row] for row in actual_final]
        assert quantized_expected == quantized_actual, (name, "8-bit quantization mismatch")

        # Without the coordinate adaptation this asymmetric input must distinguish the output.
        _, wrong_final = evaluate(original, image[::-1])
        wrong_final = wrong_final[::-1]
        wrong_error = max(abs(a-b) for ra,rb in zip(expected_final,wrong_final) for pa,pb in zip(ra,rb) for a,b in zip(pa,pb))
        assert wrong_error > 1e-5, (name, "negative control failed to distinguish", wrong_error)
        print(name+": residual & final combine equivalence passed; residual error="+str(res_error)
              +", final max error="+str(final_error))


if __name__ == "__main__":
    main()
