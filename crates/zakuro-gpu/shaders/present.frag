#version 450
// compiled twice, as it is and with STEREO defined, which draws the top
// screen's two pictures in one for 3D:
//   glslc present.frag -o present.frag.spv
//   glslc -DSTEREO present.frag -o present_stereo.frag.spv
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;
layout(set = 0, binding = 0) uniform sampler2D screen;
#ifdef STEREO
layout(set = 0, binding = 1) uniform sampler2D right_eye;
#endif
// where the screen lies in the image, its corner and size as texture
// coordinates, then the corners inset by half a texel, which samples keep
// within so filtering takes nothing from around it. the whole image when it
// holds just the screen. then how a screen drawn bigger than its pixels is
// filtered: 0 smooth, 1 pixels, 2 sharp.
layout(push_constant) uniform Crop {
    layout(offset = 16) vec4 area;
    vec4 bounds;
    int mode;
#ifdef STEREO
    // how the eyes are shown, 1 in red and cyan, 2 on alternate rows,
    // whether they swap, then where the right eye's picture lies
    int stereo;
    int swap;
    int unused;
    vec4 right_area;
    vec4 right_bounds;
#endif
    // the picture effect, 0 none, 1 sharpen, 2 vivid colours, 3 scanlines,
    // 4 smooth edges, and how strongly it applies, 0 to 1
    int fx;
    float fx_strength;
} crop;

