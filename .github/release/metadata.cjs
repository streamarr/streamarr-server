const { registerPlugin } = require('release-please');
const { ManifestPlugin } = require('release-please/build/src/plugin');
const { PullRequestTitle } = require('release-please/build/src/util/pull-request-title');

class ReleaseMetadata extends ManifestPlugin {
  processCommits(commits) {
    const pattern = this.repositoryConfig['.'].pullRequestTitlePattern;
    return commits.map(commit => {
      const title = commit.pullRequest?.title || commit.message;
      const release = PullRequestTitle.parse(title, pattern);
      return release ? { ...commit, type: 'chore', breaking: false, notes: [] } : commit;
    });
  }

  async run(candidates) {
    for (const { path, pullRequest } of candidates) {
      pullRequest.title = PullRequestTitle.ofComponentVersion(
        pullRequest.title.getComponent() || '',
        pullRequest.version,
        this.repositoryConfig[path].pullRequestTitlePattern,
      );
      pullRequest.body.header = 'Updates the Maven version and release notes.';
      pullRequest.body.footer = 'Publishing uses the version and revision recorded in this pull request.';
    }
    return candidates;
  }
}

registerPlugin('streamarr-release-metadata', options =>
  new ReleaseMetadata(options.github, options.targetBranch, options.repositoryConfig));

module.exports = { ReleaseMetadata };
