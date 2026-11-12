# tvos27

The shared tvOS 27 foundation for Home. It uses tvOS 26 (Liquid Glass) as its baseline, because little tvOS 27
material was public. `config.json` turns on `"design_language": "tvos27"` and `"focus_parallax": true`. Both keys
are off by default in `launcher/app/src/main/res/values/config.xml`, so other themes look the same as before.

These values come from Apple's HIG
(developer.apple.com/design/human-interface-guidelines: typography, layout, focus-and-selection, materials, color,
app-icons and tab-bars) and from tvOS 26 press screenshots:

- Type: Inter (SIL OFL, rsms/inter, `launcher/FONT-LICENSE-INTER-OFL.txt`), the closest free match to SF Pro,
  whose licence keeps it on Apple devices. It is a Latin subset of the variable font (weights 400 to 700, 77 KB),
  used only by tvos27: `TvDesign.font(ctx, TvDesign.MEDIUM)` and the type tokens, and Home's layouts swap their
  Outfit for Inter at the same weight. Sizes, in pt (1 pt = 1 px at 1920x1080): Title1 76/96, Title2 57/66, Title3 48/56, Headline 38/46, Callout 31/38,
  Body 29/36, Caption1 25/32, Caption2 23/30. The minimum size is 23.
- Layout: the safe area is 60 pt at the top and bottom and 80 pt at the sides. Gutters are 40 pt and rows are at least
  100 pt apart. Columns are 860/560/410/320/260/217/184/160 pt wide for 2 to 9 columns.
- Focus: the item scales to 1.13 and lifts 12 pt on a 40 pt shadow (52 pt in Light), with Home's window shadows
  stronger than Android's default (`TvosLighting`, spot 50%). On Home the focused card also gets a faint 2 pt white
  rim and the others sit at 86% brightness (a colour filter on their images, animated only during the focus change).
  Select presses it down to 1.02. Parallax tilts it
  up to 6° and back on a D-pad press. As the HIG's Focus and selection describes, nothing sweeps across the item,
  it does not overshoot, and its colours do not change.
- Liquid Glass: a white tint at 20% to 10%, a top sheen at 25%, and a 1.5 pt rim at 40% to 12%. There is no blur,
  because the launcher never blurs. A focused control is a white (#F5FFFFFF) capsule with dark text. Panels have
  44 pt radii, and cards have 28 pt radii.
- Motion: focus takes 150 ms and unfocus 140 ms. A press takes 90 ms, a nudge 260 ms and the shelf's cross-fade 520 ms. Screens
  enter in 320 ms and exit in 220 ms. Ease-out is (0.2, 0.8, 0.2, 1) and ease-in-out is (0.4, 0, 0.2, 1). The spring
  has stiffness 500 and damping 0.82. With `reduced_motion`, every one of these takes 90 ms or less.

The shared API for screen work is in `launcher/app/src/main/java/com/gathin/tvhome/`:

| Class | Use |
|---|---|
| `TvDesign` | Tokens (`Type`, `Layout`, `Radius`, `Focus`, `Glass`, `Color`, `Motion`). `px(ctx, pt)` converts points to pixels, and `type(textView, Type.BODY)` sets a text style. |
| `TvFocus` | `Spec.of(cfg)`. `focus(v, focused, spec)` goes in OnFocusChangeListener and `key(v, event, spec)` in OnKeyListener. `snap(v, focused, spec)` is for binding a view, and `press(...)` for Select. |
| `LiquidGlass` | `LiquidGlass.panel(ctx)` makes a glass platter, and `LiquidGlass.control(ctx)` a capsule that turns white when focused. Both are drawables. |
| `TvMotion` | `easeOut()` and `easeInOut()`. `enter(v, ...)` and `exit(v, ..., done)` bring panels in and out. `open(activity, ...)` and `close(...)` run the zoom transitions between screens. `focusSpring(spring, reduced)` sets the focus spring. |

`Config.tvos27()` reports whether a screen should use these classes.

## Background

With no picture of its own (no `wallpaper.jpg`, no `wallpapers/`, no phone photos), tvos27 draws tvOS's calm
default: near-black with a faint cool graphite lift at the top, #1C1F26 down to #08090B (`TvDesign.backdrop()`, a
two-stop GradientDrawable, so no image ships). Home's grid, the App library, Settings and the screensaver's fallback
all use it; a theme wallpaper or phone photos draw over it. `background_color` is ignored under tvos27. In the Light
appearance it is a soft light grey, #F2F2F7 down to #D9DAE0; the screensaver's fallback stays dark.

