from __future__ import annotations

import json
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from google.protobuf import descriptor_pb2
from google.protobuf.message import DecodeError

from .pipeline import Settings, tool_hashes
from .util import (
    PipelineError,
    file_identity,
    python_command,
    python_environment,
    read_json,
    reset_path,
    run_command,
    write_json,
)


NETMSG_SOURCE_PATH = "GameCore/Network/NetMsgId.lua"
NETWORK_MARKERS = frozenset({"ike.proto", "player_login.proto"})
DATA_MARKER = "client_table.proto"
RUNTIME_MARKER = "roguelike_tempData.proto"


@dataclass(frozen=True)
class DescriptorCandidate:
    source: Path
    file_names: tuple[str, ...]
    message_count: int
    enum_count: int

    def to_json(self) -> dict[str, Any]:
        return {
            "source": file_identity(self.source),
            "file_count": len(self.file_names),
            "file_names": list(self.file_names),
            "message_count": self.message_count,
            "enum_count": self.enum_count,
        }


def _definition_counts(
    messages: list[descriptor_pb2.DescriptorProto],
) -> tuple[int, int]:
    message_count = 0
    enum_count = 0
    pending = list(messages)
    while pending:
        message = pending.pop()
        if not message.options.map_entry:
            message_count += 1
        enum_count += len(message.enum_type)
        pending.extend(message.nested_type)
    return message_count, enum_count


def read_descriptor_candidate(path: Path) -> DescriptorCandidate | None:
    descriptor_set = descriptor_pb2.FileDescriptorSet()
    try:
        descriptor_set.ParseFromString(path.read_bytes())
    except (DecodeError, OSError):
        return None
    if not descriptor_set.file:
        return None
    file_names = tuple(file_proto.name for file_proto in descriptor_set.file)
    if any(not name for name in file_names) or len(set(file_names)) != len(file_names):
        return None
    message_count = 0
    enum_count = 0
    for file_proto in descriptor_set.file:
        nested_messages, nested_enums = _definition_counts(list(file_proto.message_type))
        message_count += nested_messages
        enum_count += nested_enums + len(file_proto.enum_type)
    if message_count == 0 and enum_count == 0:
        return None
    return DescriptorCandidate(
        source=path.resolve(),
        file_names=file_names,
        message_count=message_count,
        enum_count=enum_count,
    )


def find_descriptor_candidates(hashes: Path) -> list[DescriptorCandidate]:
    candidates = []
    for path in sorted(hashes.glob("*.bin"), key=lambda item: item.name.casefold()):
        candidate = read_descriptor_candidate(path)
        if candidate is not None:
            candidates.append(candidate)
    return candidates


def classify_descriptor_candidates(
    candidates: list[DescriptorCandidate],
) -> tuple[dict[str, DescriptorCandidate], list[str]]:
    network = [
        row for row in candidates if NETWORK_MARKERS.issubset(set(row.file_names))
    ]
    data = [row for row in candidates if DATA_MARKER in row.file_names]
    runtime = [
        row
        for row in candidates
        if row not in network
        and row not in data
        and RUNTIME_MARKER in row.file_names
    ]
    runtime.sort(key=lambda row: row.source.name.casefold())

    errors = []
    if len(network) != 1:
        errors.append(f"expected 1 network descriptor set, found {len(network)}")
    if len(data) != 1:
        errors.append(f"expected 1 data descriptor set, found {len(data)}")
    if len(runtime) != 2:
        errors.append(f"expected 2 runtime descriptor sets, found {len(runtime)}")
    classified_sources = {row.source for row in network + data + runtime}
    unclassified = [row for row in candidates if row.source not in classified_sources]
    if unclassified:
        errors.append(f"found {len(unclassified)} unclassified descriptor set(s)")

    if errors:
        return {}, errors
    return {
        "network": network[0],
        "data": data[0],
        # These labels are deterministic archive-hash order, not chronology.
        "runtime_v1": runtime[0],
        "runtime_v2": runtime[1],
    }, []


