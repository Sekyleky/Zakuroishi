use ndk::audio::{
    AudioCallbackResult, AudioDirection, AudioFormat, AudioPerformanceMode, AudioStream,
    AudioStreamBuilder,
};
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};

const CAPACITY: usize = 8192;
struct Queue {
    frames: VecDeque<[i16; 2]>,
    fraction: f64,
}
pub struct Audio {
    stream: AudioStream,
    queue: Arc<Mutex<Queue>>,
    disconnected: Arc<AtomicBool>,
}
impl Audio {
    pub fn open() -> Result<Self, String> {
        let queue = Arc::new(Mutex::new(Queue {
            frames: VecDeque::with_capacity(CAPACITY),
            fraction: 0.0,
        }));
        let disconnected = Arc::new(AtomicBool::new(false));
        let reader = queue.clone();
        let failed = disconnected.clone();
        let stream = AudioStreamBuilder::new()
            .map_err(|e| e.to_string())?
            .direction(AudioDirection::Output)
            .channel_count(2)
            .format(AudioFormat::PCM_Float)
            .performance_mode(AudioPerformanceMode::LowLatency)
            .error_callback(Box::new(move |_, _| {
                failed.store(true, Ordering::Relaxed);
            }))
            .data_callback(Box::new(move |stream, data, count| {
                // AAudio supplies exactly count frames in the negotiated stereo float format.
                let output = unsafe {
                    std::slice::from_raw_parts_mut(data.cast::<[f32; 2]>(), count as usize)
                };
                output.fill([0.0; 2]);
                if let Ok(mut queue) = reader.try_lock() {
                    let step = zakuro_core::AUDIO_SAMPLE_RATE / f64::from(stream.sample_rate());
                    for out in output {
                        if queue.frames.len() < 2 {
                            queue.fraction = 0.0;
                            break;
                        }
                        let a = queue.frames[0];
                        let b = queue.frames[1];
                        let t = queue.fraction as f32;
                        *out = std::array::from_fn(|i| {
                            (f32::from(a[i]) + (f32::from(b[i]) - f32::from(a[i])) * t) / 32768.0
                        });
                        queue.fraction += step;
                        while queue.fraction >= 1.0 && !queue.frames.is_empty() {
                            queue.frames.pop_front();
                            queue.fraction -= 1.0;
                        }
                    }
                }
                AudioCallbackResult::Continue
            }))
            .open_stream()
            .map_err(|e| e.to_string())?;
        if stream.channel_count() != 2
            || stream.format() != AudioFormat::PCM_Float
            || stream.sample_rate() <= 0
        {
            return Err("AAudio did not negotiate stereo float PCM".into());
        }
        stream.request_start().map_err(|e| e.to_string())?;
        Ok(Self {
            stream,
            queue,
            disconnected,
        })
    }
    pub fn push(&self, frames: &[[i16; 2]]) {
        if let Ok(mut queue) = self.queue.lock() {
            let frames = &frames[frames.len().saturating_sub(CAPACITY)..];
            let excess = (queue.frames.len() + frames.len()).saturating_sub(CAPACITY);
            queue.frames.drain(..excess);
            queue.frames.extend(frames.iter().copied());
        }
    }
    pub fn disconnected(&self) -> bool {
        self.disconnected.load(Ordering::Relaxed)
    }
}
impl Drop for Audio {
    fn drop(&mut self) {
        let _ = self.stream.request_stop();
    }
}
