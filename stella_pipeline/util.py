from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable


class PipelineError(RuntimeError):
    """An expected, user-actionable pipeline failure."""


def now_iso() -> str:
    return datetime.now().astimezone().isoformat()


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8-sig") as stream:
        return json.load(stream)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(value, encoding="utf-8", newline="\n")
    temporary.replace(path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def md5_file(path: Path) -> str:
    digest = hashlib.md5(usedforsecurity=False)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_signature(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def ensure_within(path: Path, root: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise PipelineError(f"refusing to modify path outside run directory: {resolved}")
    return resolved


def reset_path(path: Path, run_root: Path) -> None:
    resolved = ensure_within(path, run_root)
    if not resolved.exists():
        return
    if resolved.is_dir():
        shutil.rmtree(resolved)
    else:
        resolved.unlink()


def copy_core_manifest(source: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    names = (
        "source.json",
        "serverlist.json",
        "resource_manifest.json",
        "special_resources.json",
        "downloads.json",
    )
    for name in names:
        candidate = source / name
        if candidate.exists():
            shutil.copy2(candidate, destination / name)


def command_text(command: Iterable[object]) -> str:
    return subprocess.list2cmdline([str(item) for item in command])


def run_command(
    command: list[object],
    *,
    log_path: Path,
    cwd: Path,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    merged_env = os.environ.copy()
    merged_env.update(
        {
            "PYTHONUTF8": "1",
            "PYTHONIOENCODING": "utf-8",
        }
    )
    if env:
        merged_env.update(env)
    rendered = command_text(command)
    print(f"    $ {rendered}", flush=True)
    result = subprocess.run(
        [str(item) for item in command],
        cwd=cwd,
        env=merged_env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="backslashreplace",
        check=False,
    )
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(f"\n$ {rendered}\n")
        if result.stdout:
            stream.write(result.stdout)
            if not result.stdout.endswith("\n"):
                stream.write("\n")
        if result.stderr:
            stream.write("[stderr]\n")
            stream.write(result.stderr)
            if not result.stderr.endswith("\n"):
                stream.write("\n")
        stream.write(f"[exit_code] {result.returncode}\n")
    if result.returncode:
        tail = (result.stderr or result.stdout or "").strip()[-2000:]
        raise PipelineError(
            f"command failed with exit code {result.returncode}; "
            f"see {log_path.resolve()}\n{tail}"
        )
    return result


def python_environment(analysis_root: Path) -> dict[str, str]:
    dependency_root = analysis_root / "pydeps"
    existing = os.environ.get("PYTHONPATH")
    value = str(dependency_root.resolve())
    if existing:
        value = value + os.pathsep + existing
    return {"PYTHONPATH": value}


def python_command(script: Path, *arguments: object) -> list[object]:
    return [sys.executable, "-u", script, *arguments]


def file_identity(path: Path) -> dict[str, Any]:
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
    }
