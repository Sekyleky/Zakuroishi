use crate::audio_queue::{enqueue, Queue as Managed, Stretch};
use ndk::audio::{
    AudioCallbackResult, AudioDirection, AudioFormat, AudioPerformanceMode, AudioStream,
    AudioStreamBuilder, AudioStreamDataCallback, AudioUsage,
};
use std::{
    cell::RefCell,
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};

const CAPACITY: usize = 8192;
/// The 0.2.0 queue (`audio_queue_managed` off): fills to CAPACITY and drops the oldest.
struct Legacy {
    frames: VecDeque<[i16; 2]>,
    fraction: f64,
    underruns: u64,
}
enum Shared {
    Legacy(Arc<Mutex<Legacy>>),
    Managed(Arc<Mutex<Managed>>, RefCell<Stretch>),
}
pub struct Audio {
    stream: AudioStream,
    shared: Shared,
    disconnected: Arc<AtomicBool>,
}
impl Audio {
    pub fn open(managed: bool, low_latency: bool) -> Result<Self, String> {
        let disconnected = Arc::new(AtomicBool::new(false));
        let failed = disconnected.clone();
        let (shared, callback): (Shared, AudioStreamDataCallback) = if managed {
            // The output rate is not known yet; the callback sets the step from the stream.
            let queue = Arc::new(Mutex::new(Managed::new(1.0)));
            let reader = queue.clone();
            let callback: AudioStreamDataCallback = Box::new(move |stream, data, count| {
                let output = unsafe {
                    std::slice::from_raw_parts_mut(data.cast::<[f32; 2]>(), count as usize)
                };
                // Blocking: push holds the lock only to queue one frame's sound.
                let Ok(mut queue) = reader.lock() else {
                    output.fill([0.0; 2]);
                    return AudioCallbackResult::Continue;
                };
                queue.step = zakuro_core::AUDIO_SAMPLE_RATE / f64::from(stream.sample_rate());
                for out in output {
                    *out = queue.next();
                }
                AudioCallbackResult::Continue
            });
            (Shared::Managed(queue, RefCell::new(Stretch::new())), callback)
        } else {
            let queue = Arc::new(Mutex::new(Legacy {
                frames: VecDeque::with_capacity(CAPACITY),
                fraction: 0.0,
                underruns: 0,
            }));
            let reader = queue.clone();
            let callback: AudioStreamDataCallback = Box::new(move |stream, data, count| {
                // AAudio supplies exactly count frames in the negotiated stereo float format.
                let output = unsafe {
                    std::slice::from_raw_parts_mut(data.cast::<[f32; 2]>(), count as usize)
                };
                output.fill([0.0; 2]);
                let Ok(mut queue) = reader.try_lock() else {
                    return AudioCallbackResult::Continue;
                };
                let step = zakuro_core::AUDIO_SAMPLE_RATE / f64::from(stream.sample_rate());
                for out in output {
                    if queue.frames.len() < 2 {
                        queue.fraction = 0.0;
                        queue.underruns += 1;
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
                AudioCallbackResult::Continue
            });
            (Shared::Legacy(queue), callback)
        };
        let mut builder = AudioStreamBuilder::new().map_err(|e| e.to_string())?;
        if low_latency {
            // Low latency with no session asks for FAST|RAW; this output has FAST only, so
            // AudioFlinger refuses FAST altogether. An allocated session drops RAW.
            builder = builder.session_id(None).usage(AudioUsage::Game);
        }
        let stream = builder
            .direction(AudioDirection::Output)
            .channel_count(2)
            .format(AudioFormat::PCM_Float)
            .performance_mode(AudioPerformanceMode::LowLatency)
            .error_callback(Box::new(move |_, _| {
                failed.store(true, Ordering::Relaxed);
            }))
            .data_callback(callback)
            .open_stream()
            .map_err(|e| e.to_string())?;
        if stream.channel_count() != 2
            || stream.format() != AudioFormat::PCM_Float
            || stream.sample_rate() <= 0
        {
            return Err("AAudio did not negotiate stereo float PCM".into());
        }
        log::info!(
            "AAudio stream rate={} burst={} buffer={}/{} performance={:?} managed={} low_latency={}",
            stream.sample_rate(),
            stream.frames_per_burst(),
            stream.buffer_size_in_frames(),
            stream.buffer_capacity_in_frames(),
            stream.performance_mode(),
            managed,
            low_latency
        );
        stream.request_start().map_err(|e| e.to_string())?;
        Ok(Self {
            stream,
            shared,
            disconnected,
        })
    }
    pub fn push(&self, frames: &[[i16; 2]], volume: f32) {
        match &self.shared {
            Shared::Managed(queue, stretch) => {
                let scale = volume / 32768.0;
                let samples: Vec<[f32; 2]> =
                    frames.iter().map(|s| s.map(|v| f32::from(v) * scale)).collect();
                if let Ok(mut queue) = queue.lock() {
                    enqueue(&mut queue, &mut stretch.borrow_mut(), &samples);
                }
            }
            Shared::Legacy(queue) => {
                if let Ok(mut queue) = queue.lock() {
                    let frames = &frames[frames.len().saturating_sub(CAPACITY)..];
                    let excess = (queue.frames.len() + frames.len()).saturating_sub(CAPACITY);
                    queue.frames.drain(..excess);
                    queue.frames.extend(frames.iter().map(|frame| {
                        frame.map(|v| {
                            (f32::from(v) * volume).round().clamp(-32768.0, 32767.0) as i16
                        })
                    }));
                }
            }
        }
    }
    /// Sound waiting to play (ms, excluding the stretcher's ~20 ms) and times it ran dry
    /// since the last call, for the benchmark line.
    pub fn take_stats(&self) -> (f64, u64) {
        let (len, underruns) = match &self.shared {
            Shared::Managed(queue, _) => queue.lock().map_or((0, 0), |mut q| {
                (q.samples.len(), std::mem::take(&mut q.underruns))
            }),
            Shared::Legacy(queue) => queue.lock().map_or((0, 0), |mut q| {
                (q.frames.len(), std::mem::take(&mut q.underruns))
            }),
        };
        (len as f64 * 1000.0 / zakuro_core::AUDIO_SAMPLE_RATE, underruns)
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
