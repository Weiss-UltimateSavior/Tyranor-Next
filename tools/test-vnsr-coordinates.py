#!/usr/bin/env python3
"""Compare pinned top-left GLSL math with adapted bottom-left math on asymmetric RGB.

This FP64 CPU evaluator checks exported coefficients, taps, MRT pass fusion and
PixelShuffle, verifying bit-exact mathematical parity between original and adapted shaders.
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


def strip_glsl_comments(source: str) -> str:
    """Splice line continuations then strip GLSL // and /* */ comments."""
    spliced = re.sub(r"\\\r?\n", "", source)
    out = []
    i = 0
    n = len(spliced)
    while i < n:
        if spliced[i:i + 2] == "//":
            nl = spliced.find("\n", i + 2)
            i = n if nl == -1 else nl
        elif spliced[i:i + 2] == "/*":
            end = spliced.find("*/", i + 2)
            if end == -1:
                raise ValueError("Unterminated block comment in shader")
            newlines = spliced[i:end + 2].count("\n")
            out.append("\n" * newlines if newlines else " ")
            i = end + 2
        else:
            out.append(spliced[i])
            i += 1
    return "".join(out)


def parse_tap_offset(raw: str) -> int:
    """Validates and converts a 3x3 integer grid tap offset; rejects fractional offsets like 0.5."""
    val = float(raw)
    if not math.isfinite(val) or not val.is_integer():
        raise ValueError(f"Unsupported fractional tap offset in CPU grid evaluator: {raw!r}")
    iv = int(val)
    if iv not in (-1, 0, 1):
        raise ValueError(f"Tap offset outside supported 3x3 grid: {iv}")
    return iv


