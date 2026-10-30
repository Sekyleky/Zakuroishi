//! Platform-independent transforms; also testable with `rustc --test`.
use std::path::{Path, PathBuf};

/// Calibrate once per sensor enable, only after half a second held still.
#[derive(Default)]
pub struct GyroCalibration {
    pub bias: Option<[f32; 3]>,
    count: u32,
    start: i64,
    last: i64,
    sum: [f64; 3],
    squares: [f64; 3],
    gravity: Option<[f32; 3]>,
}
impl GyroCalibration {
    /// Samples are device-coordinate g, degrees/second and monotonic nanoseconds.
    pub fn observe(
        &mut self,
        acceleration: [f32; 3],
        gyro: [f32; 3],
        timestamp: i64,
    ) -> Option<[f32; 3]> {
        if self.bias.is_some() {
            return None;
        }
        let norm = |v: [f32; 3]| v.iter().map(|v| v * v).sum::<f32>().sqrt();
        let still = acceleration
            .iter()
            .chain(gyro.iter())
            .all(|v| v.is_finite())
            && (norm(acceleration) - 1.0).abs() <= 0.03
            && norm(gyro) <= 0.5
            && self
                .gravity
                .is_none_or(|g| norm(std::array::from_fn(|i| g[i] - acceleration[i])) <= 0.03)
            && (self.count == 0 || (timestamp > self.last && timestamp - self.last <= 100_000_000));
        if !still {
            *self = Self::default();
            return None;
        }
        if self.count == 0 {
            self.start = timestamp;
            self.gravity = Some(acceleration);
        }
        self.last = timestamp;
        self.count += 1;
        for (i, value) in gyro.into_iter().enumerate() {
            let v = f64::from(value);
            self.sum[i] += v;
            self.squares[i] += v * v;
        }
        if self.count >= 25 && timestamp - self.start >= 500_000_000 {
            let n = f64::from(self.count);
            let mean = self.sum.map(|v| v / n);
            if (0..3).any(|i| self.squares[i] / n - mean[i] * mean[i] > 0.01) {
                *self = Self::default();
                return None;
            }
            let bias = mean.map(|v| v as f32);
            self.bias = Some(bias);
            return Some(bias);
        }
        None
    }
    pub fn correct(&self, gyro: [f32; 3]) -> [f32; 3] {
        let bias = self.bias.unwrap_or([0.0; 3]);
        std::array::from_fn(|i| gyro[i] - bias[i])
    }
}

/// Android sensor coordinates are fixed to the device's natural orientation.
/// Surface.ROTATION_* is counterclockwise from natural orientation.
pub fn display_vector([x, y, z]: [f32; 3], rotation: i32) -> [f32; 3] {
    match rotation {
        1 => [-y, x, z],
        2 => [-x, -y, z],
        3 => [y, -x, z],
        _ => [x, y, z],
    }
}

pub fn stick(value: f32) -> f32 {
    if !value.is_finite() || value.abs() <= 0.15 {
        0.0
    } else {
        value.signum() * ((value.abs() - 0.15) / 0.85).min(1.0)
    }
}

pub fn touch(x: f32, y: f32, [left, top, width, height]: [f32; 4]) -> Option<(u16, u16)> {
    if !x.is_finite()
        || !y.is_finite()
        || width <= 0.0
        || height <= 0.0
        || x < left
        || y < top
        || x >= left + width
        || y >= top + height
    {
        return None;
    }
    Some((
        ((x - left) * 320.0 / width).floor() as u16,
        ((y - top) * 240.0 / height).floor() as u16,
    ))
}