## Appearance

Settings > Appearance > Appearance offers Dark (the default), Light and Automatic, as Apple TV's Settings > General >
Appearance does; a theme sets the default with `"appearance": "dark" | "light" | "auto"`. It is a token swap, not a
second look: `TvDesign.applyAppearance` sets the `Color`, `Glass` and `Focus.SHADOW` tokens to the light or dark
palette before an activity builds its views, and every tvos27 screen reads them, so there are no extra layers or
animations. Light follows the HIG's light palette:

- Text is Apple's light labels: black, then #3C3C43 at 60%, 30% and 18%.
- Liquid Glass is frosted white: a 72% to 55% white tint, a 35% sheen and a rim that goes from 94% white at the top to
  a faint grey at the bottom, which reads as a soft shadow edge. A focused control fills pure white, and focused cards
  rise on a stronger 36 pt shadow, since white sits on white. A module inside a panel (Control Center's tiles, Now
  Playing) is a faint grey well instead of more white, and fills white under focus.
- Platters that float over anything (Control Center, the menus) sit on a near-white base (#E5E5EA at 95%).
- Dims and scrims are light grey rather than black. The TMDB backdrop and the shelf keep their pictures; the scrim
  over them washes the lower part light grey for the dock, and the logo's baked shadow is softer.

Automatic is light from sunrise to sunset and dark otherwise, or light whenever the TV's own UI mode says light
(`UiModeManager` night mode "no"; TVs report dark by default without anyone choosing it, so dark is not followed).
The sun's times come from `Sun` at config.xml's `latitude` and `longitude` when a build sets them; otherwise from the
TV's time zone, with no location permission (`ZonePlace`): the zone's main city for about 60 common zones, else the
longitude of its standard offset and a typical latitude for its continent. Off by a few hundred kilometres, sunrise
moves by minutes. A zone with no place (UTC) keeps 07:00 to 19:00; under midnight sun it is light all day, and in
polar night dark. Changing it rebuilds Settings on the Appearance level at once and Home when you return to it, with
no restart; Home also checks Automatic each time it comes back, so the turn at sunrise and sunset shows the next time
Home is shown.

## Home

With `cinematic_home` on, Home follows the tvOS 26/27 Home Screen. The backdrop behind the focused top-row app is
a soft gradient in its colours: a dominant colour, a second hue and a muted base are sampled from its banner
(`HeroArt.palette`), and the gradient fades to near-black at the bottom and edges. An app with no hue, such as one with a
black and white banner, gets graphite. The banner itself is never drawn there, since Home shows no app data. Moving along the row cross-fades it. The app's name sits at the top left in bold at 84 pt, and
the large clock gives way to one glass capsule at the top right: the time with the Google account's avatar in it.
The top row has 6 columns and rests low in a floating Liquid Glass platter. The tiles sit one gap apart
(`TvDesign.Layout.dockGap`, 40 pt for 6 columns), and the platter's padding on every side is that gap plus a focused
tile's growth (`dockPad`, about 54 pt), so a focused end tile, grown 12%, is still a full gap from the glass. The grid is
inset so the platter ends exactly on the safe area, and the rows below line up under the dock. Corners are concentric
(HIG): a tile's radius is `Radius.CARD` in proportion to its width (28 pt on a 400 pt icon), and the platter's is that
plus the padding. A focused tile rises 12 pt and stays inside the glass; its shadow is not clipped at the safe area. The
next row peeks in at the bottom. The
platter stays round the top row in the grid too. It is one drawable (tint, top sheen, rim), with no blur. Pressing Down
lifts the shelf away and fades it out to Home's own background (the wallpaper, or `TvDesign.backdrop()`) as the grid
scrolls up; going back up to the top row fades the shelf back in.

The top left stays empty: no app name or "Last opened" line. The time capsule is the only control up there:
selecting it opens Control Center. The other top-bar pills stay hidden, and the App library is the pill at the end
of the grid (`show_library_pill`). `fade_rows` is off, because the grid scrolls its top row into the fade.

## App library and Control Center

With `app_library` on, the App library is a full screen in Home's columns, as tvOS lays out its app grid. Each app
is a 28 pt card: its TV banner edge to edge, or its icon on a card tinted with its own colours. Its name and
status show under it only while it has focus. The backdrop cross-fades to the focused app's colours, as Home's
does. The library zooms up over Home and shrinks back into it, and holding OK opens a glass menu by the card.

With `settings_key_panel` on, the remote's Settings button opens Control Center, and so does Home's time capsule. It
is a glass panel at the top right that slides in from the edge over a soft dim and slides back out on Back. The
time and date sit on top. Below them are glass tiles that fill white under focus, in place: they sit a gap apart, so unlike Home's cards
they do not grow or tilt (`TvFocus.Spec.flat()`), and only a soft shadow lifts them: tvOS's large Sleep tile, Network
(with the network's name) where it has Wi-Fi, Display and the sleep timer, then a row of small tiles: Settings,
Inputs (only on a TV with inputs), Apps and Launcher settings. Now Playing sits under them (see Sleep and Now Playing).
Above them is the profile: the Google account's avatar and address. Home's time capsule fades out
while Control Center or the Inputs panel is up, so it never shows through the glass.

Since Android 8 an app sees no account it has not been given, so the profile starts as a person with "Choose
account". Selecting it opens Android's own account chooser (`android/.accounts.ChooseTypeAndAccountActivity`,
named outright: Google TV points the standard chooser at GMS's picker, which hands it to launcherx's profile chooser
and crashes once launcherx is disabled). Picking the account there lets the launcher see it, so the profile and
Home's capsule show its address's first letter on a colour of its own (`GoogleAccount`; a photo would need OAuth).
From then on the profile opens Google TV's Accounts and profiles
(`com.android.tv.settings/com.google.android.tv.settings.AccountActivity`), then Android TV's accounts list, then
Settings. Never the bare `SYNC_SETTINGS`: with no account named it shows "Unknown account".

## Sleep and Now Playing

**Sleep** puts the TV in standby at once, through the accessibility "lock screen" action (Android 9 and later), as the
`sleep` action word and the Sleep Timer add-on do. An ordinary app cannot sleep a TV, so one of the launcher's key
helpers must be on (Home button in TV Settings > Accessibility, or a `HOME_KEY_SERVICE=1` / `INPUT_KEY_SERVICE=1`
install); without one, Sleep says so. **Timer** opens a list in Control Center's place, as tvOS opens its pickers:
Off, 15, 30, 60 or 90 minutes. While one runs, Sleep shows "In 23 min", the timer tile the minutes left, and Home's
time capsule a small moon; Off cancels it, and Sleep cancels it too. The end time is kept in prefs, a main-thread
Handler fires it on time while the launcher's process lives (it always does while a key helper is bound), and an
AlarmManager alarm is the backstop after the process is killed: exact if the launcher may set exact alarms
(`adb shell appops set com.gathin.tvhome SCHEDULE_EXACT_ALARM allow`), else within 10 minutes. No service runs in
between. With no key helper when it ends, the screensaver starts instead (`SleepTimer`).

**Now Playing** shows the active media session: its artwork (or the app's icon), title, artist and app, with
previous, play/pause and next as round glass buttons that fill white under focus. A playing session wins over a
paused one, and the module hides when nothing is playing. Reading media sessions needs notification access for
`NowPlayingListener`, once:

    adb shell cmd notification allow_listener com.gathin.tvhome/.NowPlayingListener

or TV Settings > Apps > Special app access > Notification access. The listener reads no notification and asks to be
unbound as soon as it connects, so nothing stays bound; the access stays granted. Without it the module is a hint
that opens that screen where the TV has it. Its session callbacks are registered only while Control Center is open
(`NowPlaying`).

## Navigation sounds

A soft tick when focus moves between cards, tiles and rows (Home, the App library, Control Center, Settings, Inputs)
and a lower one on Select, as cleanyourtv-ui plays them (`NavSound`). Many TVs have Android's own sound effects off
(`sound_effects_enabled=0`, TCL), so the two sounds are synthesized as tiny WAVs at first use, with no assets, and
played quietly through one SoundPool, only from the launcher's own windows. Where the TV's system sounds are on,
Android ticks itself, so these stay silent rather than double it. Settings > General > Navigation sounds turns them
off; `"navigation_sounds": false` in a theme sets the default.

## Inputs

The Inputs picker is a Control Center sub-panel, as tvOS shows its audio output and AirPlay pickers (`TvosInputs`). It
takes Control Center's place at the top right: "Inputs", then one row per input with a glyph in a round well (HDMI, AV,
Live TV), the CEC device's name or the port's, and the port and its state under it ("HDMI 1 · Connected"). The input
last switched to from here carries a checkmark and gets focus first, since Android does not say which input is on
screen. A focused row turns white with dark text and lifts slightly. Which inputs are listed, folded, named and how
each opens is unchanged (`MainActivity.showInputs()`). It opens from Control Center's Inputs tile, shown only when there
are inputs or Live TV, where Back returns to Control Center, and straight from the remote's Input button (see Remote
buttons) or VIEW_INPUTS, where Back returns to Home.

