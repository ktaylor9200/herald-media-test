# Fork notes: in-feed artifacts and read-aloud

Status as of 2026-10-18. Keep this file current as work lands.

## Goal
The goal is proper in-feed artifact support, as in the Hermes Desktop app. Things the agent delivers (`MEDIA:` files,
`::preview` artifacts, Markdown images) should show and play right in the chat, not as download-only cards.
Separately, a speaker button under each reply reads it aloud with the profile's voice.

## Branches and PRs (all inside the fork)
| Branch | PR | What |
|---|---|---|
| `media-viewer` | #1 | File viewer + in-feed media, HTML pages, SVG drawings; also carries the fork-only dev-APK workflow |
| `read-aloud` | #2 | Speaker button under replies |
| `pilot` | #3 (draft, never merge) | `media-viewer` + `read-aloud` + two commits so they don't play over each other. **Test APKs come from here.** |

All three were synced with upstream `master` (3cc3f60) on 2026-10-18.

Feature commits (cherry-pick these to offer upstream; skip the `CI (fork only)` ones and merges):
- Media: `8f57533` viewer for HTML/audio/video and opening delivered files, `4740607` seek thumb fix,
  `4f8448e` in-feed audio/video/pages, `25b304c` SVG; review fixes `5271cdd` a page reads only its own folder and
  leaves it only on a tap, `6cf4101` no WebSockets before Load (with `ed9f0e9`, its build fix), `926a035` `.svgz` is a plain file, `db85209` the
  feed fetches through the chat's profile with the 64 MB cap.
- Read aloud: `0272d39`; review fix `8c3daea` dictating stops a reading.
- Pilot only: `a13c92d` the speaker and the feed's player take turns, `3dabf4d` dictation pauses the feed and the
  viewer's player stops a reading. They're only needed once both features are present.

## What's built
**File kinds** (`core/media/GatewayFile.kt`). New kinds are `Html`, `Svg`, `Audio` and `Video`. `fetchedWhole` covers
PDF, audio and video, which the viewer fetches through `/api/fs/download` (profile-aware, 64 MB cap). SVG is no longer
`Code`.

**Feed** (`ReplyMediaList` in `MediaViews.kt` routes to `FeedMedia.kt`):
- **Audio**: `ReplyAudio` is a 56dp pill with play/pause, the name over a `SeekBar`, the time, and a ⋮ menu (full screen,
  open in another app, save, share).
- **Video**: `ReplyVideo` plays in place on a `TextureView`. It has a scrubber and a full-screen button, and pauses when
  disposed (scrolled off).
- **One player per chat**: `FeedPlayer`, created in `ChatScreen` and provided through `LocalFeedPlayer`. It fetches on
  the first Play, plays one thing at a time, and keeps playing while its card scrolls away. It pauses when the app stops,
  and when the viewer opens.
- **Fetching**: recordings, films, pages, drawings and the ⋮ menu go through `LocalFileDownload` →
  `ChatViewModel.downloadFile` (`/api/fs/download`, the chat's profile, 64 MB cap, not cached), like the viewer. The
  gateway's reason for a refusal is shown. Pictures still use upstream's `loadMedia`.
- **HTML**: `ReplyPage` draws the page live. It is sized from the WebView's `contentHeight` and clamped to 96–460dp, with
  a fade below. A transparent overlay takes touches, so drags scroll the chat and a tap opens the page full screen.
- **SVG**: `ReplySvg` draws `svgPage(src)`, a tiny HTML page with `<img src=…>` on white. Scripts in the SVG don't run.
  Web SVGs wait behind `RemotePictureGate`, like web pictures.
- **Other files**: `ReplyFile` cards are tappable and open the viewer.

**Viewer** (`FileViewer.kt`): HTML and SVG have a Preview/Source switch. Audio and video play in `MediaPlayerView`. Files
with no preview, or that the phone can't play, get Open (`ACTION_VIEW` through the `.attachments` FileProvider), Save
and Share.

**Web view sandbox** (`HtmlView.android.kt`, rules in `HtmlView.kt`, all tested):
- The page loads at the made-up origin `https://files.herald.invalid/<gateway path>`, so relative links resolve to its
  folder. `pageUrlOf` and `gatewayFileOfPage` map between the two.
- `shouldInterceptRequest` serves that origin from the gateway, but only files `pageMayRead` allows: the page's own
  folder and below, no hidden file or folder. Before this, a page (say, one saved from a hostile site) could read any
  file the gateway serves, `~/.ssh` and token files included; the gateway's own deny list only covers `.env`,
  `auth.json`, `config.yaml` and a few more. Hermes Desktop gives such pages no gateway files at all (an opaque-origin
  `sandbox="allow-scripts"` iframe).
- Other http(s) is blocked until the person taps **Load**; blocked hosts are reported. Until then `guardedPage` puts a
  CSP `connect-src` (only the made-up origin, `data:` and `blob:`) after the doctype, which stops WebSockets and
  beacons, as the request filter never sees them.
- `pageLinkOf`: a link out of the page is followed only on a tap (`hasGesture`). Gateway files open in the viewer; web,
  mail and phone links open in their app; other schemes are dropped. A page can no longer send the browser to a site
  with what it read in the address.
- JavaScript is on. There are no cookies, no file or content access and no JS bridge.

**Read aloud** (`VoiceController.readAloud` / `stopReading`, `ReadAloud` state):
- It uses the same pipeline as the chained voice chat: `speakableText` → `speechChunks` → `/api/audio/speak?profile=`
  per chunk, with the next chunk voiced while one plays.
- The button (`ReadAloudButton` in `ChatScreen.kt`) is a speaker; a spinner while preparing; then an accented Stop.
  Another reply takes over. A voice chat or dictation stops it, and it won't start during either (the microphone would
  write it down).
