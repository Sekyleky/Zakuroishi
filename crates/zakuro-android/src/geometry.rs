use crate::settings::{Advanced, Filter, Layout, StereoMode};
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
    }
}
pub fn filter(settings: &Advanced) -> ScreenFilter {
    match settings.filter {
        Filter::Smooth => ScreenFilter::Smooth,
        Filter::Sharp => ScreenFilter::Sharp,
        Filter::Pixels => ScreenFilter::Pixels,
    }
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
        settings.top_width,
        settings.integer_scale,
        stereo(settings),
        right,
        settings.stretch,
        settings.swap_screens,
    )
}
pub fn touch(settings: &Advanced, width: u32, height: u32, x: f32, y: f32) -> Option<(u16, u16)> {
    places(settings, width, height, true)
        .into_iter()
        .find_map(|(v, shown)| {
            (shown == Shown::Bottom)
                .then(|| crate::transform::touch(x, y, [v.x, v.y, v.width, v.height]))
                .flatten()
        })
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
