from __future__ import annotations

import csv
import importlib.util
import json
import shutil
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from . import __version__
from .adapters import select_adapter
from .adapters.bar102_il2cpp31 import probe_archive, probe_metadata_wrapper
from .diffing import diff_datasets
from .resources import (
    ResourceChain,
    locate_download,
    select_chains,
    validate_download,
    write_unpatched_provenance,
)
from .state import StateStore
from .util import (
    PipelineError,
    copy_core_manifest,
    file_identity,
    now_iso,
    python_command,
    python_environment,
    read_json,
    reset_path,
    run_command,
    sha256_file,
    write_json,
)


csv.field_size_limit(min(sys.maxsize, 1024 * 1024 * 1024))


@dataclass(frozen=True)
class Settings:
    workspace: Path
    config_path: Path
    game_root: Path
    analysis_root: Path
    reference_root: Path
    runs_root: Path
    offline_manifest_dir: Path
    baseline_dataset: Path
    metadata_relative_path: Path
    resource_targets: tuple[str, ...]

    @property
    def tools_root(self) -> Path:
        return self.analysis_root / "tools"

    @property
    def game_manifest(self) -> Path:
        return self.game_root / "manifest.json"

    @property
    def wrapped_metadata(self) -> Path:
        return self.game_root / self.metadata_relative_path


def resolve_setting(workspace: Path, value: str) -> Path:
    path = Path(value)
    return path.resolve() if path.is_absolute() else (workspace / path).resolve()


def load_settings(config_path: Path) -> Settings:
    config_path = config_path.resolve()
    raw = read_json(config_path)
    workspace = config_path.parent.parent.resolve()
    return Settings(
        workspace=workspace,
        config_path=config_path,
        game_root=resolve_setting(workspace, raw["game_root"]),
        analysis_root=resolve_setting(workspace, raw["analysis_root"]),
        reference_root=resolve_setting(workspace, raw["reference_root"]),
        runs_root=resolve_setting(workspace, raw["runs_root"]),
        offline_manifest_dir=resolve_setting(workspace, raw["offline_manifest_dir"]),
        baseline_dataset=resolve_setting(workspace, raw["baseline_dataset"]),
        metadata_relative_path=Path(raw["metadata_relative_path"]),
        resource_targets=tuple(raw.get("resource_targets", ["data.arcx", "hotfix.arch"])),
    )


def tool_hashes(settings: Settings, names: list[str]) -> dict[str, str]:
    return {
        name: sha256_file(settings.tools_root / name)
        for name in names
        if (settings.tools_root / name).exists()
    }


