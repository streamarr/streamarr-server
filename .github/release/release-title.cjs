const { PullRequestTitle } = require('release-please/build/src/util/pull-request-title');

function isReleaseTitle(title, pattern) {
  try {
    const parsed = PullRequestTitle.parse(title, pattern);
    const version = parsed?.getVersion();
    return Boolean(version && !version.build
      && (!version.preRelease || version.preRelease === 'SNAPSHOT')
      && parsed.toString() === title);
  } catch {
    return false;
  }
}

module.exports = { isReleaseTitle };
