"""Reject a pull request whose new Flyway migration would sort at or below the base branch."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "flyway-migration-order.py"
SQL = "src/main/resources/db/migration"
JAVA = "src/main/java/db/migration"


class FlywayMigrationOrderTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.origin = root / "origin.git"
        self.checkout = root / "checkout"
        self.environment = dict(os.environ,
            GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null",
            GIT_AUTHOR_NAME="Migration fixture", GIT_AUTHOR_EMAIL="fixture@example.test",
            GIT_COMMITTER_NAME="Migration fixture", GIT_COMMITTER_EMAIL="fixture@example.test",
        )
        self.git(root, "init", "--quiet", "--bare", "--initial-branch=main", str(self.origin))
        self.git(root, "clone", "--quiet", str(self.origin), str(self.checkout))
        self.git(self.checkout, "switch", "--quiet", "--orphan", "main")
        self.commit(message="initial")
        self.git(self.checkout, "push", "--quiet", "origin", "main")

    def git(self, directory, *arguments):
        result = subprocess.run(["git", "-c", "commit.gpgsign=false", *arguments],
            cwd=directory, env=self.environment, capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def commit(self, *paths, message="change", content=None):
        for path in paths:
            file = self.checkout / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content or f"-- {path}\n")
        self.git(self.checkout, "add", "--all")
        self.git(self.checkout, "commit", "--quiet", "--allow-empty", "-m", message)

    def publish_main(self, *paths, content=None):
        self.git(self.checkout, "switch", "--quiet", "main")
        self.commit(*paths, message="main", content=content)
        self.git(self.checkout, "push", "--quiet", "origin", "main")

    def start_pull_request(self, name="pull-request", start="main"):
        self.git(self.checkout, "switch", "--quiet", "--create", name, start)

    def check(self, directory=None):
        return subprocess.run(["python3", str(SCRIPT), "main"], cwd=directory or self.checkout,
            env=self.environment, capture_output=True, text=True, timeout=30)

    def assertRejected(self, result, migration, highest):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(f"::error file={migration}", result.stdout)
        self.assertIn(highest, result.stdout)

    def test_shouldRejectAddedMigrationWhenTheBaseBranchMovedPastItsVersion(self):
        self.publish_main(f"{SQL}/V115__Store_Retention.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V116__Store_Language_Preferences.sql")
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.git(self.checkout, "switch", "--quiet", "pull-request")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V116__Store_Language_Preferences.sql",
            f"{SQL}/V118__Store_Skipped_File_Reasons.sql")

    def test_shouldRejectAddedMigrationWhenTheBaseBranchAddedItsVersionWithTheSameContent(self):
        statement = "ALTER TABLE library ADD COLUMN refreshed_on TIMESTAMPTZ;\n"
        self.publish_main(f"{SQL}/V115__Store_Retention.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V116__Store_Library_Refresh.sql", content=statement)
        self.publish_main(f"{SQL}/V116__Add_Library_Refresh.sql", content=statement)
        self.git(self.checkout, "switch", "--quiet", "pull-request")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V116__Store_Library_Refresh.sql",
            f"{SQL}/V116__Add_Library_Refresh.sql")

    def test_shouldAcceptStackedPullRequestWhenTheBaseBranchSquashMergedItsParent(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request("parent")
        self.commit(f"{SQL}/V119__Store_Language_Preferences.sql")
        self.start_pull_request("pull-request", "parent")
        self.commit(f"{SQL}/V120__Store_Subtitle_Preferences.sql")
        self.publish_main(f"{SQL}/V119__Store_Language_Preferences.sql")
        self.git(self.checkout, "switch", "--quiet", "pull-request")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldRejectAddedMigrationWhenItsVersionEqualsTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V118__Store_Language_Preferences.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V118__Store_Language_Preferences.sql", "V118")

    def test_shouldAcceptAddedMigrationWhenItsVersionIsAboveTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V119__Store_Language_Preferences.sql")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("::error", result.stdout)

    def test_shouldAcceptAddedMigrationWhenAMoreDigitsVersionIsNumericallyHigher(self):
        self.publish_main(f"{SQL}/V9__Create_Library.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V10__Create_Movie.sql")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldRejectAddedMigrationWhenAFewerDigitsVersionIsNumericallyLower(self):
        self.publish_main(f"{SQL}/V100__Create_Library.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V99__Create_Movie.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V99__Create_Movie.sql", "V100")

    def test_shouldRejectAddedMigrationWhenAZeroPaddedBaseVersionIsNumericallyHigher(self):
        self.publish_main(f"{SQL}/V036__Create_Library.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V36__Create_Movie.sql", f"{SQL}/V4__Create_Series.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V36__Create_Movie.sql", "V036")
        self.assertRejected(result, f"{SQL}/V4__Create_Series.sql", "V036")

    def test_shouldRejectAddedMigrationWhenADottedVersionSortsBelowTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V2_1__Create_Library.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V2.0.9__Create_Movie.sql", f"{SQL}/V2.1.0__Create_Series.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V2.0.9__Create_Movie.sql", "V2_1")
        self.assertRejected(result, f"{SQL}/V2.1.0__Create_Series.sql", "V2_1")

    def test_shouldAcceptAddedMigrationWhenADottedVersionSortsAboveTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V2_1__Create_Library.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V2.1.1__Create_Movie.sql")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldRejectAddedJavaMigrationWhenAnSqlMigrationOnTheBaseBranchIsHigher(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql",
            f"{JAVA}/V106__Strip_Title_Sorts.java")
        self.start_pull_request()
        self.commit(f"{JAVA}/V117__Encode_Title_Sorts.java", f"{JAVA}/StoredTitleSorts.java")

        result = self.check()

        self.assertRejected(result, f"{JAVA}/V117__Encode_Title_Sorts.java", "V118")
        self.assertNotIn("StoredTitleSorts", result.stdout)

    def test_shouldRejectAddedSqlMigrationWhenAJavaMigrationOnTheBaseBranchIsHigher(self):
        self.publish_main(f"{SQL}/V105__Store_Probe.sql", f"{JAVA}/V106__Strip_Title_Sorts.java")
        self.start_pull_request()
        self.commit(f"{SQL}/V106__Store_Language_Preferences.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V106__Store_Language_Preferences.sql",
            f"{JAVA}/V106__Strip_Title_Sorts.java")

    def test_shouldAcceptChangeWhenItRenamesOrModifiesAnExistingMigration(self):
        self.publish_main(f"{SQL}/V001__Create_Library.sql", f"{SQL}/V002__Create_Movie.sql",
            f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.git(self.checkout, "mv", f"{SQL}/V001__Create_Library.sql",
            f"{SQL}/V001__Create_Libraries.sql")
        (self.checkout / SQL / "V002__Create_Movie.sql").write_text("-- edited\n")
        self.commit(message="rename and edit")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldRejectRenamedFileWhenItBecomesAMigrationBelowTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql",
            f"{SQL}/Store_Language_Preferences.sql.disabled")
        self.start_pull_request()
        self.git(self.checkout, "mv", f"{SQL}/Store_Language_Preferences.sql.disabled",
            f"{SQL}/V117__Store_Language_Preferences.sql")
        self.commit(message="enable")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V117__Store_Language_Preferences.sql", "V118")

    def test_shouldRejectRenamedMigrationWhenItsVersionChangesBelowTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V010__Create_Library.sql",
            f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.git(self.checkout, "mv", f"{SQL}/V010__Create_Library.sql",
            f"{SQL}/V117__Create_Library.sql")
        self.commit(message="renumber")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V117__Create_Library.sql", "V118")

    def test_shouldRejectAddedMigrationWhenItsDescriptionIsEmpty(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V116__.sql")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V116__.sql", "V118")

    def test_shouldRejectAddedFileWhenFlywayCouldReadItsNameAsAnotherVersion(self):
        names = [f"{SQL}/V116__Store_Language_Preferences.SQL", f"{SQL}/V+117__Backfill.sql",
            f"{SQL}/V117_+1__Backfill.sql", f"{SQL}/V117_\u0661__Backfill.sql",
            f"{SQL}/V117__Backfill.\u017fql", f"{SQL}/Vcurrent__Backfill.sql",
            f"{SQL}/V9223372036854775808__Backfill.sql", f"{JAVA}/V117.java"]
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.commit(*names)

        result = self.check()

        for name in names:
            with self.subTest(name=name):
                self.assertRejected(result, name, "V118")

    def test_shouldFailWhenTheBaseBranchHoldsAMigrationNameTheCheckCannotRead(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql",
            f"{SQL}/V120__Store_Subtitle_Preferences.SQL")
        self.start_pull_request()
        self.commit(f"{SQL}/V119__Store_Language_Preferences.sql")

        result = self.check()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertIn(f"::error file={SQL}/V120__Store_Subtitle_Preferences.SQL", result.stdout)

    def test_shouldAcceptAddedMigrationWhenItAndTheBaseBranchHaveScriptConfigurationFiles(self):
        self.publish_main(f"{SQL}/V118__Index_Skipped_Files.sql",
            f"{SQL}/V118__Index_Skipped_Files.sql.conf")
        self.start_pull_request()
        self.commit(f"{SQL}/V119__Index_Language_Preferences.sql",
            f"{SQL}/V119__Index_Language_Preferences.sql.conf")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldIgnoreMigrationWhenItReachedThePullRequestByMergingTheBaseBranch(self):
        self.publish_main(f"{SQL}/V116__Store_Language_Preferences.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V117__Store_Skipped_Files.sql")
        self.publish_main(f"{SQL}/V118__Store_More_Skipped_File_Reasons.sql")
        self.git(self.checkout, "switch", "--quiet", "pull-request")
        self.git(self.checkout, "merge", "--quiet", "--no-edit", "main")

        result = self.check()

        self.assertRejected(result, f"{SQL}/V117__Store_Skipped_Files.sql", "V118")
        self.assertNotIn(f"file={SQL}/V118", result.stdout)

    def test_shouldIgnoreRepeatableMigrationWhenAddedBelowTheBaseBranchHighest(self):
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/R__Refresh_Views.sql")

        result = self.check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shouldRejectAddedMigrationWhenTheCheckoutIsShallow(self):
        self.publish_main(f"{SQL}/V115__Store_Retention.sql")
        self.start_pull_request()
        self.commit(f"{SQL}/V116__Store_Language_Preferences.sql")
        self.git(self.checkout, "push", "--quiet", "origin", "pull-request")
        self.publish_main(f"{SQL}/V118__Store_Skipped_File_Reasons.sql")
        shallow = Path(self.directory.name) / "shallow"
        self.git(self.checkout, "clone", "--quiet", "--depth=1", "--branch=pull-request",
            f"file://{self.origin}", str(shallow))

        result = self.check(shallow)

        self.assertRejected(result, f"{SQL}/V116__Store_Language_Preferences.sql", "V118")


if __name__ == "__main__":
    unittest.main()