def evaluate(text, image, flip_coordinates=False):
    """Evaluate exported convolution passes, residual shuffle and bilinear base combine."""
    height, width = len(image), len(image[0])
    textures = {"MAIN": image}
    passes = text.split("//!DESC ")[1:]
    for pass_text in passes[:-1]:
        saves = []
        for line in pass_text.splitlines():
            trimmed = line.strip()
            if trimmed.startswith("//!SAVE"):
                targets = trimmed[len("//!SAVE"):].split()
                saves.extend(targets)
        shader = strip_glsl_comments(pass_text)
        sample_decls = re.findall(
            r"vec4\s+sample_(\d+)\(\s*vec2\s+p\s*\)\s*\{([^}]*)\}",
            shader,
        )
        inputs = {}
        for idx_str, expr_body in sample_decls:
            if idx_str in inputs:
                raise ValueError(f"Duplicate sample_{idx_str} declaration in shader")
            match_body = re.fullmatch(
                r"\s*return\s+(\w+)_tex\(\s*\1_pos\s*\+\s*p\s*\*\s*\1_pt\s*\)\s*;\s*",
                expr_body,
            )
            if not match_body:
                raise ValueError(
                    f"Unsupported sample_{idx_str} coordinate expression: {expr_body.strip()!r}"
                )
            inputs[idx_str] = match_body.group(1)
        if not inputs:
            raise ValueError(f"Missing sample_k declarations in pass: {shader[:60]}")
        flip = re.search(r"vec2\s*\(\s*p\.x\s*,\s*-p\.y\s*\)", shader) is not None
        flip = flip != flip_coordinates
        flip_factor = -1 if flip else 1

        hook_match = re.search(r"\bvoid\s+hook\s*\(\s*(?:void)?\s*\)\s*\{([^}]*)\}", shader)
        if hook_match is not None:
            hook_body = hook_match.group(1)
            segments = re.split(r"\bs\s*=\s*sample_", hook_body)
            bias_stmt_pat = re.compile(r"\s*vec4\s+v(\d+)\s*=\s*vec4\(([^)]+)\)\s*;")
            scratch_s_pat = re.compile(r"\s*vec4\s+s\s*;")
            bias_map = {}
            pre_pos = 0
            while pre_pos < len(segments[0]):
                mb = bias_stmt_pat.match(segments[0], pre_pos)
                if mb:
                    b_idx = int(mb.group(1))
                    if b_idx in bias_map:
                        raise ValueError(f"Duplicate vec4 v{b_idx} bias declaration")
                    bias_map[b_idx] = [float(x) for x in mb.group(2).split(",")]
                    pre_pos = mb.end()
                    continue
                ms = scratch_s_pat.match(segments[0], pre_pos)
                if ms:
                    pre_pos = ms.end()
                    continue
                leftover = segments[0][pre_pos:].strip()
                if not leftover:
                    break
                raise ValueError(f"Unsupported statement before sample_ in void hook(): {leftover[:80]!r}")
            expected_indices = set(range(len(saves)))
            if set(bias_map.keys()) != expected_indices:
                raise ValueError(
                    f"Mismatched bias accumulator indices: expected {sorted(expected_indices)}, got {sorted(bias_map.keys())}"
                )
            biases = [bias_map[i] for i in range(len(saves))]
            if len(segments) < 2:
                raise ValueError(f"Missing sample_ statements inside void hook(): {hook_body[:60]!r}")
            mat_stmt_pat = re.compile(r"\s*v(\d+)\s*\+=\s*mat4\(([^)]+)\)\s*\*\s*s\s*;")
            route_stmt_pat = re.compile(r"\s*fragColor(\d+)\s*=\s*(max\(\s*)?v(\d+)(?:\s*,\s*0\.0\s*\))?\s*;")
            ops = []
            routes = {}
            for seg_idx, seg in enumerate(segments[1:]):
                sample_info, mat_part = seg.split(";", 1)
                m = re.fullmatch(r"\s*(\d+)\s*\(\s*vec2\s*\(\s*([^,]+)\s*,\s*([^)]+)\s*\)\s*\)\s*", sample_info)
                if not m:
                    raise ValueError(f"Unsupported sample_k call syntax: {sample_info!r}")
                pack = int(m.group(1))
                dx = parse_tap_offset(m.group(2))
                dy = parse_tap_offset(m.group(3)) * flip_factor
                mats = []
                pos = 0
                while pos < len(mat_part):
                    mm = mat_stmt_pat.match(mat_part, pos)
                    if mm:
                        mats.append((int(mm.group(1)), [float(v) for v in mm.group(2).split(",")]))
                        pos = mm.end()
                        continue
                    if seg_idx == len(segments) - 2:
                        rm = route_stmt_pat.match(mat_part, pos)
                        if rm:
                            fc_idx, has_max, v_idx = int(rm.group(1)), rm.group(2) is not None, int(rm.group(3))
                            assert fc_idx not in routes, f"duplicate fragColor{fc_idx} in {hook_body[:60]}"
                            routes[fc_idx] = (v_idx, has_max)
                            pos = rm.end()
                            continue
                    leftover = mat_part[pos:].strip()
                    if not leftover:
                        break
                    raise ValueError(f"Unparsed statement in void hook() body: {leftover[:80]!r}")
                ops.append((pack, dx, dy, mats))

            assert len(routes) == len(saves), f"expected {len(saves)} fragColor assignments, got {routes}"
            for g in range(len(saves)):
                assert g in routes and routes[g][0] == g, f"invalid MRT target routing: expected fragColor{g}=v{g}, got {routes}"

            fused_outs = [[] for _ in saves]
            for y in range(height):
                rows = [[] for _ in saves]
                for x in range(width):
                    vals = [b.copy() for b in biases]
                    for pack, dx, dy, mats in ops:
                        in_name = inputs[str(pack)]
                        sy = min(height - 1, max(0, y + dy))
                        sx = min(width - 1, max(0, x + dx))
                        sample = textures[in_name][sy][sx]
                        for g, mat in mats:
                            for out in range(4):
                                vals[g][out] += sum(mat[ch * 4 + out] * sample[ch] for ch in range(4))
                    for g in range(len(saves)):
                        v = vals[routes[g][0]]
                        if routes[g][1]:
                            v = [max(val, 0.0) for val in v]
                        rows[g].append(v)
                for g in range(len(saves)):
                    fused_outs[g].append(rows[g])
            for g, s in enumerate(saves):
                textures[s] = fused_outs[g]
        else:
            save = saves[0]
            bias = [float(x) for x in re.search(r"vec4 value=vec4\(([^)]+)\)", shader)[1].split(",")]
            terms = re.findall(r"mat4\(([^)]+)\)\*sample_(\d+)\(vec2\(([-.\d]+),([-.\d]+)\)\)", shader)
            assert terms and len(bias) == 4
            terms = [
                ([float(v) for v in matrix.split(",")], inputs[index], parse_tap_offset(dx), parse_tap_offset(dy) * flip_factor)
                for matrix, index, dx, dy in terms
            ]
            output = []
            for y in range(height):
                row = []
                for x in range(width):
                    value = bias.copy()
                    for matrix, name, dx, dy in terms:
                        sample = textures[name][min(height - 1, max(0, y + dy))][min(width - 1, max(0, x + dx))]
                        for out in range(4):
                            value[out] += sum(matrix[ch * 4 + out] * sample[ch] for ch in range(4))
                    if "value=max(value,0.0)" in shader:
                        value = [max(v, 0.0) for v in value]
                    row.append(value)
                output.append(row)
            textures[save] = output

    final_pass = passes[-1]
    final_stripped = strip_glsl_comments(final_pass)
    final_binds = re.findall(r"^//!BIND\s+(\S+)", final_pass, re.MULTILINE)
    final_width = re.search(r"^//!WIDTH\s+(\S+)", final_pass, re.MULTILINE)
    final_height = re.search(r"^//!HEIGHT\s+(\S+)", final_pass, re.MULTILINE)
    if not final_width or final_width.group(1) != "MAIN.w":
        raise ValueError(f"Unexpected final pass //!WIDTH: {final_width.group(1) if final_width else None!r}")
    if not final_height or final_height.group(1) != "MAIN.h":
        raise ValueError(f"Unexpected final pass //!HEIGHT: {final_height.group(1) if final_height else None!r}")
    residuals = re.findall(r"(vnsr_output_\d+)_tex\(center\)\[index\]", final_stripped)
    assert len(residuals) == 3 and "//!SAVE MAIN" in final_pass
    for req_bind in ["MAIN"] + residuals:
        if req_bind not in final_binds:
            raise ValueError(f"Missing required //!BIND {req_bind} in final pass")
    assert re.search(r"return\s+vec4\(\s*MAIN_tex\(\s*MAIN_pos\s*\)\.rgb\s*\+\s*residual\s*,\s*1\.0\s*\);", final_stripped)
    if not re.search(r"\bvec2\s+cell\s*=\s*floor\(\s*MAIN_pos\s*\*\s*MAIN_size\s*\)\s*;", final_stripped):
        raise ValueError("Missing or unexpected PixelShuffle cell formula in final pass")
    m_phase_scale = re.search(
        r"\bivec2\s+phase\s*=\s*ivec2\(\s*floor\(\s*fract\(\s*MAIN_pos\s*\*\s*MAIN_size\s*\)\s*\*\s*([-+]?\d+(?:\.\d+)?)\s*\)\s*\)\s*;",
        final_stripped,
    )
    if not m_phase_scale:
        raise ValueError("Missing or unexpected PixelShuffle phase formula in final pass")
    phase_scale = float(m_phase_scale.group(1))
    m_center_off = re.search(
        r"\bvec2\s+center\s*=\s*\(\s*cell\s*\+\s*vec2\(\s*([-+]?\d+(?:\.\d+)?)\s*\)\s*\)\s*\*\s*MAIN_pt\s*;",
        final_stripped,
    )
    if not m_center_off:
        raise ValueError("Missing or unexpected PixelShuffle center formula in final pass")
    center_offset = float(m_center_off.group(1))
    m_phase_idx = re.search(r"\bint\s+index\s*=\s*([^;]+);", final_stripped)
    if not m_phase_idx:
        raise ValueError("Missing PixelShuffle index formula in final pass")
    phase_expr = re.sub(r"\s+", "", m_phase_idx.group(1))
    if phase_expr in ("(1-phase.y)*2+phase.x", "((1-phase.y)*2)+phase.x"):
        shader_flips_phase = True
    elif phase_expr in ("phase.y*2+phase.x", "(phase.y*2)+phase.x"):
        shader_flips_phase = False
    else:
        raise ValueError(f"Unsupported PixelShuffle index formula: {m_phase_idx.group(1).strip()!r}")

    out_residuals = []
    out_final = []
    out_h, out_w = height * 2, width * 2
    for y in range(out_h):
        res_row = []
        final_row = []
        for x in range(out_w):
            # Physical fragment center vPos = ((x + 0.5) / out_w, (y + 0.5) / out_h).
            # When flip_coordinates is True (bottom-left physical input, top-left shader),
            # MAIN_pos = hookCoord(vPos) = (vPos.x, 1.0 - vPos.y).
            u = (x + 0.5) / out_w
            v = (y + 0.5) / out_h
            main_pos_u = u
            main_pos_v = (1.0 - v) if flip_coordinates else v
            cell_x = math.floor(main_pos_u * width)
            cell_y = math.floor(main_pos_v * height)
            frac_x = (main_pos_u * width) - cell_x
            frac_y = (main_pos_v * height) - cell_y
            phase_x = int(math.floor(frac_x * phase_scale))
            phase_y = int(math.floor(frac_y * phase_scale))
            idx = ((1 - phase_y) if shader_flips_phase else phase_y) * 2 + phase_x
            if idx < 0 or idx >= 4:
                raise ValueError(f"PixelShuffle index out of range: {idx} (phase=({phase_x},{phase_y}), scale={phase_scale})")
            center_u = (cell_x + center_offset) / width
            center_v = (cell_y + center_offset) / height
            phys_center_u = center_u
            phys_center_v = (1.0 - center_v) if flip_coordinates else center_v
            res = [sample_bilinear(textures[name], phys_center_u, phys_center_v)[idx] for name in residuals]
            res_row.append(res)

            # MAIN_tex(MAIN_pos) invokes texture(u_MAIN, hookCoord(MAIN_pos)) = texture(u_MAIN, vPos).
            base_sample = sample_bilinear(textures["MAIN"], u, v)
            combined_rgb = [base_sample[c] + res[c] for c in range(3)]
            final_row.append(combined_rgb + [1.0])
        out_residuals.append(res_row)
        out_final.append(final_row)

    return out_residuals, out_final


