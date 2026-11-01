use std::sync::Arc;

type CpuImage = (Arc<Vec<u8>>, u32, u32);

/// Keep the Arc alive so a later allocation cannot reuse its identity.
pub(crate) fn cpu_pixels<'a>(
    last: &mut Option<CpuImage>,
    image: &'a CpuImage,
    gpu: bool,
) -> &'a [u8] {
    if gpu || image.0.is_empty() || image.1 == 0 || image.2 == 0 {
        *last = None;
        return &image.0;
    }
    if last
        .as_ref()
        .is_some_and(|old| Arc::ptr_eq(&old.0, &image.0) && old.1 == image.1 && old.2 == image.2)
    {
        return &[];
    }
    *last = Some(image.clone());
    &image.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reuse_only_the_same_cpu_image_and_dimensions() {
        let mut last = None;
        let image = (Arc::new(vec![1; 16]), 2, 2);
        assert_eq!(cpu_pixels(&mut last, &image, false), image.0.as_slice());
        assert!(cpu_pixels(&mut last, &image, false).is_empty());

        let resized = (image.0.clone(), 4, 1);
        assert_eq!(cpu_pixels(&mut last, &resized, false), image.0.as_slice());
        let changed = (Arc::new(vec![1; 16]), 4, 1);
        assert_eq!(cpu_pixels(&mut last, &changed, false), changed.0.as_slice());
        assert!(cpu_pixels(&mut last, &changed, false).is_empty());

        cpu_pixels(&mut last, &changed, true);
        assert!(last.is_none());
        assert_eq!(cpu_pixels(&mut last, &changed, false), changed.0.as_slice());
        let hidden = (Arc::new(Vec::new()), 0, 0);
        cpu_pixels(&mut last, &hidden, false);
        assert!(last.is_none());
    }
}
