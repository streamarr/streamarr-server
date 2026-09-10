# Maven releases

Release Please owns the Maven version, changelog, release tag, and GitHub release.
The architectural decision and alternatives are recorded in [ADR 0034](https://github.com/streamarr/streamarr-adr/pull/10).
The serialized workflow runs after pushes to `main`; `always-update` refreshes the open release PR against the current base.
GitHub auto-merge waits for the repository ruleset, including an up-to-date branch and successful build, Sonar, and Snyk checks.
Merging a release PR creates its Git tag and an unpublished GitHub draft release.
The same lifecycle merges the next development snapshot PR while the draft waits for publication; snapshot updates do not create GitHub releases.

## Publish a prepared release

Open the prepared draft under the repository's **Releases** page, review its notes, and click **Publish release** when ready.
That publication event starts the container publisher. Preparing the draft does not publish images.
The draft records the version and source revision already merged through the release PR. Keep its tag unchanged; choosing another version must happen before the release PR merges so the POM and tag continue to agree.

Drafts have Git tags immediately so Release Please can find the prepared version and advance the Maven snapshot without waiting for publication.
Each draft is a fixed release candidate; later commits belong to subsequent release PRs and do not move its tag.

The release publisher checks that the tag is stable SemVer, the tagged commit belongs to `main`, and the POM version matches the tag.
Both architectures build that validated commit and use its version in the image metadata and tags.
Manual publishing retries require an already-published GitHub release and perform the same validation. A tag belonging to an unpublished draft is rejected.
Only the current GitHub release can advance Docker's `latest` tag; retrying an older release preserves it.

## Setup

- Enable repository auto-merge; retain the existing required-check ruleset and give the release App no bypass.
- Create a dedicated, organization-owned release App with repository **Contents**, **Pull requests**, and **Issues** write permissions, installed only on `streamarr/streamarr-server`.
- Store that App's credentials as `RELEASE_APP_CLIENT_ID` and `RELEASE_APP_PRIVATE_KEY`. Release automation requires these secrets and has no fallback to another App.
- Keep the FFmpeg App's credentials and permissions separate. Rotate or revoke the release App's private keys independently.

## Migration from Release Drafter

Remove the unpublished Release Drafter draft when enabling the replacement. Published releases and tags remain the migration baseline.

The checked-in manifest starts from the last published release, `0.0.10`.
Release Please generates the initial `0.0.11-SNAPSHOT` update itself; no POM version is changed as a shortcut to resetting Sonar's baseline.

## Version signals

Release Please reads conventional commit metadata: `fix:` produces a patch, `feat:` a minor, and a breaking-change marker a major.
The repository's `structural:` and `behavioral:` subjects remain visible in the release notes and count as patch changes.
Ordinary patch releases need no extra labels or commit-body metadata.
Only the release bot uses `chore(main): release X` subjects, with an empty squash body. The native Maven strategy recognizes its snapshot commits, and ordinary `chore:` updates do not initiate another release or appear in the notes.
Human subjects such as `behavioral: release 1.2.3` and `behavioral: release 4k playback sessions` remain regular changes.
Release preparation uses the SDK's native Maven strategy and PR grouping, with configured headers and footers for factual release and snapshot PR bodies. The group title pattern preserves the Maven version in the upstream `chore(main)` title format.
While retaining those subjects, a commit can include an additional `feat: ...` paragraph for a feature or a `BREAKING CHANGE: ...` footer for an incompatible change.
Separate those paragraphs from preceding prose by a blank line and preserve them when merging.
This follows [Release Please's guidance for multiple changes in one commit](https://github.com/googleapis/release-please#what-if-my-pr-contains-multiple-fixes-or-features).
PR labels do not calculate a second release version.
Renovate explicitly uses semantic commits. Production Maven dependency updates use `fix(deps):`; ordinary `chore(deps):` updates do not initiate a release or appear in the notes.

## Verification and recovery

```sh
npm ci --ignore-scripts --prefix .github/release
npm test --prefix .github/release
npm audit --omit=dev --prefix .github/release
```

Tests exercise the actual pinned Release Please Maven strategy, including snapshot transitions, SemVer changes, POM updates, and release tags.
The workflow installs dependencies before minting its App token.
Renovate maintains the tooling version and lockfile.

Re-run **Release Please** with `workflow_dispatch` after an API failure; it reconciles already merged PRs and pending releases.
If it reports an unprocessed merged release PR, restore that PR's original release title and structured release body before retrying. A pending release must be processed before another release PR is prepared.
Re-run **Release Publisher** with the already-published release's tag after an image publishing failure.
Correct a failed required check through the normal PR workflow; keep the release PR pending until the ruleset allows it to merge.

The release notes omit author attribution, and automated squash merges explicitly use an empty commit body.