def find_netmsg_chunk(catalog: dict[str, Any], hashes: Path) -> Path:
    rows = [
        row
        for row in catalog.get("files", [])
        if str(row.get("path", "")).replace("\\", "/") == NETMSG_SOURCE_PATH
    ]
    if len(rows) != 1:
        raise PipelineError(
            f"expected exactly one {NETMSG_SOURCE_PATH}, found {len(rows)}"
        )
    chunk = hashes / f"{rows[0]['name_hash_hex']}.bin"
    if not chunk.is_file():
        raise PipelineError(f"cataloged NetMsgId chunk is missing: {chunk}")
    return chunk.resolve()


def _safe_protocol_output(output: Path, analysis_root: Path) -> Path:
    resolved = output.resolve()
    root = analysis_root.resolve()
    if not resolved.is_relative_to(root) or resolved == root:
        raise PipelineError(
            f"protocol output must be a child of analysis_root ({root}): {resolved}"
        )
    if len(resolved.relative_to(root).parts) < 1:
        raise PipelineError(f"refusing broad protocol output path: {resolved}")
    return resolved


def _replace_staging_paths(root: Path, final: Path) -> None:
    old = json.dumps(str(root.resolve()), ensure_ascii=False)[1:-1]
    new = json.dumps(str(final.resolve()), ensure_ascii=False)[1:-1]
    for pattern in ("*.json", "*.jsonl"):
        for path in root.rglob(pattern):
            text = path.read_text(encoding="utf-8")
            replaced = text.replace(old, new)
            if replaced != text:
                path.write_text(replaced, encoding="utf-8", newline="\n")


def _promote(staging: Path, output: Path, analysis_root: Path, force: bool) -> None:
    _replace_staging_paths(staging, output)
    if output.exists():
        if not force:
            raise PipelineError(f"protocol output already exists: {output}; use --force")
        reset_path(output, analysis_root)
    staging.replace(output)


