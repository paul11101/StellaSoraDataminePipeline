from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .util import PipelineError, md5_file, read_json, sha256_file, write_json


@dataclass(frozen=True)
class ResourceChain:
    target: str
    base: dict[str, Any]
    patches: tuple[dict[str, Any], ...]

    @property
    def version(self) -> int:
        rows = (self.base, *self.patches)
        return max(int(row["version"]) for row in rows)

    @property
    def names(self) -> list[str]:
        return [self.base["name"], *[row["name"] for row in self.patches]]

    def to_json(self) -> dict[str, Any]:
        result = asdict(self)
        result["patches"] = list(self.patches)
        result["resolved_version"] = self.version
        result["ordered_names"] = self.names
        return result


def select_resource_chain(resources: list[dict[str, Any]], target: str) -> ResourceChain:
    bases = [row for row in resources if row.get("name") == target]
    if not bases:
        raise PipelineError(f"resource manifest has no full base for {target}")
    highest_base_version = max(int(row["version"]) for row in bases)
    newest_bases = [row for row in bases if int(row["version"]) == highest_base_version]
    if len(newest_bases) != 1:
        raise PipelineError(
            f"resource manifest has {len(newest_bases)} ambiguous {target} bases "
            f"at version {highest_base_version}"
        )
    base = newest_bases[0]
    pattern = re.compile(rf"^p_(\d+)_u\.{re.escape(target)}$", re.IGNORECASE)
    candidates: list[tuple[int, int, dict[str, Any]]] = []
    for row in resources:
        match = pattern.fullmatch(str(row.get("name", "")))
        if match and int(row["version"]) > highest_base_version:
            candidates.append((int(row["version"]), int(match.group(1)), row))

    by_version: dict[int, list[dict[str, Any]]] = {}
    for version, _index, row in candidates:
        by_version.setdefault(version, []).append(row)
    ambiguous = {version: rows for version, rows in by_version.items() if len(rows) != 1}
    if ambiguous:
        details = {version: [row["name"] for row in rows] for version, rows in ambiguous.items()}
        raise PipelineError(f"ambiguous patch chain for {target}: {details}")

    patches = tuple(row for _version, _index, row in sorted(candidates))
    return ResourceChain(target=target, base=base, patches=patches)


def select_chains(manifest_dir: Path, targets: list[str]) -> dict[str, ResourceChain]:
    resources = read_json(manifest_dir / "special_resources.json")
    return {target: select_resource_chain(resources, target) for target in targets}


def locate_download(row: dict[str, Any], manifest_dir: Path) -> Path:
    recorded = Path(str(row.get("output", ""))) if row.get("output") else None
    candidates = [manifest_dir / "downloads" / row["name"]]
    if recorded is not None:
        candidates.insert(0, recorded)
    for path in candidates:
        if path.exists():
            return path.resolve()
    raise PipelineError(f"downloaded resource is missing: {row['name']}")


def validate_download(path: Path, row: dict[str, Any]) -> dict[str, Any]:
    actual_md5 = md5_file(path)
    expected_md5 = str(row.get("md5", "")).lower()
    if expected_md5 and actual_md5 != expected_md5:
        raise PipelineError(
            f"MD5 mismatch for {row['name']}: expected {expected_md5}, got {actual_md5}"
        )
    return {
        "name": row["name"],
        "version": int(row["version"]),
        "path": str(path),
        "size": path.stat().st_size,
        "md5": actual_md5,
        "sha256": sha256_file(path),
    }


def write_unpatched_provenance(base: Path, output: Path) -> Path:
    provenance = {
        "format": "FULL_RESOURCE_NO_PATCH",
        "base": {
            "path": str(base.resolve()),
            "size": base.stat().st_size,
            "md5": md5_file(base),
            "sha256": sha256_file(base),
        },
        "steps": [],
        "output": {
            "path": str(output.resolve()),
            "size": output.stat().st_size,
            "md5": md5_file(output),
            "sha256": sha256_file(output),
        },
    }
    path = output.with_suffix(output.suffix + ".provenance.json")
    write_json(path, provenance)
    return path