- Errors go through `ChatRequest.Notice`.

## Device-tested
All of it is confirmed on the person's phone:
- Round 1: the viewer (HTML, audio and video full screen, Open).
- Round 2: in-feed media (the audio pill, inline video, inline pages).
- The speaker button (read aloud) works as designed.
- SVG drawings work in the chat feed and in the full-screen viewer (Preview/Source).
- Round 3 (2026-10-18, the review fixes, pilot APK of that day), all as expected:
  - A test page read its own `style.css` and `sub/data.json` and was refused `../outside.txt` and `.hidden.txt`, also
    after **Load**.
  - Its WebSocket was blocked until **Load**, Chart.js waited for **Load**, a scripted move to a site did nothing, and a
    tapped link opened the browser.
  - An MP3 and an MP4 played in the feed. A 70 MB film said it's over 64 MB. An SVG drew, and an `.svgz` was a file card.
  - Dictating stopped a reading and paused the feed's player. Opening a film full screen stopped a reading.
  - The APK installed over the last Herald Dev.
- Read aloud failed for a while after the server's Hermes v0.22.2 switch (2026-10-17): the new install lacked Hermes's
  `tts-premium` extra (ElevenLabs). That was a server problem, not the app's, and it was fixed on the server the same day.

## Herald push plugin (`herald-push`)
Checked 2026-10-18 against `Giton22/hermes-herald-push` (0.4.2; `master` = the pinned `PLUGIN_REF` 45e76c0 in content).
The fork changes nothing the plugin talks to (`core/push`, `core/phone`, `core/inbox`, notifications): only file kinds,
chat UI and voice. So it behaves as with upstream Herald, and Herald Dev registers as a phone of its own (its own app id,
keys and topics). Its Hermes calls exist in Hermes v0.22.2 and it needs in-process plugins (the default). On the
person's server, server plugins are platform tier: an unreviewed one is switched off within 5 minutes, so it stays off
until the push brief after the pilot review. Its ntfy calls carry no auth, so it can't use a login-only ntfy server.

## Open items and known gaps
- **Sandbox**: WebRTC and a `<link rel=preconnect>`'s DNS lookup still reach the web without Load (no WebView switch
  stops them; a JS shim is bypassed through a fresh iframe). With the folder rule, all a page could send that way is
  what its own folder holds.
- **Memory**: audio and video are still loaded whole into memory (64 MB cap). Hermes v0.22.2 has
  `/api/files/stream` (managed files, HTTP Range) that a streaming player could use; untested.
- **One origin for every page**, so pages share `localStorage`. A host per folder would split them.
- **Audio focus**: the feed's and viewer's players and read-aloud don't ask for audio focus (nor does upstream's voice
  chat), so other apps' sound plays on under them.
- **Performance**: each visible inline page or drawing is a live WebView, rebuilt when scrolled back. This hasn't been
  checked in a long chat.
- Inline height is fixed for pages with `100vh` layouts.
- Read aloud uses `/api/audio/speak` only. There's no streaming, so it waits for the first ~160-character chunk. It
  doesn't use GPT-Live's voice, and it keeps playing in the background with no foreground service.
- ROADMAP item "Run `::preview` widgets inline (a WebView with Desktop's theme prelude)" is largely done, but Desktop's
  theme prelude isn't injected yet.
- `dev-apk.yml` pins `actions/cache` to v6.1.0 and the runner to `ubuntu-24.04`. Each branch has its own cached debug
  key (caches are per branch); pilot's is the one that matters, as that APK is the one installed.

## Deferred by the person
- Inline rendering of Markdown, CSV and PDF `::preview` artifacts: **hold for now**.

## Next likely steps
1. Get device feedback on the pilot APK and fix what comes up on the branch that owns it, then merge into `pilot`.
2. If going upstream: open issues on Giton22/herald, then cherry-pick the feature commits onto branches from
   `upstream/master`. Add before/after screenshots from `PreviewGalleryActivity` and tick ROADMAP items.
