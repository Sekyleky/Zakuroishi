//! Java calls only enqueue commands and input. The emulator stays on android_main.
use android_activity::{AndroidApp, AndroidAppWaker};
use jni::{
    objects::{JObject, JString},
    sys::{jboolean, jfloat, jint},
    JNIEnv,
};
use std::{collections::VecDeque, sync::Mutex};

#[derive(Clone, Copy)]
pub enum Command {
    Pause,
    Resume,
    Restart,
    /// Slot 0 is the quick state; 1 to 9 are the numbered slots.
    Save(u8),
    Load(u8),
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
    /// the software keyboard's text and the button pressed, once typed.
    keyboard: Option<(String, usize)>,
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
    keyboard: None,
});

pub struct Session;
impl Session {
    pub fn start(app: &AndroidApp) -> Self {
        let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
        state.commands.clear();
        state.input = VirtualInput::default();
        state.waker = Some(app.create_waker());
        state.keyboard = None;
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

/// the software keyboard's answer, once the dialog closed.
pub fn keyboard_answer() -> Option<(String, usize)> {
    STATE.lock().unwrap_or_else(|e| e.into_inner()).keyboard.take()
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeKeyboard(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    text: JString<'_>,
    button: jint,
) {
    let text: String = env.get_string(&text).map(Into::into).unwrap_or_default();
    let mut state = STATE.lock().unwrap_or_else(|e| e.into_inner());
    state.keyboard = Some((text, button.max(0) as usize));
    if let Some(waker) = &state.waker {
        waker.wake();
    }
}

/// Low 4 bits are the command id, the rest is the save-state slot (0 for plain SAVE/LOAD).
fn decode(command: i32) -> Option<Command> {
    let slot = u8::try_from(command >> 4).ok().filter(|slot| *slot <= 9)?;
    Some(match command & 15 {
        0 => Command::Pause,
        1 => Command::Save(slot),
        2 => Command::Load(slot),
        3 => Command::Restart,
        4 => Command::Recalibrate,
        5 => Command::Swap,
        6 => Command::Resume,
        7 => Command::Exit,
        _ => return None,
    })
}
#[no_mangle]
pub extern "system" fn Java_io_github_zakuro_android_Game_nativeCommand(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    command: jint,
) {
    let Some(command) = decode(command) else {
        return;
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn command_ids_carry_the_state_slot() {
        assert!(matches!(decode(1), Some(Command::Save(0))));
        assert!(matches!(decode(2), Some(Command::Load(0))));
        assert!(matches!(decode(1 | 3 << 4), Some(Command::Save(3))));
        assert!(matches!(decode(2 | 9 << 4), Some(Command::Load(9))));
        assert!(decode(1 | 10 << 4).is_none() && decode(8).is_none() && decode(-1).is_none());
        assert!(matches!(decode(7), Some(Command::Exit)));
    }
}
