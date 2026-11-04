//! Frame generation: whole pictures made between the game's own by lsfg-vk 1.0, on the device the
//! renderer draws on. The shaders come from the user's own Lossless.dll, see
//! android/third_party/lsfg/README.md. Each new picture is copied into one of two inputs, the graph is
//! submitted to the queue, then the generated pictures and the new one go up over the next refreshes.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Instant;

use ash::vk;

use super::vulkan::find_memory_type;
use super::GpuScreen;

/// generated pictures that reached a present.
pub static GENERATED_SHOWN: AtomicU64 = AtomicU64::new(0);
/// the game's pictures replaced by a newer one before they went up.
pub static REAL_DROPPED: AtomicU64 = AtomicU64::new(0);
/// presents beyond the one per emulated refresh.
pub static EXTRA_PRESENTS: AtomicU64 = AtomicU64::new(0);
static GPU_NS: AtomicU64 = AtomicU64::new(0);
static CPU_NS: AtomicU64 = AtomicU64::new(0);
static PASSES: AtomicU64 = AtomicU64::new(0);

/// mean GPU and CPU milliseconds per generation pass since the last call, and the passes.
pub fn take_stats() -> (f64, f64, u64) {
    let passes = PASSES.swap(0, Relaxed);
    let (gpu, cpu) = (GPU_NS.swap(0, Relaxed), CPU_NS.swap(0, Relaxed));
    let mean = |ns: u64| ns as f64 / 1e6 / passes.max(1) as f64;
    (mean(gpu), mean(cpu), passes)
}

#[derive(Debug, Clone, PartialEq)]
pub struct FrameGenOptions {
    /// pictures shown per game picture: 2 at a 60 Hz panel rate, 4 at 120 Hz.
    pub multiplier: u32,
    /// the motion search's share of the picture size, percent.
    pub flow_percent: u32,
    /// app-private folder holding the import and the translated shaders.
    pub dir: PathBuf,
}

