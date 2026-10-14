# Bluetooth-Device-Finder

Android app (it shows up on the phone as **BT Finder**) for finding headphones, PCs, phones and any
other Bluetooth device transmitting nearby. It estimates distance from signal strength and direction
by matching the signal to the compass while you turn on the spot.

Local only: no server, no account, no network access. Independent project, not affiliated with or
endorsed by Bluetooth SIG.

- **List** of every detected device, nearest first, including unpaired and unnamed ones.
- **Radar** overview with all devices as numbered dots.
- **Cone view** for a single device, with hot/cold indicator, estimated distance and proximity beep.
- Available in English and Italian (follows the phone's language).

## Download

Every push to `main` is built by GitHub Actions.

**[➡ Open the list of successful builds](https://github.com/Kingly26/Bluetooth-Device-Finder/actions/workflows/build.yml?query=branch%3Amain+is%3Asuccess)**

1. Sign in to GitHub (artifacts are only downloadable when logged in).
2. Open the first run in the list.
3. At the bottom, under **Artifacts**, download `Bluetooth-Device-Finder-apk`.
4. Unzip it and install the `.apk` on your phone.

Requires Android 8 or later. Artifacts expire 90 days after the build.

## Limits

It only finds devices that are powered on and within Bluetooth range (about 10 m). Distance and
direction are estimates: walls and reflections shift them.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under the [MIT License](LICENSE).