// a screen drawn bigger than the window shows it is averaged over every
// texel a window pixel covers, which smooths its edges the way drawing at a
// higher resolution should. one drawn smaller is filtered as asked. a
// little over a texel a pixel still counts as one, at 1:1 the derivatives
// land on either side of it.
vec3 shown(sampler2D image, vec4 area, vec4 bounds) {
    vec2 at = area.xy + uv * area.zw;
    vec2 size = vec2(textureSize(image, 0));
    vec2 covered = fwidth(at) * size;
    if (max(covered.x, covered.y) <= 1.001) {
        vec2 texel = at * size;
        if (crop.mode == 1) {
            // the nearest texel, its middle sampled
            at = (floor(texel) + 0.5) / size;
        } else if (crop.mode == 2) {
            // bilinear between texel centres, its blend squeezed into the
            // one window pixel each texel's edge falls in, at any scale
            vec2 scale = 1.0 / max(covered, vec2(1e-6));
            vec2 centred = texel - 0.5;
            vec2 blend = clamp((fract(centred) - 0.5) * scale + 0.5, 0.0, 1.0);
            at = (floor(centred) + 0.5 + blend) / size;
        }
        return texture(image, clamp(at, bounds.xy, bounds.zw)).rgb;
    }
    // each sample is bilinear, so it already averages its four texels
    ivec2 taps = ivec2(clamp(ceil(covered), 1.0, 4.0));
    vec3 sum = vec3(0.0);
    for (int y = 0; y < taps.y; y++) {
        for (int x = 0; x < taps.x; x++) {
            vec2 offset = ((vec2(x, y) + 0.5) / vec2(taps) - 0.5) * covered / size;
            sum += texture(image, clamp(at + offset, bounds.xy, bounds.zw)).rgb;
        }
    }
    return sum / float(taps.x * taps.y);
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// one bilinear sample, kept within the screen like shown's
vec3 tap(sampler2D image, vec4 bounds, vec2 at) {
    return textureLod(image, clamp(at, bounds.xy, bounds.zw), 0.0).rgb;
}

// the screen as shown, then the picture effect over it. none returns it
// untouched and samples nothing more. neighbours are a window pixel away, or
// a texel when the screen is drawn bigger than its pixels, so the effects
// still find the picture's edges then.
vec3 picture(sampler2D image, vec4 area, vec4 bounds) {
    vec3 c = shown(image, area, bounds);
    float s = crop.fx_strength;
    if (crop.fx == 0 || s <= 0.0) {
        return c;
    }
    vec2 at = area.xy + uv * area.zw;
    vec2 step = max(fwidth(at), 1.0 / vec2(textureSize(image, 0)));
    // the console's 240 lines down the screen, as uv runs down it
    float line = uv.y * 240.0;
    float lines = fwidth(line);
    if (crop.fx == 1) {
        // contrast adaptive sharpening: a cross of neighbours pulls the
        // middle away from them, less where the contrast is already high
        vec3 n = tap(image, bounds, at - vec2(0.0, step.y));
        vec3 so = tap(image, bounds, at + vec2(0.0, step.y));
        vec3 w = tap(image, bounds, at - vec2(step.x, 0.0));
        vec3 e = tap(image, bounds, at + vec2(step.x, 0.0));
        vec3 low = min(c, min(min(n, so), min(w, e)));
        vec3 high = max(c, max(max(n, so), max(w, e)));
        vec3 amount = sqrt(clamp(min(low, 1.0 - high) / max(high, vec3(1e-4)), 0.0, 1.0));
        vec3 weight = -amount * 0.2 * s;
        return clamp((c + weight * (n + so + w + e)) / (1.0 + 4.0 * weight), 0.0, 1.0);
    } else if (crop.fx == 2) {
        // more saturation, then a little more contrast about the middle grey
        vec3 vivid = mix(vec3(luma(c)), c, 1.0 + 0.6 * s);
        return clamp((vivid - 0.5) * (1.0 + 0.25 * s) + 0.5, 0.0, 1.0);
    } else if (crop.fx == 3) {
        // each console line bright in its middle and dark at its edges,
        // fading out once a line is too few window pixels to show it
        float middle = 0.5 - 0.5 * cos(6.2831853 * fract(line));
        float fade = clamp(1.5 - 2.0 * lines, 0.0, 1.0);
        return c * (1.0 - 0.5 * s * fade * (1.0 - middle));
    } else if (crop.fx == 4) {
        // a light FXAA: where the corners' luma shows an edge, blend along it
        float nw = luma(tap(image, bounds, at + vec2(-step.x, -step.y)));
        float ne = luma(tap(image, bounds, at + vec2(step.x, -step.y)));
        float sw = luma(tap(image, bounds, at + vec2(-step.x, step.y)));
        float se = luma(tap(image, bounds, at + vec2(step.x, step.y)));
        float middle = luma(c);
        float low = min(middle, min(min(nw, ne), min(sw, se)));
        float high = max(middle, max(max(nw, ne), max(sw, se)));
        if (high - low < max(0.0312, high * 0.125)) {
            return c;
        }
        vec2 along = vec2(sw + se - nw - ne, nw + sw - ne - se);
        float reduce = max((nw + ne + sw + se) * 0.03125, 1.0 / 128.0);
        along = clamp(along / (min(abs(along.x), abs(along.y)) + reduce), -2.0, 2.0) * step;
        vec3 blended = 0.5 * (tap(image, bounds, at - along / 6.0) + tap(image, bounds, at + along / 6.0));
        return mix(c, blended, s);
    }
    return c;
}

void main() {
#ifdef STEREO
    vec3 left = picture(screen, crop.area, crop.bounds);
    vec3 right = picture(right_eye, crop.right_area, crop.right_bounds);
    if (crop.swap != 0) {
        vec3 other = left;
        left = right;
        right = other;
    }
    if (crop.stereo == 1) {
        // Dubois' red and cyan projection, least squares over what the
        // glasses let through, with less ghosting than the plain split
        vec3 red = vec3(dot(left, vec3(0.437, 0.449, 0.164)), dot(left, vec3(-0.062, -0.062, -0.024)), dot(left, vec3(-0.048, -0.050, -0.017)));
        vec3 cyan = vec3(dot(right, vec3(-0.011, -0.032, -0.007)), dot(right, vec3(0.377, 0.761, 0.009)), dot(right, vec3(-0.026, -0.093, 1.234)));
        colour = vec4(clamp(red + cyan, 0.0, 1.0), 1.0);
    } else {
        // window rows from the top, even ones the left eye's
        colour = vec4((int(gl_FragCoord.y) & 1) == 0 ? left : right, 1.0);
    }
#else
    colour = vec4(picture(screen, crop.area, crop.bounds), 1.0);
#endif
}
