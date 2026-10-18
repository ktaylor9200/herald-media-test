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
| `pilot` | #3 (draft, never merge) | `media-viewer` + `read-aloud` + one commit so they don't play over each other. **Test APKs come from here.** |

All three were synced with upstream `master` (3cc3f60) on 2026-10-18.

Feature commits (cherry-pick these to offer upstream; skip the `CI (fork only)` ones and merges):
- Media: `8f57533` viewer for HTML/audio/video and opening delivered files, `4740607` seek thumb fix,
  `4f8448e` in-feed audio/video/pages, `25b304c` SVG.
- Read aloud: `0272d39`.
- Pilot only: `a13c92d` the speaker and the feed's player take turns. It's only needed once both features are present.

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
- **HTML**: `ReplyPage` draws the page live. It is sized from the WebView's `contentHeight` and clamped to 96–460dp, with
  a fade below. A transparent overlay takes touches, so drags scroll the chat and a tap opens the page full screen.
- **SVG**: `ReplySvg` draws `svgPage(src)`, a tiny HTML page with `<img src=…>` on white. Scripts in the SVG don't run.
  Web SVGs wait behind `RemotePictureGate`, like web pictures.
- **Other files**: `ReplyFile` cards are tappable and open the viewer.

**Viewer** (`FileViewer.kt`): HTML and SVG have a Preview/Source switch. Audio and video play in `MediaPlayerView`. Files
with no preview, or that the phone can't play, get Open (`ACTION_VIEW` through the `.attachments` FileProvider), Save
and Share.

**Web view sandbox** (`HtmlView.android.kt`):
- The page loads at the made-up origin `https://files.herald.invalid/<gateway path>`, so relative links resolve to its
  folder. `pageUrlOf` and `gatewayFileOfPage` map between the two (tested).
- `shouldInterceptRequest` serves that origin from the gateway and blocks other http(s) until the person taps
  **Load**; blocked hosts are reported.
- JavaScript is on. There are no cookies, no file or content access and no JS bridge. Links to gateway files open in
  the viewer; web links open in the browser; other schemes are dropped.

**Read aloud** (`VoiceController.readAloud` / `stopReading`, `ReadAloud` state):
- It uses the same pipeline as the chained voice chat: `speakableText` → `speechChunks` → `/api/audio/speak?profile=`
  per chunk, with the next chunk voiced while one plays.
- The button (`ReadAloudButton` in `ChatScreen.kt`) is a speaker; a spinner while preparing; then an accented Stop.
  Another reply takes over. A voice chat stops it, and it won't start during one.
- Errors go through `ChatRequest.Notice`.

## Device-tested
All of it is confirmed on the person's phone:
- Round 1: the viewer (HTML, audio and video full screen, Open).
- Round 2: in-feed media (the audio pill, inline video, inline pages).
- The speaker button (read aloud) works as designed.
- SVG drawings work in the chat feed and in the full-screen viewer (Preview/Source).

## Open items and known gaps
- **Sandbox gap**: WebSockets and WebRTC don't go through `shouldInterceptRequest`, so a page can open them without
  Load. Fix with a CSP `connect-src` injected into the page, or by blocking them in a `WebViewClient`/JS shim.
- **Memory**: audio and video are loaded whole into memory. Feed loads go through `loadMedia` → `MediaApi.file`
  (`/api/files/download`), which has no size cap and ignores the chat's profile; the viewer's path has both. Make the
  feed use `downloadFile`. Streaming with range requests is untested against the gateway.
- **One origin for every page**, so pages share `localStorage`.
- **Performance**: each visible inline page or drawing is a live WebView, rebuilt when scrolled back. This hasn't been
  checked in a long chat.
- Inline height is fixed for pages with `100vh` layouts.
- Read aloud uses `/api/audio/speak` only. There's no streaming, so it waits for the first ~160-character chunk. It
  doesn't use GPT-Live's voice, and it keeps playing in the background with no foreground service.
- ROADMAP item "Run `::preview` widgets inline (a WebView with Desktop's theme prelude)" is largely done, but Desktop's
  theme prelude isn't injected yet.
- `dev-apk.yml` uses `actions/cache@v4` (a Node 20 deprecation warning). Pin it to a newer commit if it breaks.

## Deferred by the person
- Inline rendering of Markdown, CSV and PDF `::preview` artifacts: **hold for now**.

## Next likely steps
1. Get device feedback on the pilot APK and fix what comes up on the branch that owns it, then merge into `pilot`.
2. Close the sandbox gap and route feed loads through the profile-aware, capped download.
3. If going upstream: open issues on Giton22/herald, then cherry-pick the feature commits onto branches from
   `upstream/master`. Add before/after screenshots from `PreviewGalleryActivity` and tick ROADMAP items.
