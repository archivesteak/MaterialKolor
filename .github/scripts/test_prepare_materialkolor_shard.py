from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
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
REPOSITORY_ROOT = Path(__file__).parents[2]
RELEASE_WORKFLOW = Path(__file__).parents[1] / "workflows/release-host-shards.yml"


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


def write_archive(path: Path) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        if path.suffix == ".klib":
            archive.writestr("default/manifest", "unique_name=fixture")
            archive.writestr("default/ir/body.knb", "verified")
        elif path.suffix == ".aar":
            archive.writestr("AndroidManifest.xml", "<manifest />")
        else:
            archive.writestr("content.txt", "verified")
    return path


def version_directory(repository: Path, artifact: str) -> Path:
    return repository.joinpath(*GROUP.split("."), artifact, VERSION)


def create_publications(root: Path, requirements: dict[str, object], owner: str) -> Path:
    group = root.joinpath(*GROUP.split("."))
    modules = {
        module["coordinate"].split(":")[1]: module
        for module in requirements["modules"]
    }
    targets = {
        target: (root_artifact, platform, module["requiredVariants"][platform])
        for root_artifact, module in modules.items()
        for platform, target in module["targetModules"].items()
    }
    platform_owners = requirements["platformOwners"]
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
        (version / f"{base}.pom").write_text(pom, encoding="utf-8")

        root_module = modules.get(artifact)
        if root_module is not None:
            root_artifact = artifact
            variant_names = [
                name
                for platform, names in root_module["requiredVariants"].items()
                if platform == "common" or platform_owners[platform] == owner
                for name in names
            ]
            component = {
                "group": GROUP,
                "module": root_artifact,
                "version": VERSION,
            }
        else:
            root_artifact, _platform, variant_names = targets[artifact]
            component = {
                "url": (
                    f"../../{root_artifact}/{VERSION}/"
                    f"{root_artifact}-{VERSION}.module"
                ),
                "group": GROUP,
                "module": root_artifact,
                "version": VERSION,
            }
        variants = [{"name": variant_name} for variant_name in variant_names]
        if artifact.startswith("material-kolor-"):
            variants[0]["dependencies"] = [
                {
                    "group": "io.github.archivesteak.compose.material3",
                    "module": "material3",
                    "version": {"requires": "1.12.0-alpha03-mingw"},
                }
            ]
        write_json(
            version / f"{base}.module",
            {"component": component, "variants": variants},
        )

        write_archive(version / f"{base}.{primary_extension(artifact)}")
        write_archive(version / f"{base}-sources.jar")
        write_archive(version / f"{base}-javadoc.jar")
        if root_module is not None:
            write_json(
                version / f"{base}-kotlin-tooling-metadata.json",
                {
                    "schemaVersion": "1.1.0",
                    "buildSystem": "Gradle",
                    "buildPlugin": (
                        "org.jetbrains.kotlin.gradle.plugin."
                        "KotlinMultiplatformPluginWrapper"
                    ),
                    "projectTargets": [{"platformType": "common"}],
                },
            )
        else:
            if any(
                name.endswith("MetadataElements-published") for name in variant_names
            ):
                write_archive(version / f"{base}-metadata.jar")
            if any(
                name.endswith("ResourcesElements-published") for name in variant_names
            ):
                write_archive(
                    version / f"{base}-kotlin_resources.kotlin_resources.zip"
                )
    return root


