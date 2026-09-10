# Maven releases

Release Please owns the Maven version, changelog, release tag, and GitHub release.
The architectural decision and alternatives are recorded in [ADR 0032](https://github.com/streamarr/streamarr-adr/pull/10).
The serialized workflow runs after pushes to `main`; `always-update` refreshes the open release PR against the current base.
GitHub auto-merge waits for the repository ruleset, including an up-to-date branch and successful build, Sonar, and Snyk checks.
The same lifecycle merges the next development snapshot PR without publishing a snapshot release.

The release publisher checks that the tag is stable SemVer, the tagged commit belongs to `main`, and the POM version matches the tag.
Both architectures build that validated commit and use its version in the image metadata and tags.
Manual publishing requires an existing tag and performs the same validation.
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
Automated release and snapshot commits use `behavioral:` too.
The metadata plugin recognizes those version commits and removes their version-bump signals and changelog entries while preserving the snapshot marker needed by Maven's release lifecycle.
The `streamarr-maven` strategy extends the pinned Maven strategy with strict release-title recognition in its snapshot scan, so ordinary titles such as `behavioral: release 4k playback sessions` remain regular changes.
The metadata plugin restores the configured title on snapshot PRs because the SDK builds them with its default title pattern; subsequent runs must recognize the merged snapshot to advance to a stable release.
While retaining those subjects, a commit can include an additional `feat: ...` paragraph for a feature or a `BREAKING CHANGE: ...` footer for an incompatible change.
Place those paragraphs at the bottom of the squash commit body, separated from preceding prose by a blank line, and preserve them when merging.
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
Re-run **Release Publisher** with the existing tag after a publishing failure.
Correct a failed required check through the normal PR workflow; keep the release PR pending until the ruleset allows it to merge.

The release notes omit author attribution, and automated squash merges explicitly use an empty commit body.
