#!/usr/bin/env python3
"""Collect one exact MaterialKolor host publication and attach core provenance."""

from __future__ import annotations

import argparse
import json
import shutil
import zipfile
from pathlib import Path
from typing import Any

from validate_core_artifact_inputs import (
    ContractError,
    OWNERS,
    load_json,
    validate_commit,
)


GROUP = "io.github.archivesteak.materialkolor"
VERSION = "5.0.1-mingw"
ROOT_ARTIFACTS = ("material-color-utilities", "material-kolor")


def module_requirements(requirements: dict[str, Any]) -> dict[str, dict[str, Any]]:
    modules = requirements.get("modules")
    if not isinstance(modules, list):
        raise ContractError("MaterialKolor requirements must contain a modules array")
    result: dict[str, dict[str, Any]] = {}
    for module in modules:
        if not isinstance(module, dict):
            raise ContractError("MaterialKolor module requirement must be an object")
        coordinate = module.get("coordinate")
        if not isinstance(coordinate, str):
            raise ContractError("MaterialKolor module coordinate must be a string")
        group, separator, remainder = coordinate.partition(":")
        artifact, separator2, version = remainder.partition(":")
        if not separator or not separator2 or group != GROUP or version != VERSION:
            raise ContractError(f"unexpected MaterialKolor coordinate: {coordinate!r}")
        if artifact in result:
            raise ContractError(f"duplicate MaterialKolor root artifact: {artifact}")
        result[artifact] = module
    if set(result) != set(ROOT_ARTIFACTS):
        raise ContractError(
            f"MaterialKolor roots must be exactly {ROOT_ARTIFACTS}, found {sorted(result)}"
        )
    return result


def expected_artifacts(requirements: dict[str, Any], owner: str) -> set[str]:
    if owner not in OWNERS:
        raise ContractError(f"invalid MaterialKolor owner {owner!r}")
    platform_owners = requirements.get("platformOwners")
    expected_owners = {
        "common": "windows",
        "jvm": "windows",
        "mingwX64": "windows",
        "macosArm64": "apple",
        "iosX64": "apple",
        "iosArm64": "apple",
        "iosSimulatorArm64": "apple",
        "js": "web",
        "wasmJs": "web",
        "android": "web",
    }
    if platform_owners != expected_owners:
        raise ContractError("MaterialKolor platform ownership differs from the exact host contract")

    artifacts = set(ROOT_ARTIFACTS)
    for root_artifact, module in module_requirements(requirements).items():
        required_variants = module.get("requiredVariants")
        target_modules = module.get("targetModules")
        if not isinstance(required_variants, dict) or not isinstance(target_modules, dict):
            raise ContractError(f"{root_artifact} lacks variants or target modules")
        if set(target_modules) != set(required_variants) - {"common"}:
            raise ContractError(f"{root_artifact} target module platforms are incomplete")
        for platform in required_variants:
            if platform == "common" or platform_owners[platform] != owner:
                continue
            artifact = target_modules.get(platform)
            if not isinstance(artifact, str) or not artifact.startswith(f"{root_artifact}-"):
                raise ContractError(
                    f"{root_artifact} has invalid targetModules[{platform!r}]"
                )
            artifacts.add(artifact)
    return artifacts


def ensure_tree_has_no_symlinks(root: Path) -> None:
    if root.is_symlink():
        raise ContractError(f"publication path must not be a symlink: {root}")
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ContractError(f"publication contains a symlink: {path}")


def core_sources_from_report(path: Path) -> dict[str, dict[str, str]]:
    report = load_json(path, "validated core merge report")
    provenance = report.get("sourceProvenance")
    if not isinstance(provenance, dict) or set(provenance) != set(OWNERS):
        raise ContractError("core merge report lacks exact owner provenance")
    expected_names = {
        "windows": {"compose", "skia", "skiko"},
        "apple": {"compose", "skiko"},
        "web": {"compose", "skiko"},
    }
    combined: dict[str, str] = {}
    by_owner: dict[str, dict[str, str]] = {}
    for owner in OWNERS:
        owner_record = provenance[owner]
        if not isinstance(owner_record, dict):
            raise ContractError(f"core merge report {owner} provenance is not an object")
        sources = owner_record.get("sources")
        if not isinstance(sources, dict) or set(sources) != expected_names[owner]:
            raise ContractError(f"core merge report {owner} sources differ from contract")
        validated_sources: dict[str, str] = {}
        for name, raw_commit in sources.items():
            commit = validate_commit(raw_commit, f"core merge report {owner}/{name}")
            validated_sources[name] = commit
            previous = combined.setdefault(name, commit)
            if previous != commit:
                raise ContractError(
                    f"core merge report gives {name} inconsistent commits: {previous}, {commit}"
                )
        by_owner[owner] = validated_sources
    return by_owner