impl FrameGenOptions {
    /// presents per emulated refresh of 60 Hz.
    pub fn per_tick(&self) -> usize {
        if self.multiplier == 4 { 2 } else { 1 }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Item {
    Generated(usize),
    Real(usize),
}

/// what goes up for a new picture, when the last two pictures came `gap` refreshes apart. a game at
/// the refresh rate has room only for the middle of the generated pictures.
fn schedule(generated: usize, gap: u32, current: usize) -> VecDeque<Item> {
    let mut items: VecDeque<Item> = if gap >= 2 {
        (0..generated).map(Item::Generated).collect()
    } else if generated >= 3 {
        [Item::Generated(generated / 2)].into()
    } else {
        VecDeque::new()
    };
    items.push_back(Item::Real(current));
    items
}

type Init = unsafe extern "C" fn(vk::PFN_vkGetInstanceProcAddr, vk::Instance, vk::PhysicalDevice, vk::Device, u32, vk::Queue, f32, u64, *const std::ffi::c_char) -> i32;
type Create = unsafe extern "C" fn(vk::Image, vk::Image, *const vk::Image, u32, u32, u32, vk::Format) -> i32;
type Run = unsafe extern "C" fn(i32) -> i32;
type Finish = unsafe extern "C" fn();

struct Bridge {
    init: Init,
    create: Create,
    run: Run,
    finish: Finish,
}

#[cfg(target_os = "android")]
fn load_bridge() -> Result<Bridge, String> {
    extern "C" {
        fn dlopen(name: *const std::ffi::c_char, flags: i32) -> *mut std::ffi::c_void;
        fn dlsym(handle: *mut std::ffi::c_void, name: *const std::ffi::c_char) -> *mut std::ffi::c_void;
    }
    const RTLD_NOW: i32 = 2;
    // SAFETY: a library of this app, whose exports have these signatures (bridge.cpp); it stays loaded
    unsafe {
        let library = dlopen(c"liblsfg_bridge.so".as_ptr(), RTLD_NOW);
        if library.is_null() {
            return Err("could not load liblsfg_bridge.so".into());
        }
        let symbol = |name: &std::ffi::CStr| {
            let found = dlsym(library, name.as_ptr());
            if found.is_null() { Err(format!("liblsfg_bridge.so has no {name:?}")) } else { Ok(found) }
        };
        Ok(Bridge {
            init: std::mem::transmute::<*mut std::ffi::c_void, Init>(symbol(c"zlsfg_init")?),
            create: std::mem::transmute::<*mut std::ffi::c_void, Create>(symbol(c"zlsfg_create")?),
            run: std::mem::transmute::<*mut std::ffi::c_void, Run>(symbol(c"zlsfg_run")?),
            finish: std::mem::transmute::<*mut std::ffi::c_void, Finish>(symbol(c"zlsfg_finish")?),
        })
    }
}

#[cfg(not(target_os = "android"))]
fn load_bridge() -> Result<Bridge, String> {
    Err("frame generation is built for Android only".into())
}

const SLOTS: usize = 8;
const SHADERS: usize = 26;

/// whether the folder holds a whole translated set, written only by the import.
pub fn shaders_ready(dir: &std::path::Path) -> bool {
    std::fs::read_dir(dir.join("spv")).is_ok_and(|entries| {
        entries.flatten().filter(|entry| entry.path().extension().is_some_and(|extension| extension == "spv")).count() == SHADERS
    })
}

struct Slot {
    copy: vk::CommandBuffer,
    stamp: vk::CommandBuffer,
    fence: vk::Fence,
    used: bool,
}

struct OwnImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
}

pub(super) struct FrameGen {
    device: ash::Device,
    queue: vk::Queue,
    bridge: Bridge,
    context: i32,
    /// the two inputs, then the generated pictures.
    images: Vec<OwnImage>,
    width: u32,
    height: u32,
    per_tick: usize,
    pool: vk::CommandPool,
    slots: Vec<Slot>,
    queries: vk::QueryPool,
    nanos_per_tick: f64,
    fed: u64,
    newest: Option<(vk::ImageView, u64)>,
    ticks_since_new: u32,
    gap: u32,
    waiting: VecDeque<Item>,
    showing: Option<Item>,
}

/// what the presenter hands over to make one.
pub(super) struct Device<'a> {
    pub entry: &'a ash::Entry,
    pub instance: &'a ash::Instance,
    pub physical: vk::PhysicalDevice,
    pub device: &'a ash::Device,
    pub family: u32,
    pub queue: vk::Queue,
    pub memory: &'a vk::PhysicalDeviceMemoryProperties,
}

