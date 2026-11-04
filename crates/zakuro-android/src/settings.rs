use serde::{Deserialize, Serialize};

macro_rules! choice {
    ($name:ident, $default:ident, $($variant:ident),+ $(,)?) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
        #[serde(rename_all = "snake_case")]
        pub enum $name { $($variant),+ }
        impl Default for $name { fn default() -> Self { Self::$default } }
    };
}
choice!(Layout, Stacked, Stacked, SideBySide, TopOnly, BottomOnly, LargeTop);
choice!(Filter, Smooth, Smooth, Sharp, Pixels);
choice!(ButtonMapping, Nintendo, Nintendo, Xbox);
choice!(LeftStick, CirclePad, CirclePad, Dpad);
choice!(RightStick, CStick, CStick, Cursor);
choice!(ShaderMode, Auto, Auto, Gpu, Cpu);
choice!(ShaderExecution, Compiled, Compiled, Interpreted);
choice!(ShaderCompilation, Async, Async, Wait);
choice!(TiltButton, L3, Always, L1, L2, R1, R2, L3, R3);

impl TiltButton {
    pub fn keycode(self) -> Option<i32> {
        match self {
            Self::Always => None,
            Self::L1 => Some(102),
            Self::L2 => Some(104),
            Self::R1 => Some(103),
            Self::R2 => Some(105),
            Self::L3 => Some(106),
            Self::R3 => Some(107),
        }
    }
    pub fn guest_bits(self) -> u32 {
        match self {
            Self::L1 => Binding::L.bits(),
            Self::L2 => Binding::Zl.bits(),
            Self::R1 => Binding::R.bits(),
            Self::R2 => Binding::Zr.bits(),
            _ => 0,
        }
    }
}
choice!(
    StereoMode,
    Off,
    Off,
    Anaglyph,
    SideBySide,
    HalfSideBySide,
    Interlaced
);
choice!(
    Binding, None, None, A, B, X, Y, L, R, Zl, Zr, Start, Select, Up, Down, Left, Right, MotionGate, FastForward
);

impl Binding {
    pub fn bits(self) -> u32 {
        match self {
            Self::A => 1,
            Self::B => 2,
            Self::Select => 4,
            Self::Start => 8,
            Self::Right => 16,
            Self::Left => 32,
            Self::Up => 64,
            Self::Down => 128,
            Self::R => 256,
            Self::L => 512,
            Self::X => 1024,
            Self::Y => 2048,
            Self::Zl => 1 << 14,
            Self::Zr => 1 << 15,
            Self::None | Self::MotionGate | Self::FastForward => 0,
        }
    }
}

