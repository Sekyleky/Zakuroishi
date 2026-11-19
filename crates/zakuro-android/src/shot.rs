//! The screenshot picture: the top screen above the bottom screen, each centred, as RGBA.

/// Both screens' RGBA pictures (width, height) in one buffer as wide as the wider one and as tall as both;
/// the margins beside the narrower screen are transparent black.
pub fn both_screens(top: (&[u8], u32, u32), bottom: (&[u8], u32, u32)) -> (Vec<u8>, u32, u32) {
    let width = top.1.max(bottom.1);
    let height = top.2 + bottom.2;
    let mut out = vec![0u8; width as usize * height as usize * 4];
    for (y0, (pixels, w, h)) in [(0, top), (top.2, bottom)] {
        let x0 = (width - w) / 2;
        for row in 0..h {
            let from = row as usize * w as usize * 4;
            let to = ((y0 + row) as usize * width as usize + x0 as usize) * 4;
            out[to..to + w as usize * 4].copy_from_slice(&pixels[from..from + w as usize * 4]);
        }
    }
    (out, width, height)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_bottom_screen_is_centred_under_the_top() {
        let top = vec![1u8; 400 * 240 * 4];
        let bottom = vec![2u8; 320 * 240 * 4];
        let (out, w, h) = both_screens((&top, 400, 240), (&bottom, 320, 240));
        assert_eq!((w, h, out.len()), (400, 480, 400 * 480 * 4));
        let px = |x: usize, y: usize| out[(y * 400 + x) * 4];
        assert_eq!((px(0, 0), px(399, 239)), (1, 1));
        // 40 transparent columns each side of the bottom screen
        assert_eq!((px(39, 240), px(40, 240), px(359, 479), px(360, 479)), (0, 2, 2, 0));
        assert_eq!(out[(240 * 400 + 39) * 4 + 3], 0);
    }

    #[test]
    fn a_wider_bottom_centres_the_top() {
        let top = vec![1u8; 4 * 2 * 4];
        let bottom = vec![2u8; 6 * 3 * 4];
        let (out, w, h) = both_screens((&top, 4, 2), (&bottom, 6, 3));
        assert_eq!((w, h), (6, 5));
        assert_eq!((out[0], out[4], out[5 * 4], out[(2 * 6) * 4]), (0, 1, 0, 2));
    }
}
