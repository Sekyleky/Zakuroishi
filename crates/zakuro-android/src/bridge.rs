//! Java calls only enqueue commands and input. The emulator stays on android_main.
use android_activity::{AndroidApp, AndroidAppWaker};
use jni::{
    objects::JObject,
    sys::{jboolean, jfloat, jint},
    JNIEnv,
};
use std::{collections::VecDeque, sync::Mutex};

#[derive(Clone, Copy)]
pub enum Command {
    Pause,
    Resume,
    Restart,
    Save,
    Load,
    Recalibrate,
    Swap,
    Exit,
}
use crate::input::VirtualInput;
struct State {
    commands: VecDeque<Command>,
    input: VirtualInput,
    waker: Option<AndroidAppWaker>,
    second_window: Option<ndk::native_window::NativeWindow>,
    second_changed: bool,
    second_touch: Option<(f32, f32, u32, u32)>,
}
static STATE: Mutex<State> = Mutex::new(State {
    commands: VecDeque::new(),
    input: VirtualInput {
        buttons: 0,
        touch: None,
        sticks: [None; 2],
        keys: std::collections::BTreeSet::new(),
    },
    waker: None,
    second_window: None,
    second_changed: false,
    second_touch: None,
});

pub struct Session;
impl Session {
    pub fn start(app: &AndroidApp) -> Self {
        let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
        state.commands.clear();
        state.input = VirtualInput::default();
        state.waker = Some(app.create_waker());
        Self
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
        state.waker = None;
        state.second_window = None;
        state.second_changed = false;
        state.second_touch = None;
        state.input = VirtualInput::default();
        state.commands.clear();
    }
}
pub fn pending() -> Vec<Command> {
    STATE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .commands
        .drain(..)
        .collect()
}
pub fn input() -> VirtualInput {
    STATE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .input
        .clone()
}
pub fn clear_input() {
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.input = VirtualInput::default();
    state.second_touch = None;
}

#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeCommand(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    command: jint,
) {
    let command = match command {
        0 => Command::Pause,
        1 => Command::Save,
        2 => Command::Load,
        3 => Command::Restart,
        4 => Command::Recalibrate,
        5 => Command::Swap,
        6 => Command::Resume,
        7 => Command::Exit,
        _ => return,
    };
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    if state.commands.len() >= 32 {
        state.commands.pop_front();
    }
    state.commands.push_back(command);
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeTouch(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    mask: jint,
    x: jfloat,
    y: jfloat,
) {
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.input.buttons = mask as u32 & 0xCFFF;
    state.input.touch =
        (x.is_finite() && y.is_finite() && (0.0..320.0).contains(&x) && (0.0..240.0).contains(&y))
            .then_some((x as u16, y as u16));
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeStick(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    stick: jint,
    x: jfloat,
    y: jfloat,
) {
    if !(0..2).contains(&stick) {
        return;
    }
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.input.sticks[stick as usize] =
        (x.is_finite() && y.is_finite()).then_some([x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0)]);
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeButton(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    code: jint,
    pressed: jboolean,
) {
    if !(0..=1024).contains(&code) {
        return;
    }
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    if pressed != 0 {
        state.input.keys.insert(code);
    } else {
        state.input.keys.remove(&code);
    }
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}

/// Replacing a surface retains an ANativeWindow reference until android_main consumes it.
pub fn second_surface() -> Option<Option<ndk::native_window::NativeWindow>> {
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    if !state.second_changed {
        return None;
    }
    state.second_changed = false;
    Some(state.second_window.take())
}
pub fn second_touch() -> Option<(f32, f32, u32, u32)> {
    STATE.lock().unwrap_or_else(|e| e.into_inner()).second_touch
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeSecondSurface(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    surface: JObject<'_>,
) {
    let window = if surface.is_null() {
        None
    } else {
        unsafe {
            ndk::native_window::NativeWindow::from_surface(
                env.get_native_interface().cast(),
                surface.as_raw().cast(),
            )
        }
    };
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.second_window = window;
    state.second_changed = true;
    state.second_touch = None;
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeSecondTouch(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    pressed: jboolean,
    x: jfloat,
    y: jfloat,
    width: jint,
    height: jint,
) {
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.second_touch = (pressed != 0
        && x.is_finite()
        && y.is_finite()
        && width > 0
        && height > 0)
        .then_some((x, y, width.max(0) as u32, height.max(0) as u32));
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}
