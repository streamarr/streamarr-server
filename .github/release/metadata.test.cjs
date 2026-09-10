const assert = require('node:assert/strict');
const { test } = require('node:test');
const { PullRequestBody } = require('release-please/build/src/util/pull-request-body');
const { PullRequestTitle } = require('release-please/build/src/util/pull-request-title');
const { Version } = require('release-please/build/src/version');
const { ReleaseMetadata } = require('./metadata.cjs');

test('keeps release and snapshot PR bodies factual while preserving release data', async () => {
  const plugin = new ReleaseMetadata({}, 'main', {
    '.': { pullRequestTitlePattern: 'behavioral${scope}: release${component} ${version}' },
  });
  const data = [{ notes: '### Fixes\n\n* Correct playback' }];
  const candidate = {
    path: '.',
    pullRequest: {
      title: PullRequestTitle.ofTargetBranchVersion('main', Version.parse('1.2.3-SNAPSHOT')),
      body: new PullRequestBody(data),
      version: Version.parse('1.2.3-SNAPSHOT'),
    },
  };
  const [result] = await plugin.run([candidate]);
  const body = result.pullRequest.body;
  assert.deepEqual(body.releaseData, data);
  assert.doesNotMatch(body.toString(), /robot|beep|generated|authored|co-authored/i);
  assert.match(body.toString(), /Maven version/);
  assert.equal(result.pullRequest.title.toString(), 'behavioral: release 1.2.3-SNAPSHOT');
});
