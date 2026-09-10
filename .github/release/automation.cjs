const { execFileSync } = require('node:child_process');
const { GitHub, Manifest } = require('release-please');
const { reconcile } = require('./reconcile.cjs');
require('./metadata.cjs');

async function runReleaseAutomation({ repository, token, execute = execFileSync, connect = GitHub.create }) {
  const [owner, repo] = repository.split('/');
  const autoMerge = execute('gh', ['api', `repos/${repository}`, '--jq', '.allow_auto_merge'], { encoding: 'utf8' }).trim();
  if (autoMerge !== 'true') throw new Error('Enable repository auto-merge before running release automation');
  const github = await connect({ owner, repo, token });
  await reconcile({
    github,
    manifest: () => Manifest.fromManifest(github, 'main'),
    enqueue: async pr => {
      const head = execute('gh', [
        'api', `repos/${repository}/pulls/${pr.number}`, '--jq', '.head.sha',
      ], { encoding: 'utf8' }).trim();
      if (!/^[a-f0-9]{40}$/.test(head)) throw new Error('Release PR has no head revision');
      execute('gh', [
        'pr', 'merge', String(pr.number), '--repo', repository,
        '--auto', '--squash', '--match-head-commit', head,
        '--subject', pr.title, '--body', '',
      ], { stdio: 'inherit' });
    },
  });
}

module.exports = { runReleaseAutomation };
