"""Adapter for the currently known BAR v102 / wrapped IL2CPP v31 layout."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


BAR_MAGIC = 0x5241421A
BAR_VERSION = 102
METADATA_WRAPPER_MAGIC = 0x1357FEDA
IL2CPP_MAGIC = 0xFAB11BAF
IL2CPP_VERSION = 31
TABLE_MAGIC = 0x00039354


@dataclass(frozen=True)
class Bar102Il2Cpp31Adapter:
    id: str = "bar102-il2cpp31-v1"
    description: str = "BAR v102 + AC.DA/XXTEA tables + wrapped IL2CPP metadata v31"

    def supports(self, archive_probe: dict, metadata_probe: dict) -> bool:
        return (
            archive_probe.get("magic") == BAR_MAGIC
            and archive_probe.get("version") == BAR_VERSION
            and metadata_probe.get("wrapper_magic") == METADATA_WRAPPER_MAGIC
            and metadata_probe.get("payload_length_matches") is True
        )

    def validate_decrypted_metadata(self, path: Path) -> dict:
        head = path.read_bytes()[:8]
        if len(head) < 8:
            return {"valid": False, "reason": "decrypted metadata is truncated"}
        magic = int.from_bytes(head[:4], "little")
        version = int.from_bytes(head[4:8], "little")
        return {
            "valid": magic == IL2CPP_MAGIC and version == IL2CPP_VERSION,
            "magic": magic,
            "magic_hex": f"0x{magic:08x}",
            "version": version,
            "expected_magic_hex": f"0x{IL2CPP_MAGIC:08x}",
            "expected_version": IL2CPP_VERSION,
        }

    def validate_extracted_tables(self, manifest: dict) -> dict:
        entries = manifest.get("entries", [])
        table_prefix = TABLE_MAGIC.to_bytes(4, "little").hex()
        matching = [
            row for row in entries if row.get("head_hex", "").startswith(table_prefix)
        ]
        return {
            "valid": bool(entries) and bool(matching),
            "entry_count": len(entries),
            "table_container_count": len(matching),
            "table_magic_hex": f"0x{TABLE_MAGIC:08x}",
        }


def probe_archive(path: Path) -> dict:
    head = path.read_bytes()[:24]
    if len(head) < 24:
        return {"path": str(path.resolve()), "valid_header": False, "size": path.stat().st_size}
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "valid_header": True,
        "magic": int.from_bytes(head[0:4], "little"),
        "magic_hex": f"0x{int.from_bytes(head[0:4], 'little'):08x}",
        "version": int.from_bytes(head[4:8], "little"),
        "flags": int.from_bytes(head[8:12], "little"),
        "flags_hex": f"0x{int.from_bytes(head[8:12], 'little'):08x}",
        "entry_count": int.from_bytes(head[20:24], "little"),
        "head_hex": head.hex(),
    }


def probe_metadata_wrapper(path: Path) -> dict:
    head = path.read_bytes()[:8]
    if len(head) < 8:
        return {"path": str(path.resolve()), "valid_header": False, "size": path.stat().st_size}
    payload_length = int.from_bytes(head[4:8], "little")
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "valid_header": True,
        "wrapper_magic": int.from_bytes(head[:4], "little"),
        "wrapper_magic_hex": f"0x{int.from_bytes(head[:4], 'little'):08x}",
        "payload_length": payload_length,
        "payload_length_matches": payload_length == path.stat().st_size - 0x148,
    }
