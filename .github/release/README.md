# Maven releases

Release Please owns the Maven version, changelog, release tag, and GitHub release.
The architectural decision and alternatives are recorded in [ADR 0034](https://github.com/streamarr/streamarr-adr/pull/10).
The serialized workflow runs after pushes to `main`; `always-update` refreshes the open release PR against the current base.
Release PRs stay open until a maintainer chooses to merge them. The repository ruleset requires an up-to-date branch and successful build, Sonar, and Snyk checks.
Merging a release PR is the single manual release gate: Release Please creates its Git tag and publishes the GitHub release automatically.
This follows [Release Please's documented release PR lifecycle](https://github.com/googleapis/release-please#whats-a-release-pr), using its default published-release behavior.
Only development snapshot PRs use GitHub auto-merge, under the same required checks. Snapshot updates do not create GitHub releases.

## Cut and publish a release

Review the maintained release PR's version, changelog, and required checks when ready to cut a release.
Squash-merge that PR with its generated `chore(main): release X` title and an empty commit body.
Release Please then tags the merged revision and publishes its GitHub release. The release App creates the GitHub release, allowing its publication event to trigger the container publisher automatically.
Release Please also prepares the next Maven snapshot PR for auto-merge after its required checks pass.
Choose the version and review the notes before merging. Each release records a fixed version and source revision; later commits belong to subsequent release PRs and do not move its tag.

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
The official Release Please action uses the native Maven strategy and PR grouping, with configured headers and footers for factual release and snapshot PR bodies. The group title pattern preserves the Maven version in the upstream `chore(main)` title format.
While retaining those subjects, a commit can include an additional `feat: ...` paragraph for a feature or a `BREAKING CHANGE: ...` footer for an incompatible change.
Separate those paragraphs from preceding prose by a blank line and preserve them when merging.
This follows [Release Please's guidance for multiple changes in one commit](https://github.com/googleapis/release-please#what-if-my-pr-contains-multiple-fixes-or-features).
PR labels do not calculate a second release version.
Renovate explicitly uses semantic commits. Production Maven dependency updates use `fix(deps):`; ordinary `chore(deps):` updates do not initiate a release or appear in the notes.

## Verification and recovery

```sh
./mvnw -Dtest=ReleaseAutomationTest,ReleasePublisherTest,ReleaseWorkflowTest test
```

Workflow tests run the actual shell steps against fake external commands and temporary Git repositories. They cover snapshot-only auto-merge, pending-release recovery, version and revision validation, and image publication safeguards.
The official action is pinned to a commit and maintained by Renovate. Upstream owns the Maven engine and its tests; this repository has no release SDK package or npm lockfile.
The action's documented `skip-github-pull-request` and `skip-github-release` inputs separate publication from PR preparation. Between those phases, **Verify merged releases were processed** checks for merged PRs still labeled `autorelease: pending` and fails with recovery instructions.

Re-run **Release Please** with `workflow_dispatch` after an API failure; it reconciles already merged PRs and pending releases.
If it reports an unprocessed merged release PR, restore that PR's original release title and structured release body before retrying. A pending release must be processed before another release PR is prepared.
Re-run **Release Publisher** with the already-published release's tag after an image publishing failure.
Correct a failed required check through the normal PR workflow; keep the release PR pending until the ruleset allows it to merge.

The release notes omit author attribution, and automated squash merges explicitly use an empty commit body.
