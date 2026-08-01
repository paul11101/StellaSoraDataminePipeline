from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from .util import PipelineError, read_json, sha256_file, write_json, write_text


SCOPES = ("bin", "language/zh_TW", "language/zh_CN")


def source_summary(root: Path) -> dict[str, Any]:
    source_path = root / "source.json"
    source = read_json(source_path) if source_path.exists() else {}
    client = source.get("installed_client", {})
    snapshot = source.get("resource_snapshot", {})
    return {
        "path": str(root.resolve()),
        "client_version": client.get("version"),
        "resource_version": snapshot.get("resource_version"),
        "generated_at": source.get("generated_at"),
    }


def changed_fields(left: Any, right: Any) -> list[str]:
    if not isinstance(left, dict) or not isinstance(right, dict):
        return ["$"]
    keys = set(left) | set(right)
    return sorted(
        str(key)
        for key in keys
        if key not in left or key not in right or left[key] != right[key]
    )


def compare_payload(left: Any, right: Any, max_examples: int) -> dict[str, Any]:
    if isinstance(left, dict) and isinstance(right, dict):
        left_keys = set(left)
        right_keys = set(right)
        added = sorted(right_keys - left_keys, key=str)
        removed = sorted(left_keys - right_keys, key=str)
        changed = sorted(
            (key for key in left_keys & right_keys if left[key] != right[key]),
            key=str,
        )
        field_counts: Counter[str] = Counter()
        examples = []
        for key in changed:
            fields = changed_fields(left[key], right[key])
            field_counts.update(fields)
            if len(examples) < max_examples:
                examples.append({"key": str(key), "changed_fields": fields})
        return {
            "kind": "object",
            "old_rows": len(left),
            "new_rows": len(right),
            "added_rows": len(added),
            "removed_rows": len(removed),
            "changed_rows": len(changed),
            "unchanged_rows": len(left_keys & right_keys) - len(changed),
            "added_key_examples": [str(key) for key in added[:max_examples]],
            "removed_key_examples": [str(key) for key in removed[:max_examples]],
            "changed_row_examples": examples,
            "changed_field_occurrences": dict(sorted(field_counts.items())),
        }
    if isinstance(left, list) and isinstance(right, list):
        shared = min(len(left), len(right))
        changed_indexes = [index for index in range(shared) if left[index] != right[index]]
        return {
            "kind": "array",
            "old_rows": len(left),
            "new_rows": len(right),
            "added_rows": max(0, len(right) - len(left)),
            "removed_rows": max(0, len(left) - len(right)),
            "changed_rows": len(changed_indexes),
            "unchanged_rows": shared - len(changed_indexes),
            "changed_index_examples": changed_indexes[:max_examples],
            "changed_field_occurrences": {},
        }
    return {
        "kind": "scalar",
        "old_rows": 1,
        "new_rows": 1,
        "added_rows": 0,
        "removed_rows": 0,
        "changed_rows": int(left != right),
        "unchanged_rows": int(left == right),
        "changed_field_occurrences": {},
    }


def category_map(dataset: Path) -> dict[str, list[str]]:
    path = dataset / "catalog" / "tables.json"
    if not path.exists():
        return {}
    catalog = read_json(path)
    return {
        row["table"]: list(row.get("categories", []))
        for row in catalog.get("tables", [])
    }


