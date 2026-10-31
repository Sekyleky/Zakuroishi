//! Build native game code against the same pinned ABI as the Android runtime.
use std::path::PathBuf;

fn run() -> Result<(), String> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 3 {
        return Err("usage: zakuro-build-title <decrypted-rom> <new-output-directory> <ndk-directory>".into());
    }
    let rom = PathBuf::from(&args[0]).canonicalize().map_err(|error| format!("Cannot read ROM: {error}"))?;
    let out = PathBuf::from(&args[1]);
    let ndk = PathBuf::from(&args[2]).canonicalize().map_err(|error| format!("Cannot find NDK: {error}"))?;
    let host = if cfg!(windows) { "windows-x86_64" } else if cfg!(target_os = "macos") { "darwin-x86_64" } else { "linux-x86_64" };
    let clang = ndk.join("toolchains/llvm/prebuilt").join(host).join("bin").join(if cfg!(windows) { "clang.exe" } else { "clang" });
    if !clang.is_file() { return Err(format!("Android NDK compiler not found: {}", clang.display())); }
    // Never reuse generated files or overwrite another build.
    if let Some(parent) = out.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    std::fs::create_dir(&out).map_err(|error| format!("Choose an output path that does not already exist: {error}"))?;
    let out = out.canonicalize().map_err(|error| error.to_string())?;
    let work = out.join("generated");
    let compiler = recomp3ds::compile::Compiler::new(clang, &[
        "--target=aarch64-linux-android30", "-fPIC", "-fvisibility=hidden", "-Wl,-z,max-page-size=16384",
        "-Wl,--no-undefined", "-Wl,--no-as-needed", "-lm",
    ]);
    let options = recomp3ds::build::Options { dir: Some(&work), compiler: Some(compiler), ..Default::default() };
    println!("Building Android ARM64 code for Zakuro ABI {}. ROM, textures and saves are not copied or changed.", recomp_abi::ABI);
    let library = recomp3ds::build::build(&rom, &options, &|event| match event {
        recomp3ds::build::Event::Compiled { done, total } => {
            if done % 10 == 0 || done == total { println!("Compiled {done}/{total}"); }
        }
        other => println!("{other:?}"),
    })?;
    let target = out.join("title.so");
    std::fs::copy(&library, &target).map_err(|error| error.to_string())?;
    println!("Native code: {}", target.display());
    println!("In Zakuro: Game settings > Replace native game code. Select this title.so; the game and saves stay in place.");
    Ok(())
}
fn main() {
    if let Err(error) = run() { eprintln!("{error}"); std::process::exit(1); }
}