/// Stable Intent `advanced` JSON contract, shared with the Java settings editor.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Advanced {
    pub layout: Layout,
    pub filter: Filter,
    pub integer_scale: bool,
    pub top_width: f32,
    pub stretch: bool,
    pub swap_screens: bool,
    pub dual_display: bool,
    pub external_stretch: bool,
    pub custom_driver: bool,
    pub hardware_renderer: bool,
    pub gpu_readback: bool,
    pub shader_mode: ShaderMode,
    pub shader_execution: ShaderExecution,
    pub cpu_shader_jit: bool,
    pub shader_compilation: ShaderCompilation,
    pub generic_shaders: bool,
    pub depth_blits: bool,
    pub float_depth: bool,
    pub static_blend: bool,
    pub motion_enabled: bool,
    pub gyro_sensitivity: f32,
    pub gyro_invert_x: bool,
    pub gyro_invert_y: bool,
    pub gyro_invert_z: bool,
    pub stick_motion: bool,
    pub stick_motion_gate: bool,
    pub stick_motion_sensitivity: f32,
    pub tilt_button: TiltButton,
    pub reverse_tilt_x: bool,
    pub reverse_tilt_y: bool,
    pub button_mapping: ButtonMapping,
    /// Android keycode -> guest button; explicit bindings override the face preset.
    pub controller_bindings: std::collections::BTreeMap<i32, Binding>,
    pub left_stick: LeftStick,
    pub right_stick: RightStick,
    pub cursor_speed: f32,
    pub deadzone: f32,
    pub volume: f32,
    pub mute: bool,
    pub show_fps: bool,
    /// -1 retains the loader's automatic title region.
    pub region: i32,
    pub language: i32,
    pub new_3ds: bool,
    pub skip_idle_loops: bool,
    #[serde(alias = "recompiled")]
    pub aot: bool,
    pub speed_limit: i32,
    pub fast_forward_speed: i32,
    pub frame_cap: i32,
    pub slider_3d: f32,
    pub stereo: StereoMode,
    pub stereo_swap: bool,
    pub interpolate: bool,
    /// FIFO at normal speed and the 60 FPS cap, mailbox otherwise; off keeps mailbox (0.1.8).
    pub vsync_pacing: bool,
    /// Stock Qualcomm drivers abort on GPU vertex shaders; fall back to Auto there.
    pub safe_gpu_shaders: bool,
    /// Mesa Turnip `TU_DEBUG=sysmem` for custom drivers (tests the corner artifact).
    pub turnip_sysmem: bool,
    /// HD picture upload fixes for battle-start hitches (0.1.9); each can be turned off to compare.
    pub pooled_uploads: bool,
    pub upload_size_budget: bool,
    pub threaded_picture_copy: bool,
    pub fast_texture_decode: bool,
    pub async_pack_lookup: bool,
    /// shader warm-up at launch from earlier sessions (0.1.9).
    pub warm_vertex_programs: bool,
    pub warm_pipelines: bool,
    /// hold the first frame until both warm-ups are done, for 30 s at most.
    pub warm_before_start: bool,
    /// upstream 0.2.31: shade a command list's draws ahead on other threads.
    pub shade_ahead: bool,
    /// the desktop queue (~125 ms target, drift trim, time stretch, fades); off is 0.2.0's.
    pub audio_queue_managed: bool,
    /// AAudio FAST track: allocated session (no RAW flag) and Game usage. Off: the Thor's
    /// global software equalizer denies FAST to every track anyway.
    pub low_latency_audio: bool,
    /// a press and release between two frames still counts as pressed for one frame.
    pub latch_short_presses: bool,
    /// under vsync, the surface's minimum swapchain images instead of one more. Off: Turnip
    /// shows frames 57 -> 40 ms sooner, but the stock driver gains nothing and hitches more.
    pub short_vsync_queue: bool,
    pub enhancements: Vec<String>,
}
impl Default for Advanced {
    fn default() -> Self {
        Self {
            layout: Layout::Stacked,
            filter: Filter::Smooth,
            integer_scale: false,
            top_width: 400.0,
            stretch: false,
            swap_screens: false,
            dual_display: false,
            external_stretch: false,
            custom_driver: false,
            hardware_renderer: true,
            gpu_readback: false,
            shader_mode: ShaderMode::Auto,
            shader_execution: ShaderExecution::Compiled,
            cpu_shader_jit: true,
            shader_compilation: ShaderCompilation::Async,
            generic_shaders: false,
            depth_blits: true,
            float_depth: false,
            static_blend: false,
            motion_enabled: true,
            gyro_sensitivity: 1.0,
            gyro_invert_x: false,
            gyro_invert_y: false,
            gyro_invert_z: false,
            stick_motion: false,
            stick_motion_gate: true,
            stick_motion_sensitivity: 1.0,
            tilt_button: TiltButton::L3,
            reverse_tilt_x: false,
            reverse_tilt_y: false,
            button_mapping: ButtonMapping::Nintendo,
            controller_bindings: std::collections::BTreeMap::new(),
            left_stick: LeftStick::CirclePad,
            right_stick: RightStick::CStick,
            cursor_speed: 1.0,
            deadzone: 0.15,
            volume: 1.0,
            mute: false,
            show_fps: false,
            region: -1,
            language: 1,
            new_3ds: false,
            skip_idle_loops: true,
            aot: true,
            speed_limit: 100,
            fast_forward_speed: 150,
            frame_cap: 60,
            slider_3d: 0.0,
            stereo: StereoMode::Off,
            stereo_swap: false,
            interpolate: false,
            vsync_pacing: true,
            safe_gpu_shaders: true,
            turnip_sysmem: false,
            pooled_uploads: true,
            upload_size_budget: true,
            threaded_picture_copy: true,
            fast_texture_decode: true,
            async_pack_lookup: true,
            warm_vertex_programs: true,
            warm_pipelines: true,
            warm_before_start: false,
            shade_ahead: true,
            audio_queue_managed: true,
            low_latency_audio: false,
            latch_short_presses: true,
            short_vsync_queue: false,
            enhancements: Vec::new(),
        }
    }
}
impl Advanced {
    #[cfg(any(target_os = "android", test))]
    pub fn renderer_options(&self) -> zakuro_gpu::backend::RendererOptions {
        zakuro_gpu::backend::RendererOptions {
            gpu_shaders: match self.shader_mode {
                ShaderMode::Auto => None,
                ShaderMode::Gpu => Some(true),
                ShaderMode::Cpu => Some(false),
            },
            translate_shaders: self.shader_execution == ShaderExecution::Compiled,
            cpu_shader_jit: self.cpu_shader_jit,
            wait_for_shaders: self.shader_compilation == ShaderCompilation::Wait,
            generic_shaders: self.generic_shaders,
            depth_blits: self.depth_blits,
            float_depth: self.float_depth,
            static_blend: self.static_blend,
            pooled_uploads: self.pooled_uploads,
            upload_size_budget: self.upload_size_budget,
            threaded_picture_copy: self.threaded_picture_copy,
            fast_texture_decode: self.fast_texture_decode,
            async_pack_lookup: self.async_pack_lookup,
            warm_vertex_programs: self.warm_vertex_programs,
            warm_pipelines: self.warm_pipelines,
            // upstream 0.2.31's default; no Android setting for it yet
            shade_ahead: self.shade_ahead,
        }
    }
    pub fn interpolate_on_main(&self, split: bool) -> bool {
        self.interpolate
            && self.hardware_renderer
            && !self.gpu_readback
            && !(self.dual_display && split && self.swap_screens)
    }
    pub fn parse(json: &str) -> Result<Self, String> {
        if json.len() > 32 * 1024 {
            return Err("Advanced settings exceed 32 KiB".into());
        }
        let mut settings: Self =
            serde_json::from_str(json).map_err(|e| format!("Invalid advanced settings: {e}"))?;
        settings.validate();
        Ok(settings)
    }
    pub fn validate(&mut self) {
        fn bounded(value: f32, min: f32, max: f32, fallback: f32) -> f32 {
            if value.is_finite() {
                value.clamp(min, max)
            } else {
                fallback
            }
        }
        self.top_width = bounded(self.top_width, 320.0, 800.0, 400.0);
        self.gyro_sensitivity = bounded(self.gyro_sensitivity, 0.0, 5.0, 1.0);
        self.stick_motion_sensitivity = bounded(self.stick_motion_sensitivity, 0.0, 5.0, 1.0);
        self.cursor_speed = bounded(self.cursor_speed, 0.1, 5.0, 1.0);
        self.deadzone = bounded(self.deadzone, 0.0, 0.9, 0.15);
        self.volume = bounded(self.volume, 0.0, 1.0, 1.0);
        self.slider_3d = bounded(self.slider_3d, 0.0, 1.0, 0.0);
        self.region = self.region.clamp(-1, 6);
        self.language = self.language.clamp(0, 11);
        self.speed_limit = self.speed_limit.clamp(0, 400);
        self.fast_forward_speed = self.fast_forward_speed.clamp(0, 400);
        self.frame_cap = self.frame_cap.clamp(0, 240);
        self.enhancements.truncate(64);
        self.enhancements
            .retain(|s| !s.is_empty() && s.len() <= 128);
        self.controller_bindings
            .retain(|code, _| (0..=1024).contains(code));
    }
    pub fn binding(&self, code: i32) -> Option<Binding> {
        if let Some(binding) = self.controller_bindings.get(&code) {
            return Some(*binding);
        }
        use Binding::*;
        let binding = match code {
            96 => A,
            97 => B,
            99 => X,
            100 => Y,
            102 => L,
            103 => R,
            104 => Zl,
            105 => Zr,
            106 => MotionGate,
            108 => Start,
            109 => Select,
            19 => Up,
            20 => Down,
            21 => Left,
            22 => Right,
            _ => return Option::None,
        };
        Some(if self.button_mapping == ButtonMapping::Xbox {
            match binding {
                A => B,
                B => A,
                X => Y,
                Y => X,
                other => other,
            }
        } else {
            binding
        })
    }
    pub fn emulation_interval(&self, base: std::time::Duration) -> std::time::Duration {
        if self.speed_limit == 0 {
            std::time::Duration::ZERO
        } else {
            base.mul_f64(100.0 / f64::from(self.speed_limit.clamp(1, 400)))
        }
    }
    pub fn presentation_interval(&self) -> std::time::Duration {
        if self.frame_cap == 0 {
            std::time::Duration::ZERO
        } else {
            std::time::Duration::from_secs_f64(1.0 / f64::from(self.frame_cap))
        }
    }
    pub fn axis(&self, value: f32) -> f32 {
        if !value.is_finite() || value.abs() <= self.deadzone {
            0.0
        } else {
            value.signum() * ((value.abs() - self.deadzone) / (1.0 - self.deadzone)).min(1.0)
        }
    }
    pub fn gyro(&self, velocity: [f32; 3]) -> [f32; 3] {
        let invert = [self.gyro_invert_x, self.gyro_invert_y, self.gyro_invert_z];
        std::array::from_fn(|i| {
            velocity[i] * self.gyro_sensitivity * if invert[i] { -1.0 } else { 1.0 }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn upstream_renderer_controls_use_supported_options_and_reset_to_defaults() {
        let defaults = Advanced::default().renderer_options();
        assert_eq!(defaults.gpu_shaders, None);
        assert!(defaults.translate_shaders && defaults.cpu_shader_jit && defaults.depth_blits);
        assert!(
            !defaults.wait_for_shaders
                && !defaults.generic_shaders
                && !defaults.float_depth
                && !defaults.static_blend
        );
        let settings = Advanced::parse(r#"{"shader_mode":"cpu","shader_execution":"interpreted","cpu_shader_jit":false,"shader_compilation":"wait","generic_shaders":true,"depth_blits":false,"float_depth":true,"static_blend":true}"#).unwrap();
        let options = settings.renderer_options();
        assert_eq!(options.gpu_shaders, Some(false));
        assert!(!options.translate_shaders && !options.cpu_shader_jit && !options.depth_blits);
        assert!(
            options.wait_for_shaders
                && options.generic_shaders
                && options.float_depth
                && options.static_blend
        );
        assert_eq!(
            Advanced::parse(r#"{"shader_mode":"gpu"}"#)
                .unwrap()
                .renderer_options()
                .gpu_shaders,
            Some(true)
        );
        assert!(Advanced::parse(r#"{"shader_compilation":"fake"}"#).is_err());
        assert_eq!(Advanced::parse("{}").unwrap().renderer_options(), defaults);
    }
    #[test]
    fn old_preferences_keep_runtime_defaults() {
        assert_eq!(Advanced::parse("{}").unwrap(), Advanced::default());
        assert_eq!(
            Advanced::parse(r#"{"show_clock":true,"touch_controls":false}"#).unwrap(),
            Advanced::default()
        );
    }
    #[test]
    fn testing_toggles_default_to_the_measured_choices() {
        let settings = Advanced::parse("{}").unwrap();
        assert!(settings.vsync_pacing && settings.safe_gpu_shaders && !settings.turnip_sysmem);
        let off = Advanced::parse(r#"{"vsync_pacing":false,"safe_gpu_shaders":false,"turnip_sysmem":true}"#).unwrap();
        assert!(!off.vsync_pacing && !off.safe_gpu_shaders && off.turnip_sysmem);
        assert!(settings.audio_queue_managed && !settings.low_latency_audio);
        assert!(Advanced::parse(r#"{"low_latency_audio":true}"#).unwrap().low_latency_audio);
        assert!(settings.latch_short_presses);
        assert!(!Advanced::parse(r#"{"latch_short_presses":false}"#).unwrap().latch_short_presses);
        assert!(!settings.short_vsync_queue);
        assert!(Advanced::parse(r#"{"short_vsync_queue":true}"#).unwrap().short_vsync_queue);
        assert!(!Advanced::parse(r#"{"audio_queue_managed":false}"#).unwrap().audio_queue_managed);
        let options = settings.renderer_options();
        assert!(options.pooled_uploads && options.upload_size_budget && options.threaded_picture_copy && options.fast_texture_decode && options.async_pack_lookup);
        assert!(!Advanced::parse(r#"{"async_pack_lookup":false}"#).unwrap().renderer_options().async_pack_lookup);
        assert!(options.warm_vertex_programs && options.warm_pipelines);
        let cold = Advanced::parse(r#"{"warm_vertex_programs":false,"warm_pipelines":false}"#).unwrap().renderer_options();
        assert!(!cold.warm_vertex_programs && !cold.warm_pipelines);
        assert!(!settings.warm_before_start);
        assert!(Advanced::parse(r#"{"warm_before_start":true}"#).unwrap().warm_before_start);
        assert!(options.shade_ahead);
        assert!(!Advanced::parse(r#"{"shade_ahead":false}"#).unwrap().renderer_options().shade_ahead);
        let off = Advanced::parse(r#"{"pooled_uploads":false,"upload_size_budget":false,"threaded_picture_copy":false,"fast_texture_decode":false}"#).unwrap().renderer_options();
        assert!(!off.pooled_uploads && !off.upload_size_budget && !off.threaded_picture_copy && !off.fast_texture_decode);
    }
    #[test]
    fn speed_limit_defaults_clamps_and_scales_emulation_only() {
        assert_eq!(Advanced::parse("{}").unwrap().fast_forward_speed,150);
        assert_eq!(Advanced::parse(r#"{"fast_forward_speed":999}"#).unwrap().fast_forward_speed,400);
        assert_eq!(Advanced::parse(r#"{"fast_forward_speed":-1}"#).unwrap().fast_forward_speed,0);
        let base = std::time::Duration::from_millis(20);
        assert_eq!(Advanced::parse("{}").unwrap().speed_limit, 100);
        assert_eq!(Advanced::default().emulation_interval(base), base);
        for (json, limit, interval) in [
            (r#"{"speed_limit":0}"#, 0, std::time::Duration::ZERO),
            (r#"{"speed_limit":100}"#, 100, base),
              (r#"{"speed_limit":150}"#, 150, base.mul_f64(2.0 / 3.0)),
            (r#"{"speed_limit":400}"#, 400, base / 4),
            (r#"{"speed_limit":-1}"#, 0, std::time::Duration::ZERO),
            (r#"{"speed_limit":999}"#, 400, base / 4),
        ] {
            let settings = Advanced::parse(json).unwrap();
            assert_eq!(settings.speed_limit, limit);
            assert_eq!(settings.emulation_interval(base), interval);
            assert_eq!(settings.presentation_interval(), Advanced::default().presentation_interval());
            assert_eq!(Advanced::parse(&serde_json::to_string(&settings).unwrap()).unwrap(), settings);
        }
    }
    #[test]
    fn typed_settings_clamp_and_round_trip() {
        let settings = Advanced::parse(r#"{"top_width":900,"deadzone":2,"volume":-1,"frame_cap":999,"region":-9,"layout":"large_top","aot":false,"enhancements":["60 FPS"]}"#).unwrap();
        assert_eq!(
            (
                settings.top_width,
                settings.deadzone,
                settings.volume,
                settings.frame_cap,
                settings.region
            ),
            (800.0, 0.9, 0.0, 240, -1)
        );
        assert!(!settings.aot);
        assert_eq!(
            settings,
            Advanced::parse(&serde_json::to_string(&settings).unwrap()).unwrap()
        );
        assert!(Advanced::parse(r#"{"layout":"fake"}"#).is_err());
        assert!(Advanced::parse(r#"{"volume":"loud"}"#).is_err());
    }
    #[test]
    fn deadzone_and_gyro_controls_apply() {
        let settings =
            Advanced::parse(r#"{"deadzone":0.25,"gyro_sensitivity":2,"gyro_invert_y":true}"#)
                .unwrap();
        assert_eq!(settings.axis(0.25), 0.0);
        assert!((settings.axis(0.625) - 0.5).abs() < 1e-6);
        assert_eq!(settings.gyro([1.0, 2.0, 3.0]), [2.0, -4.0, 6.0]);
    }
    #[test]
    fn bindings_are_typed_and_presentation_cap_can_be_disabled() {
        let settings = Advanced::parse(
            r#"{"controller_bindings":{"-1":"a","96":"motion_gate","2048":"b"},"frame_cap":0}"#,
        )
        .unwrap();
        assert_eq!(settings.controller_bindings.len(), 1);
        assert_eq!(settings.binding(96), Some(Binding::MotionGate));
        assert_eq!(settings.presentation_interval(), std::time::Duration::ZERO);
        assert!(Advanced::parse(r#"{"controller_bindings":{"96":"fake"}}"#).is_err());
        assert_eq!(Advanced::default().binding(96), Some(Binding::A));
        assert_eq!(
            Advanced::default().presentation_interval(),
            std::time::Duration::from_secs_f64(1.0 / 60.0)
        );
    }
    #[test]
    fn interpolation_requires_hardware_images_on_the_main_display() {
        let mut settings = Advanced {
            interpolate: true,
            dual_display: true,
            ..Advanced::default()
        };
        assert!(settings.interpolate_on_main(true));
        settings.swap_screens = true;
        assert!(!settings.interpolate_on_main(true));
        assert!(settings.interpolate_on_main(false));
        settings.gpu_readback = true;
        assert!(!settings.interpolate_on_main(false));
        settings.gpu_readback = false;
        settings.hardware_renderer = false;
        assert!(!settings.interpolate_on_main(false));
    }
}
