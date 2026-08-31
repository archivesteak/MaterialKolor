from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from validate_core_artifact_inputs import ContractError, validate_contract


CORE = "1" * 40
SKIA = "2" * 40
SKIKO = "3" * 40


def write_requirements(root: Path, value: dict[str, object]) -> Path:
    path = root / "requirements.json"
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def requirements() -> dict[str, object]:
    return {
        "schemaVersion": 2,
        "sourceProvenance": {
            "windows": {"compose": CORE, "skia": SKIA, "skiko": SKIKO},
            "apple": {"compose": CORE, "skiko": SKIKO},
            "web": {"compose": CORE, "skiko": SKIKO},
        },
    }


class ValidateCoreArtifactInputsTest(unittest.TestCase):
    def test_accepts_exact_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            sources = validate_contract(
                repository="archivesteak/compose-multiplatform-core",
                core_ref=CORE,
                run_ids={"windows": "101", "apple": "102", "web": "103"},
                core_requirements_path=write_requirements(
                    Path(temporary),
                    requirements(),
                ),
            )
        self.assertEqual(sources, {"compose": CORE, "skia": SKIA, "skiko": SKIKO})

    def test_rejects_wrong_repository_ref_and_run_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write_requirements(Path(temporary), requirements())
            cases = (
                ("someone/else", CORE, {"windows": "1", "apple": "2", "web": "3"}),
                (
                    "archivesteak/compose-multiplatform-core",
                    "A" * 40,
                    {"windows": "1", "apple": "2", "web": "3"},
                ),
                (
                    "archivesteak/compose-multiplatform-core",
                    CORE,
                    {"windows": "0", "apple": "2", "web": "3"},
                ),
            )
            for repository, core_ref, run_ids in cases:
                with self.subTest(repository=repository, core_ref=core_ref, run_ids=run_ids):
                    with self.assertRaises(ContractError):
                        validate_contract(
                            repository=repository,
                            core_ref=core_ref,
                            run_ids=run_ids,
                            core_requirements_path=path,
                        )

    def test_rejects_mixed_and_placeholder_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mixed = requirements()
            mixed["sourceProvenance"]["apple"]["compose"] = "4" * 40  # type: ignore[index]
            placeholder = requirements()
            placeholder["sourceProvenance"]["web"]["skiko"] = "0" * 40  # type: ignore[index]
            for name, value in (("mixed", mixed), ("placeholder", placeholder)):
                with self.subTest(name=name), self.assertRaises(ContractError):
                    validate_contract(
                        repository="archivesteak/compose-multiplatform-core",
                        core_ref=CORE,
                        run_ids={"windows": "1", "apple": "2", "web": "3"},
                        core_requirements_path=write_requirements(root, value),
                    )


if __name__ == "__main__":
    unittest.main()