class PrepareMaterialKolorShardTest(unittest.TestCase):
    def test_release_javadocs_are_nonempty_and_cross_host_reproducible(self) -> None:
        build_script = (REPOSITORY_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        for contract in (
            'tasks.register("readmeJavadocJar", Jar::class.java)',
            "isPreserveFileTimestamps = false",
            "isReproducibleFileOrder = true",
            "entryCompression = ZipEntryCompression.STORED",
            'artifact.classifier == "javadoc"',
            ".forEach(artifacts::remove)",
        ):
            self.assertIn(contract, build_script)
        readme = REPOSITORY_ROOT / "gradle/maven-central-javadoc/README.md"
        self.assertGreater(readme.stat().st_size, 0)

    def test_release_sources_have_cross_host_canonical_line_endings(self) -> None:
        attributes = (REPOSITORY_ROOT / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn("* text=auto eol=lf", attributes.splitlines())
        self.assertIn("*.bat text eol=crlf", attributes.splitlines())
        self.assertIn("*.jar binary", attributes.splitlines())

    def test_release_checkout_materializes_the_pinned_upstream_test_oracle(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        source_checkout = workflow.split(
            "- name: Check out the exact MaterialKolor source",
            maxsplit=1,
        )[1].split("- name:", maxsplit=1)[0]
        self.assertIn("submodules: recursive", source_checkout)

    def test_host_specific_test_runtime_is_portable_and_complete(self) -> None:
        build_script = (
            REPOSITORY_ROOT / "material-kolor/build.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertEqual(build_script.count("binaries.executable()"), 2)
        self.assertIn('named("androidHostTest").dependencies', build_script)
        self.assertIn("runtimeOnly(libs.androidx.compose.material3)", build_script)

        quantizer = (
            REPOSITORY_ROOT
            / "material-color-utilities/src/commonMain/kotlin/com/materialkolor/quantize/QuantizerResult.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("internal data class QuantizerResult", quantizer)
        self.assertNotIn("JvmInline", quantizer)

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
        material_utilities = next(
            module
            for module in requirements["modules"]
            if ":material-color-utilities:" in module["coordinate"]
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
        for module, platforms in (
            (
                material_utilities,
                ("macosArm64", "iosX64", "iosArm64", "iosSimulatorArm64"),
            ),
            (
                material_kolor,
                ("macosArm64", "iosArm64", "iosSimulatorArm64"),
            ),
        ):
            for platform in platforms:
                with self.subTest(
                    artifact=module["coordinate"],
                    platform=platform,
                ):
                    self.assertIn(
                        f"{platform}MetadataElements-published",
                        module["requiredVariants"][platform],
                    )
            self.assertFalse(
                any(
                    name.endswith("MetadataElements-published")
                    for name in module["requiredVariants"]["mingwX64"]
                )
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
                if owner == "apple":
                    utilities = version_directory(
                        destination,
                        "material-color-utilities-macosarm64",
                    )
                    material_kolor = version_directory(
                        destination,
                        "material-kolor-macosarm64",
                    )
                    self.assertTrue(
                        (
                            utilities
                            / f"material-color-utilities-macosarm64-{VERSION}-metadata.jar"
                        ).is_file()
                    )
                    self.assertFalse(
                        (
                            utilities
                            / (
                                "material-color-utilities-macosarm64-"
                                f"{VERSION}-kotlin_resources.kotlin_resources.zip"
                            )
                        ).exists()
                    )
                    self.assertTrue(
                        (
                            material_kolor
                            / f"material-kolor-macosarm64-{VERSION}-metadata.jar"
                        ).is_file()
                    )
                    self.assertTrue(
                        (
                            material_kolor
                            / (
                                f"material-kolor-macosarm64-{VERSION}-"
                                "kotlin_resources.kotlin_resources.zip"
                            )
                        ).is_file()
                    )

    def test_rejects_missing_or_corrupt_apple_metadata_archive(self) -> None:
        for mutation in ("missing", "corrupt"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                requirements = checked_in_requirements()
                requirements_path = write_json(root / "requirements.json", requirements)
                source = create_publications(root / "source", requirements, "apple")
                artifact = "material-color-utilities-macosarm64"
                metadata = version_directory(source, artifact) / (
                    f"{artifact}-{VERSION}-metadata.jar"
                )
                if mutation == "missing":
                    metadata.unlink()
                    expected_error = "publication files differ"
                else:
                    metadata.write_bytes(b"not a ZIP archive")
                    expected_error = "published archive is corrupt"
                with self.assertRaisesRegex(ContractError, expected_error):
                    prepare_shard(
                        owner="apple",
                        source_repository=source,
                        destination=root / "output",
                        requirements_path=requirements_path,
                        materialkolor_ref=MATERIALKOLOR,
                        core_report_path=core_report(root),
                    )

    def test_rejects_leaf_with_wrong_root_redirect_or_variant_set(self) -> None:
        for mutation in ("redirect", "variants"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                requirements = checked_in_requirements()
                requirements_path = write_json(root / "requirements.json", requirements)
                source = create_publications(root / "source", requirements, "apple")
                artifact = "material-kolor-macosarm64"
                module_path = version_directory(source, artifact) / (
                    f"{artifact}-{VERSION}.module"
                )
                module = json.loads(module_path.read_text(encoding="utf-8"))
                if mutation == "redirect":
                    module["component"]["url"] = (
                        f"../../material-color-utilities/{VERSION}/"
                        f"material-color-utilities-{VERSION}.module"
                    )
                    expected_error = "wrong root redirect"
                else:
                    module["variants"].pop()
                    expected_error = "Gradle metadata variants differ"
                write_json(module_path, module)
                with self.assertRaisesRegex(ContractError, expected_error):
                    prepare_shard(
                        owner="apple",
                        source_repository=source,
                        destination=root / "output",
                        requirements_path=requirements_path,
                        materialkolor_ref=MATERIALKOLOR,
                        core_report_path=core_report(root),
                    )

    def test_rejects_resources_on_non_compose_leaf_and_metadata_on_windows(self) -> None:
        cases = (
            (
                "apple",
                "material-color-utilities-macosarm64",
                "kotlin_resources.kotlin_resources.zip",
            ),
            ("windows", "material-color-utilities-mingwx64", "metadata.jar"),
        )
        for owner, artifact, suffix in cases:
            with self.subTest(owner=owner, artifact=artifact):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    requirements = checked_in_requirements()
                    requirements_path = write_json(
                        root / "requirements.json", requirements
                    )
                    source = create_publications(root / "source", requirements, owner)
                    write_archive(
                        version_directory(source, artifact)
                        / f"{artifact}-{VERSION}-{suffix}"
                    )
                    with self.assertRaisesRegex(
                        ContractError, "publication files differ"
                    ):
                        prepare_shard(
                            owner=owner,
                            source_repository=source,
                            destination=root / "output",
                            requirements_path=requirements_path,
                            materialkolor_ref=MATERIALKOLOR,
                            core_report_path=core_report(root),
                        )

    def test_rejects_invalid_root_tooling_metadata_and_root_redirect(self) -> None:
        for mutation in ("tooling", "redirect"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                requirements = checked_in_requirements()
                requirements_path = write_json(root / "requirements.json", requirements)
                source = create_publications(root / "source", requirements, "windows")
                artifact = "material-color-utilities"
                publication = version_directory(source, artifact)
                if mutation == "tooling":
                    tooling = publication / (
                        f"{artifact}-{VERSION}-kotlin-tooling-metadata.json"
                    )
                    value = json.loads(tooling.read_text(encoding="utf-8"))
                    value["projectTargets"] = []
                    write_json(tooling, value)
                    expected_error = "Kotlin tooling metadata is incomplete"
                else:
                    module_path = publication / f"{artifact}-{VERSION}.module"
                    module = json.loads(module_path.read_text(encoding="utf-8"))
                    module["component"]["url"] = "../../unexpected.module"
                    write_json(module_path, module)
                    expected_error = "root Gradle metadata must not redirect"
                with self.assertRaisesRegex(ContractError, expected_error):
                    prepare_shard(
                        owner="windows",
                        source_repository=source,
                        destination=root / "output",
                        requirements_path=requirements_path,
                        materialkolor_ref=MATERIALKOLOR,
                        core_report_path=core_report(root),
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
