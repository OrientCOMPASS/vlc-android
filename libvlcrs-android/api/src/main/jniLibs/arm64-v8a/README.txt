libvlcrs.so is placed here by CI (cross compiled from the `libvlcrs/` workspace
of the vlc repository for aarch64-linux-android, release profile).

It is intentionally not committed: build it with

    cargo build --release --target aarch64-linux-android -p vlcrs-lite

or download it from the `libvlcrs-v1.0.0-arm64` release of the vlc repository.
