# Maven releases

Release Please owns the Maven version, generated changelog, release tag, and draft GitHub release.
The pinned reusable workflows in [`streamarr/streamarr-workflows`](https://github.com/streamarr/streamarr-workflows) own release automation, release validation, and final image publication. Server image builds and native checks stay in this repository.
The release automation is recorded in [ADR 0034](https://github.com/streamarr/streamarr-adr/blob/main/adr/0034-release-please-owns-maven-release-versions.adoc); [ADR 0058](https://github.com/streamarr/streamarr-adr/blob/main/adr/0058-releases-stay-draft-until-their-notes-are-curated.adoc) adds the draft-publication gate for Streamarr repositories using Release Please.
The serialized workflow runs after pushes to `main`; `always-update` refreshes the open release PR against the current base.
Release PRs stay open until a maintainer chooses to merge them. The repository ruleset requires an up-to-date branch and successful build, Sonar, and Snyk checks.
Merging a release PR fixes its version and revision and creates a draft GitHub release. A maintainer curates that draft's notes and publishes it separately.
The Maven package enables Release Please's native `draft` and `force-tag-creation` settings. Creating the tag immediately gives subsequent version calculations a fixed baseline while the release remains unpublished. See [Release Please's configuration documentation](https://github.com/googleapis/release-please/blob/v17.6.0/docs/manifest-releaser.md).
Only development snapshot PRs use GitHub auto-merge, under the same required checks. Snapshot updates do not create GitHub releases.

## Cut and publish a release

Review the maintained release PR's version, included changes, and required checks when ready to cut a release.
Squash-merge that PR with its generated `chore(main): release X` title and an empty commit body.
Release Please then tags the merged revision and creates a draft GitHub release with generated notes. The tag is visible immediately; the GitHub release and stable image tags remain unpublished.
Release Please also prepares the next Maven snapshot PR for auto-merge after its required checks pass.
Each release records a fixed version and source revision; later commits belong to subsequent release PRs and do not move its tag.

Open the draft on GitHub's **Releases** page and curate its description. Use a release-wide summary and sections such as Features, Bug Fixes, and Housekeeping, as in [v0.0.10](https://github.com/streamarr/streamarr-server/releases/tag/v0.0.10).
Edit the draft GitHub release, not the generated release PR or `CHANGELOG.md`. Normal Release Please runs have already processed the merged PR and leave the draft's edited notes intact. The generated changelog remains the commit-level record; curated notes are not copied back into it.
Keep the draft's generated tag and stable-release classification. When the notes are ready, click **Publish release**. The stable release's `released` event starts **Release Publisher**, which builds and publishes the images.
Creating the tag or draft and editing its notes do not start stable image publication. Main CI continues publishing commit and snapshot images independently.

The release publisher checks that the tag is stable SemVer, the tagged commit belongs to `main`, and the POM version matches the tag.
Both architectures build that validated commit locally and verify the server and Cedar engine before pushing a native image. The image metadata records the Maven version and Git revision. Native jobs upload digest receipts; the shared publisher combines those exact images after both jobs succeed. Main CI also waits for the arm64 HLS smoke check before its native push, and all required CI checks before final publication.
Manual publishing retries require an already-published GitHub release and perform the same validation. Build actions come from the workflow revision, while application source comes from the validated tag; retries therefore use the current checks even when that tag predates the build workflow. A tag belonging to an unpublished draft is rejected.
Only the current GitHub release can advance Docker's `latest` tag; retrying an older release preserves it.

## Setup

- Enable repository auto-merge; retain the existing required-check ruleset and give the release App no bypass.
- Create a dedicated, organization-owned release App with repository **Contents**, **Pull requests**, and **Issues** write permissions, installed on the Streamarr repositories that use release automation.
- Store that App's credentials as `ORG_STREAMARR_RELEASE_CLIENT_ID` and `ORG_STREAMARR_RELEASE_PRIVATE_KEY`. Release automation requires these secrets and has no fallback to another App.
- The worker's FFmpeg lock synchronization also mints a contents-only token from the release App. Rotating or revoking the release App's private keys therefore affects both automations ([ADR 0034](https://github.com/streamarr/streamarr-adr/blob/main/adr/0034-release-please-owns-maven-release-versions.adoc)).

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
./mvnw -Dtest=ReleaseWorkflowTest,PackBuildActionTest,PackagedConfigurationTest,CiPipelineWorkflowTest test
python3 -m unittest discover -s .github/actions/pack-build/test -p 'test_*.py'
```

Local tests cover workflow inputs and gates, build-before-push behavior, and native digest receipts. The shared repository tests snapshot-only auto-merge, pending-release recovery, release validation, and final image publication safeguards.
Reusable workflows are pinned to a reviewed commit and maintained by Renovate. Merge the shared workflow change before adopting its commit here. The shared repository pins the official Release Please action; upstream owns the Maven engine and its tests.
The action's documented `skip-github-pull-request` and `skip-github-release` inputs separate publication from PR preparation. Between those phases, **Verify merged releases were processed** checks for merged PRs still labeled `autorelease: pending` and fails with recovery instructions.

Re-run **Release Please** with `workflow_dispatch` after an API failure; it reconciles already merged PRs and pending releases. Wait for that workflow to finish successfully before editing the draft's notes.
If it reports an unprocessed merged release PR, restore that PR's original release title and structured release body before retrying. A pending release must be processed before another release PR is prepared.
Re-run **Release Publisher** with the already-published release's tag after an image publishing failure.
Correct a failed required check through the normal PR workflow; keep the release PR pending until the ruleset allows it to merge.

The release notes omit author attribution, and automated squash merges explicitly use an empty commit body.