impl FrameGen {
    pub(super) fn new(on: Device<'_>, options: &FrameGenOptions, width: u32, height: u32) -> Result<FrameGen, String> {
        let bridge = load_bridge()?;
        let dir = std::ffi::CString::new(options.dir.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        if !shaders_ready(&options.dir) {
            return Err("import Lossless.dll first".into());
        }
        let device = on.device.clone();
        let generated = options.multiplier.max(2) as usize - 1;
        let flow = 100.0 / options.flow_percent.clamp(25, 100) as f32;
        // SAFETY: the handles are live and outlive this, which drop() ends first. the graph is only
        // submitted from the presenting thread, the one that submits everything else on this queue
        let result = unsafe {
            (bridge.init)(on.entry.static_fn().get_instance_proc_addr, on.instance.handle(), on.physical,
                device.handle(), on.family, on.queue, flow, generated as u64, dir.as_ptr())
        };
        if result != 0 {
            return Err("the graph could not start".into());
        }
        let mut images = Vec::new();
        for _ in 0..2 + generated {
            images.push(make_image(&device, on.memory, width, height)?);
        }
        let outputs: Vec<vk::Image> = images[2..].iter().map(|i| i.image).collect();
        let started = Instant::now();
        // SAFETY: images of this device, in the general layout before the graph first runs
        let context = unsafe {
            (bridge.create)(images[0].image, images[1].image, outputs.as_ptr(), outputs.len() as u32, width, height, vk::Format::R8G8B8A8_UNORM)
        };
        if context == -1 {
            return Err("the shaders do not fit the graph".into());
        }
        log::info!("frame generation: graph of {generated} pictures at {width}x{height}, flow {}%, built in {:?}", options.flow_percent, started.elapsed());
        let vk_error = |what: &'static str| move |e: vk::Result| format!("{what}: {e}");
        // SAFETY: plain object creation on a live device
        let (pool, buffers, queries) = unsafe {
            let pool = device.create_command_pool(
                &vk::CommandPoolCreateInfo::default().queue_family_index(on.family).flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                None,
            ).map_err(vk_error("command pool"))?;
            let buffers = device.allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default().command_pool(pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(2 * SLOTS as u32),
            ).map_err(vk_error("command buffers"))?;
            let queries = device.create_query_pool(
                &vk::QueryPoolCreateInfo::default().query_type(vk::QueryType::TIMESTAMP).query_count(2 * SLOTS as u32),
                None,
            ).map_err(vk_error("query pool"))?;
            (pool, buffers, queries)
        };
        let mut slots = Vec::new();
        for pair in buffers.chunks(2) {
            // SAFETY: as above
            let fence = unsafe { device.create_fence(&vk::FenceCreateInfo::default(), None) }.map_err(vk_error("fence"))?;
            slots.push(Slot { copy: pair[0], stamp: pair[1], fence, used: false });
        }
        // SAFETY: the physical device came from this instance
        let nanos_per_tick = f64::from(unsafe { on.instance.get_physical_device_properties(on.physical) }.limits.timestamp_period);
        Ok(FrameGen {
            device,
            queue: on.queue,
            bridge,
            context,
            images,
            width,
            height,
            per_tick: options.per_tick(),
            pool,
            slots,
            queries,
            nanos_per_tick,
            fed: 0,
            newest: None,
            ticks_since_new: 0,
            gap: 2,
            waiting: VecDeque::new(),
            showing: None,
        })
    }

    /// whether this graph makes the pictures the options ask for.
    pub(super) fn follows(&self, options: &FrameGenOptions) -> bool {
        self.per_tick == options.per_tick()
    }

    pub(super) fn fits(&self, gpu: &GpuScreen) -> bool {
        gpu.rect[2] == self.width && gpu.rect[3] == self.height
    }

    /// one emulated refresh: starts on the screen's picture if it is new, and returns what to present,
    /// and whether each is a generated picture.
    pub(super) fn tick(&mut self, gpu: GpuScreen) -> Result<Vec<(GpuScreen, bool)>, String> {
        self.ticks_since_new = self.ticks_since_new.saturating_add(1);
        if self.newest != Some((gpu.view, gpu.batch)) {
            self.newest = Some((gpu.view, gpu.batch));
            self.gap = self.ticks_since_new;
            self.ticks_since_new = 0;
            let current = (self.fed % 2) as usize;
            self.generate(&gpu, current)?;
            if self.waiting.iter().any(|item| matches!(item, Item::Real(_))) {
                REAL_DROPPED.fetch_add(1, Relaxed);
            }
            self.waiting = if self.fed == 1 {
                [Item::Real(current)].into()
            } else {
                schedule(self.images.len() - 2, self.gap, current)
            };
        }
        let mut out = Vec::with_capacity(self.per_tick);
        for _ in 0..self.per_tick {
            let popped = self.waiting.pop_front();
            if let Some(item) = popped {
                self.showing = Some(item);
            }
            let Some(item) = self.showing else { continue };
            let (index, generated) = match item {
                Item::Generated(i) => (2 + i, true),
                Item::Real(i) => (i, false),
            };
            let (w, h) = (self.width as f32, self.height as f32);
            out.push((
                GpuScreen {
                    image: self.images[index].image,
                    view: self.images[index].view,
                    area: [0.0, 0.0, 1.0, 1.0],
                    bounds: [0.5 / w, 0.5 / h, 1.0 - 0.5 / w, 1.0 - 0.5 / h],
                    rect: [0, 0, self.width, self.height],
                    batch: 0,
                },
                // a repeat of the last picture is no new one
                generated && popped.is_some(),
            ));
        }
        Ok(out)
    }

    fn generate(&mut self, gpu: &GpuScreen, current: usize) -> Result<(), String> {
        let started = Instant::now();
        let index = (self.fed % SLOTS as u64) as usize;
        let device = &self.device;
        let slot = &mut self.slots[index];
        let vk_error = |what: &'static str| move |e: vk::Result| format!("{what}: {e}");
        // SAFETY: the slot's fence is waited on before its buffers are reused; every image is in the
        // general layout from the first recording on, and the barriers order the copy after the
        // renderer's work and before the graph's, all on this one queue
        unsafe {
            if slot.used {
                device.wait_for_fences(&[slot.fence], true, 2_000_000_000).map_err(vk_error("waiting for a pass"))?;
                device.reset_fences(&[slot.fence]).map_err(vk_error("resetting a fence"))?;
                let mut stamps = [0u64; 2];
                if device.get_query_pool_results(self.queries, 2 * index as u32, &mut stamps, vk::QueryResultFlags::TYPE_64).is_ok() {
                    let ns = stamps[1].saturating_sub(stamps[0]) as f64 * self.nanos_per_tick;
                    GPU_NS.fetch_add(ns as u64, Relaxed);
                    PASSES.fetch_add(1, Relaxed);
                }
            }
            slot.used = true;
            let begin = vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
            device.begin_command_buffer(slot.copy, &begin).map_err(vk_error("recording"))?;
            device.cmd_reset_query_pool(slot.copy, self.queries, 2 * index as u32, 2);
            device.cmd_write_timestamp(slot.copy, vk::PipelineStageFlags::TOP_OF_PIPE, self.queries, 2 * index as u32);
            if self.fed == 0 {
                let range = vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1);
                let barriers: Vec<_> = self.images.iter().map(|own| {
                    vk::ImageMemoryBarrier::default()
                        .dst_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                        .old_layout(vk::ImageLayout::UNDEFINED)
                        .new_layout(vk::ImageLayout::GENERAL)
                        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .image(own.image)
                        .subresource_range(range)
                }).collect();
                device.cmd_pipeline_barrier(slot.copy, vk::PipelineStageFlags::TOP_OF_PIPE, vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::DependencyFlags::empty(), &[], &[], &barriers);
            }
            let before = vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::MEMORY_WRITE).dst_access_mask(vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE);
            device.cmd_pipeline_barrier(slot.copy, vk::PipelineStageFlags::ALL_COMMANDS, vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(), &[before], &[], &[]);
            let layers = vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1);
            device.cmd_copy_image(slot.copy, gpu.image, vk::ImageLayout::GENERAL, self.images[current].image, vk::ImageLayout::GENERAL, &[
                vk::ImageCopy::default()
                    .src_subresource(layers)
                    .src_offset(vk::Offset3D { x: gpu.rect[0] as i32, y: gpu.rect[1] as i32, z: 0 })
                    .dst_subresource(layers)
                    .extent(vk::Extent3D { width: self.width, height: self.height, depth: 1 }),
            ]);
            let after = vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::SHADER_READ);
            device.cmd_pipeline_barrier(slot.copy, vk::PipelineStageFlags::TRANSFER, vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::FRAGMENT_SHADER,
                vk::DependencyFlags::empty(), &[after], &[], &[]);
            device.end_command_buffer(slot.copy).map_err(vk_error("recording"))?;
            let buffers = [slot.copy];
            device.queue_submit(self.queue, &[vk::SubmitInfo::default().command_buffers(&buffers)], vk::Fence::null()).map_err(vk_error("submitting"))?;

            if (self.bridge.run)(self.context) != 0 {
                return Err("the graph failed".into());
            }

            device.begin_command_buffer(slot.stamp, &begin).map_err(vk_error("recording"))?;
            device.cmd_write_timestamp(slot.stamp, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.queries, 2 * index as u32 + 1);
            device.end_command_buffer(slot.stamp).map_err(vk_error("recording"))?;
            let buffers = [slot.stamp];
            device.queue_submit(self.queue, &[vk::SubmitInfo::default().command_buffers(&buffers)], slot.fence).map_err(vk_error("submitting"))?;
        }
        self.fed += 1;
        CPU_NS.fetch_add(started.elapsed().as_nanos() as u64, Relaxed);
        Ok(())
    }
}

