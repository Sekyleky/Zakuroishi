use crate::{audio::Audio, platform, sensors::Sensors, transform};
use android_activity::{
    input::{Axis, InputEvent, KeyAction, Keycode, MotionAction, Source},
    AndroidApp, InputStatus, MainEvent, PollEvent,
};
use ndk::native_window::NativeWindow;
use raw_window_handle::{
    AndroidDisplayHandle, DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle,
    WindowHandle,
};
use std::time::{Duration, Instant};
use zakuro_common::Screen;
use zakuro_core::{
    services::hid::{InputState, PadState},
    Config, FrameOutcome, System,
};
use zakuro_gpu::{
    backend::vulkan::VulkanPresenter, GpuScreen, Overlay, PresentError, Presenter, ScreenImage,
    ScreenLayout,
};

// Keep a NativeWindow reference until its Vulkan surface has been destroyed.
struct Window(NativeWindow);
impl HasWindowHandle for Window {
    fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
        self.0.window_handle()
    }
}
impl HasDisplayHandle for Window {
    fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
        Ok(unsafe { DisplayHandle::borrow_raw(AndroidDisplayHandle::new().into()) })
    }
}
struct Surface {
    presenter: VulkanPresenter,
    window: Window,
    shared: bool,
}
impl Surface {
    fn new(window: NativeWindow) -> Result<Self, String> {
        let window = Window(window);
        let size = (
            window.0.width().max(1) as u32,
            window.0.height().max(1) as u32,
        );
        let mut presenter = VulkanPresenter::new(&window, size).map_err(|e| {
            format!("Could not start Vulkan. Zakuro needs a Vulkan 1.3 capable driver. {e}")
        })?;
        presenter.set_layout(ScreenLayout::Stacked);
        Ok(Self {
            presenter,
            window,
            shared: false,
        })
    }
    fn resize(&mut self) {
        self.presenter.resize(
            self.window.0.width().max(1) as u32,
            self.window.0.height().max(1) as u32,
        );
    }
    fn touch(&self, x: f32, y: f32) -> Option<(u16, u16)> {
        let (_, bottom) = zakuro_gpu::layout(
            self.window.0.width().max(1) as u32,
            self.window.0.height().max(1) as u32,
            ScreenLayout::Stacked,
            400.0,
            false,
        );
        let r = bottom?;
        transform::touch(x, y, [r.x, r.y, r.width, r.height])
    }
    fn present(&mut self, system: &mut System) -> Result<(), PresentError> {
        let (top, top_gpu) = picture(system, Screen::Top, self.shared);
        let (bottom, bottom_gpu) = picture(system, Screen::Bottom, self.shared);
        self.presenter.present(
            ScreenImage {
                pixels: &top.0,
                width: top.1,
                height: top.2,
                gpu: top_gpu,
            },
            ScreenImage {
                pixels: &bottom.0,
                width: bottom.1,
                height: bottom.2,
                gpu: bottom_gpu,
            },
            ScreenImage {
                pixels: &[],
                width: 0,
                height: 0,
                gpu: None,
            },
            &Overlay::default(),
        )
    }
}
fn picture(
    system: &mut System,
    screen: Screen,
    shared: bool,
) -> (zakuro_core::Screen, Option<GpuScreen>) {
    if shared {
        if let Some(gpu) = system.gpu_screen(screen) {
            let scale = system.gpu.scale();
            return (
                (
                    std::sync::Arc::new(Vec::new()),
                    screen.width() * scale,
                    screen.height() * scale,
                ),
                Some(gpu),
            );
        }
    }
    (system.read_screen_scaled(screen), None)
}