def compare_scope(
    old_root: Path,
    new_root: Path,
    scope: str,
    output: Path,
    *,
    max_examples: int,
    table_categories: dict[str, list[str]],
) -> dict[str, Any]:
    old_dir = old_root / scope
    new_dir = new_root / scope
    old_files = {path.stem: path for path in old_dir.glob("*.json")} if old_dir.exists() else {}
    new_files = {path.stem: path for path in new_dir.glob("*.json")} if new_dir.exists() else {}
    names = sorted(set(old_files) | set(new_files), key=str.casefold)
    tables: list[dict[str, Any]] = []
    changed_category_counts: Counter[str] = Counter()

    for name in names:
        old_path = old_files.get(name)
        new_path = new_files.get(name)
        if old_path is None:
            payload = read_json(new_path)
            count = len(payload) if isinstance(payload, (dict, list)) else 1
            row = {
                "table": name,
                "status": "added",
                "old_rows": 0,
                "new_rows": count,
                "added_rows": count,
                "removed_rows": 0,
                "changed_rows": 0,
                "categories": table_categories.get(name, []),
            }
        elif new_path is None:
            payload = read_json(old_path)
            count = len(payload) if isinstance(payload, (dict, list)) else 1
            row = {
                "table": name,
                "status": "removed",
                "old_rows": count,
                "new_rows": 0,
                "added_rows": 0,
                "removed_rows": count,
                "changed_rows": 0,
                "categories": table_categories.get(name, []),
            }
        elif sha256_file(old_path) == sha256_file(new_path):
            continue
        else:
            details = compare_payload(read_json(old_path), read_json(new_path), max_examples)
            row = {
                "table": name,
                "status": "changed",
                "categories": table_categories.get(name, []),
                **details,
            }

        for category in row.get("categories", []):
            changed_category_counts[category] += 1
        row["change_count"] = (
            row.get("added_rows", 0)
            + row.get("removed_rows", 0)
            + row.get("changed_rows", 0)
        )
        tables.append(row)

        detail_path = output / "tables" / scope.replace("/", "_") / f"{name}.json"
        write_json(detail_path, row)
        row["detail"] = str(detail_path.relative_to(output)).replace("\\", "/")

    summary = {
        "scope": scope,
        "old_table_count": len(old_files),
        "new_table_count": len(new_files),
        "added_tables": sum(row["status"] == "added" for row in tables),
        "removed_tables": sum(row["status"] == "removed" for row in tables),
        "changed_tables": sum(row["status"] == "changed" for row in tables),
        "added_rows": sum(row.get("added_rows", 0) for row in tables),
        "removed_rows": sum(row.get("removed_rows", 0) for row in tables),
        "changed_rows": sum(row.get("changed_rows", 0) for row in tables),
        "impacted_categories": dict(sorted(changed_category_counts.items())),
        "tables": tables,
    }
    return summary


def markdown_report(report: dict[str, Any]) -> str:
    old = report["old"]
    new = report["new"]
    lines = [
        "# Stella Sora 版本数据差分",
        "",
        f"- 旧版本：客户端 `{old.get('client_version')}`，资源 `{old.get('resource_version')}`",
        f"- 新版本：客户端 `{new.get('client_version')}`，资源 `{new.get('resource_version')}`",
        "",
        "| 范围 | 新增表 | 删除表 | 改动表 | 新增行 | 删除行 | 改动行 |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for scope in report["scopes"]:
        lines.append(
            f"| `{scope['scope']}` | {scope['added_tables']} | {scope['removed_tables']} | "
            f"{scope['changed_tables']} | {scope['added_rows']} | {scope['removed_rows']} | "
            f"{scope['changed_rows']} |"
        )
    binary = next((row for row in report["scopes"] if row["scope"] == "bin"), None)
    if binary and binary["tables"]:
        lines.extend(["", "## 主要改动表", ""])
        for row in sorted(binary["tables"], key=lambda item: item["change_count"], reverse=True)[:30]:
            lines.append(
                f"- `{row['table']}`：{row['status']}，新增 {row.get('added_rows', 0)}，"
                f"删除 {row.get('removed_rows', 0)}，修改 {row.get('changed_rows', 0)}"
            )
    if not any(scope["tables"] for scope in report["scopes"]):
        lines.extend(["", "两个数据集在所比较范围内完全一致。"])
    lines.append("")
    return "\n".join(lines)


def diff_datasets(
    old_root: Path,
    new_root: Path,
    output: Path,
    *,
    max_examples: int = 20,
) -> dict[str, Any]:
    old_root = old_root.resolve()
    new_root = new_root.resolve()
    if not (old_root / "index.json").exists():
        raise PipelineError(f"old dataset is invalid: {old_root}")
    if not (new_root / "index.json").exists():
        raise PipelineError(f"new dataset is invalid: {new_root}")
    output.mkdir(parents=True, exist_ok=True)
    categories = category_map(new_root)
    scopes = [
        compare_scope(
            old_root,
            new_root,
            scope,
            output,
            max_examples=max_examples,
            table_categories=categories,
        )
        for scope in SCOPES
    ]
    report = {
        "schema_version": "1.0.0",
        "old": source_summary(old_root),
        "new": source_summary(new_root),
        "scopes": scopes,
        "summary": {
            "added_tables": sum(row["added_tables"] for row in scopes),
            "removed_tables": sum(row["removed_tables"] for row in scopes),
            "changed_tables": sum(row["changed_tables"] for row in scopes),
            "added_rows": sum(row["added_rows"] for row in scopes),
            "removed_rows": sum(row["removed_rows"] for row in scopes),
            "changed_rows": sum(row["changed_rows"] for row in scopes),
        },
    }
    write_json(output / "diff.json", report)
    write_text(output / "README.md", markdown_report(report))
    return report