## Settings

Launcher settings use the tvOS Settings layout (`TvosSettings`). The title is centred at the top. A large silver
tile with a glyph sits on the left, with the focused row's description under it, and the list is on the right. The
list is grouped into Home Screen, Appearance and General. Each row shows its value in grey, with a chevron if it
opens another level. A focused row turns white with dark text and lifts slightly. Choices such as Apps per row,
Wallpaper and Clock open their own level of checkmarked options. A new level slides in from the right, and Back
slides it out and returns focus to the row it came from. In the Wallpaper list, the left tile shows a preview of the
focused picture, decoded at a small size off the main thread. Advanced effects asks for confirmation in a tvOS alert:
the screen dims and stacked glass buttons appear. The settings and what they save are unchanged. The screen behind the
Home picker uses the same background and shows the house tile.

## Wallpaper from your phone

Settings > Wallpaper > Choose from phone… shows a QR code and its address. Scanned on a phone on the same
Wi-Fi, it opens a page whose button opens the phone's photo picker, Google Photos included, with no sign-in.
Each photo is cropped to the panel's shape, scaled to its physical size (at most 3840x2160) and saved as JPEG in the
theme's `wallpapers/` folder as `phone-*.jpg`. The sheet counts them as they arrive, and Done makes them the
wallpaper ("Phone photos"), taking turns every 10 minutes unless the theme sets `wallpaper_rotation_minutes`;
the screensaver shows them too. A new visit replaces the last one's photos, and Remove phone photos deletes them.

