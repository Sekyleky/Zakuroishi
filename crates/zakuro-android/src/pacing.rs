use std::time::{Duration, Instant};

/// Guest cycles measure game-clock progress even when presentation skips or repeats pictures.
pub(crate) fn emulation_rates(cycles: u64, elapsed: Duration) -> (f64, f64) {
    if elapsed.is_zero() { return (0.0, 0.0); }
    let cycles_per_second = cycles as f64 / elapsed.as_secs_f64();
    (cycles_per_second / zakuro_core::CYCLES_PER_FRAME as f64, cycles_per_second / 268_111_856.0 * 100.0)
}

/// Keep alternating heavy/cheap guest ticks on the same timeline, with at most one step of debt.
pub(crate) fn next_deadline(deadline: Instant, now: Instant, step: Duration) -> Instant {
    (deadline + step).max(now.checked_sub(step).unwrap_or(now))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_comes_from_guest_time_not_present_count() {
        assert_eq!(emulation_rates(0, Duration::ZERO), (0.0, 0.0));
        for speed in [1, 3, 4] {
            let (fps, percent) = emulation_rates(268_111_856 * speed, Duration::from_secs(1));
            assert!((percent - speed as f64 * 100.0).abs() < 0.0001);
            assert!((fps - 268_111_856.0 / zakuro_core::CYCLES_PER_FRAME as f64 * speed as f64).abs() < 0.0001);
        }
    }

    #[test]
    fn alternating_heavy_and_cheap_ticks_keep_the_timeline() {
        let start = Instant::now();
        let step = Duration::from_millis(16);
        let after_heavy = next_deadline(start, start + Duration::from_millis(20), step);
        assert_eq!(after_heavy, start + step);
        let after_cheap = next_deadline(after_heavy, start + Duration::from_millis(21), step);
        assert_eq!(after_cheap, start + step * 2);
    }

    #[test]
    fn long_pause_keeps_at_most_one_step_of_debt() {
        let start = Instant::now();
        let step = Duration::from_millis(16);
        let now = start + Duration::from_secs(10);
        assert_eq!(next_deadline(start, now, step), now - step);
    }

    #[test]
    fn light_non_fifo_frames_keep_the_configured_interval() {
        let start = Instant::now();
        let step = Duration::from_millis(20);
        let first = next_deadline(start, start + Duration::from_millis(2), step);
        let second = next_deadline(first, first + Duration::from_millis(2), step);
        assert_eq!(first, start + step);
        assert_eq!(second, start + step * 2);
    }
}
