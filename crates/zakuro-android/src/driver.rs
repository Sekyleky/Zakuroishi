//! Android driver selection uses Pocket Native's adrenotools loading path.
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

pub struct DriverSpec {
    pub directory: PathBuf,
    pub name: String,
    pub hooks: PathBuf,
}
impl DriverSpec {
    pub fn validate(
        directory: &Path,
        name: &str,
        internal: &Path,
        hooks: PathBuf,
    ) -> Result<Self, String> {
        if name.is_empty()
            || !name.ends_with(".so")
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        {
            return Err("Custom driver library must be a .so basename".into());
        }
        let directory = crate::transform::owned_path(directory, &[internal.to_path_buf()])?;
        let library =
            crate::transform::owned_path(&directory.join(name), std::slice::from_ref(&directory))?;
        let mut header = [0u8; 64];
        File::open(library)
            .and_then(|mut file| file.read_exact(&mut header))
            .map_err(|e| format!("Could not read custom driver: {e}"))?;
        if &header[..6] != b"\x7fELF\x02\x01"
            || u16::from_le_bytes([header[16], header[17]]) != 3
            || u16::from_le_bytes([header[18], header[19]]) != 183
        {
            return Err("Custom driver must be an ARM64 ELF shared library".into());
        }
        Ok(Self {
            directory,
            name: name.into(),
            hooks,
        })
    }
}

pub fn directory_prefix(path: &Path) -> Result<std::ffi::CString, String> {
    let mut bytes = path.as_os_str().as_encoded_bytes().to_vec();
    if !bytes.ends_with(b"/") {
        bytes.push(b'/');
    }
    std::ffi::CString::new(bytes).map_err(|e| e.to_string())
}

#[cfg(target_os = "android")]
pub struct Driver {
    pub entry: ash::Entry,
    // Drop after all Vulkan objects, before unloading adrenotools.
    _vulkan: Option<libloading::Library>,
    _tools: Option<libloading::Library>,
}
#[cfg(target_os = "android")]
impl Driver {
    pub fn load(spec: Option<&DriverSpec>) -> Result<Self, String> {
        let Some(spec) = spec else {
            return Ok(Self {
                entry: unsafe { ash::Entry::load() }
                    .map_err(|e| format!("No Vulkan loader: {e}"))?,
                _vulkan: None,
                _tools: None,
            });
        };
        use std::ffi::{c_char, c_int, c_void, CString};
        let directory = directory_prefix(&spec.directory)?;
        let hooks =
            CString::new(spec.hooks.as_os_str().as_encoded_bytes()).map_err(|e| e.to_string())?;
        let name = CString::new(spec.name.as_bytes()).map_err(|e| e.to_string())?;
        // Same adrenotools ABI used by Pocket Native: API30+, custom driver feature only.
        unsafe {
            let tools = libloading::Library::new(spec.hooks.join("libadrenotools.so"))
                .map_err(|e| format!("Custom driver support could not load adrenotools: {e}"))?;
            type Open = unsafe extern "C" fn(
                c_int,
                c_int,
                *const c_char,
                *const c_char,
                *const c_char,
                *const c_char,
                *const c_char,
                *mut *mut c_void,
            ) -> *mut c_void;
            let open: libloading::Symbol<Open> = tools
                .get(b"adrenotools_open_libvulkan\0")
                .map_err(|e| e.to_string())?;
            let handle = open(
                2,
                1,
                std::ptr::null(),
                hooks.as_ptr(),
                directory.as_ptr(),
                name.as_ptr(),
                std::ptr::null(),
                std::ptr::null_mut(),
            );
            if handle.is_null() {
                return Err("adrenotools could not load the selected custom driver; select System driver to recover".into());
            }
            let vulkan: libloading::Library =
                libloading::os::unix::Library::from_raw(handle).into();
            let proc = *vulkan
                .get(b"vkGetInstanceProcAddr\0")
                .map_err(|e| e.to_string())?;
            let entry = ash::Entry::from_static_fn(ash::StaticFn {
                get_instance_proc_addr: proc,
            });
            Ok(Self {
                entry,
                _vulkan: Some(vulkan),
                _tools: Some(tools),
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn custom_driver_requires_private_arm64_shared_library_and_safe_basename() {
        let root = std::env::temp_dir().join(format!("zakuro-driver-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join("driver.so");
        let mut header = [0u8; 64];
        header[..6].copy_from_slice(b"\x7fELF\x02\x01");
        header[16] = 3;
        header[18] = 183;
        std::fs::write(&path, header).unwrap();
        let spec = DriverSpec::validate(&root, "driver.so", &root, root.clone()).unwrap();
        assert_eq!(spec.directory, root.canonicalize().unwrap());
        assert_eq!(spec.name, "driver.so");
        assert_eq!(spec.hooks, root);
        assert!(DriverSpec::validate(&root, "../driver.so", &root, root.clone()).is_err());
        assert!(
            DriverSpec::validate(&root, "driver.so", &root.join("other"), root.clone()).is_err()
        );
        header[18] = 62;
        std::fs::write(&path, header).unwrap();
        assert!(DriverSpec::validate(&root, "driver.so", &root, root.clone()).is_err());
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(root).unwrap();
    }
    #[test]
    fn adrenotools_directory_always_has_separator() {
        assert_eq!(
            directory_prefix(Path::new("/private/driver"))
                .unwrap()
                .to_bytes(),
            b"/private/driver/"
        );
        assert_eq!(
            directory_prefix(Path::new("/private/driver/"))
                .unwrap()
                .to_bytes(),
            b"/private/driver/"
        );
        assert!(directory_prefix(Path::new("bad\0path")).is_err());
    }
}