The server (`PhoneUpload`) runs only while the sheet is open, and stops after Done or 10 minutes. Every request
needs the sheet's random one-time token from the QR address and must come from a private address. A photo is at
most 20 MB, a visit at most 30 photos, and only JPEG, PNG and WebP are kept. On the emulator, forward the port
shown in the address and send a photo from the computer:
`adb forward tcp:<port> tcp:<port>`, then `curl --data-binary @photo.jpg "http://127.0.0.1:<port>/photo?t=<token>"`.

## Remote buttons

On a TCL Google TV with Google TV's launcher (launcherx) disabled, the remote's app buttons and the gear
reach nothing: TCL hands them to launcherx (`docs/devices/tcl-g10-app-keys.md`). This theme's `app_keys`
bring them back, with the codes measured on TCL units in `docs/devices/tcl-*.md` and
`examples/tcl-g08-us-google-tv`:

| Button | Code | Opens |
|---|---|---|
| Netflix | 4062 | Netflix |
| Prime Video | 4099 | Prime Video |
| YouTube | 4065, or 190 on the G08 | YouTube |
| Apple TV | 4160 | Apple TV |
| TCL tv+ / TCL Channel | 4184, or 4020 | TCL TV+ (`com.tcl.tv.plus`) |
| Input | `KEYCODE_TV_INPUT` | the Inputs panel |
| Gear | `KEYCODE_NOTIFICATION` (83, in `settings_key_keycodes`) | Control Center |

`KEYCODE_FEATURED_APP_1` (297) and `KEYCODE_MACRO_1` (313) also open TCL TV+: this TV's TCL `global_keys` overlay
routes them to the disabled launcherx. Disney+ has no entry: TCL opens it itself.

On another brand these entries are harmless. An entry whose app is not installed is skipped and the key goes on to
the TV as if it were not listed, so `com.tcl.tv.plus` entries do nothing elsewhere (`RemoteKeys.handle`). The codes
from 4000 up exist only in TCL's firmware, and Home answers only those itself; 297 and 313 are Android's rarely used
`FEATURED_APP_1` and `MACRO_1`. 190 is `KEYCODE_BUTTON_3`, which is also a game controller's third face button: a press
from a controller (an input device that is both a gamepad and a joystick) always goes to the app, never to `app_keys`
(`RemoteKeys.passThrough`), so a game never opens YouTube.

