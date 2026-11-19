//! picture shaders a user writes: a GLSL function over the screen, put in
//! the app's shaders folder, which the present pass draws the screen with
//! instead of the built-in picture effects.
//!
//! the user writes
//!
//! ```glsl
//! vec3 picture(vec2 uv, vec2 texel, float strength)
//! ```
//!
//! returning the colour, 0 to 1, at uv across the screen (0,0 its top left,
//! 1,1 its bottom right). texel is one of the screen's pixels in uv, and
//! strength the effect strength setting, 0 to 1. `vec3 screen_at(vec2 uv)`
//! reads the screen, bilinear, kept within it. the file holds no #version
//! line and no main: those are wrapped around it here, with present.frag's
//! inputs, descriptor set and push constants, so the presenter's pipeline
//! layout serves it unchanged and only the fragment module differs.

use std::io::Read;
use std::path::Path;

/// the largest picture shader file read.
pub const LIMIT: u64 = 64 * 1024;

/// what goes before the user's code: present.frag's interface. naga takes
/// no combined sampler2D, so the screen is an image and a sampler, which
/// compile moves to the image's binding, where a combined image sampler
/// descriptor serves both. naga ignores layout(offset), so the push
/// constants start with the vertex shader's 16 bytes, which this never
/// reads.
const PRELUDE: &str = "#version 450
layout(location = 0) in vec2 zk_uv;
layout(location = 0) out vec4 zk_colour;
layout(set = 0, binding = 0) uniform texture2D zk_image;
layout(set = 0, binding = 1) uniform sampler zk_sampler;
layout(push_constant) uniform ZkCrop { vec4 vertex_words; vec4 area; vec4 bounds; int mode; int fx; float strength; } zk_crop;
vec3 screen_at(vec2 uv) {
    vec2 at = zk_crop.area.xy + uv * zk_crop.area.zw;
    return textureLod(sampler2D(zk_image, zk_sampler), clamp(at, zk_crop.bounds.xy, zk_crop.bounds.zw), 0.0).rgb;
}
";

/// what goes after it: main calls picture.
const EPILOGUE: &str = "
void main() {
    vec2 size = vec2(textureSize(sampler2D(zk_image, zk_sampler), 0)) * zk_crop.area.zw;
    zk_colour = vec4(clamp(picture(zk_uv, 1.0 / max(size, vec2(1.0)), zk_crop.strength), 0.0, 1.0), 1.0);
}
";

/// a file name in the shaders folder, as the settings allow it: up to 64
/// letters, digits, spaces, dots, dashes and underscores, not starting
/// with a dot, so no path leaves the folder.
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && !name.starts_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | '.' | '-' | '_'))
}

/// the picture shader `name` in `folder` read and compiled to SPIR-V, or
/// why not, naming the file.
pub fn load(folder: &Path, name: &str) -> Result<Vec<u32>, String> {
    if !valid_name(name) {
        return Err(format!("\"{name}\" is not a shader file name"));
    }
    let mut source = String::new();
    std::fs::File::open(folder.join(name))
        .and_then(|file| file.take(LIMIT + 1).read_to_string(&mut source))
        .map_err(|error| format!("{name}: {error}"))?;
    if source.len() as u64 > LIMIT {
        return Err(format!("{name}: larger than {} KiB", LIMIT / 1024));
    }
    compile(&source).map_err(|error| format!("{name}: {error}"))
}

