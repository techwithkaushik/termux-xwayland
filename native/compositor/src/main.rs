mod bridge;
mod protocol;

fn main() {
    env_logger::init();
    log::info!(
        "Termux XWayland bridge smoke test (protocol v{})",
        protocol::VERSION
    );
    if let Err(error) = bridge::run() {
        eprintln!("Bridge stopped: {error}");
        std::process::exit(1);
    }
}