On Home the launcher answers these itself. From inside other apps a key helper must be on: build with
`HOME_KEY_SERVICE=1 launcher/build-and-install.sh`, or turn on Home button in TV Settings > Accessibility. It
answers the app buttons, Input and the gear; `INPUT_KEY_SERVICE=1` answers the app buttons and Input only. On TCL's Android 12 firmware a key-filtering helper stops a held volume key from
ramping (`docs/devices/tcl-55p71b-g09-key-repeat.md`); that page covers the trade.

TCL models differ. If a button does nothing, read its code while pressing it on the real remote:
`adb shell getevent -lt` (the scancode, in hex) or `adb logcat -s WindowManager:D | grep handleKeyBeforeQueueing`
(the keycode), then add `"<keycode>:<package>"` or `"scan:<scancode>:<package>"` to a copy of this theme's
`app_keys` and push it with `tools/theme.sh`. TCL's codes from 4000 up exist only in TCL's firmware: the emulator
turns `input keyevent 4062` into `KEYCODE_UNKNOWN`, so they can be tried only on the TV.

## TMDB Top Shelf

With a TMDB key, resting on a streaming app in the top row for 3 s cross-fades the gradient to full-screen backdrops
of today's top titles on that service, films and series in turn. A new one fades in every 6 s, settling from a slight zoom,
and moving focus brings the gradient straight back. To stay light, at most two full-screen layers draw: the
gradient stops drawing under a backdrop, the wallpaper stops drawing under the shelf, old bitmaps go once a fade
ends, and nothing animates between fades. Timers and downloads stop in the grid and while Home is not visible. The key never goes in this folder: copy `config.json` somewhere outside the repo, add the keys
below and push that copy with `tools/theme.sh`. This local fork adds the `INTERNET` permission for it.

| Key | Value |
|---|---|
| `tmdb_key` | A TMDB v3 API key, or a v4 read access token (`eyJ…`), from themoviedb.org → Settings → API |
| `tmdb_region` | Two letters (`"US"`, `"IN"`), or `"auto"` (the default; see Region below) |
| `tmdb_providers` | Optional: `{ "com.some.app": "8,1796" }` (ids separated by commas) sets an app's provider ids by hand, over the match, and `"off"` turns one off |

**Which apps get backdrops.** No table of apps is kept. Once a day the launcher fetches TMDB's provider list for the
region (`/watch/providers/movie` and `/watch/providers/tv`, kept in `cache/tmdb/providers-<region>.json`) and matches
each app's own label to a provider's name (`TmdbMatch`). Both are normalised first: lowercase, with "+", "plus", "tv",
"app", punctuation and anything in brackets such as "(demo)" taken out, so "Disney+" meets "Disney Plus" and "Tubi"
meets "Tubi TV". An exact normalised match wins, then a provider whose name has all the label's words, then one that
shares most of its words (at least 60%); TMDB's own display order breaks a tie. Resellers ("Amazon Channel", "Apple
TV Channel", "The Roku Channel") never match, and tiers of the matched service come with it: Netflix with "Netflix
Standard with Ads", Peacock Premium with Peacock Premium Plus. The day's matches are kept in
`cache/tmdb/matches-<region>-<day>.json`, and an app installed later is matched the first time it is focused.
YouTube, the Play Store and Settings never match; an app that matches nothing keeps the gradient. Three labels need an
alias, in `TmdbMatch.ALIASES`: "Apple TV" is TMDB's "Apple TV+" (its "Apple TV" is the rental store), "Prime Video" is
"Amazon Prime Video", and "Max" and "HBO Max" stand for each other, as the service was renamed. `adb logcat -s Tmdb`
prints each app's match and the region.

**Region.** `"auto"` is the country of the TV's time zone (ICU's `TimeZone.getRegion`, so America/Los_Angeles is US):
many TVs ship with an en-GB or en-US locale wherever they are sold, and TMDB has no Hulu or Peacock titles in GB. A
zone with no country (UTC) falls back to the locale's country, then US. When a service has no titles in the region,
its list is asked for once more in the locale's country; an empty answer is kept for the day, so it is not asked again
until midnight.

