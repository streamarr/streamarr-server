const { registerReleaseType } = require('release-please');
const { Maven } = require('release-please/build/src/strategies/maven');
const { isReleaseTitle } = require('./release-title.cjs');

class StreamarrMaven extends Maven {
  async needsSnapshot(commits, latestRelease) {
    // The SDK's snapshot scan parses every matching commit title as a version.
    const versionCommits = commits.filter(commit =>
      isReleaseTitle(commit.pullRequest?.title || commit.message, this.pullRequestTitlePattern));
    return super.needsSnapshot(versionCommits, latestRelease);
  }
}

registerReleaseType('streamarr-maven', options => new StreamarrMaven(options));
