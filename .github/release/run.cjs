const { runReleaseAutomation } = require('./automation.cjs');

runReleaseAutomation({ repository: process.env.GITHUB_REPOSITORY, token: process.env.GH_TOKEN }).catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
