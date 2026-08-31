from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).parents[1] / "workflows" / "release-host-shards.yml"
REPOSITORY = Path(__file__).resolve().parents[2]


class ReleaseWorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_every_action_is_pinned_to_a_full_commit(self) -> None:
        uses = re.findall(r"^\s*-?\s*uses:\s*([^\s#]+)", self.text, re.MULTILINE)
        self.assertTrue(uses)
        for action in uses:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[^@]+@[0-9a-f]{40}$")
        self.assertIn(
            "actions/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131",
            uses,
        )
        self.assertIn(
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
            uses,
        )

    def test_contract_requires_distinct_source_contract_and_run_inputs(self) -> None:
        required = {
            "materialkolor_ref",
            "materialkolor_contract_ref",
            "core_repository",
            "core_ref",
            "core_contract_ref",
            "core_windows_run_id",
            "core_apple_run_id",
            "core_web_run_id",
        }
        for name in required:
            with self.subTest(name=name):
                self.assertIn(f"      {name}:\n", self.text)
        self.assertIn("head_sha", self.text)
        self.assertIn("github.workflow_sha", self.text)

    def test_artifacts_are_fixed_short_lived_and_never_remotely_published(self) -> None:
        for artifact in (
            "maven-windows",
            "maven-apple",
            "maven-web-android",
            "materialkolor-maven-windows",
            "materialkolor-maven-apple",
            "materialkolor-maven-web-android",
            "validated-materialkolor-maven",
        ):
            with self.subTest(artifact=artifact):
                self.assertIn(artifact, self.text)
        self.assertEqual(self.text.count("retention-days: 1"), 3)
        self.assertIn("publishHostShardToMavenLocal", self.text)
        self.assertIn("runner: macos-15", self.text)
        self.assertIn("Apple shard requires an arm64 runner", self.text)
        self.assertGreaterEqual(self.text.count("--dry-run"), 2)
        self.assertIn("materialkolor-merge-report.json", self.text)
        self.assertIn("core-merge-report.json", self.text)
        for forbidden in (
            "publishToMavenCentral",
            "publishAllPublications",
            "mavenCentralUsername",
            "mavenCentralPassword",
            "signingInMemoryKey",
            "contents: write",
            "actions: write",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_publications_identify_the_fork_maintainer(self) -> None:
        for relative in (
            "material-color-utilities/build.gradle.kts",
            "material-kolor/build.gradle.kts",
        ):
            with self.subTest(relative=relative):
                publication = (REPOSITORY / relative).read_text(encoding="utf-8")
                self.assertIn('id.set("archivesteak")', publication)
                self.assertIn('name.set("Jack Harrington")', publication)
                self.assertIn("developerConnection.set(", publication)

    def test_final_artifact_contains_core_and_materialkolor_without_overlay(self) -> None:
        final_union = self.text.split(
            "- name: Strictly union and validate the complete MaterialKolor repository", 1
        )[1].split("- name: Upload the short-lived validated MaterialKolor repository", 1)[0]
        self.assertIn("cp -a validated-core/repository", final_union)
        self.assertIn(
            "test ! -e validated-core/repository/io/github/archivesteak/materialkolor",
            final_union,
        )
        self.assertIn(
            'validated-materialkolor/repository/io/github/archivesteak/compose',
            final_union,
        )
        self.assertIn(
            'validated-materialkolor/repository/io/github/archivesteak/materialkolor',
            final_union,
        )


if __name__ == "__main__":
    unittest.main()