/// a picture function wrapped and compiled to SPIR-V words, or its first
/// error with the line in the user's code. naga can panic on some inputs,
/// so a panic is an error too.
pub fn compile(code: &str) -> Result<Vec<u32>, String> {
    use naga::back::spv;
    use naga::front::glsl;
    use naga::valid::{Capabilities, ValidationFlags, Validator};
    if code.len() as u64 > LIMIT {
        return Err(format!("larger than {} KiB", LIMIT / 1024));
    }
    let source = format!("{PRELUDE}{code}{EPILOGUE}");
    let at = |span: naga::Span| {
        let line = span.location(&source).line_number as usize;
        let first = PRELUDE.lines().count();
        match line.checked_sub(first) {
            Some(line) if line >= 1 && line <= code.lines().count() => format!("line {line}: "),
            _ => String::new(),
        }
    };
    let run = || -> Result<Vec<u32>, String> {
        let module = glsl::Frontend::default()
            .parse(&glsl::Options::from(naga::ShaderStage::Fragment), &source)
            .map_err(|errors| match errors.errors.first() {
                Some(error) => format!("{}{}", at(error.meta), error.kind),
                None => "does not compile".to_owned(),
            })?;
        let info = Validator::new(ValidationFlags::all(), Capabilities::IMMEDIATES)
            .validate(&module)
            .map_err(|error| {
                let mut message = error.to_string();
                let mut cause = std::error::Error::source(&error);
                while let Some(inner) = cause {
                    message = format!("{message}: {inner}");
                    cause = inner.source();
                }
                let line = error.spans().next().map(|(span, _)| at(*span)).unwrap_or_default();
                format!("{line}{message}")
            })?;
        let options = spv::Options {
            lang_version: (1, 0),
            // the default flags turn y around, as WebGPU wants, and label
            // everything
            flags: spv::WriterFlags::empty(),
            // the default counts every loop's iterations
            force_loop_bounding: false,
            // the sampler at binding 1 onto binding 0 with the image,
            // which validation would call a conflict
            binding_map: spv::BindingMap::from([(
                naga::ResourceBinding { group: 0, binding: 1 },
                spv::BindingInfo { descriptor_set: 0, binding: 0, binding_array_size: None },
            )]),
            ..spv::Options::default()
        };
        let pipeline = spv::PipelineOptions { shader_stage: naga::ShaderStage::Fragment, entry_point: "main".into() };
        spv::write_vec(&module, &info, &options, Some(&pipeline)).map_err(|error| error.to_string())
    };
    std::panic::catch_unwind(run).unwrap_or_else(|_| Err("the compiler failed on it".to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// the example docs/android.md gives.
    const SEPIA: &str = "vec3 picture(vec2 uv, vec2 texel, float strength) {
    vec3 c = screen_at(uv);
    vec3 sepia = vec3(dot(c, vec3(0.393, 0.769, 0.189)),
                      dot(c, vec3(0.349, 0.686, 0.168)),
                      dot(c, vec3(0.272, 0.534, 0.131)));
    return mix(c, sepia, strength);
}
";

    #[test]
    fn a_picture_function_compiles_to_spirv() {
        let words = compile(SEPIA).unwrap();
        assert_eq!(words[0], 0x0723_0203, "the SPIR-V magic number");
        // the image and the sampler both at binding 0 of set 0, and the push
        // constants at present.frag's offsets
        let (mut bindings, mut offsets) = (Vec::new(), Vec::new());
        let mut at = 5;
        while at < words.len() {
            let (count, op) = ((words[at] >> 16) as usize, words[at] & 0xffff);
            match (op, words.get(at + 2)) {
                (71, Some(33)) => bindings.push(words[at + 3]),
                (71, Some(34)) => assert_eq!(words[at + 3], 0, "descriptor set"),
                (72, _) if words[at + 3] == 35 => offsets.push(words[at + 4]),
                _ => {}
            }
            at += count.max(1);
        }
        assert_eq!(bindings, [0, 0]);
        assert!(offsets.starts_with(&[0, 16, 32, 48, 52, 56]), "{offsets:?}");
        // one that reads neighbours through texel too
        let blur = "vec3 picture(vec2 uv, vec2 texel, float strength) {
    vec3 sum = vec3(0.0);
    for (int i = -1; i <= 1; i++) {
        sum += screen_at(uv + vec2(float(i), 0.0) * texel);
    }
    return mix(screen_at(uv), sum / 3.0, strength);
}
";
        assert!(compile(blur).is_ok());
    }

    #[test]
    fn a_broken_shader_is_an_error_with_its_line() {
        let error = compile("vec3 picture(vec2 uv, vec2 texel, float strength) {\n    return nothing(uv);\n}\n").unwrap_err();
        assert!(error.starts_with("line 2: "), "{error}");
        assert!(compile("this is not GLSL").is_err());
        // no picture function, or the wrong type of one
        assert!(compile("vec3 other(vec2 uv) { return vec3(uv, 0.0); }").is_err());
        assert!(compile("float picture(vec2 uv, vec2 texel, float strength) { return 1.0; }").is_err());
        assert!(compile("").is_err());
    }

    #[test]
    fn oversized_or_misnamed_files_are_refused() {
        let big = format!("{SEPIA}{}", "//".repeat(LIMIT as usize));
        assert!(compile(&big).unwrap_err().contains("KiB"));
        let folder = std::env::temp_dir().join(format!("zakuro-picture-shaders-{}", std::process::id()));
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::write(folder.join("sepia.glsl"), SEPIA).unwrap();
        std::fs::write(folder.join("big.glsl"), &big).unwrap();
        assert!(load(&folder, "sepia.glsl").is_ok());
        assert!(load(&folder, "big.glsl").unwrap_err().starts_with("big.glsl: larger than 64 KiB"));
        assert!(load(&folder, "missing.glsl").unwrap_err().starts_with("missing.glsl: "));
        for name in ["", "../sepia.glsl", "a/b.glsl", "a\\b.glsl", ".hidden", "x:y", &"a".repeat(65)] {
            assert!(!valid_name(name), "{name}");
            assert!(load(&folder, name).is_err(), "{name}");
        }
        assert!(valid_name("My shader-2_v1.glsl"));
        std::fs::remove_dir_all(&folder).unwrap();
    }
}