def primary_extension(artifact: str) -> str:
    if artifact in ROOT_ARTIFACTS or artifact.endswith("-jvm"):
        return "jar"
    if artifact.endswith("-android"):
        return "aar"
    return "klib"


def verify_archive(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if not entries:
                raise ContractError(f"published archive is empty: {path}")
            for entry in entries:
                parts = Path(entry.filename.replace("\\", "/")).parts
                if entry.filename.startswith(("/", "\\")) or any(
                    part in {"", ".", ".."} for part in parts
                ):
                    raise ContractError(f"published archive has an unsafe entry: {path}")
            names = {entry.filename for entry in entries}
    except zipfile.BadZipFile as error:
        raise ContractError(f"published archive is corrupt: {path}") from error
    if path.suffix == ".klib" and "default/manifest" not in names:
        raise ContractError(f"published KLIB lacks default/manifest: {path}")
    if path.suffix == ".aar" and "AndroidManifest.xml" not in names:
        raise ContractError(f"published AAR lacks AndroidManifest.xml: {path}")


def validate_publication(
    version_directory: Path,
    artifact: str,
    root_artifact: str,
    expected_variants: set[str] | None = None,
) -> None:
    if root_artifact not in ROOT_ARTIFACTS:
        raise ContractError(
            f"{artifact} maps to unknown MaterialKolor root {root_artifact}"
        )
    is_root = artifact == root_artifact
    if expected_variants is None or not expected_variants:
        raise ContractError(f"{artifact} has no expected publication variants")
    base_name = f"{artifact}-{VERSION}"
    expected = {
        f"{base_name}.pom",
        f"{base_name}.module",
        f"{base_name}.{primary_extension(artifact)}",
        f"{base_name}-sources.jar",
        f"{base_name}-javadoc.jar",
    }
    tooling_name = f"{base_name}-kotlin-tooling-metadata.json"
    metadata_name = f"{base_name}-metadata.jar"
    resources_name = f"{base_name}-kotlin_resources.kotlin_resources.zip"
    if is_root:
        expected.add(tooling_name)
    else:
        if any(name.endswith("MetadataElements-published") for name in expected_variants):
            expected.add(metadata_name)
        if any(name.endswith("ResourcesElements-published") for name in expected_variants):
            expected.add(resources_name)

    entries = list(version_directory.iterdir())
    existing = {path.name for path in entries}
    if existing != expected or any(not path.is_file() for path in entries):
        raise ContractError(
            f"{artifact} publication files differ: expected {sorted(expected)}, "
            f"found {sorted(existing)}"
        )

    pom = (version_directory / f"{base_name}.pom").read_text(encoding="utf-8")
    module_path = version_directory / f"{base_name}.module"
    module = load_json(module_path, f"{artifact} Gradle metadata")
    component = module.get("component")
    if not isinstance(component, dict) or (
        component.get("group"),
        component.get("module"),
        component.get("version"),
    ) != (GROUP, root_artifact, VERSION):
        raise ContractError(f"{artifact} Gradle metadata has the wrong component")
    component_url = component.get("url")
    if is_root:
        if component_url is not None:
            raise ContractError(f"{artifact} root Gradle metadata must not redirect")
    else:
        expected_url = (
            f"../../{root_artifact}/{VERSION}/"
            f"{root_artifact}-{VERSION}.module"
        )
        if component_url != expected_url:
            raise ContractError(
                f"{artifact} Gradle metadata has the wrong root redirect"
            )

    variants = module.get("variants")
    if not isinstance(variants, list) or not variants:
        raise ContractError(f"{artifact} Gradle metadata has no variants")
    variant_names = [
        variant.get("name") for variant in variants if isinstance(variant, dict)
    ]
    if len(variant_names) != len(variants) or any(
        not isinstance(name, str) or not name for name in variant_names
    ):
        raise ContractError(f"{artifact} Gradle metadata has malformed variants")
    if len(set(variant_names)) != len(variant_names):
        raise ContractError(f"{artifact} Gradle metadata repeats a variant")
    if set(variant_names) != expected_variants:
        raise ContractError(
            f"{artifact} Gradle metadata variants differ: "
            f"expected {sorted(expected_variants)}, found {sorted(variant_names)}"
        )

    if is_root:
        tooling = load_json(
            version_directory / tooling_name,
            f"{artifact} Kotlin tooling metadata",
        )
        if (
            tooling.get("schemaVersion") != "1.1.0"
            or tooling.get("buildSystem") != "Gradle"
            or tooling.get("buildPlugin")
            != "org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper"
            or not isinstance(tooling.get("projectTargets"), list)
            or not tooling["projectTargets"]
        ):
            raise ContractError(f"{artifact} Kotlin tooling metadata is incomplete")

    module_text = module_path.read_text(encoding="utf-8")
    expected_name = (
        "Material Color Utilities for Kotlin Multiplatform"
        if artifact.startswith("material-color-utilities")
        else "MaterialKolor"
    )
    expected_license = (
        "The Apache License, Version 2.0"
        if artifact.startswith("material-color-utilities")
        else "The MIT License"
    )
    required_identity = {
        f"<groupId>{GROUP}</groupId>",
        f"<artifactId>{artifact}</artifactId>",
        f"<version>{VERSION}</version>",
        f"<name>{expected_name}</name>",
        f"<name>{expected_license}</name>",
        "<url>https://github.com/archivesteak/MaterialKolor</url>",
        "<id>archivesteak</id>",
        "<id>jordond</id>",
        "<connection>scm:git:https://github.com/archivesteak/MaterialKolor.git</connection>",
    }
    missing_identity = sorted(value for value in required_identity if value not in pom)
    if missing_identity:
        raise ContractError(f"{artifact} POM lacks required identity: {missing_identity}")
    if pom.count("<license>") != 1:
        raise ContractError(f"{artifact} POM must declare exactly one module-specific license")
    if pom.count("<developer>") != 2:
        raise ContractError(
            f"{artifact} POM must declare exactly the fork maintainer and upstream author"
        )
    for developer_id in ("archivesteak", "jordond"):
        if pom.count(f"<id>{developer_id}</id>") != 1:
            raise ContractError(
                f"{artifact} POM must declare developer {developer_id} exactly once"
            )
    for forbidden in (
        "com.materialkolor",
        "org.jetbrains.compose.foundation",
        "org.jetbrains.compose.material3",
        "org.jetbrains.compose.runtime",
        "org.jetbrains.compose.ui",
    ):
        if forbidden in module_text:
            raise ContractError(f"{artifact} metadata contains forbidden coordinate {forbidden}")
    if (
        artifact.startswith("material-kolor-")
        and "io.github.archivesteak.compose.material3" not in module_text
    ):
        raise ContractError(f"{artifact} metadata lacks forked Material3 lineage")

    for filename in (
        f"{base_name}.{primary_extension(artifact)}",
        f"{base_name}-sources.jar",
        f"{base_name}-javadoc.jar",
    ):
        verify_archive(version_directory / filename)
    for filename in (metadata_name, resources_name):
        if filename in expected:
            verify_archive(version_directory / filename)


def validate_release_contract(
    *,
    requirements_path: Path,
    materialkolor_ref: str,
    core_report_path: Path,
) -> tuple[dict[str, Any], dict[str, dict[str, str]]]:
    materialkolor_ref = validate_commit(materialkolor_ref, "materialkolor_ref")
    requirements = load_json(requirements_path, "MaterialKolor requirements")
    if requirements.get("schemaVersion") != 2 or requirements.get("groupPrefix") != GROUP:
        raise ContractError("MaterialKolor requirements have the wrong schema or group")

    core_sources = core_sources_from_report(core_report_path)
    actual_sources = {
        owner: {**core_sources[owner], "materialkolor": materialkolor_ref}
        for owner in OWNERS
    }
    provenance = requirements.get("sourceProvenance")
    if not isinstance(provenance, dict) or set(provenance) != set(OWNERS):
        raise ContractError("MaterialKolor requirements lack exact owner provenance")
    for provenance_owner in OWNERS:
        if provenance[provenance_owner] != actual_sources[provenance_owner]:
            raise ContractError(
                f"MaterialKolor {provenance_owner} provenance does not match selected sources"
            )
    module_requirements(requirements)
    return requirements, actual_sources


def prepare_shard(
    *,
    owner: str,
    source_repository: Path,
    destination: Path,
    requirements_path: Path,
    materialkolor_ref: str,
    core_report_path: Path,
) -> Path:
    requirements, actual_sources = validate_release_contract(
        requirements_path=requirements_path,
        materialkolor_ref=materialkolor_ref,
        core_report_path=core_report_path,
    )
    modules = module_requirements(requirements)
    platform_owners = requirements["platformOwners"]
    publication_specs: dict[str, tuple[str, set[str]]] = {
        root: (
            root,
            {
                variant
                for platform, variants in modules[root]["requiredVariants"].items()
                if platform == "common" or platform_owners[platform] == owner
                for variant in variants
            },
        )
        for root in ROOT_ARTIFACTS
    }
    for root, module in modules.items():
        required_variants = module.get("requiredVariants")
        target_modules = module.get("targetModules")
        if not isinstance(required_variants, dict) or not isinstance(
            target_modules, dict
        ):
            raise ContractError(f"{root} lacks variants or target modules")
        for platform, target in target_modules.items():
            names = required_variants.get(platform)
            if not isinstance(target, str) or not isinstance(names, list) or not names:
                raise ContractError(
                    f"{root} has an invalid publication mapping for {platform!r}"
                )
            if not all(isinstance(name, str) and name for name in names):
                raise ContractError(f"{root} has invalid variants for {platform!r}")
            spec = (root, set(names))
            previous = publication_specs.setdefault(target, spec)
            if previous != spec:
                raise ContractError(
                    f"MaterialKolor target {target} has conflicting publication mappings"
                )

    source_repository = source_repository.resolve()
    if source_repository.is_symlink() or not source_repository.is_dir():
        raise ContractError(f"source Maven repository is invalid: {source_repository}")
    destination = destination.resolve()
    if destination.exists():
        raise ContractError(f"destination must be fresh and absent: {destination}")

    source_group = source_repository.joinpath(*GROUP.split("."))
    if source_group.is_symlink() or not source_group.is_dir():
        raise ContractError(f"source repository has no MaterialKolor group: {source_group}")
    artifacts = expected_artifacts(requirements, owner)
    existing = {path.name for path in source_group.iterdir() if path.is_dir()}
    if existing != artifacts:
        raise ContractError(
            f"{owner} MaterialKolor artifacts differ: expected {sorted(artifacts)}, "
            f"found {sorted(existing)}"
        )

    destination_group = destination.joinpath(*GROUP.split("."))
    for artifact in sorted(artifacts):
        source_version = source_group / artifact / VERSION
        ensure_tree_has_no_symlinks(source_version)
        spec = publication_specs.get(artifact)
        if spec is None:
            raise ContractError(
                f"{artifact} has no MaterialKolor root publication mapping"
            )
        root_artifact, expected_variants = spec
        validate_publication(
            source_version,
            artifact,
            root_artifact,
            expected_variants,
        )
        shutil.copytree(source_version, destination_group / artifact / VERSION)

    marker = destination / "provenance" / f"{owner}.json"
    marker.parent.mkdir(parents=True)
    marker.write_text(
        json.dumps(
            {"schemaVersion": 1, "owner": owner, "sources": actual_sources[owner]},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    if {path.name for path in destination.iterdir()} != {"io", "provenance"}:
        raise ContractError("prepared MaterialKolor shard has unexpected root entries")
    return marker


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner", required=True, choices=OWNERS)
    parser.add_argument("--source-repository", type=Path)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--requirements", required=True, type=Path)
    parser.add_argument("--materialkolor-ref", required=True)
    parser.add_argument("--core-report", required=True, type=Path)
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.validate_only:
            validate_release_contract(
                requirements_path=args.requirements,
                materialkolor_ref=args.materialkolor_ref,
                core_report_path=args.core_report,
            )
            print("validated exact MaterialKolor and core source contract")
            return 0
        if args.source_repository is None or args.destination is None:
            raise ContractError(
                "--source-repository and --destination are required unless --validate-only is used"
            )
        marker = prepare_shard(
            owner=args.owner,
            source_repository=args.source_repository,
            destination=args.destination,
            requirements_path=args.requirements,
            materialkolor_ref=args.materialkolor_ref,
            core_report_path=args.core_report,
        )
    except (ContractError, OSError, UnicodeError) as error:
        print(f"ERROR: {error}")
        return 1
    print(f"prepared exact MaterialKolor shard: {marker.parent.parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
