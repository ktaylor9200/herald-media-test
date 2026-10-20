# Fork notes: in-feed artifacts and side chats

Status as of 2026-10-20. Keep this file current as work lands.

## Goal
The goal is proper in-feed artifact support, as in the Hermes Desktop app. Things the agent delivers (`MEDIA:` files,
`::preview` artifacts, Markdown images) should show and play right in the chat, not as download-only cards.
Separately, a speaker button under each reply read it aloud with the profile's voice; upstream shipped its own in
v0.12.0 (#230), which replaced the fork's on 2026-10-20.

Second project (2026-10-19, fork only by the person's choice, never offered upstream): the Bots tab of the sidebar
reaches a bot's other chats ("Side chats"), and New chat starts beside the open chat.

## Branches and PRs (all inside the fork)
| Branch | PR | What |
|---|---|---|
| `media-viewer` | #1 | File viewer + in-feed media, HTML pages, SVG drawings; also carries the fork-only dev-APK workflow |
| `read-aloud` | #2 (closed) | Retired 2026-10-20: upstream's Read aloud (v0.12.0, #230) does all it did and more. Kept for history; not merged into `pilot` any more |
| `side-chats` | #4 | Bots tab: Side chats, Hidden last, New chat beside the open chat, a side chat reopens on launch. Fork only |
| `pet-room` | #5 | The last reply's actions leave room for the pet, so Read aloud isn't under it. An upstream bug too; fork only unless the person says |
| `pilot` | #3 (draft, never merge) | `media-viewer` + `side-chats` + `pet-room` + one commit so upstream's Read aloud and the feed's player take turns. **Test APKs come from here.** |

All were synced with upstream's **v0.12.0 tag** (c781aca) on 2026-10-20: the release, not `master`, which since has
Suggested replies through a community server plugin (Next Prompt) the person hasn't decided on. Sync to a release tag
until then (`git merge v0.X.0` on `master`). For the switch to upstream's Read aloud, `pilot` reverted the fork's four
read-aloud commits (`0272d39`, `8c3daea` and the two pilot-only ones) and re-added the turn-taking as `7a7d6cd`.

Since 2026-10-19 (upstream `master` 8861253, its inline `::preview` widgets): upstream's
`InlineWidget` takes a `::preview` HTML page (Desktop's theme, its own strict sandbox); this fork's `ReplyPage` still
draws `MEDIA:` HTML files. `ReplyMediaList` routes a `::preview` page to the widget first, then everything else as
before.

Feature commits (cherry-pick these to offer upstream; skip the `CI (fork only)` ones and merges):
- Media: `8f57533` viewer for HTML/audio/video and opening delivered files, `4740607` seek thumb fix,
  `4f8448e` in-feed audio/video/pages, `25b304c` SVG; review fixes `5271cdd` a page reads only its own folder and
  leaves it only on a tap, `6cf4101` no WebSockets before Load (with `ed9f0e9`, its build fix), `926a035` `.svgz` is a plain file, `db85209` the
  feed fetches through the chat's profile with the 64 MB cap; `4a5b061` pages, drawings and widgets come folded.
- Read aloud (retired, superseded by upstream #230): `0272d39`, `8c3daea`.
- Side chats (fork only): `fe0919b` Hidden below the rooms, `f4942a8` New chat beside the open chat, `a3e3daa` a
  side chat reopens on launch, `868cd03` Side chats.
- Pet room: `5a11c04` the last reply's actions leave room for the pet (`petSize`, `petRoom`, `LocalPetRoom` in `ChatScreen`).
- Pilot only: `7a7d6cd` the feed's player and upstream's Read aloud take turns (replaces `a13c92d` and `3dabf4d`).

## What's built
**File kinds** (`core/media/GatewayFile.kt`). New kinds are `Html`, `Svg`, `Audio` and `Video`. `fetchedWhole` covers
PDF, audio and video, which the viewer fetches through `/api/fs/download` (profile-aware, 64 MB cap). SVG is no longer
`Code`.

**Feed** (`ReplyMediaList` in `MediaViews.kt` routes to `FeedMedia.kt`):
- **Folded until asked** (the person's request, 2026-10-19): pages, drawings and `::preview` widgets (`foldOf`) come as a
  card at their place in the reply, with a full-screen button; a tap shows the item (`FoldedItem`, Hide under it).
  Nothing is fetched or drawn while folded. What's shown stays shown while the chat is open (`ShownItems`, per chat).
  Pictures, recordings and films (nothing loads until Play) and web drawings (behind "tap to load") aren't folded.
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

**Read aloud** is upstream's since v0.12.0 (`VoiceController.readAloud`, `ReadAloudState`): it keeps each piece's
take for a day, plays on through a rotation, stops when Herald goes out of sight, and doesn't start during a voice chat or
dictation. The pilot adds only the turn-taking with the feed's player (`ChatScreen`).

**Side chats** (`ui/bots/SideChatsViewModel.kt`, `ui/sessions/SideChats.kt`, hooks in `BotsRoster` and
`SessionsSidebar`):
- A folding "Side chats · <bot>" section between the bots and the rooms: the newest 8 of the user's conversations in
  the bot's profile, then Show more. `/api/sessions?profile=<bot>` with the Chats list's exclusions
  (`MACHINE_SOURCES`) plus `api_server` (Runs API: the server's check probes, handoffs) and `bot_room`. Hermes leaves
  hidden sessions out of listings, which drops the Bot Chat and room turns.
- It follows the open chat (`sideChatsBot`): a bot's Bot Chat or any chat in its profile shows that bot; a room or a
  chat in the launch profile keeps the last one. The open side chat's row is filled and its bot's card outlined.
- Long press: pin, rename, archive, delete, in the bot's profile. The section's + and the sidebar's New chat (on the
  Bots tab) start a chat with that bot. Folded (remembered, `sideChatsCollapsed`), only the open side chat shows.
- The header's New chat and `/new` start in the open chat's profile (`AppViewModel.newChatBeside`); in a Bot Chat,
  `/new` still compresses. A side chat is remembered where the Chats list is, with its own profile in `LastChat`.
- herald-push watches only each bot's Bot Chat, so side-chat replies don't notify while Herald is closed.

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

**To check on the phone (side chats, pilot APK of 2026-10-19):**
- Hidden is the last thing in the Bots list.
- Open Vivian's Bot Chat: "Side chats · Vivian" lists her other chats, no scheduled runs or "Run claude --version"
  probes. Open one: the drawer closes, reopening it shows the Bots tab with that row filled and Vivian's card outlined.
- From that side chat, the header's New chat (and `/new`) starts a chat with Vivian: Vivian's card stays outlined, and
  after the first message it's listed under Side chats · Vivian.
- Fold the section: only the open side chat stays. Long press a row: rename, pin, archive, delete work.
- Close Herald in a side chat and reopen it: it comes back to that chat.
- Inline `::preview` widgets (upstream's) and `MEDIA:` pages, audio and video still draw as before. Confirmed on the
  phone 2026-10-19, with the side chats.

- Folding (2026-10-19): pages, drawings and `::preview` widgets come folded, show in place on a tap, Hide and full
  screen work, a shown one stays shown while the chat is open. Confirmed on the phone 2026-10-19.

## Herald push plugin (`herald-push`)
**From v0.12.0 Herald pins plugin 0.5.1** (`c88f027`: `phone_call_me` and quiet hours, in the `herald_phone` toolset);
the person's server runs the reviewed 0.4.2 (`45e76c0`, ASSUMPTIONS 191). Herald Dev from this sync offers to update the
gateway's plugin: **decline** until AgentStack has reviewed and pinned 0.5.1 (the person is having that done). An
update installed from the app is switched off by the capture, and pushes stop until it's reviewed.

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
- ROADMAP item "Run `::preview` widgets inline (a WebView with Desktop's theme prelude)" is largely done, but Desktop's
  theme prelude isn't injected yet.
- `dev-apk.yml` pins `actions/cache` to v6.1.0 and the runner to `ubuntu-24.04`. Each branch has its own cached debug
  key (caches are per branch); pilot's is the one that matters, as that APK is the one installed.

## Deferred by the person
- Inline rendering of Markdown, CSV and PDF `::preview` artifacts: **hold for now**.

**To check on the phone (sync to v0.12.0, pilot APK of 2026-10-20):**
- Read aloud (upstream's) under a finished reply: plays, Stop works, a reply read again replays at once; dictating or a
  voice chat stops it. Play a recording in the feed, then tap Read aloud: the recording pauses; play it again: the
  reading stops. Open a film full screen: a reading stops.
- The media circuit, folded items, side chats and New chat as before; a linked file in a room opens in the viewer
  (pages, audio, video too).
- New in v0.12.0: talk over a spoken reply in voice chat to cut in; calls (only once AgentStack has updated the plugin).
- Herald Dev offers a gateway plugin update: decline it for now.

## Next likely steps
1. Get device feedback on the pilot APK and fix what comes up on the branch that owns it, then merge into `pilot`.
2. If going upstream: open issues on Giton22/herald, then cherry-pick the feature commits onto branches from
   `upstream/master`. Add before/after screenshots from `PreviewGalleryActivity` and tick ROADMAP items.