class Pipeline:
    def __init__(
        self,
        settings: Settings,
        *,
        offline: bool,
        resume: bool,
        run_dir: Path | None,
        previous_dataset: Path | None,
    ):
        self.settings = settings
        self.offline = offline
        self.resume = resume
        self.previous_dataset = previous_dataset.resolve() if previous_dataset else None
        run_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.run_dir = (
            run_dir.resolve()
            if run_dir is not None
            else (settings.runs_root / run_id).resolve()
        )
        self.state_path = self.run_dir / "state.json"
        if self.run_dir.exists() and not resume:
            raise PipelineError(
                f"run directory already exists: {self.run_dir}; use --resume to continue it"
            )
        if resume and not self.state_path.exists():
            raise PipelineError(f"cannot resume without state.json: {self.run_dir}")
        self.run_dir.mkdir(parents=True, exist_ok=True)
        initial = None
        if not self.state_path.exists():
            initial = {
                "schema_version": "1.0.0",
                "pipeline_version": __version__,
                "run_id": self.run_dir.name,
                "run_dir": str(self.run_dir),
                "created_at": now_iso(),
                "status": "created",
                "mode": "offline-cache" if offline else "online",
                "config": self.public_config(),
                "stages": {},
            }
        self.state = StateStore(self.state_path, initial)
        self.env = python_environment(settings.analysis_root)

    @property
    def manifests_dir(self) -> Path:
        return self.run_dir / "manifests"

    @property
    def archives_dir(self) -> Path:
        return self.run_dir / "archives"

    @property
    def metadata_dir(self) -> Path:
        return self.run_dir / "metadata"

    @property
    def extract_dir(self) -> Path:
        return self.run_dir / "extract"

    @property
    def dataset_dir(self) -> Path:
        return self.run_dir / "dataset"

    @property
    def diff_dir(self) -> Path:
        return self.run_dir / "diff"

    @property
    def packages_dir(self) -> Path:
        return self.run_dir / "packages"

    @property
    def diagnostics_dir(self) -> Path:
        return self.run_dir / "diagnostics"

    @property
    def name_map_path(self) -> Path:
        return self.run_dir / "archive_name_map.json"

    def log(self, stage: str) -> Path:
        return self.run_dir / "logs" / f"{stage}.log"

    def public_config(self) -> dict[str, Any]:
        return {
            "config_path": str(self.settings.config_path),
            "workspace": str(self.settings.workspace),
            "game_root": str(self.settings.game_root),
            "analysis_root": str(self.settings.analysis_root),
            "reference_root": str(self.settings.reference_root),
            "runs_root": str(self.settings.runs_root),
            "offline_manifest_dir": str(self.settings.offline_manifest_dir),
            "baseline_dataset": str(self.settings.baseline_dataset),
            "resource_targets": list(self.settings.resource_targets),
        }

    def run(self) -> dict[str, Any]:
        self.inspect_client()
        self.acquire_resources()
        self.reconstruct_archives()
        self.probe_formats()
        self.extract_metadata()
        self.extract_archive()
        self.map_archive_names()
        self.decode_tables()
        self.build_dataset()
        self.create_diff()
        self.package_dataset()
        result = self.finalize()
        return result

    def inspect_client(self) -> None:
        manifest = self.settings.game_manifest
        metadata = self.settings.wrapped_metadata

        def action() -> dict[str, Any]:
            for path in (manifest, metadata):
                if not path.exists():
                    raise PipelineError(f"required client file is missing: {path}")
            game = read_json(manifest)
            snapshot = {
                "game_manifest": file_identity(manifest),
                "wrapped_metadata": file_identity(metadata),
                "client": {
                    "name": game.get("name"),
                    "version": game.get("version"),
                    "basis": game.get("basis"),
                    "file_count": len(game.get("files", [])),
                },
            }
            write_json(self.run_dir / "client_snapshot.json", snapshot)
            return snapshot["client"]

        self.state.run_stage(
            "01_inspect_client",
            {
                "manifest": file_identity(manifest) if manifest.exists() else str(manifest),
                "metadata": file_identity(metadata) if metadata.exists() else str(metadata),
            },
            action,
            lambda: (self.run_dir / "client_snapshot.json").exists(),
            resume=self.resume,
        )

    def _resource_plan(self) -> dict[str, Any]:
        return read_json(self.manifests_dir / "resource_plan.json")

    def _selected_downloads(
        self, chains: dict[str, ResourceChain]
    ) -> dict[str, dict[str, Any]]:
        records = {
            row["name"]: row for row in read_json(self.manifests_dir / "downloads.json")
        }
        selected: dict[str, dict[str, Any]] = {}
        for chain in chains.values():
            for name in chain.names:
                row = records.get(name)
                if row is None:
                    raise PipelineError(f"downloads.json has no selected resource: {name}")
                path = locate_download(row, self.manifests_dir)
                selected[name] = validate_download(path, row)
        return selected

    def acquire_resources(self) -> None:
        signature: dict[str, Any] = {
            "mode": "offline" if self.offline else "online",
            "targets": self.settings.resource_targets,
            "fetcher": tool_hashes(self.settings, ["stella_tw_resource_fetch.py"]),
        }
        if self.offline:
            for name in ("special_resources.json", "downloads.json", "source.json"):
                path = self.settings.offline_manifest_dir / name
                signature[name] = file_identity(path) if path.exists() else str(path)

        def action() -> dict[str, Any]:
            reset_path(self.manifests_dir, self.run_dir)
            if self.offline:
                source = self.settings.offline_manifest_dir
                for name in ("special_resources.json", "downloads.json", "source.json"):
                    if not (source / name).exists():
                        raise PipelineError(f"offline cache is incomplete: {source / name}")
                copy_core_manifest(source, self.manifests_dir)
            else:
                fetcher = self.settings.tools_root / "stella_tw_resource_fetch.py"
                run_command(
                    python_command(fetcher, self.manifests_dir),
                    log_path=self.log("02_acquire_resources"),
                    cwd=self.settings.workspace,
                    env=self.env,
                )
                chains = select_chains(self.manifests_dir, list(self.settings.resource_targets))
                names = [name for chain in chains.values() for name in chain.names]
                run_command(
                    python_command(fetcher, self.manifests_dir, "--download", *names),
                    log_path=self.log("02_acquire_resources"),
                    cwd=self.settings.workspace,
                    env=self.env,
                )

            chains = select_chains(self.manifests_dir, list(self.settings.resource_targets))
            selected = self._selected_downloads(chains)
            versions = {target: chain.version for target, chain in chains.items()}
            plan = {
                "mode": "offline-cache" if self.offline else "online",
                "source_manifest_dir": str(
                    (self.settings.offline_manifest_dir if self.offline else self.manifests_dir).resolve()
                ),
                "chains": {target: chain.to_json() for target, chain in chains.items()},
                "selected_downloads": selected,
                "target_versions": versions,
                "resource_version": max(versions.values()),
                "versions_in_sync": len(set(versions.values())) == 1,
            }
            write_json(self.manifests_dir / "resource_plan.json", plan)
            return {
                "resource_version": plan["resource_version"],
                "selected_resource_count": len(selected),
                "chains": {target: chain.names for target, chain in chains.items()},
            }

        def valid() -> bool:
            try:
                plan = self._resource_plan()
                return bool(plan["selected_downloads"]) and all(
                    Path(row["path"]).exists() for row in plan["selected_downloads"].values()
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "02_acquire_resources", signature, action, valid, resume=self.resume
        )

    def reconstruct_archives(self) -> None:
        plan = self._resource_plan()

        def action() -> dict[str, Any]:
            reset_path(self.archives_dir, self.run_dir)
            self.archives_dir.mkdir(parents=True, exist_ok=True)
            outputs: dict[str, Any] = {}
            selected = plan["selected_downloads"]
            for target, chain in plan["chains"].items():
                base = Path(selected[chain["base"]["name"]]["path"])
                patches = [Path(selected[row["name"]]["path"]) for row in chain["patches"]]
                output = self.archives_dir / target
                if patches:
                    run_command(
                        python_command(
                            self.settings.tools_root / "bspatch_chain.py",
                            base,
                            *patches,
                            output,
                        ),
                        log_path=self.log("03_reconstruct_archives"),
                        cwd=self.settings.workspace,
                        env=self.env,
                    )
                else:
                    shutil.copy2(base, output)
                    write_unpatched_provenance(base, output)
                outputs[target] = file_identity(output)
                outputs[target]["provenance"] = str(
                    output.with_suffix(output.suffix + ".provenance.json").resolve()
                )
            write_json(self.archives_dir / "reconstruction.json", outputs)
            return outputs

        def valid() -> bool:
            try:
                outputs = read_json(self.archives_dir / "reconstruction.json")
                for target in self.settings.resource_targets:
                    path = self.archives_dir / target
                    provenance = path.with_suffix(path.suffix + ".provenance.json")
                    if not path.exists() or not provenance.exists():
                        return False
                    if sha256_file(path) != outputs[target]["sha256"]:
                        return False
                return True
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "03_reconstruct_archives",
            {
                "selected_downloads": plan["selected_downloads"],
                "chains": plan["chains"],
                "tools": tool_hashes(self.settings, ["bspatch_chain.py"]),
            },
            action,
            valid,
            resume=self.resume,
        )

    def probe_formats(self) -> None:
        data_archive = self.archives_dir / "data.arcx"
        metadata = self.settings.wrapped_metadata

        def action() -> dict[str, Any]:
            archive_probe = probe_archive(data_archive)
            metadata_probe = probe_metadata_wrapper(metadata)
            adapter = select_adapter(archive_probe, metadata_probe)
            report = {
                "archive": archive_probe,
                "metadata": metadata_probe,
                "selected_adapter": adapter.id if adapter else None,
                "supported": adapter is not None,
            }
            write_json(self.run_dir / "format_probe.json", report)
            if adapter is None:
                diagnostic = {
                    **report,
                    "reason": "no registered adapter matches the observed signatures",
                    "next_steps": [
                        "保留本次 manifests、archives 和 format_probe.json",
                        "在 automation/stella_pipeline/adapters 中新增格式适配器",
                        "对更新后的 GameAssembly.dll/GameFramework.dll 重新定位解密和容器结构",
                        "使用 --resume 从此阶段继续",
                    ],
                }
                write_json(self.diagnostics_dir / "unsupported_format.json", diagnostic)
                raise PipelineError(
                    "unsupported client/resource structure; diagnostic written to "
                    f"{self.diagnostics_dir / 'unsupported_format.json'}"
                )
            return {"adapter": adapter.id, "archive": archive_probe, "metadata": metadata_probe}

        def valid() -> bool:
            try:
                return bool(read_json(self.run_dir / "format_probe.json")["supported"])
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "04_probe_formats",
            {
                "archive": file_identity(data_archive),
                "metadata": file_identity(metadata),
                "adapter_registry": __version__,
            },
            action,
            valid,
            resume=self.resume,
        )

    def current_adapter(self):
        report = read_json(self.run_dir / "format_probe.json")
        adapter = select_adapter(report["archive"], report["metadata"])
        if adapter is None:
            raise PipelineError("format adapter disappeared after successful probe")
        return adapter

    def extract_metadata(self) -> None:
        adapter = self.current_adapter()
        decrypted = self.metadata_dir / "global-metadata.decrypted.dat"
        constants = self.metadata_dir / "field_constants.csv"
        strings = self.metadata_dir / "string_literals.csv"

        def action() -> dict[str, Any]:
            reset_path(self.metadata_dir, self.run_dir)
            self.metadata_dir.mkdir(parents=True, exist_ok=True)
            commands = (
                python_command(
                    self.settings.tools_root / "decrypt_metadata.py",
                    self.settings.wrapped_metadata,
                    decrypted,
                ),
                python_command(
                    self.settings.tools_root / "metadata_constants.py", decrypted, constants
                ),
                python_command(
                    self.settings.tools_root / "metadata_strings.py", decrypted, strings
                ),
            )
            try:
                for command in commands:
                    run_command(
                        command,
                        log_path=self.log("05_extract_metadata"),
                        cwd=self.settings.workspace,
                        env=self.env,
                    )
            except PipelineError as error:
                write_json(
                    self.diagnostics_dir / "metadata_extract_failed.json",
                    {
                        "adapter": adapter.id,
                        "wrapper_probe": probe_metadata_wrapper(
                            self.settings.wrapped_metadata
                        ),
                        "error": str(error),
                        "log": str(self.log("05_extract_metadata").resolve()),
                        "likely_causes": [
                            "元数据 VM opcode 或处理常量随版本变化",
                            "IL2CPP 元数据版本或字段布局变化",
                        ],
                    },
                )
                raise
            probe = adapter.validate_decrypted_metadata(decrypted)
            if not probe["valid"]:
                write_json(self.diagnostics_dir / "metadata_format_changed.json", probe)
                raise PipelineError("decrypted metadata no longer matches IL2CPP v31")
            with strings.open("r", encoding="utf-8-sig", newline="") as stream:
                string_count = sum(1 for _row in csv.DictReader(stream))
            with constants.open("r", encoding="utf-8-sig", newline="") as stream:
                constant_count = sum(1 for _row in csv.DictReader(stream))
            summary = {
                "adapter": adapter.id,
                "decrypted": file_identity(decrypted),
                "decrypted_probe": probe,
                "string_literal_count": string_count,
                "field_constant_count": constant_count,
                "strings": file_identity(strings),
                "constants": file_identity(constants),
            }
            write_json(self.metadata_dir / "summary.json", summary)
            return {
                "adapter": adapter.id,
                "string_literal_count": string_count,
                "field_constant_count": constant_count,
            }

        def valid() -> bool:
            try:
                summary = read_json(self.metadata_dir / "summary.json")
                return (
                    summary["decrypted_probe"]["valid"]
                    and strings.exists()
                    and constants.exists()
                    and strings.stat().st_size > 0
                    and constants.stat().st_size > 0
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "05_extract_metadata",
            {
                "metadata": file_identity(self.settings.wrapped_metadata),
                "adapter": adapter.id,
                "tools": tool_hashes(
                    self.settings,
                    ["decrypt_metadata.py", "metadata_constants.py", "metadata_strings.py"],
                ),
            },
            action,
            valid,
            resume=self.resume,
        )

    def extract_archive(self) -> None:
        adapter = self.current_adapter()
        archive = self.archives_dir / "data.arcx"
        manifest_path = self.extract_dir / "archive_manifest.json"

        def action() -> dict[str, Any]:
            reset_path(self.extract_dir, self.run_dir)
            try:
                run_command(
                    python_command(
                        self.settings.tools_root / "archive_extract.py",
                        archive,
                        self.extract_dir,
                        "--ac-table-decrypt",
                    ),
                    log_path=self.log("06_extract_archive"),
                    cwd=self.settings.workspace,
                    env=self.env,
                )
            except PipelineError as error:
                write_json(
                    self.diagnostics_dir / "archive_extract_failed.json",
                    {
                        "adapter": adapter.id,
                        "archive_probe": probe_archive(archive),
                        "error": str(error),
                        "log": str(self.log("06_extract_archive").resolve()),
                        "likely_causes": [
                            "BAR header/table encryption or compression changed",
                            "AC.DA/XXTEA key derivation or table container changed",
                        ],
                    },
                )
                raise
            manifest = read_json(manifest_path)
            validation = adapter.validate_extracted_tables(manifest)
            write_json(self.extract_dir / "format_validation.json", validation)
            if not validation["valid"]:
                write_json(self.diagnostics_dir / "table_container_changed.json", validation)
                raise PipelineError("no supported table containers were found after extraction")
            return validation

        def valid() -> bool:
            try:
                validation = read_json(self.extract_dir / "format_validation.json")
                manifest = read_json(manifest_path)
                return (
                    validation["valid"]
                    and manifest["source"]["sha256"] == sha256_file(archive)
                    and len(manifest["entries"]) == validation["entry_count"]
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "06_extract_archive",
            {
                "archive": file_identity(archive),
                "adapter": adapter.id,
                "tools": tool_hashes(self.settings, ["archive_extract.py"]),
            },
            action,
            valid,
            resume=self.resume,
        )

    def map_archive_names(self) -> None:
        manifest = self.extract_dir / "archive_manifest.json"
        strings = self.metadata_dir / "string_literals.csv"
        reference_bin = self.settings.reference_root / "TW" / "bin"

        def action() -> dict[str, Any]:
            reset_path(self.name_map_path, self.run_dir)
            run_command(
                python_command(
                    self.settings.tools_root / "map_local_archive.py",
                    manifest,
                    strings,
                    self.name_map_path,
                    "--candidate-bin-dir",
                    reference_bin,
                ),
                log_path=self.log("07_map_archive_names"),
                cwd=self.settings.workspace,
                env=self.env,
            )
            name_map = read_json(self.name_map_path)
            stats = name_map["statistics"]
            expected = int(name_map["source"]["archive_entry_count"])
            complete = (
                stats.get("mapped") == expected
                and stats.get("unmapped", 0) == 0
                and stats.get("ambiguous", 0) == 0
            )
            if not complete:
                diagnostic = {
                    "reason": "archive filename mapping is incomplete",
                    "expected_entries": expected,
                    "statistics": stats,
                    "unmapped_entries": name_map.get("unmapped_entries", []),
                    "next_steps": [
                        "检查新版 IL2CPP string_literals.csv 是否仍含资源路径",
                        "更新参考仓库只用于补充候选文件名，再由本地 XXH64 验证",
                        "必要时扩展 map_local_archive.py 的本地结构推断规则",
                    ],
                }
                write_json(self.diagnostics_dir / "incomplete_name_map.json", diagnostic)
                raise PipelineError(
                    f"archive name mapping incomplete: {stats.get('mapped')}/{expected}"
                )
            return stats

        def valid() -> bool:
            try:
                value = read_json(self.name_map_path)
                stats = value["statistics"]
                return (
                    stats["mapped"] == value["source"]["archive_entry_count"]
                    and stats.get("unmapped", 0) == 0
                    and stats.get("ambiguous", 0) == 0
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "07_map_archive_names",
            {
                "manifest": file_identity(manifest),
                "strings": file_identity(strings),
                "reference_filenames": sorted(path.name for path in reference_bin.glob("*.json")),
                "tools": tool_hashes(self.settings, ["map_local_archive.py"]),
            },
            action,
            valid,
            resume=self.resume,
        )

    def decode_tables(self) -> None:
        reference_bin = self.settings.reference_root / "TW" / "bin"
        constants = self.metadata_dir / "field_constants.csv"

        def action() -> dict[str, Any]:
            reset_path(self.dataset_dir, self.run_dir)
            run_command(
                python_command(
                    self.settings.tools_root / "decode_local_tables.py",
                    self.name_map_path,
                    reference_bin,
                    self.dataset_dir,
                    "--field-constants",
                    constants,
                ),
                log_path=self.log("08_decode_tables"),
                cwd=self.settings.workspace,
                env=self.env,
            )
            catalog = read_json(self.dataset_dir / "table_catalog.json")
            stats = catalog["statistics"]
            complete = (
                stats["decoded_tables"] == stats["requested_tables"]
                and stats["failed_tables"] == 0
                and stats["tables_with_unknown_fields"] == 0
                and stats["tables_with_complete_field_alignment"] == stats["decoded_tables"]
            )
            if not complete:
                write_json(
                    self.diagnostics_dir / "incomplete_table_decode.json",
                    {
                        "reason": "table schema/wire decoding is incomplete",
                        "statistics": stats,
                        "next_steps": [
                            "检查新增或改名表是否已加入参考 schema 候选",
                            "检查 AOT FieldNumber 常量导出是否覆盖新程序集",
                            "若容器或 protobuf 规则变化，新增版本适配器并更新解码器",
                        ],
                    },
                )
                raise PipelineError(f"table decode is incomplete: {stats}")
            return stats

        def valid() -> bool:
            try:
                stats = read_json(self.dataset_dir / "table_catalog.json")["statistics"]
                return (
                    stats["decoded_tables"] == stats["requested_tables"]
                    and stats["failed_tables"] == 0
                    and stats["tables_with_unknown_fields"] == 0
                    and stats["tables_with_complete_field_alignment"]
                    == stats["decoded_tables"]
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "08_decode_tables",
            {
                "name_map": file_identity(self.name_map_path),
                "constants": file_identity(constants),
                "reference_schema": sorted(
                    (path.name, path.stat().st_size) for path in reference_bin.glob("*.json")
                ),
                "tools": tool_hashes(self.settings, ["decode_local_tables.py"]),
            },
            action,
            valid,
            resume=self.resume,
        )

    def _clear_builder_outputs(self) -> None:
        for relative in (
            "readable",
            "categories",
            "catalog",
            "localization",
            "source.json",
            "index.json",
            "validation.json",
            "README.md",
            "evidence/archive_entries.json",
            "validation/reference_crosscheck.json",
        ):
            reset_path(self.dataset_dir / relative, self.run_dir)

    def build_dataset(self) -> None:
        data_archive = self.archives_dir / "data.arcx"
        hotfix_archive = self.archives_dir / "hotfix.arch"

        def action() -> dict[str, Any]:
            self._clear_builder_outputs()
            run_command(
                python_command(
                    self.settings.tools_root / "build_local_datamine.py",
                    self.name_map_path,
                    self.dataset_dir,
                    self.settings.game_manifest,
                    data_archive.with_suffix(data_archive.suffix + ".provenance.json"),
                    hotfix_archive.with_suffix(hotfix_archive.suffix + ".provenance.json"),
                    self.manifests_dir / "source.json",
                    self.manifests_dir / "downloads.json",
                    self.settings.reference_root,
                ),
                log_path=self.log("09_build_dataset"),
                cwd=self.settings.workspace,
                env=self.env,
            )
            validation = read_json(self.dataset_dir / "validation.json")
            if not validation["all_checks_passed"]:
                write_json(self.diagnostics_dir / "dataset_validation_failed.json", validation)
                raise PipelineError("final dataset validation failed")
            return read_json(self.dataset_dir / "index.json")["statistics"]

        def valid() -> bool:
            try:
                return (
                    (self.dataset_dir / "index.json").exists()
                    and read_json(self.dataset_dir / "validation.json")["all_checks_passed"]
                )
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "09_build_dataset",
            {
                "table_catalog": file_identity(self.dataset_dir / "table_catalog.json"),
                "name_map": file_identity(self.name_map_path),
                "game_manifest": file_identity(self.settings.game_manifest),
                "downloads": file_identity(self.manifests_dir / "downloads.json"),
                "tools": tool_hashes(self.settings, ["build_local_datamine.py"]),
            },
            action,
            valid,
            resume=self.resume,
        )

    def find_previous_dataset(self) -> Path | None:
        if self.previous_dataset is not None:
            return self.previous_dataset
        current_source = read_json(self.dataset_dir / "source.json")
        current_version = current_source.get("resource_snapshot", {}).get("resource_version")
        candidates: list[tuple[int, str, Path]] = []
        if self.settings.runs_root.exists():
            for state_path in self.settings.runs_root.glob("*/state.json"):
                if state_path.resolve() == self.state_path.resolve():
                    continue
                try:
                    state = read_json(state_path)
                    dataset = state_path.parent / "dataset"
                    source = read_json(dataset / "source.json")
                    if state.get("status") != "complete" or not (dataset / "index.json").exists():
                        continue
                    version = source.get("resource_snapshot", {}).get("resource_version")
                    if current_version is not None and version is not None and int(version) >= int(current_version):
                        continue
                    candidates.append((int(version or -1), state.get("completed_at", ""), dataset))
                except (OSError, ValueError, KeyError, json.JSONDecodeError):
                    continue
        if candidates:
            return max(candidates, key=lambda row: (row[0], row[1]))[2]
        if (self.settings.baseline_dataset / "index.json").exists():
            return self.settings.baseline_dataset
        return None

    def create_diff(self) -> None:
        previous = self.find_previous_dataset()
        signature: dict[str, Any] = {
            "new": file_identity(self.dataset_dir / "index.json"),
            "diff_schema": "1.0.0",
        }
        if previous is not None and (previous / "index.json").exists():
            signature["old"] = file_identity(previous / "index.json")
        else:
            signature["old"] = None

        def action() -> dict[str, Any]:
            reset_path(self.diff_dir, self.run_dir)
            self.diff_dir.mkdir(parents=True, exist_ok=True)
            if previous is None:
                report = {
                    "available": False,
                    "reason": "no previous validated dataset was found",
                }
                write_json(self.diff_dir / "diff.json", report)
                return report
            report = diff_datasets(previous, self.dataset_dir, self.diff_dir)
            return {"available": True, "old": str(previous), **report["summary"]}

        def valid() -> bool:
            try:
                read_json(self.diff_dir / "diff.json")
                return True
            except (OSError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "10_diff_versions", signature, action, valid, resume=self.resume
        )

    def package_dataset(self) -> None:
        source = read_json(self.dataset_dir / "source.json")
        client_version = str(source.get("installed_client", {}).get("version") or "unknown")
        resource_version = str(
            source.get("resource_snapshot", {}).get("resource_version") or "unknown"
        )
        safe_client = "".join(char if char.isalnum() or char in ".-_" else "_" for char in client_version)
        archive_name = f"StellaSora_TW_client-{safe_client}_resource-v{resource_version}.zip"
        archive_path = self.packages_dir / archive_name

        def action() -> dict[str, Any]:
            reset_path(self.packages_dir, self.run_dir)
            self.packages_dir.mkdir(parents=True, exist_ok=True)
            prefix = f"StellaSora_TW_resource-v{resource_version}"
            with zipfile.ZipFile(
                archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
            ) as bundle:
                for path in sorted(
                    (item for item in self.dataset_dir.rglob("*") if item.is_file()),
                    key=lambda item: item.relative_to(self.dataset_dir).as_posix().casefold(),
                ):
                    relative = path.relative_to(self.dataset_dir).as_posix()
                    info = zipfile.ZipInfo(f"{prefix}/{relative}")
                    info.date_time = (1980, 1, 1, 0, 0, 0)
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    bundle.writestr(info, path.read_bytes(), compresslevel=9)
            manifest = {
                "schema_version": "1.0.0",
                "client_version": client_version,
                "resource_version": source.get("resource_snapshot", {}).get("resource_version"),
                "dataset": str(self.dataset_dir.resolve()),
                "archive": file_identity(archive_path),
                "file_count": sum(1 for path in self.dataset_dir.rglob("*") if path.is_file()),
                "deterministic_zip_timestamps": True,
            }
            write_json(self.packages_dir / "package_manifest.json", manifest)
            return manifest

        def valid() -> bool:
            try:
                manifest = read_json(self.packages_dir / "package_manifest.json")
                path = Path(manifest["archive"]["path"])
                return path.exists() and sha256_file(path) == manifest["archive"]["sha256"]
            except (OSError, KeyError, json.JSONDecodeError):
                return False

        self.state.run_stage(
            "11_package",
            {
                "dataset_index": file_identity(self.dataset_dir / "index.json"),
                "dataset_validation": file_identity(self.dataset_dir / "validation.json"),
                "archive_name": archive_name,
                "packager": "deterministic-zip-v1",
            },
            action,
            valid,
            resume=self.resume,
        )

    def finalize(self) -> dict[str, Any]:
        package = read_json(self.packages_dir / "package_manifest.json")
        dataset = read_json(self.dataset_dir / "index.json")
        source = read_json(self.dataset_dir / "source.json")
        diff = read_json(self.diff_dir / "diff.json")
        result = {
            "run_dir": str(self.run_dir),
            "dataset": str(self.dataset_dir),
            "client_version": source.get("installed_client", {}).get("version"),
            "resource_version": source.get("resource_snapshot", {}).get("resource_version"),
            "adapter": read_json(self.run_dir / "format_probe.json")["selected_adapter"],
            "statistics": dataset["statistics"],
            "diff": str((self.diff_dir / "diff.json").resolve()),
            "diff_summary": diff.get("summary") if isinstance(diff, dict) else None,
            "package": package["archive"],
            "validation": str((self.dataset_dir / "validation.json").resolve()),
        }
        write_json(self.run_dir / "release.json", result)
        self.state.finish(result)
        self.settings.runs_root.mkdir(parents=True, exist_ok=True)
        write_json(
            self.settings.runs_root / "latest.json",
            {
                "run_dir": str(self.run_dir),
                "state": str(self.state_path),
                "release": str((self.run_dir / "release.json").resolve()),
                "updated_at": now_iso(),
            },
        )
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
        return result


