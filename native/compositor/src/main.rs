mod protocol;

fn main() {
    env_logger::init();
    log::info!(
        "Termux XWayland native scaffold started (bridge protocol v{})",
        protocol::VERSION
    );
    eprintln!(
        "Protocol framing and tests are available. This executable still does not create a Wayland socket, authenticate peers, transport frames, forward input, or start XWayland."
    );
}