def recover_protocol(
    settings: Settings,
    *,
    archive: Path | None,
    hashes: Path | None,
    output: Path,
    force: bool,
    discover_only: bool,
) -> dict[str, Any]:
    if (archive is None) == (hashes is None):
        raise PipelineError("choose exactly one protocol input: --archive or --hashes")
    output = _safe_protocol_output(output, settings.analysis_root)
    if output.exists() and not force:
        raise PipelineError(f"protocol output already exists: {output}; use --force")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_name(f".{output.name}.staging-{uuid.uuid4().hex}")
    staging.mkdir(parents=True)
    env = python_environment(settings.analysis_root)
    source: dict[str, Any]
    try:
        if archive is not None:
            archive = archive.resolve()
            if not archive.is_file():
                raise PipelineError(f"lua archive does not exist: {archive}")
            extraction = staging / "input" / "lua_extract"
            run_command(
                python_command(
                    settings.tools_root / "archive_extract.py",
                    archive,
                    extraction,
                    "--ac-table-decrypt",
                ),
                log_path=staging / "logs" / "01_extract_lua.log",
                cwd=settings.workspace,
                env=env,
            )
            hashes_dir = extraction / "hashes"
            source = {
                "mode": "lua_archive",
                "archive": file_identity(archive),
                "extraction_manifest": file_identity(
                    extraction / "archive_manifest.json"
                ),
            }
        else:
            assert hashes is not None
            hashes_dir = hashes.resolve()
            if not hashes_dir.is_dir():
                raise PipelineError(f"decrypted hashes directory does not exist: {hashes_dir}")
            source = {"mode": "decrypted_hashes", "path": str(hashes_dir)}

        hash_files = list(hashes_dir.glob("*.bin"))
        if not hash_files:
            raise PipelineError(f"no <xxh64>.bin files found in {hashes_dir}")
        source["hash_file_count"] = len(hash_files)

        catalog_dir = staging / "lua_catalog"
        run_command(
            python_command(
                settings.tools_root / "lua53_catalog.py", hashes_dir, catalog_dir
            ),
            log_path=staging / "logs" / "02_catalog_lua.log",
            cwd=settings.workspace,
            env=env,
        )
        catalog = read_json(catalog_dir / "catalog.json")
        netmsg_chunk = find_netmsg_chunk(catalog, hashes_dir)
        candidates = find_descriptor_candidates(hashes_dir)
        classified, errors = classify_descriptor_candidates(candidates)
        discovery = {
            "schema_version": "1.0.0",
            "source": source,
            "netmsg": file_identity(netmsg_chunk),
            "descriptor_candidate_count": len(candidates),
            "descriptor_candidates": [row.to_json() for row in candidates],
            "classification": {
                label: row.to_json() for label, row in classified.items()
            },
            "runtime_label_note": (
                "runtime_v1/v2 use deterministic archive-hash order; labels do not "
                "assert chronological version order"
            ),
            "errors": errors,
        }
        write_json(staging / "descriptor_discovery.json", discovery)
        if errors:
            if output.exists():
                diagnostic = output.with_name(
                    f"{output.name}.descriptor_discovery.failed.json"
                )
                write_json(diagnostic, discovery)
                location = diagnostic
            else:
                _promote(staging, output, settings.analysis_root, force=False)
                location = output / "descriptor_discovery.json"
            raise PipelineError(
                "protocol descriptor structure changed: "
                + "; ".join(errors)
                + f"; see {location}"
            )

        if discover_only:
            result = {
                "status": "discovered",
                "output": str(output),
                "descriptor_sets": len(classified),
            }
            write_json(staging / "protocol_recovery.json", result)
            _promote(staging, output, settings.analysis_root, force)
            return result

        message_ids = staging / "message_ids.json"
        assignments = staging / "evidence" / "netmsg_assignments.json"
        run_command(
            python_command(
                settings.tools_root / "recover_netmsg_ids.py",
                netmsg_chunk,
                message_ids,
                "--assignments",
                assignments,
            ),
            log_path=staging / "logs" / "03_recover_message_ids.log",
            cwd=settings.workspace,
            env=env,
        )
        run_command(
            python_command(
                settings.tools_root / "recover_protobuf.py",
                "--network",
                classified["network"].source,
                "--data",
                classified["data"].source,
                "--runtime-v1",
                classified["runtime_v1"].source,
                "--runtime-v2",
                classified["runtime_v2"].source,
                "--message-ids",
                message_ids,
                "--output",
                staging,
            ),
            log_path=staging / "logs" / "04_recover_protobuf.log",
            cwd=settings.workspace,
            env=env,
        )

        validation = read_json(staging / "validation.json")
        inventory = read_json(staging / "descriptor_inventory.json")
        all_checks_passed = (
            validation["message_id_count"] == validation["resolved_binding_count"]
            and validation["missing_binding_count"] == 0
            and not validation["missing_imports"]
            and not validation["unresolved_type_references"]
            and not validation["duplicate_message_ids"]
            and not validation["duplicate_message_names"]
        )
        if not all_checks_passed:
            raise PipelineError(f"recovered protobuf validation failed: {validation}")
        result = {
            "schema_version": "1.0.0",
            "status": "complete",
            "output": str(output),
            "source": source,
            "netmsg": file_identity(netmsg_chunk),
            "descriptor_sets": {
                label: row.to_json() for label, row in classified.items()
            },
            "tools": tool_hashes(
                settings,
                [
                    "archive_extract.py",
                    "lua53_catalog.py",
                    "recover_netmsg_ids.py",
                    "recover_protobuf.py",
                ],
            ),
            "summary": inventory["message_catalog"],
            "validation": validation,
            "all_checks_passed": all_checks_passed,
        }
        write_json(staging / "protocol_recovery.json", result)
        _promote(staging, output, settings.analysis_root, force)
        return result
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise
