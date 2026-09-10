const { DEFAULT_LABELS } = require('release-please/build/src/manifest');

async function reconcile({ github, manifest, enqueue }) {
  await (await manifest()).createReleases();
  // The SDK's parsed-release iterator omits PRs with malformed bodies.
  for await (const pullRequest of github.pullRequestIterator('main', 'MERGED', 200, false)) {
    if (DEFAULT_LABELS.every(label => pullRequest.labels.includes(label))) {
      throw new Error(`Unprocessed merged release PR #${pullRequest.number}: restore its release title and body, then rerun Release Please`);
    }
  }
  const pullRequests = await (await manifest()).createPullRequests();
  for (const pullRequest of pullRequests.filter(Boolean)) {
    await enqueue(pullRequest);
  }
}

module.exports = { reconcile };