fn button(code: Keycode) -> Option<PadState> {
    Some(match code {
        Keycode::ButtonA => PadState::A,
        Keycode::ButtonB => PadState::B,
        Keycode::ButtonX => PadState::X,
        Keycode::ButtonY => PadState::Y,
        Keycode::ButtonL1 => PadState::L,
        Keycode::ButtonR1 => PadState::R,
        Keycode::ButtonL2 => PadState::ZL,
        Keycode::ButtonR2 => PadState::ZR,
        Keycode::ButtonStart => PadState::START,
        Keycode::ButtonSelect => PadState::SELECT,
        Keycode::DpadUp => PadState::UP,
        Keycode::DpadDown => PadState::DOWN,
        Keycode::DpadLeft => PadState::LEFT,
        Keycode::DpadRight => PadState::RIGHT,
        _ => return None,
    })
}
#[derive(Default)]
struct Input {
    state: InputState,
    keys: PadState,
    axes: PadState,
    finger: Option<i32>,
}
impl Input {
    fn read(&mut self, app: &AndroidApp, surface: Option<&Surface>) {
        let Ok(mut events) = app.input_events_iter() else {
            return;
        };
        while events.next(|event| {
            match event {
                InputEvent::KeyEvent(key) => {
                    let Some(button) = button(key.key_code()) else {
                        return InputStatus::Unhandled;
                    };
                    match key.action() {
                        KeyAction::Down => self.keys.insert(button),
                        KeyAction::Up => self.keys.remove(button),
                        _ => return InputStatus::Unhandled,
                    }
                }
                InputEvent::MotionEvent(motion) => {
                    let source: u32 = motion.source().into();
                    if source & u32::from(Source::Joystick) == u32::from(Source::Joystick) {
                        if motion.pointer_count() == 0 {
                            return InputStatus::Unhandled;
                        }
                        let p = motion.pointer_at_index(0);
                        self.state.circle_x = transform::stick(p.axis_value(Axis::X));
                        self.state.circle_y = -transform::stick(p.axis_value(Axis::Y));
                        self.state.c_stick_x = transform::stick(p.axis_value(Axis::Z));
                        self.state.c_stick_y = -transform::stick(p.axis_value(Axis::Rz));
                        self.axes = PadState::empty();
                        for (pressed, bit) in [
                            (p.axis_value(Axis::HatX) < -0.5, PadState::LEFT),
                            (p.axis_value(Axis::HatX) > 0.5, PadState::RIGHT),
                            (p.axis_value(Axis::HatY) < -0.5, PadState::UP),
                            (p.axis_value(Axis::HatY) > 0.5, PadState::DOWN),
                            (
                                p.axis_value(Axis::Ltrigger).max(p.axis_value(Axis::Brake)) > 0.5,
                                PadState::ZL,
                            ),
                            (
                                p.axis_value(Axis::Rtrigger).max(p.axis_value(Axis::Gas)) > 0.5,
                                PadState::ZR,
                            ),
                        ] {
                            if pressed {
                                self.axes.insert(bit);
                            }
                        }
                    } else if source & u32::from(Source::Touchscreen)
                        == u32::from(Source::Touchscreen)
                    {
                        match motion.action() {
                            MotionAction::Cancel | MotionAction::Up => {
                                self.finger = None;
                                self.state.touch = None;
                            }
                            MotionAction::PointerUp => {
                                if motion.pointer_index() < motion.pointer_count()
                                    && Some(
                                        motion
                                            .pointer_at_index(motion.pointer_index())
                                            .pointer_id(),
                                    ) == self.finger
                                {
                                    self.finger = None;
                                    self.state.touch = None;
                                }
                            }
                            MotionAction::Down | MotionAction::PointerDown | MotionAction::Move => {
                                for p in motion.pointers() {
                                    let position = surface.and_then(|s| s.touch(p.x(), p.y()));
                                    if self.finger == Some(p.pointer_id())
                                        || (self.finger.is_none() && position.is_some())
                                    {
                                        self.finger = Some(p.pointer_id());
                                        self.state.touch = position;
                                        break;
                                    }
                                }
                            }
                            _ => return InputStatus::Unhandled,
                        }
                    } else {
                        return InputStatus::Unhandled;
                    }
                }
                _ => return InputStatus::Unhandled,
            }
            self.state.buttons = self.keys | self.axes;
            InputStatus::Handled
        }) {}
    }
}

