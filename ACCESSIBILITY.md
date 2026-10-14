# Accessibility

Bluetooth-Device-Finder is a hobby project. We want it to be usable by as many people as possible,
but it has **not been formally audited** and has **not yet been tested with a screen reader**. This
page describes honestly what works today and what doesn't.

## What works today

- **Information is not conveyed by graphics alone.** Everything shown by the radar and the cone view
  is also available as text: the device list gives name, state, distance and signal for each device,
  and the cone view always shows a written instruction ("Straight ahead", "Turn right 40°") together
  with the hot/cold label and the estimated distance.
- **Colour is not the only signal.** The blue-to-red colour scale is always paired with a text label,
  a distance and signal bars.
- **Non-visual feedback.** When tracking a device the app beeps faster as you get closer and vibrates
  when you are very close. The beep can be turned off, and its volume follows the phone's media volume.
- **Text follows the system font size.** All text is sized in scalable units.
- **Large touch targets.** List rows, the List/Radar selector and the beep setting are tappable across
  their whole width.
- **Languages.** English and Italian, following the phone's language.

## Known limitations

- **Screen readers (TalkBack).** The radar and the cone are drawn graphics with no spoken description,
  and the dots on the radar cannot be reached with TalkBack. Use the **List** view and the list under
  the radar instead: they contain the same devices. Direction changes are not announced automatically.
- **Very large font sizes** have not been tested; some rows may be truncated or crowded.
- **Dark theme only.** There is no light theme and no dedicated high-contrast mode, and colour
  contrast has not been measured against WCAG.
- **Portrait only.** The app does not rotate to landscape.
- **Finding the direction requires turning on the spot** while holding the phone flat. If that isn't
  possible for you, the distance, the hot/cold label and the beep still work without turning.
- **Small secondary text** (signal values, hints) uses a muted grey that may be hard to read for
  people with low vision.

## Reporting a problem

If something makes the app hard or impossible for you to use, please
[open an issue](https://github.com/Kingly26/Bluetooth-Device-Finder/issues/new/choose) and mention
the assistive technology you use (for example TalkBack, font size, display size or colour
correction), your phone model and Android version. Accessibility reports are treated as bugs.

Contributions that improve accessibility are very welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).
