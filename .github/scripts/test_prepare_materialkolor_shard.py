from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from prepare_materialkolor_shard import (
    ContractError,
    GROUP,
    VERSION,
    expected_artifacts,
    prepare_shard,
    primary_extension,
)


CORE = "1" * 40
SKIA = "2" * 40
SKIKO = "3" * 40
MATERIALKOLOR = "4" * 40
CONTRACT = Path(__file__).parents[1] / "materialkolor-maven-variant-requirements.json"


def checked_in_requirements() -> dict[str, object]:
    value = json.loads(CONTRACT.read_text(encoding="utf-8"))
    for owner, sources in value["sourceProvenance"].items():
        sources.update({"compose": CORE, "materialkolor": MATERIALKOLOR, "skiko": SKIKO})
        if owner == "windows":
            sources["skia"] = SKIA
    return value


def write_json(path: Path, value: object) -> Path:
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def core_report(root: Path) -> Path:
    provenance = {
        "windows": {"sources": {"compose": CORE, "skia": SKIA, "skiko": SKIKO}},
        "apple": {"sources": {"compose": CORE, "skiko": SKIKO}},
        "web": {"sources": {"compose": CORE, "skiko": SKIKO}},
    }
    return write_json(root / "core-report.json", {"sourceProvenance": provenance})


def create_publications(root: Path, requirements: dict[str, object], owner: str) -> Path:
    group = root.joinpath(*GROUP.split("."))
    for artifact in expected_artifacts(requirements, owner):
        version = group / artifact / VERSION
        version.mkdir(parents=True)
        base = f"{artifact}-{VERSION}"
        name = (
            "Material Color Utilities for Kotlin Multiplatform"
            if artifact.startswith("material-color-utilities")
            else "MaterialKolor"
        )
        license_name = (
            "The Apache License, Version 2.0"
            if artifact.startswith("material-color-utilities")
            else "The MIT License"
        )
        pom = """
<project><groupId>io.github.archivesteak.materialkolor</groupId>
<artifactId>{artifact}</artifactId><version>5.0.1-mingw</version>
<name>{name}</name>
<url>https://github.com/archivesteak/MaterialKolor</url>
<licenses><license><name>{license_name}</name></license></licenses>
<developers><developer><id>archivesteak</id></developer>
<developer><id>jordond</id></developer></developers>
<scm><connection>scm:git:https://github.com/archivesteak/MaterialKolor.git</connection></scm>
</project>
""".format(artifact=artifact, name=name, license_name=license_name)
        module = "io.github.archivesteak.compose.material3" if artifact.startswith("material-kolor-") else "{}"
        (version / f"{base}.pom").write_text(pom, encoding="utf-8")
        (version / f"{base}.module").write_text(module, encoding="utf-8")
        for suffix in (
            primary_extension(artifact),
            "sources.jar",
            "javadoc.jar",
        ):
            (version / f"{base}-{suffix}" if suffix.endswith(".jar") else version / f"{base}.{suffix}").write_bytes(b"x")
    return root


class PrepareMaterialKolorShardTest(unittest.TestCase):
    def test_exact_artifact_sets_match_all_three_hosts(self) -> None:
        requirements = checked_in_requirements()
        self.assertEqual(len(expected_artifacts(requirements, "windows")), 6)
        self.assertEqual(len(expected_artifacts(requirements, "apple")), 9)
        self.assertEqual(len(expected_artifacts(requirements, "web")), 8)
        material_kolor = next(
            module
            for module in requirements["modules"]
            if ":material-kolor:" in module["coordinate"]
        )
        for platform in (
            "mingwX64",
            "macosArm64",
            "iosArm64",
            "iosSimulatorArm64",
            "js",
            "wasmJs",
        ):
            with self.subTest(platform=platform):
                self.assertIn(
                    f"{platform}ResourcesElements-published",
                    material_kolor["requiredVariants"][platform],
                )

    def test_prepares_each_exact_host_with_complete_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            requirements = checked_in_requirements()
            requirements_path = write_json(root / "requirements.json", requirements)
            report = core_report(root)
            for owner in ("windows", "apple", "web"):
                source = create_publications(root / f"source-{owner}", requirements, owner)
                destination = root / f"output-{owner}"
                marker = prepare_shard(
                    owner=owner,
                    source_repository=source,
                    destination=destination,
                    requirements_path=requirements_path,
                    materialkolor_ref=MATERIALKOLOR,
                    core_report_path=report,
                )
                value = json.loads(marker.read_text(encoding="utf-8"))
                self.assertEqual(value["owner"], owner)
                self.assertEqual(value["sources"]["materialkolor"], MATERIALKOLOR)
                self.assertEqual(
                    set(value["sources"]),
                    {"compose", "materialkolor", "skia", "skiko"}
                    if owner == "windows"
                    else {"compose", "materialkolor", "skiko"},
                )
                self.assertEqual(
                    {path.name for path in destination.iterdir()},
                    {"io", "provenance"},
                )

    def test_rejects_an_extra_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            requirements = checked_in_requirements()
            requirements_path = write_json(root / "requirements.json", requirements)
            source = create_publications(root / "source", requirements, "windows")
            source.joinpath(*GROUP.split("."), "unexpected").mkdir()
            with self.assertRaises(ContractError):
                prepare_shard(
                    owner="windows",
                    source_repository=source,
                    destination=root / "output",
                    requirements_path=requirements_path,
                    materialkolor_ref=MATERIALKOLOR,
                    core_report_path=core_report(root),
                )

    def test_rejects_duplicate_pom_identity_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            requirements = checked_in_requirements()
            requirements_path = write_json(root / "requirements.json", requirements)
            source = create_publications(root / "source", requirements, "windows")
            version = source.joinpath(
                *GROUP.split("."),
                "material-kolor",
                VERSION,
            )
            pom_path = version / f"material-kolor-{VERSION}.pom"
            pom = pom_path.read_text(encoding="utf-8")
            pom = pom.replace(
                "</licenses>",
                "<license><name>The MIT License</name></license></licenses>",
            ).replace(
                "</developers>",
                "<developer><id>archivesteak</id></developer></developers>",
            )
            pom_path.write_text(pom, encoding="utf-8")
            with self.assertRaises(ContractError):
                prepare_shard(
                    owner="windows",
                    source_repository=source,
                    destination=root / "output",
                    requirements_path=requirements_path,
                    materialkolor_ref=MATERIALKOLOR,
                    core_report_path=core_report(root),
                )

    def test_rejects_placeholder_or_mismatched_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_requirements = checked_in_requirements()
            source = create_publications(root / "source", source_requirements, "windows")
            for name, requirements in (
                ("placeholder", json.loads(CONTRACT.read_text(encoding="utf-8"))),
                (
                    "mismatch",
                    {
                        **source_requirements,
                        "sourceProvenance": {
                            **source_requirements["sourceProvenance"],
                            "web": {
                                **source_requirements["sourceProvenance"]["web"],
                                "skiko": "5" * 40,
                            },
                        },
                    },
                ),
            ):
                with self.subTest(name=name), self.assertRaises(ContractError):
                    prepare_shard(
                        owner="windows",
                        source_repository=source,
                        destination=root / f"output-{name}",
                        requirements_path=write_json(root / f"{name}.json", requirements),
                        materialkolor_ref=MATERIALKOLOR,
                        core_report_path=core_report(root),
                    )


if __name__ == "__main__":
    unittest.main()
