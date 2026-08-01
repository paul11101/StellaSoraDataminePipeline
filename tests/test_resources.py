from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from stella_pipeline.adapters import select_adapter
from stella_pipeline.adapters.bar102_il2cpp31 import (
    BAR_MAGIC,
    METADATA_WRAPPER_MAGIC,
    probe_archive,
    probe_metadata_wrapper,
)
from stella_pipeline.resources import select_resource_chain
from stella_pipeline.state import StateStore
from stella_pipeline.util import PipelineError


class ResourceChainTests(unittest.TestCase):
    def test_selects_newest_base_and_only_newer_patches(self) -> None:
        rows = [
            {"name": "data.arcx", "version": 100},
            {"name": "p_9_u.data.arcx", "version": 104},
            {"name": "data.arcx", "version": 105},
            {"name": "p_1_u.data.arcx", "version": 107},
            {"name": "p_2_u.data.arcx", "version": 109},
            {"name": "p_1_u.hotfix.arch", "version": 107},
        ]
        chain = select_resource_chain(rows, "data.arcx")
        self.assertEqual(105, chain.base["version"])
        self.assertEqual(
            ["data.arcx", "p_1_u.data.arcx", "p_2_u.data.arcx"], chain.names
        )
        self.assertEqual(109, chain.version)

    def test_rejects_ambiguous_patch_version(self) -> None:
        rows = [
            {"name": "data.arcx", "version": 100},
            {"name": "p_1_u.data.arcx", "version": 101},
            {"name": "p_2_u.data.arcx", "version": 101},
        ]
        with self.assertRaises(PipelineError):
            select_resource_chain(rows, "data.arcx")


class AdapterTests(unittest.TestCase):
    def test_signature_probe_selects_current_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "data.arcx"
            archive.write_bytes(
                BAR_MAGIC.to_bytes(4, "little")
                + (102).to_bytes(4, "little")
                + (0x111).to_bytes(4, "little")
                + (0).to_bytes(8, "little")
                + (921).to_bytes(4, "little")
            )
            metadata = root / "global-metadata.dat"
            payload_size = 64
            metadata.write_bytes(
                METADATA_WRAPPER_MAGIC.to_bytes(4, "little")
                + payload_size.to_bytes(4, "little")
                + bytes(0x148 - 8 + payload_size)
            )
            archive_probe = probe_archive(archive)
            metadata_probe = probe_metadata_wrapper(metadata)
            adapter = select_adapter(archive_probe, metadata_probe)
            self.assertIsNotNone(adapter)
            self.assertEqual("bar102-il2cpp31-v1", adapter.id)
            self.assertTrue(metadata_probe["payload_length_matches"])


class StateTests(unittest.TestCase):
    def test_resume_skips_valid_completed_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "output.txt"
            state = StateStore(
                root / "state.json",
                {"status": "created", "stages": {}},
            )
            calls = []

            def action():
                calls.append(1)
                output.write_text("ok", encoding="utf-8")
                return {"ok": True}

            validator = output.exists
            state.run_stage("stage", {"input": 1}, action, validator, resume=False)
            state.run_stage("stage", {"input": 1}, action, validator, resume=True)
            self.assertEqual(1, len(calls))


if __name__ == "__main__":
    unittest.main()
