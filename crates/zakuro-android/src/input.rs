//! Platform-independent controller mapping and motion/cursor configuration.
use crate::settings::{Advanced, Binding, LeftStick, RightStick};
use std::collections::BTreeSet;
use zakuro_core::services::hid::{InputState, MotionSample, PadState};

#[derive(Clone, Default)]
pub struct VirtualInput {
    pub buttons: u32,
    pub touch: Option<(u16, u16)>,
    pub sticks: [Option<[f32; 2]>; 2],
    pub keys: BTreeSet<i32>,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn all_seven_motion_gates_and_independent_tilt_inversion_work() {
        use crate::settings::TiltButton;
        for gate in [
            TiltButton::Always,
            TiltButton::L1,
            TiltButton::L2,
            TiltButton::R1,
            TiltButton::R2,
            TiltButton::L3,
            TiltButton::R3,
        ] {
            let settings = Advanced {
                stick_motion: true,
                tilt_button: gate,
                reverse_tilt_x: true,
                reverse_tilt_y: true,
                ..Advanced::default()
            };
            let mut input = Input::default();
            input.state.c_stick_x = 1.0;
            input.state.c_stick_y = 1.0;
            if let Some(code) = gate.keycode() {
                assert_eq!(
                    input
                        .configured(&settings, &VirtualInput::default(), None)
                        .tilt,
                    [0.0, 0.0]
                );
                input.keys.insert(code);
            }
            let result = input.configured(&settings, &VirtualInput::default(), None);
            assert_eq!(result.tilt, [-1.0, 1.0]);
            assert_eq!(result.buttons, PadState::empty());
            assert_eq!((result.c_stick_x, result.c_stick_y), (0.0, 0.0));
            if gate.guest_bits() != 0 {
                input.keys.clear();
                let virtual_input = VirtualInput {
                    buttons: gate.guest_bits(),
                    ..VirtualInput::default()
                };
                assert_eq!(
                    input.configured(&settings, &virtual_input, None).tilt,
                    [-1.0, 1.0]
                );
            }
        }
    }

