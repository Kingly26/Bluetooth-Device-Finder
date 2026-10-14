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

**[⬇ Download the latest APK](https://github.com/Kingly26/Bluetooth-Device-Finder/releases/latest/download/Bluetooth-Device-Finder.apk)**

Open the link on your phone and install the file. Android will ask you to allow installing from your
browser, and Play Protect may warn that the app is unverified because it is not from the Play Store.
Requires Android 8 or later.

The APK is rebuilt automatically on every push to `main`. Each build is signed with a different key,
so to update you need to uninstall the previous version first.

## Limits

It only finds devices that are powered on and within Bluetooth range (about 10 m). Distance and
direction are estimates: walls and reflections shift them.

## Accessibility

See [ACCESSIBILITY.md](ACCESSIBILITY.md) for what works today and the known limitations.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under the [MIT License](LICENSE).
