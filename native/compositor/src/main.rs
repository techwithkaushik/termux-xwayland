mod bridge;
mod protocol;

fn main() {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("TermuxXWayland"));
    #[cfg(not(target_os = "android"))]
    env_logger::init();
    log::info!(
        "Termux XWayland abstract Unix socket smoke test (protocol v{})",
        protocol::VERSION
    );
    if let Err(error) = bridge::run() {
        eprintln!("Bridge stopped: {error}");
        std::process::exit(1);
    }
}
