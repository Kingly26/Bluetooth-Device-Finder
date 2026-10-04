# Contributing to Bluetooth-Device-Finder

Thanks for your interest in the project! Bug reports, ideas and pull requests are all welcome.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Questions and ideas

For general questions, or to talk an idea through before writing code, please use
[Discussions](https://github.com/Kingly26/Bluetooth-Device-Finder/discussions).

## Reporting a bug

Open an issue with the **Bug report** template. Bluetooth behaviour varies a lot between phones, so
the most useful details are:

- phone model and Android version;
- the kind of device you were looking for (earbuds, laptop, tracker…) and whether it was paired;
- what you did, what happened, and what you expected.

Security problems should **not** go in a public issue: see [SECURITY.md](SECURITY.md).

## Proposing a change

1. Fork the repository and create a branch from `main`.
2. Make your change, keeping it focused on one thing.
3. Open a pull request against `main` and fill in the template.

### Building

There is no Gradle wrapper in the repository. Every push and pull request is built by GitHub Actions
(`.github/workflows/build.yml`), which produces the APK as the `Bluetooth-Device-Finder-apk` artifact.
To build locally you need JDK 17, the Android SDK (API 35) and Gradle 8.14.x:

```bash
gradle :app:assembleRelease
```

The build must be green before a pull request can be merged.

### Guidelines

- **Local only.** The app has no server, no account and no network access. Changes that send data
  off the phone will not be accepted.
- **Be honest about limits.** Signal-based distance and direction are estimates; please don't add
  UI text that promises more precision than the hardware can give.
- **Test on a real phone** when you touch scanning, sensors or audio, and say which phone you used.
  Emulators don't provide real Bluetooth.
- Match the style of the surrounding Kotlin code. Code comments and UI strings are currently in Italian.
- Keep new dependencies to a minimum.

## Licence

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