pub fn run(app: &AndroidApp) -> Result<(), String> {
    let launch = platform::launch(app)?;
    for axis in [
        Axis::Z,
        Axis::Rz,
        Axis::HatX,
        Axis::HatY,
        Axis::Ltrigger,
        Axis::Rtrigger,
        Axis::Brake,
        Axis::Gas,
    ] {
        app.enable_motion_axis(axis);
    }
    let mut surface: Option<Surface> = None;
    let mut system: Option<System> = None;
    let mut audio: Option<Audio> = None;
    let mut sensors = Sensors::new();
    let mut input = Input::default();
    let (mut resumed, mut focused, mut destroy) = (false, false, false);
    let mut rotation = platform::rotation(app).unwrap_or(0);
    let mut failure = None;
    let frame_time = Duration::from_secs_f64(zakuro_core::CYCLES_PER_FRAME as f64 / 268_111_856.0);
    let mut next_frame = Instant::now();
    let mut retry_audio = Instant::now();
    let mut benchmark_start = Instant::now();
    let mut benchmark_frames = 0u32;
    let mut core_time = Duration::ZERO;
    let mut present_time = Duration::ZERO;
    loop {
        let active = resumed && focused && surface.is_some();
        let timeout = if active {
            next_frame.saturating_duration_since(Instant::now())
        } else {
            Duration::from_millis(250)
        };
        app.poll_events(Some(timeout), |event| {
            let PollEvent::Main(event) = event else {
                return;
            };
            match event {
                MainEvent::InitWindow { .. } => {
                    if let Some(window) = app.native_window() {
                        match Surface::new(window) {
                            Ok(s) => {
                                if system.is_some() {
                                    // shortcut: the renderer retains its original device; use readback until upstream supports surface rebinding.
                                    log::warn!("window recreated: presentation now uses GPU readback on the new Vulkan device");
                                }
                                surface = Some(s);
                                next_frame = Instant::now();
                            }
                            Err(e) => failure = Some(e),
                        }
                    }
                }
                MainEvent::TerminateWindow { .. } => {
                    surface = None;
                    audio = None;
                    input = Input::default();
                }
                MainEvent::WindowResized { .. } | MainEvent::ContentRectChanged { .. } => {
                    if let Some(s) = &mut surface {
                        s.resize();
                    }
                    input.state.touch = None;
                    input.finger = None;
                }
                MainEvent::ConfigChanged { .. } => {
                    rotation = platform::rotation(app).unwrap_or(rotation);
                    if let Some(s) = &mut surface {
                        s.resize();
                    }
                }
                MainEvent::Resume { .. } => {
                    resumed = true;
                    next_frame = Instant::now();
                }
                MainEvent::GainedFocus => {
                    focused = true;
                    next_frame = Instant::now();
                }
                MainEvent::LostFocus => {
                    focused = false;
                    audio = None;
                    input = Input::default();
                }
                MainEvent::Pause | MainEvent::Stop => {
                    resumed = false;
                    audio = None;
                    input = Input::default();
                }
                MainEvent::Destroy => {
                    destroy = true;
                    surface = None;
                    audio = None;
                }
                MainEvent::InputAvailable => input.read(app, surface.as_ref()),
                MainEvent::RedrawNeeded { .. } => {
                    if let (Some(s), Some(system)) = (&mut surface, &mut system) {
                        if let Err(e) = s.present(system) {
                            if !matches!(e, PresentError::OutOfDate) {
                                failure = Some(e.to_string());
                            }
                        }
                    }
                }
                _ => {}
            }
            if let Some(sensors) = &mut sensors {
                sensors.set_active(resumed && focused && surface.is_some());
            }
            if !(resumed && focused && surface.is_some()) {
                benchmark_frames = 0;
                core_time = Duration::ZERO;
                present_time = Duration::ZERO;
            }
        });
        if let Some(error) = failure.take() {
            return Err(error);
        }
        if destroy {
            break;
        }
        if !(resumed && focused && surface.is_some()) {
            continue;
        }
        let s = surface.as_mut().unwrap();
        if system.is_none() {
            let device = s.presenter.shared_device();
            s.shared = device.is_some();
            let config = Config {
                recompiled: Some(launch.code.clone()),
                data_dir: Some(launch.data.clone()),
                hardware_renderer: true,
                texture_packs: launch.texture_packs,
                resolution: launch.resolution,
                device,
                ..Config::default()
            };
            let loaded =
                zakuro_core::loader::load(&launch.game, config).map_err(|e| e.to_string())?;
            if loaded.recompiled.is_none() {
                return Err(
                    "the supplied native title library could not be loaded; see logcat".into(),
                );
            }
            if !loaded.gpu.draws_on_gpu() {
                return Err("Could not start the Vulkan hardware renderer. Zakuro needs a Vulkan 1.3 capable driver; see Zakuro logcat for the device error.".into());
            }
            if !loaded.gpu.shows_directly() {
                log::warn!("Vulkan renderer is not sharing the presentation device");
            }
            system = Some(loaded);
            next_frame = Instant::now();
        }
        if Instant::now() < next_frame {
            continue;
        }
        input.read(app, Some(s));
        input.state.motion = sensors.as_mut().and_then(|s| s.sample(rotation));
        let system = system.as_mut().unwrap();
        system.set_input(input.state);
        if benchmark_frames == 0 {
            benchmark_start = Instant::now();
        }
        let core_start = Instant::now();
        let outcome = system.run_frame();
        core_time += core_start.elapsed();
        let sound = system.take_audio();
        if audio.as_ref().is_some_and(Audio::disconnected) {
            audio = None;
        }
        if audio.is_none() && Instant::now() >= retry_audio {
            audio = Audio::open()
                .inspect_err(|e| log::warn!("AAudio: {e}"))
                .ok();
            retry_audio = Instant::now() + Duration::from_secs(2);
        }
        if let Some(audio) = &audio {
            audio.push(&sound);
        }
        match outcome {
            FrameOutcome::Completed => {}
            FrameOutcome::Exited => break,
            FrameOutcome::Faulted => return Err(format!("game faulted: {}", system.debug_output)),
        }
        let present_start = Instant::now();
        match s.present(system) {
            Ok(()) | Err(PresentError::OutOfDate) => {}
            Err(e) => return Err(e.to_string()),
        }
        present_time += present_start.elapsed();
        benchmark_frames += 1;
        if benchmark_frames == 300 {
            log::info!("benchmark frames=300 fps={:.2} core_ms={:.3} present_ms={:.3} resolution={} textures={} shared={}",
                300.0 / benchmark_start.elapsed().as_secs_f64(), core_time.as_secs_f64() * 1000.0 / 300.0,
                present_time.as_secs_f64() * 1000.0 / 300.0, launch.resolution, launch.texture_packs, s.shared);
            benchmark_frames = 0;
            core_time = Duration::ZERO;
            present_time = Duration::ZERO;
        }
        // No catch-up burst after a slow frame or lifecycle transition.
        next_frame = (next_frame + frame_time).max(Instant::now());
    }
    Ok(())
}
