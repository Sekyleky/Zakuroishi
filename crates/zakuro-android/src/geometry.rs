use crate::settings::{Advanced, Filter, Layout, PictureEffect, ScreenRotation, StereoMode};
use zakuro_gpu::backend::ScreenOrientation;
use zakuro_gpu::{ScreenFilter, ScreenLayout, Shown, Stereo, Viewport};

pub fn display(settings: &Advanced, external: bool, connected: bool) -> Advanced {
    let mut result = settings.clone();
    if settings.dual_display && connected {
        result.layout = if external {
            Layout::BottomOnly
        } else {
            Layout::TopOnly
        };
        if external {
            result.stretch = settings.external_stretch;
        }
        if external != settings.swap_screens {
            result.stereo = StereoMode::Off;
        }
    }
    result
}

pub fn layout(settings: &Advanced) -> ScreenLayout {
    match settings.layout {
        Layout::Stacked => ScreenLayout::Stacked,
        Layout::SideBySide => ScreenLayout::SideBySide,
        Layout::TopOnly => ScreenLayout::TopOnly,
        Layout::BottomOnly => ScreenLayout::BottomOnly,
        Layout::LargeTop => ScreenLayout::LargeTop,
        Layout::Hybrid => ScreenLayout::Hybrid,
        Layout::Custom => ScreenLayout::Custom,
    }
}
pub fn spacing(settings: &Advanced) -> zakuro_gpu::backend::ScreenSpacing {
    zakuro_gpu::backend::ScreenSpacing {
        gap: settings.screen_gap as f32,
        small: settings.small_screen as f32 / 100.0,
        custom: [[settings.top_x, settings.top_y, settings.top_size], [settings.bottom_x, settings.bottom_y, settings.bottom_size]].map(|place| place.map(|percent| percent as f32 / 100.0)),
    }
}
pub fn filter(settings: &Advanced) -> ScreenFilter {
    match settings.filter {
        Filter::Smooth => ScreenFilter::Smooth,
        Filter::Sharp => ScreenFilter::Sharp,
        Filter::Pixels => ScreenFilter::Pixels,
    }
}
pub fn effect(settings: &Advanced) -> (zakuro_gpu::PictureEffect, f32) {
    use zakuro_gpu::PictureEffect as Effect;
    let effect = match settings.picture_effect {
        PictureEffect::None => Effect::None,
        PictureEffect::Sharpen => Effect::Sharpen,
        PictureEffect::VividColours => Effect::Vivid,
        PictureEffect::Scanlines => Effect::Scanlines,
        PictureEffect::SmoothEdges => Effect::Smooth,
        PictureEffect::Custom => Effect::Custom,
    };
    (effect, settings.effect_strength as f32 / 100.0)
}
/// the colour around the screens as RGB, 0 to 1.
pub fn background(settings: &Advanced) -> [f32; 3] {
    settings.background_rgb().map(|c| c as f32 / 255.0)
}
pub fn stereo(settings: &Advanced) -> Stereo {
    use zakuro_gpu::StereoMode as Mode;
    Stereo {
        swap: settings.stereo_swap,
        mode: match settings.stereo {
            StereoMode::Off => Mode::Off,
            StereoMode::Anaglyph => Mode::Anaglyph,
            StereoMode::SideBySide => Mode::SideBySide,
            StereoMode::HalfSideBySide => Mode::HalfSideBySide,
            StereoMode::Interlaced => Mode::Interlaced,
        },
    }
}
pub fn places(settings: &Advanced, width: u32, height: u32, right: bool) -> Vec<(Viewport, Shown)> {
    zakuro_gpu::backend::place_configured(
        width,
        height,
        layout(settings),
        // the Activity's orientation setting turns the window; this turns the screens inside it
        orientation(settings),
        settings.top_width,
        settings.integer_scale,
        stereo(settings),
        right,
        settings.stretch,
        settings.swap_screens,
        spacing(settings),
    )
}
pub fn orientation(settings: &Advanced) -> ScreenOrientation {
    match settings.screen_rotation {
        ScreenRotation::Normal => ScreenOrientation::North,
        ScreenRotation::Clockwise => ScreenOrientation::East,
        ScreenRotation::UpsideDown => ScreenOrientation::South,
        ScreenRotation::Counterclockwise => ScreenOrientation::West,
    }
}
pub fn touch(settings: &Advanced, width: u32, height: u32, x: f32, y: f32) -> Option<(u16, u16)> {
    let orientation = orientation(settings);
    places(settings, width, height, true)
        .into_iter()
        .find_map(|(v, shown)| {
            (shown == Shown::Bottom)
                .then(|| {
                    if orientation == ScreenOrientation::North {
                        return crate::transform::touch(x, y, [v.x, v.y, v.width, v.height]);
                    }
                    // the window point as a share of the turned screen, turned back to the console's
                    let (u, w) = ((x - v.x) / v.width, (y - v.y) / v.height);
                    if !(0.0..1.0).contains(&u) || !(0.0..1.0).contains(&w) {
                        return None;
                    }
                    let (u, w) = turn(orientation, u, w);
                    Some((((u * 320.0) as u16).min(319), ((w * 240.0) as u16).min(239)))
                })
                .flatten()
        })
}
/// a share of the turned screen, window-side, as a share of the console's screen.
fn turn(orientation: ScreenOrientation, u: f32, w: f32) -> (f32, f32) {
    match orientation {
        ScreenOrientation::North => (u, w),
        ScreenOrientation::East => (w, 1.0 - u),
        ScreenOrientation::South => (1.0 - u, 1.0 - w),
        ScreenOrientation::West => (1.0 - w, u),
    }
}
/// where a console point of the bottom screen shows in the window, inside its viewport v.
pub fn point(settings: &Advanced, v: Viewport, [x, y]: [f32; 2]) -> (f32, f32) {
    let (u, w) = (x / 320.0, y / 240.0);
    let (u, w) = match orientation(settings) {
        ScreenOrientation::North => (u, w),
        ScreenOrientation::East => (1.0 - w, u),
        ScreenOrientation::South => (1.0 - u, 1.0 - w),
        ScreenOrientation::West => (w, 1.0 - u),
    };
    (v.x + u * v.width, v.y + w * v.height)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn physical_displays_split_swap_and_restore_configured_layout() {
        for swap in [false, true] {
            let settings = Advanced {
                dual_display: true,
                swap_screens: swap,
                stereo: StereoMode::Anaglyph,
                ..Advanced::default()
            };
            for external in [false, true] {
                let split = display(&settings, external, true);
                let places = places(&split, 1280, 720, true);
                let bottom = places.iter().find(|(_, shown)| *shown == Shown::Bottom);
                assert_eq!(bottom.is_some(), external != swap);
                if let Some((v, _)) = bottom {
                    assert_eq!(
                        touch(
                            &split,
                            1280,
                            720,
                            v.x + v.width * (160.5 / 320.0),
                            v.y + v.height * (120.5 / 240.0)
                        ),
                        Some((160, 120))
                    );
                } else {
                    assert_eq!(touch(&split, 1280, 720, 640.0, 360.0), None);
                }
            }
            assert_eq!(display(&settings, false, false), settings);
        }
        let settings = Advanced::default();
        assert_eq!(display(&settings, false, true), settings);
    }
    #[test]
    fn physical_displays_have_independent_stretch() {
        let settings = Advanced {
            dual_display: true,
            external_stretch: true,
            ..Advanced::default()
        };
        let main = places(&display(&settings, false, true), 1280, 720, false)[0].0;
        let external = places(&display(&settings, true, true), 1280, 720, false)[0].0;
        assert!(main.width < 1280.0);
        assert_eq!(
            (external.x, external.y, external.width, external.height),
            (0.0, 0.0, 1280.0, 720.0)
        );
        let settings = Advanced {
            stretch: true,
            external_stretch: false,
            ..settings
        };
        assert_eq!(
            places(&display(&settings, false, true), 1280, 720, false)[0]
                .0
                .width,
            1280.0
        );
        assert!(
            places(&display(&settings, true, true), 1280, 720, false)[0]
                .0
                .width
                < 1280.0
        );
    }
    #[test]
    fn every_layout_uses_the_drawn_bottom_viewport_for_touch() {
        for layout in [
            Layout::Stacked,
            Layout::SideBySide,
            Layout::TopOnly,
            Layout::BottomOnly,
            Layout::LargeTop,
            Layout::Hybrid,
            Layout::Custom,
        ] {
            for swap in [false, true] {
                for stretch in [false, true] {
                    let settings = Advanced {
                        layout,
                        swap_screens: swap,
                        stretch,
                        ..Advanced::default()
                    };
                    let bottom = places(&settings, 1280, 720, false)
                        .into_iter()
                        .find(|(_, shown)| *shown == Shown::Bottom);
                    if let Some((v, _)) = bottom {
                        assert_eq!(
                            touch(
                                &settings,
                                1280,
                                720,
                                v.x + v.width * (160.5 / 320.0),
                                v.y + v.height * (120.5 / 240.0)
                            ),
                            Some((160, 120))
                        );
                    } else {
                        assert_eq!(touch(&settings, 1280, 720, 640.0, 360.0), None);
                    }
                }
            }
        }
    }
    #[test]
    fn touch_follows_the_gap_and_the_small_screen_size() {
        // stacked with a 40 px gap fills 400x520 at scale 1: the bottom screen's
        // centre is 40 lower than without it
        let settings = Advanced {
            screen_gap: 40,
            ..Advanced::default()
        };
        assert_eq!(touch(&settings, 400, 520, 200.5, 400.5), Some((160, 120)));
        assert_eq!(touch(&Advanced::default(), 400, 520, 200.5, 400.5), Some((160, 140)));
        // large top at 100% shows the bottom screen at console size beside the top
        let settings = Advanced {
            layout: Layout::LargeTop,
            small_screen: 100,
            ..Advanced::default()
        };
        assert_eq!(touch(&settings, 720, 240, 560.5, 120.5), Some((160, 120)));
        // hybrid at 1920x1080: the bottom screen is 1344,540 512x384 under the
        // small top copy, which takes no touch
        let settings = Advanced {
            layout: Layout::Hybrid,
            ..Advanced::default()
        };
        assert_eq!(touch(&settings, 1920, 1080, 1600.8, 732.8), Some((160, 120)));
        assert_eq!(touch(&settings, 1920, 1080, 1600.0, 348.0), None);
        // custom at 1920x1080: the bottom screen 36% wide at 32%,50% is 614.4,540
        // 691.2x518.4, centre 960,799.2; the top screen above it takes no touch
        let settings = Advanced {
            layout: Layout::Custom,
            ..Advanced::default()
        };
        assert_eq!(touch(&settings, 1920, 1080, 961.0, 800.0), Some((160, 120)));
        assert_eq!(touch(&settings, 1920, 1080, 960.0, 200.0), None);
        // where it overlaps the top screen, touch still goes to the bottom one
        let settings = Advanced {
            layout: Layout::Custom,
            top_size: 100,
            ..settings
        };
        assert_eq!(touch(&settings, 1920, 1080, 961.0, 800.0), Some((160, 120)));
    }
    #[test]
    fn rotated_screens_stay_inside_and_touch_turns_with_them() {
        // where the console's top-left corner shows in the turned bottom screen, as shares of it
        for (rotation, corner) in [
            (ScreenRotation::Normal, (0.0, 0.0)),
            (ScreenRotation::Clockwise, (1.0, 0.0)),
            (ScreenRotation::UpsideDown, (1.0, 1.0)),
            (ScreenRotation::Counterclockwise, (0.0, 1.0)),
        ] {
            let settings = Advanced {
                screen_rotation: rotation,
                ..Advanced::default()
            };
            let places = places(&settings, 1920, 1080, false);
            for (v, _) in &places {
                assert!(v.x >= 0.0 && v.y >= 0.0 && v.x + v.width <= 1920.0 && v.y + v.height <= 1080.0, "{rotation:?}");
                assert_eq!(v.height > v.width, orientation(&settings).is_portrait(), "{rotation:?}");
            }
            let v = places.iter().find(|(_, s)| *s == Shown::Bottom).unwrap().0;
            // half a console pixel in from the corner, along the window's axes
            let (cx, cy) = (corner.0 * v.width, corner.1 * v.height);
            let inward = |at: f32, size: f32| if at == 0.0 { 0.5 } else { -0.5 } * size;
            let (px, py) = if orientation(&settings).is_portrait() { (v.width / 240.0, v.height / 320.0) } else { (v.width / 320.0, v.height / 240.0) };
            let (x, y) = (v.x + cx + inward(cx, px), v.y + cy + inward(cy, py));
            assert_eq!(touch(&settings, 1920, 1080, x, y), Some((0, 0)), "{rotation:?}");
            let (x, y) = point(&settings, v, [160.5, 120.5]);
            assert_eq!(touch(&settings, 1920, 1080, x, y), Some((160, 120)), "{rotation:?}");
            let (x, y) = point(&settings, v, [300.5, 20.5]);
            assert_eq!(touch(&settings, 1920, 1080, x, y), Some((300, 20)), "{rotation:?}");
            assert_eq!(touch(&settings, 1920, 1080, v.x - 1.0, v.y), None, "{rotation:?}");
        }
    }
    /// every layout, swap, turn and scaling mode, in landscape and portrait windows: the middle of the drawn bottom
    /// screen is the console's middle, its corners are the console's turned corners and nothing outside it is touch.
    #[test]
    fn touch_maps_every_layout_swap_turn_and_scaling_through_the_drawn_bottom_screen() {
        let layouts = [Layout::Stacked, Layout::SideBySide, Layout::TopOnly, Layout::BottomOnly, Layout::LargeTop, Layout::Hybrid, Layout::Custom];
        let turns = [ScreenRotation::Normal, ScreenRotation::Clockwise, ScreenRotation::UpsideDown, ScreenRotation::Counterclockwise];
        let mut checked = 0;
        for layout in layouts {
            // Hybrid and Large top place the small screens by size: the three ends of its range
            for small_screen in if matches!(layout, Layout::Hybrid | Layout::LargeTop) { [25, 50, 100] } else { [50, 50, 50] } {
                for swap_screens in [false, true] {
                    for screen_rotation in turns {
                        for integer_scale in [false, true] {
                            for (width, height) in [(1920u32, 1080u32), (1080, 1920)] {
                                let settings = Advanced { layout, small_screen, swap_screens, screen_rotation, integer_scale, ..Advanced::default() };
                                let name = format!("{layout:?} small {small_screen} swap {swap_screens} {screen_rotation:?} integer {integer_scale} {width}x{height}");
                                let touch_at = |x: f32, y: f32| touch(&settings, width, height, x, y);
                                let Some((v, _)) = places(&settings, width, height, true).into_iter().find(|(_, shown)| *shown == Shown::Bottom) else {
                                    assert_eq!(touch_at(width as f32 / 2.0, height as f32 / 2.0), None, "{name}");
                                    continue;
                                };
                                checked += 1;
                                let (x, y) = point(&settings, v, [160.0, 120.0]);
                                let hit = touch_at(x, y).unwrap_or_else(|| panic!("{name}: the middle is not touch"));
                                assert!((i32::from(hit.0) - 160).abs() <= 1 && (i32::from(hit.1) - 120).abs() <= 1, "{name}: middle {hit:?}");
                                // window pixels a console pixel spans: the corners 5 window pixels in are about 5 / scale console pixels in
                                let scale = if orientation(&settings).is_portrait() { v.width / 240.0 } else { v.width / 320.0 };
                                let reach = (5.0 / scale).ceil() as i32 + 1;
                                for (cx, cy) in [(0.0f32, 0.0f32), (320.0, 0.0), (0.0, 240.0), (320.0, 240.0)] {
                                    let (corner_x, corner_y) = point(&settings, v, [cx, cy]);
                                    let (middle_x, middle_y) = point(&settings, v, [160.0, 120.0]);
                                    // 5 window pixels from the corner towards the middle, along each axis
                                    let (x, y) = (corner_x + (middle_x - corner_x).signum() * 5.0, corner_y + (middle_y - corner_y).signum() * 5.0);
                                    let hit = touch_at(x, y).unwrap_or_else(|| panic!("{name}: corner ({cx}, {cy}) is not touch"));
                                    assert!(hit.0 < 320 && hit.1 < 240, "{name}: {hit:?}");
                                    let near = |value: u16, edge: f32| (i32::from(value) - edge as i32).abs() <= reach;
                                    assert!(near(hit.0, cx.min(319.0)) && near(hit.1, cy.min(239.0)), "{name}: corner ({cx}, {cy}) gave {hit:?}");
                                }
                                for (x, y) in [(v.x - 1.0, v.y + v.height / 2.0), (v.x + v.width + 1.0, v.y + v.height / 2.0), (v.x + v.width / 2.0, v.y - 1.0), (v.x + v.width / 2.0, v.y + v.height + 1.0)] {
                                    assert_eq!(touch_at(x, y), None, "{name}: ({x}, {y}) is outside the bottom screen");
                                }
                            }
                        }
                    }
                }
            }
        }
        assert!(checked > 300, "{checked}");
    }
    #[test]
    fn stretch_fills_display_and_stereo_bottoms_both_accept_touch() {
        let settings = Advanced {
            layout: Layout::TopOnly,
            stretch: true,
            ..Advanced::default()
        };
        let v = places(&settings, 1280, 720, false)[0].0;
        assert_eq!((v.x, v.y, v.width, v.height), (0.0, 0.0, 1280.0, 720.0));
        let settings = Advanced {
            stereo: StereoMode::SideBySide,
            swap_screens: true,
            ..Advanced::default()
        };
        let places = places(&settings, 1280, 720, true);
        assert!(places.iter().any(|(_, s)| *s == Shown::Top));
        assert!(places.iter().any(|(_, s)| *s == Shown::Right));
        assert_eq!(
            places.iter().filter(|(_, s)| *s == Shown::Bottom).count(),
            2
        );
        for (v, shown) in places {
            if shown == Shown::Bottom {
                assert_eq!(
                    touch(
                        &settings,
                        1280,
                        720,
                        v.x + v.width * (160.5 / 320.0),
                        v.y + v.height * (120.5 / 240.0)
                    ),
                    Some((160, 120))
                );
            }
        }
        for (mode, expected) in [
            (Filter::Smooth, ScreenFilter::Smooth),
            (Filter::Sharp, ScreenFilter::Sharp),
            (Filter::Pixels, ScreenFilter::Pixels),
        ] {
            assert_eq!(
                filter(&Advanced {
                    filter: mode,
                    ..Advanced::default()
                }),
                expected
            );
        }
    }
}
