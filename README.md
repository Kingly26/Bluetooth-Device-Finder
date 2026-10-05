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

Every push to `main` is built by GitHub Actions. Get the APK from
*Actions › latest successful run › Artifacts › `Bluetooth-Device-Finder-apk`* (GitHub login required).
Requires Android 8 or later.

## Limits

It only finds devices that are powered on and within Bluetooth range (about 10 m). Distance and
direction are estimates: walls and reflections shift them.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under the [MIT License](LICENSE).