    #[test]
    fn explicit_bindings_override_presets_and_virtual_masks_stay_guest_buttons() {
        let settings = Advanced::parse(
            r#"{"button_mapping":"xbox","controller_bindings":{"96":"x","97":"none","19":"zr"}}"#,
        )
        .unwrap();
        let mut input = Input::default();
        input.keys.extend([96, 97, 99, 19]);
        input.axes.insert(19);
        let virtual_input = VirtualInput {
            buttons: PadState::A.bits(),
            ..VirtualInput::default()
        };
        let result = input.configured(&settings, &virtual_input, None);
        assert_eq!(
            result.buttons,
            PadState::A | PadState::X | PadState::Y | PadState::ZR
        );
        // Two physical inputs bound to one guest button must release independently.
        let settings = Advanced::parse(r#"{"controller_bindings":{"96":"a","97":"a"}}"#).unwrap();
        input.keys = [96, 97].into_iter().collect();
        input.axes.clear();
        input.keys.remove(&96);
        assert_eq!(
            input
                .configured(&settings, &VirtualInput::default(), None)
                .buttons,
            PadState::A
        );
    }

    #[test]
    fn sticks_motion_gate_and_device_gyro_have_distinct_paths() {
        let settings = Advanced::parse(r#"{"left_stick":"dpad","stick_motion":true,"gyro_sensitivity":2,"gyro_invert_y":true,"stick_motion_sensitivity":3}"#).unwrap();
        let mut input = Input::default();
        input.state.circle_x = 1.0;
        input.state.c_stick_y = 1.0;
        let motion = Some(MotionSample {
            acceleration: [0.0, -1.0, 0.0],
            angular_velocity: [1.0, 2.0, 3.0],
        });
        let result = input.configured(&settings, &VirtualInput::default(), motion);
        assert_eq!(result.circle_x, 0.0);
        assert!(result.buttons.contains(PadState::RIGHT));
        assert_eq!(result.motion.unwrap().angular_velocity, [2.0, -4.0, 6.0]);
        assert_eq!(result.c_stick_y, 1.0);
        input.keys.insert(106);
        let result = input.configured(&settings, &VirtualInput::default(), motion);
        assert_eq!(result.motion, None);
        assert_eq!(result.tilt, [0.0, -3.0]);
        assert_eq!(result.c_stick_y, 0.0);
        let settings = Advanced {
            motion_enabled: false,
            ..Advanced::default()
        };
        assert_eq!(
            input
                .configured(&settings, &VirtualInput::default(), motion)
                .motion,
            None
        );
    }

    #[test]
    fn cursor_click_release_and_stick_release_do_not_leave_stale_input() {
        let settings = Advanced {
            right_stick: RightStick::Cursor,
            ..Advanced::default()
        };
        let mut input = Input::default();
        let virtual_input = VirtualInput {
            sticks: [Some([0.0, 0.0]), Some([1.0, 1.0])],
            buttons: PadState::ZR.bits(),
            ..VirtualInput::default()
        };
        let result = input.configured(&settings, &virtual_input, None);
        assert_eq!(result.touch, Some((164, 116)));
        assert!(!result.buttons.contains(PadState::ZR));
        assert_eq!((result.c_stick_x, result.c_stick_y), (0.0, 0.0));
        let result = input.configured(&settings, &VirtualInput::default(), None);
        assert_eq!(result.touch, None);
        assert_eq!(input.cursor, [164.0, 116.0]);
    }
}
pub struct Input {
    pub state: InputState,
    pub keys: BTreeSet<i32>,
    pub axes: BTreeSet<i32>,
    #[cfg(target_os = "android")]
    pub finger: Option<i32>,
    pub cursor: [f32; 2],
}
impl Default for Input {
    fn default() -> Self {
        Self {
            state: InputState::default(),
            keys: BTreeSet::new(),
            axes: BTreeSet::new(),
            #[cfg(target_os = "android")]
            finger: None,
            cursor: [160.0, 120.0],
        }
    }
}
impl Input {
    pub fn configured(
        &mut self,
        settings: &Advanced,
        virtual_input: &VirtualInput,
        motion: Option<MotionSample>,
    ) -> InputState {
        let mut input = self.state;
        input.buttons = PadState::from_bits_truncate(virtual_input.buttons);
        let selected_gate = settings.tilt_button.keycode();
        let gated = settings.stick_motion && settings.stick_motion_gate;
        let mut motion_gate = selected_gate.is_none()
            || (settings.tilt_button.guest_bits() & virtual_input.buttons != 0);
        for code in self.keys.union(&virtual_input.keys).chain(&self.axes) {
            let gate_key = selected_gate == Some(*code)
                || settings.controller_bindings.get(code) == Some(&Binding::MotionGate);
            motion_gate |= gate_key;
            if let Some(binding) = settings.binding(*code) {
                if !(gated && gate_key) {
                    input.buttons |= PadState::from_bits_truncate(binding.bits());
                }
            }
        }
        if gated && motion_gate {
            input.buttons.remove(PadState::from_bits_truncate(
                settings.tilt_button.guest_bits(),
            ));
        }
        let [left_x, left_y] = virtual_input.sticks[0]
            .unwrap_or([input.circle_x, input.circle_y])
            .map(|v| settings.axis(v));
        let [right_x, right_y] = virtual_input.sticks[1]
            .unwrap_or([input.c_stick_x, input.c_stick_y])
            .map(|v| settings.axis(v));
        input.circle_x = left_x;
        input.circle_y = left_y;
        input.c_stick_x = right_x;
        input.c_stick_y = right_y;
        if settings.left_stick == LeftStick::Dpad {
            input.buttons |= zakuro_core::services::hid::directions(
                left_x,
                left_y,
                [
                    PadState::RIGHT,
                    PadState::LEFT,
                    PadState::UP,
                    PadState::DOWN,
                ],
            );
            input.circle_x = 0.0;
            input.circle_y = 0.0;
        }
        input.touch = virtual_input.touch.or(input.touch);
        input.motion = motion
            .filter(|_| settings.motion_enabled)
            .map(|mut sample| {
                sample.angular_velocity = settings.gyro(sample.angular_velocity);
                sample
            });
        let stick_motion = settings.stick_motion && (!settings.stick_motion_gate || motion_gate);
        if stick_motion {
            input.motion = None;
            input.tilt = [
                right_x
                    * settings.stick_motion_sensitivity
                    * if settings.reverse_tilt_x { -1.0 } else { 1.0 },
                -right_y
                    * settings.stick_motion_sensitivity
                    * if settings.reverse_tilt_y { -1.0 } else { 1.0 },
            ];
            input.c_stick_x = 0.0;
            input.c_stick_y = 0.0;
        } else if settings.right_stick == RightStick::Cursor {
            self.cursor[0] =
                (self.cursor[0] + right_x * settings.cursor_speed * 4.0).clamp(0.0, 319.0);
            self.cursor[1] =
                (self.cursor[1] - right_y * settings.cursor_speed * 4.0).clamp(0.0, 239.0);
            if input.buttons.contains(PadState::ZR) {
                input.touch = Some((self.cursor[0] as u16, self.cursor[1] as u16));
            }
            input.buttons.remove(PadState::ZR);
            input.c_stick_x = 0.0;
            input.c_stick_y = 0.0;
        }
        input
    }
}