def doctor(settings: Settings) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []

    def add(name: str, passed: bool, detail: Any, required: bool = True) -> None:
        checks.append(
            {"name": name, "passed": bool(passed), "required": required, "detail": detail}
        )

    add(
        "python_3_12_or_newer",
        sys.version_info >= (3, 12),
        {"version": sys.version.split()[0], "executable": sys.executable},
    )
    add("game_root", settings.game_root.is_dir(), str(settings.game_root))
    add("game_manifest", settings.game_manifest.is_file(), str(settings.game_manifest))
    add("wrapped_metadata", settings.wrapped_metadata.is_file(), str(settings.wrapped_metadata))
    add(
        "reference_schema",
        (settings.reference_root / "TW" / "bin").is_dir(),
        str(settings.reference_root / "TW" / "bin"),
    )
    required_tools = (
        "stella_tw_resource_fetch.py",
        "bspatch_chain.py",
        "archive_extract.py",
        "decrypt_metadata.py",
        "metadata_constants.py",
        "metadata_strings.py",
        "map_local_archive.py",
        "decode_local_tables.py",
        "build_local_datamine.py",
        "lua53_catalog.py",
        "recover_netmsg_ids.py",
        "recover_protobuf.py",
    )
    missing_tools = [name for name in required_tools if not (settings.tools_root / name).is_file()]
    add("analysis_tools", not missing_tools, {"missing": missing_tools})
    add(
        "xxhash_dependency",
        (settings.analysis_root / "pydeps" / "xxhash").is_dir(),
        str(settings.analysis_root / "pydeps" / "xxhash"),
    )
    add(
        "protobuf_dependency",
        importlib.util.find_spec("google.protobuf") is not None,
        "google.protobuf",
    )
    offline_files = [
        settings.offline_manifest_dir / name
        for name in ("source.json", "special_resources.json", "downloads.json")
    ]
    add(
        "offline_cache",
        all(path.is_file() for path in offline_files),
        [str(path) for path in offline_files],
        required=False,
    )
    report = {
        "schema_version": "1.0.0",
        "checks": checks,
        "all_required_checks_passed": all(
            row["passed"] for row in checks if row["required"]
        ),
    }
    return report
