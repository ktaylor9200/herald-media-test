# Herald (fork: ktaylor9200/herald-stackver)

Herald is a native Android client (Kotlin Multiplatform + Compose) for a self-hosted Hermes Agent gateway.
This repo is a **fork of `Giton22/herald`** used to pilot features before offering them upstream.

**Read `FORK_NOTES.md` first**: it covers the work in flight, its branches and PRs, design decisions and open items.
Read `CONTRIBUTING.md` for upstream's rules; everything there applies here.

## Fork-only files (never send upstream)
- `CLAUDE.md`, `FORK_NOTES.md`
- `.github/workflows/dev-apk.yml` (builds the "Herald Dev" APK per branch)

To offer work upstream, branch from `upstream/master` and cherry-pick only the feature commits. Don't open a PR
from a fork branch that carries these files. Upstream asks for an issue first for anything bigger than a small fix.

## Remotes and branches
- `origin` = the fork, `upstream` = `https://github.com/Giton22/herald.git`. Add `upstream` if a fresh clone lacks it.
- Fork `master` mirrors upstream plus the two notes files. To sync: `git fetch upstream --tags && git merge <tag>` on
  `master` (a release tag, such as `v0.12.0`, for now: see FORK_NOTES.md), then merge `master` into the feature
  branches and those into `pilot`. Don't rebase pushed branches.
- Feature branches each have a PR inside the fork; `pilot` merges them all for testing (see FORK_NOTES.md).

## Build and test
- JDK 17+ (CI uses Temurin 25) and the Android SDK with API 37. Gradle needs Google Maven and Maven Central.
- What CI runs, and what must pass before pushing:
  ```bash
  ./gradlew --continue :shared:core:testAndroidHostTest :shared:designsystem:testAndroidHostTest :shared:ui:testAndroidHostTest :androidApp:assembleDebug :androidApp:lintDebug
  ```
- Lint treats warnings as errors and has a baseline (`androidApp/lint-baseline.xml`). Fix new issues, don't baseline them.
- **No local build?** (A cloud sandbox may block Google Maven and Gradle.) Push the branch and read the results:
  `ci.yml` runs on every PR in the fork, and `dev-apk.yml` builds on every push to a non-master branch that contains it.
  Read results with `gh api repos/ktaylor9200/herald-stackver/commits/<sha>/check-runs`.
  Annotations on a failed run are at `.../check-runs/<id>/annotations`.
- Device testing: the person installs the `herald-dev-<branch>` artifact from the `Dev APK` run. It installs beside the
  real app as "Herald Dev", and each build installs over the last because the debug key is cached.

## Code conventions (from upstream; match the code around you)
- Commit subjects are `Area: what changed` in plain words (`Chat: read a reply aloud from a speaker under it`),
  never `feat:` or `fix:`. Keep commits small and logical, one feature or fix per PR.
- KDoc and comments say what a thing is for, in plain English, in the same terse voice as the existing code.
- Every behaviour change gets a test: chat and protocol logic in `shared/core/src/commonTest`, view-model and UI logic in
  `shared/ui/src/commonTest`. Platform code goes behind `expect`/`actual` (`commonMain` + `androidMain`).
- Talk to the gateway the way Hermes Desktop does: check `docs/hermes-protocol-research.md` and the hermes-agent source
  before adding an endpoint.
- Tick the matching `docs/ROADMAP.md` item in the PR that completes it.

## Where things live
- `shared/core`: gateway APIs and pure logic (`media/GatewayFile.kt` file kinds, `media/MediaApi.kt`,
  `chat/ReplyMedia.kt` MEDIA:/::preview parsing, `voice/AudioApi.kt`).
- `shared/ui/.../ui/chat`: the chat screen (`ChatScreen.kt`, `ChatViewModel.kt`, `ChatActions.kt`), reply media
  (`MediaViews.kt`, `FeedMedia.kt`), the file viewer (`FileViewer.kt`), web pages (`HtmlView.kt`), players
  (`MediaPlayerView.kt`). Android implementations are under `shared/ui/src/androidMain`.
- `shared/ui/.../ui/voice/VoiceController.kt`: dictation, voice chat, and reading a reply aloud.
- `shared/designsystem`: theme tokens and components (`IconButton`, `Button`, `DropdownMenu`, `Spinner`, …).