Each app gets its top 16 for the day. The candidates come from `discover/movie` and
`discover/tv` with `with_watch_providers` and `watch_region`, sorted by popularity, and a series must have aired in the
last 90 days. Titles in `trending/{movie,tv}/day` go first. Requests go to api.themoviedb.org; on a connection, DNS or TLS failure, or an answer that is not
JSON (a block page), the same request is tried once on api.tmdb.org, TMDB's official alternate domain, and that host is
kept for the rest of the day. An HTTP error such as 401 for a wrong key is not retried. Lists are rebuilt at local
midnight, and the last list is
used when offline. Backdrops
are cached in `cache/tmdb/img`, up to 120 MB: the original (3840 wide) when the panel is 4K, which is read from the
display mode's physical size since many Google TVs draw their UI at 1080p on a 4K panel, and w1280 otherwise. Each
is decoded in RGB_565 to the shelf's own pixels, at most 3840x2160 (16.6 MB), into the bitmap the last fade let go. An app with no provider (YouTube, Settings), no key, or any network error keeps the
gradient. On a debug build, `tmdb-demo/push.sh` seeds the cache with a fixture (backdrops, logos, and a US snapshot of
the provider list to match against) so the dwell can be shown without a key
or without access to api.themoviedb.org. `push.sh <serial> --clear` removes it.

### Title logos and full screen

Each backdrop shows its title's logo at the bottom left, resting a gutter above the dock's left edge, at most a third of
the screen wide and a fifth high: TMDB's `/{movie,tv}/{id}/images` logos, a PNG in the TV's language, else English,
else one with no text, the best voted (SVGs are skipped). The logo paths are fetched with the day's list and saved in
it; the w500 PNGs are cached beside the backdrops (`w500-*`) and decoded off the main thread with a soft shadow baked
in once. The logo fades out as its backdrop leaves and in with the next. A title with no logo shows nothing: no text
stands in.

With a backdrop showing and focus in the dock, Up shows it full screen, as tvOS's "swipe up for full screen": the
dock, the rows under it and the time capsule fade and sink away, the logo stays, and the rotation slows to 12 s a
title. Any key brings Home back with focus where it was, and the key does nothing else (Back does not leave, Select
does not open the app); volume passes through. Up from full screen goes on to the time capsule, and over the gradient
(an app with no backdrop) Up goes straight to it, as before. The first time a backdrop appears, "Press ▲ for full
screen" shows under the dock for a few seconds, once ever.

## Edit mode and folders

Holding Select on a Home app opens a glass menu: Edit Home Screen, Move, Hide from Home and App info (a folder's:
Edit Home Screen, Move, Rename Folder). Edit mode is the launcher's reorder mode restyled: the tiles wobble a degree
either way, neighbours out of step (one animator rotating the tiles on screen, only while the mode is on, and none
with `reduced_motion`; leaving the mode any way, or Home, levels every tile, and a tile that scrolled out mid-wobble is
levelled when it comes back or is rebound), and a glass capsule at the top says what to do. Select picks an app up, the arrows carry it,
Select puts it down. Play/Pause, or Back with nothing in hand, ends the mode and saves the order (`Order`, as before).
Hide is the library's Remove from Home.

Carrying an app onto another rests it there for a moment, and that app grows a little: Select then makes the two a
folder in that app's place, or adds the app to the folder it rests on. Waiting, or the next arrow, moves on as before.
A folder's tile is a 3x3 mini-grid of its apps. Selecting it zooms a glass panel up over a dimmed Home with the
folder's name and its apps in rows of four; Up to the name and Select renames it with the on-screen keyboard (a new
folder opens with its name ready). Holding Select or Menu on an app in it offers Move to Home Screen, which puts it
right after the folder; the last app out removes the folder. Folders are kept in the launcher's `tvos27` preferences
(`Folders`); each folder's tile takes its place in the saved order like an app.

## Screensaver

The idle clock (`ClockActivity`, after `home_idle_clock_minutes`, 10 here) and the system screensaver (`ClockDream`)
become an Aerial (`AerialScene`, layout `dream_aerial`). Full-screen stills push slowly in or out with a little drift
and dissolve into one another every 30 s. The time and date rest at the top right, and each still's name fades in at
the bottom left for 6 s, with the credit after a ` · ` in captions.txt on a smaller line under it. On the idle
clock the arrows bring the name back, as touching an Apple TV remote does, and any other key fades back to Home.
The stills are the TMDB Top Shelf's cached backdrops when a key (or the debug fixture) is set, with no network of
their own, then the theme's `wallpapers/` with their `captions.txt`, then Home's default gradient. The push
is a RenderNode scale and translation, the dissolve runs on a hardware layer, and the next still is decoded off the
main thread into the bitmap that just left the screen. `reduced_motion` keeps the stills still.
