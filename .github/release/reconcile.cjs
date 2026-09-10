async function reconcile({ manifest, enqueue }) {
  await (await manifest()).createReleases();
  const pullRequests = await (await manifest()).createPullRequests();
  for (const pullRequest of pullRequests.filter(Boolean)) {
    await enqueue(pullRequest);
  }
}

module.exports = { reconcile };
