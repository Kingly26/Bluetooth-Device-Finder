# Security Policy

## Supported versions

Only the latest build from the `main` branch is supported. Older APKs do not receive fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report them privately through GitHub: go to the repository's **Security** tab and choose
**Report a vulnerability**
([direct link](https://github.com/Kingly26/Bluetooth-Device-Finder/security/advisories/new)).

Please include:

- what the problem is and what an attacker could do with it;
- steps to reproduce it;
- phone model, Android version and the app build you used.

This is a hobby project maintained in spare time, so there is no guaranteed response time. Reports
are read and handled on a best-effort basis, and you will be credited in the fix unless you prefer
otherwise.

## Scope

The app works entirely on the device: it has no server, no account and makes no network requests.
The areas most relevant to security are the handling of Bluetooth scan data and the permissions the
app requests (Nearby devices, Location).