def main():
    """Check catalog invariants, residual outputs and full bilinear reconstructions."""
    filters = json.loads((ROOT / "engine/src/main/graphicsfilters/vnsr.json").read_text(encoding="utf-8"))["filters"]
    assert [f["id"] for f in filters] == ["vnsr_c08_x2", "vnsr_c12_x2", "vnsr_c16_x2"]
    image = [[[((x * 3 + y * 7 + c * 11) % 31) / 31 for c in range(3)] + [1.0] for x in range(8)] for y in range(8)]
    for definition in filters:
        name = Path(definition["asset"]).name
        original = (ROOT / "engine/patches/vnsr/sources" / name).read_text(encoding="utf-8")
        adapted = (ROOT / "engine/src/main/assets" / definition["asset"]).read_text(encoding="utf-8")
        assert definition["nativeScale"] == definition["maxOutputScale"] == 2
        assert definition["haloPerPass"] == 1
        assert adapted.count("//!DESC ") == 7
        offsets = re.findall(r"sample_\d+\(vec2\(([-.\d]+),([-.\d]+)\)\)", adapted)
        assert offsets and max(abs(float(v)) for pair in offsets for v in pair) == 1
        expected_residuals, expected_final = evaluate(original, image)
        assert definition["topLeftCoordinates"]
        actual_residuals, actual_final = evaluate(adapted, image[::-1], flip_coordinates=True)
        actual_residuals = actual_residuals[::-1]
        actual_final = actual_final[::-1]

        res_error = max(abs(a - b) for ra, rb in zip(expected_residuals, actual_residuals) for pa, pb in zip(ra, rb) for a, b in zip(pa, pb))
        assert res_error < 1e-10, (name, "residual", res_error)

        final_error = max(abs(a - b) for ra, rb in zip(expected_final, actual_final) for pa, pb in zip(ra, rb) for a, b in zip(pa, pb))
        assert final_error < 1e-10, (name, "final float", final_error)

        # 8-bit quantized [0, 255] check across all 4 channels (RGBA)
        quantized_expected = [[[round(max(0.0, min(1.0, c)) * 255) for c in pixel] for pixel in row] for row in expected_final]
        quantized_actual = [[[round(max(0.0, min(1.0, c)) * 255) for c in pixel] for pixel in row] for row in actual_final]
        assert quantized_expected == quantized_actual, (name, "8-bit quantization mismatch")

        # Without the coordinate adaptation this asymmetric input must distinguish the output.
        _, wrong_final = evaluate(original, image[::-1])
        wrong_final = wrong_final[::-1]
        wrong_error = max(abs(a - b) for ra, rb in zip(expected_final, wrong_final) for pa, pb in zip(ra, rb) for a, b in zip(pa, pb))
        assert wrong_error > 1e-5, (name, "negative control failed to distinguish", wrong_error)
        # Swapped MRT fragColor routing in a fused pass must fail validation.
        m_pair = re.search(
            r"(fragColor0\s*=\s*)((?:max\()?v\d+(?:,0\.0\))?;)(\s*)(fragColor1\s*=\s*)((?:max\()?v\d+(?:,0\.0\))?;)",
            adapted,
        )
        assert m_pair is not None, (name, "missing MRT fragColor0/fragColor1 pair")
        swapped = (
            adapted[:m_pair.start()]
            + m_pair.group(1) + m_pair.group(5)
            + m_pair.group(3)
            + m_pair.group(4) + m_pair.group(2)
            + adapted[m_pair.end():]
        )
        assert swapped != adapted, (name, "failed to mutate MRT fragColor routing")
        try:
            evaluate(swapped, image[::-1], flip_coordinates=True)
        except AssertionError:
            pass
        else:
            raise AssertionError((name, "swapped MRT fragColor routing must fail"))
        # Fractional tap offset (e.g. 0.5) outside supported 3x3 integer grid must be rejected.
        m_tap = re.search(r"sample_\d+\(vec2\(0\.0,0\.0\)\)", adapted)
        assert m_tap is not None, (name, "expected integer center tap in fused shader")
        fractional = adapted[:m_tap.start()] + m_tap.group(0).replace("0.0,0.0", "0.5,0.0", 1) + adapted[m_tap.end():]
        assert fractional != adapted
        try:
            evaluate(fractional, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "fractional tap offset must fail instead of truncating to 0"))
        # Scaled sample_k coordinate expression (e.g. MAIN_pos+2.0*p*MAIN_pt) must be rejected.
        assert "MAIN_tex(MAIN_pos+p*MAIN_pt)" in adapted
        scaled_sampler = adapted.replace("MAIN_tex(MAIN_pos+p*MAIN_pt)", "MAIN_tex(MAIN_pos+2.0*p*MAIN_pt)", 1)
        try:
            evaluate(scaled_sampler, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "scaled sample_k expression must be rejected"))
        # Swapping vec4 v0= / v1= declaration names must alter evaluated output relative to reference.
        assert "vec4 v0=" in adapted and "vec4 v1=" in adapted
        swapped_bias_names = (
            adapted.replace("vec4 v0=", "vec4 __SWAP_V0=", 1)
            .replace("vec4 v1=", "vec4 v0=", 1)
            .replace("vec4 __SWAP_V0=", "vec4 v1=", 1)
        )
        assert swapped_bias_names != adapted
        _, swapped_bias_final = evaluate(swapped_bias_names, image[::-1], flip_coordinates=True)
        swapped_bias_final = swapped_bias_final[::-1]
        swapped_bias_err = max(
            abs(a - b)
            for ra, rb in zip(expected_final, swapped_bias_final)
            for pa, pb in zip(ra, rb)
            for a, b in zip(pa, pb)
        )
        assert swapped_bias_err > 1e-5, (name, "swapped v0/v1 bias names must be detected", swapped_bias_err)
        # Whitespace variants on vN += mat4(...) * s; must stay equivalent and still detect weight changes.
        m_mat_stmt = re.search(r"(v\d+\s*\+=\s*mat4\()([^)]+)(\)\s*\*\s*s\s*;)", adapted)
        assert m_mat_stmt is not None, (name, "expected vN += mat4(...)*s; statement")
        spaced_same = (
            adapted[:m_mat_stmt.start()]
            + re.sub(r"\+=", " += ", m_mat_stmt.group(1))
            + m_mat_stmt.group(2)
            + " ) * s ;"
            + adapted[m_mat_stmt.end():]
        )
        _, spaced_final = evaluate(spaced_same, image[::-1], flip_coordinates=True)
        spaced_final = spaced_final[::-1]
        spaced_err = max(
            abs(a - b)
            for ra, rb in zip(expected_final, spaced_final)
            for pa, pb in zip(ra, rb)
            for a, b in zip(pa, pb)
        )
        assert spaced_err < 1e-10, (name, "whitespace-formatted mat4 term must evaluate identically", spaced_err)
        coeffs = [c.strip() for c in m_mat_stmt.group(2).split(",")]
        coeffs[0] = f"{float(coeffs[0]) + 1.5:.6f}"
        spaced_mutated = (
            adapted[:m_mat_stmt.start()]
            + re.sub(r"\+=", " += ", m_mat_stmt.group(1))
            + ",".join(coeffs)
            + " ) * s ;"
            + adapted[m_mat_stmt.end():]
        )
        _, spaced_mut_final = evaluate(spaced_mutated, image[::-1], flip_coordinates=True)
        spaced_mut_final = spaced_mut_final[::-1]
        spaced_mut_err = max(
            abs(a - b)
            for ra, rb in zip(expected_final, spaced_mut_final)
            for pa, pb in zip(ra, rb)
            for a, b in zip(pa, pb)
        )
        assert spaced_mut_err > 1e-5, (name, "weight mutation on spaced mat4 term must change output", spaced_mut_err)
        # Commented-out fragColor1 must not satisfy MRT route completeness check.
        m_fc1 = re.search(r"(fragColor1\s*=\s*(?:max\()?v\d+(?:,0\.0\))?;)", adapted)
        assert m_fc1 is not None, (name, "missing fragColor1 assignment for comment test")
        commented_route = adapted[:m_fc1.start()] + "// " + m_fc1.group(1) + adapted[m_fc1.end():]
        try:
            evaluate(commented_route, image[::-1], flip_coordinates=True)
        except AssertionError:
            pass
        else:
            raise AssertionError((name, "commented-out fragColor1 assignment must fail validation"))
        # Multi-target single-line //!SAVE A B declaration must evaluate identically to multi-line //!SAVE.
        multi_save_block = re.search(r"(?:^//!SAVE [^\r\n]+\r?\n){2,}", adapted, re.MULTILINE)
        assert multi_save_block is not None, (name, "expected multi-SAVE pass block")
        combined_targets = " ".join(
            ln.strip()[len("//!SAVE"):].strip() for ln in multi_save_block.group(0).splitlines()
        )
        single_line_save = (
            adapted[:multi_save_block.start()]
            + f"//!SAVE {combined_targets}\n"
            + adapted[multi_save_block.end():]
        )
        _, single_save_final = evaluate(single_line_save, image[::-1], flip_coordinates=True)
        single_save_final = single_save_final[::-1]
        single_save_err = max(
            abs(a - b)
            for ra, rb in zip(expected_final, single_save_final)
            for pa, pb in zip(ra, rb)
            for a, b in zip(pa, pb)
        )
        assert single_save_err < 1e-10, (name, "single-line //!SAVE targets must match multi-line", single_save_err)
        # Extra assignment before first s=sample_ in void hook() must be rejected.
        m_first_sample = re.search(r"(\bs\s*=\s*sample_)", adapted)
        assert m_first_sample is not None
        clobbered_bias = adapted[:m_first_sample.start()] + "v1 = vec4(0.0);\n" + adapted[m_first_sample.start():]
        try:
            evaluate(clobbered_bias, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "extra statement before first sample_ must fail validation"))
        # Swapping X/Y in PixelShuffle index formula must be rejected.
        m_idx_formula = re.search(r"(int\s+index\s*=\s*)([^;]+)(;)", adapted)
        assert m_idx_formula is not None
        swapped_xy_phase = (
            adapted[:m_idx_formula.start()]
            + m_idx_formula.group(1) + "phase.x * 2 + phase.y" + m_idx_formula.group(3)
            + adapted[m_idx_formula.end():]
        )
        try:
            evaluate(swapped_xy_phase, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "unmodeled swapped X/Y PixelShuffle formula must be rejected"))
        # Mutating PixelShuffle phase scale (*2.0 -> *4.0) must fail validation.
        bad_phase_scale = adapted.replace("*2.0));", "*4.0));", 1)
        assert bad_phase_scale != adapted, (name, "expected *2.0 in phase expression")
        try:
            evaluate(bad_phase_scale, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "mutated phase scale *4.0 must fail validation"))
        # Mutating sampling center offset (0.5 -> 0.25) must diverge from expected residuals.
        bad_center = adapted.replace("vec2(0.5)", "vec2(0.25)", 1)
        assert bad_center != adapted, (name, "expected vec2(0.5) in center expression")
        bad_center_res, _ = evaluate(bad_center, image[::-1], flip_coordinates=True)
        bad_center_res = bad_center_res[::-1]
        bad_center_err = max(
            abs(a - b)
            for ra, rb in zip(expected_residuals, bad_center_res)
            for pa, pb in zip(ra, rb)
            for a, b in zip(pa, pb)
        )
        assert bad_center_err > 1e-4, (name, "mutated center offset 0.25 must diverge from expected", bad_center_err)
        # Missing/corrupted //!WIDTH MAIN.w on final pass must be rejected.
        last_width_idx = adapted.rfind("//!WIDTH MAIN.w")
        assert last_width_idx >= 0
        bad_width = adapted[:last_width_idx] + "//!WIDTH MAIN.x" + adapted[last_width_idx + len("//!WIDTH MAIN.w"):]
        assert bad_width != adapted
        try:
            evaluate(bad_width, image[::-1], flip_coordinates=True)
        except ValueError:
            pass
        else:
            raise AssertionError((name, "invalid final pass //!WIDTH must be rejected"))
        print(name + ": residual & final combine equivalence passed; residual error=" + str(res_error)
              + ", final max error=" + str(final_error))


if __name__ == "__main__":
    main()