impl Drop for FrameGen {
    fn drop(&mut self) {
        // SAFETY: nothing is in flight after the wait, and the graph goes before what it wraps
        unsafe {
            let _ = self.device.device_wait_idle();
            (self.bridge.finish)();
            for slot in self.slots.drain(..) {
                self.device.destroy_fence(slot.fence, None);
            }
            self.device.destroy_query_pool(self.queries, None);
            self.device.destroy_command_pool(self.pool, None);
            for own in self.images.drain(..) {
                self.device.destroy_image_view(own.view, None);
                self.device.destroy_image(own.image, None);
                self.device.free_memory(own.memory, None);
            }
        }
    }
}

fn make_image(device: &ash::Device, memory: &vk::PhysicalDeviceMemoryProperties, width: u32, height: u32) -> Result<OwnImage, String> {
    let vk_error = |what: &'static str| move |e: vk::Result| format!("{what}: {e}");
    // SAFETY: plain object creation on a live device
    unsafe {
        let image = device.create_image(
            &vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8G8B8A8_UNORM)
                .extent(vk::Extent3D { width, height, depth: 1 })
                .mip_levels(1)
                .array_layers(1)
                .samples(vk::SampleCountFlags::TYPE_1)
                .usage(vk::ImageUsageFlags::STORAGE | vk::ImageUsageFlags::SAMPLED | vk::ImageUsageFlags::TRANSFER_DST),
            None,
        ).map_err(vk_error("image"))?;
        let needs = device.get_image_memory_requirements(image);
        let kind = find_memory_type(memory, needs.memory_type_bits, vk::MemoryPropertyFlags::DEVICE_LOCAL).ok_or("no memory for an image")?;
        let bound = device.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(needs.size).memory_type_index(kind), None).map_err(vk_error("memory"))?;
        device.bind_image_memory(image, bound, 0).map_err(vk_error("binding memory"))?;
        let view = device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(vk::Format::R8G8B8A8_UNORM)
                .subresource_range(vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)),
            None,
        ).map_err(vk_error("view"))?;
        Ok(OwnImage { image, memory: bound, view })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_whole_set_of_shaders_counts_as_ready() {
        let dir = std::env::temp_dir().join(format!("zakuro-lsfg-{}", std::process::id()));
        let spv = dir.join("spv");
        assert!(!shaders_ready(&dir));
        std::fs::create_dir_all(&spv).unwrap();
        for shader in 0..SHADERS - 1 {
            std::fs::write(spv.join(format!("{shader}.spv")), b"").unwrap();
        }
        std::fs::write(spv.join("other.txt"), b"").unwrap();
        assert!(!shaders_ready(&dir));
        std::fs::write(spv.join("last.spv"), b"").unwrap();
        assert!(shaders_ready(&dir));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_new_picture_goes_up_after_what_was_made_before_it() {
        use Item::{Generated as G, Real as R};
        assert_eq!(Vec::from(schedule(3, 2, 1)), [G(0), G(1), G(2), R(1)]);
        assert_eq!(Vec::from(schedule(3, 1, 0)), [G(1), R(0)]);
        assert_eq!(Vec::from(schedule(1, 2, 1)), [G(0), R(1)]);
        assert_eq!(Vec::from(schedule(1, 1, 1)), [R(1)]);
    }
}