/// Canonicalize before checking containment, so symlinks cannot escape a private root.
pub fn owned_path(path: &Path, roots: &[PathBuf]) -> Result<PathBuf, String> {
    if !path.is_absolute() {
        return Err(format!(
            "expected an absolute app-owned path: {}",
            path.display()
        ));
    }
    let path = path
        .canonicalize()
        .map_err(|e| format!("{}: {e}", path.display()))?;
    if !roots
        .iter()
        .filter_map(|r| r.canonicalize().ok())
        .any(|r| path.starts_with(r))
    {
        return Err(format!(
            "path is outside app-owned storage: {}",
            path.display()
        ));
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calibrates_stationary_bias_once_and_preserves_turns() {
        let mut calibration = GyroCalibration::default();
        let bias = [0.1, -0.2, 0.05];
        for i in 0..=50 {
            calibration.observe([0.0, 1.0, 0.0], bias, i * 10_000_000);
        }
        assert_eq!(calibration.bias, Some(bias));
        assert_eq!(calibration.correct(bias), [0.0; 3]);
        calibration.observe([0.0, 1.0, 0.0], [0.0, 0.0, 30.0], 600_000_000);
        assert_eq!(calibration.bias, Some(bias));
        assert!((calibration.correct([0.0, 0.0, 30.05])[2] - 30.0).abs() < 0.00001);
    }
    #[test]
    fn calibration_rejects_motion_variance_and_sample_gaps() {
        let mut calibration = GyroCalibration::default();
        for i in 0..100 {
            let gyro = if i % 2 == 0 { 0.3 } else { -0.3 };
            calibration.observe([0.0, 1.0, 0.0], [gyro, 0.0, 0.0], i * 10_000_000);
        }
        assert_eq!(calibration.bias, None);
        for i in 100..200 {
            calibration.observe([0.0, 1.0, 0.0], [0.0, 0.0, 5.0], i * 10_000_000);
        }
        assert_eq!(calibration.bias, None);
        for i in 200..300 {
            calibration.observe([0.0, 1.2, 0.0], [0.0; 3], i * 10_000_000);
        }
        assert_eq!(calibration.bias, None);
        calibration.observe([0.0, 1.0, 0.0], [0.1; 3], 3_000_000_000);
        calibration.observe([0.0, 1.0, 0.0], [0.1; 3], 4_000_000_000);
        assert_eq!(calibration.bias, None);
        assert_eq!(calibration.count, 0);
    }
    #[test]
    fn rotates_all_three_sensor_axes_without_losing_yaw() {
        assert_eq!(display_vector([1.0, 2.0, 3.0], 0), [1.0, 2.0, 3.0]);
        assert_eq!(display_vector([1.0, 2.0, 3.0], 1), [-2.0, 1.0, 3.0]);
        assert_eq!(display_vector([1.0, 2.0, 3.0], 2), [-1.0, -2.0, 3.0]);
        assert_eq!(display_vector([1.0, 2.0, 3.0], 3), [2.0, -1.0, 3.0]);
    }
    #[test]
    fn touch_rejects_letterbox_and_exclusive_edges() {
        let rect = [40.0, 240.0, 320.0, 240.0];
        assert_eq!(touch(40.0, 240.0, rect), Some((0, 0)));
        assert_eq!(touch(359.9, 479.9, rect), Some((319, 239)));
        assert_eq!(touch(39.0, 250.0, rect), None);
        assert_eq!(touch(360.0, 250.0, rect), None);
        assert_eq!(touch(f32::NAN, 250.0, rect), None);
    }
    #[test]
    fn stick_deadzone_and_bounds() {
        assert_eq!(stick(0.1), 0.0);
        assert_eq!(stick(-1.0), -1.0);
        assert_eq!(stick(2.0), 1.0);
        assert_eq!(stick(f32::NAN), 0.0);
    }
    #[test]
    fn owned_paths_require_absolute_contained_existing_paths() {
        let root = std::env::temp_dir().canonicalize().unwrap();
        assert!(owned_path(&root, std::slice::from_ref(&root)).is_ok());
        assert!(owned_path(Path::new("relative"), std::slice::from_ref(&root)).is_err());
        assert!(owned_path(&root, &[root.join("missing-private-root")]).is_err());
    }
}
