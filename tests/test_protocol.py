from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

from google.protobuf import descriptor_pb2


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from stella_pipeline.protocol import (
    classify_descriptor_candidates,
    find_descriptor_candidates,
    find_netmsg_chunk,
)


def write_descriptor_set(path: Path, file_names: list[str]) -> None:
    descriptor_set = descriptor_pb2.FileDescriptorSet()
    for index, name in enumerate(file_names):
        file_proto = descriptor_set.file.add()
        file_proto.name = name
        file_proto.package = "proto"
        message = file_proto.message_type.add()
        message.name = f"Message{index}"
        field = message.field.add()
        field.name = "Value"
        field.number = 1
        field.label = descriptor_pb2.FieldDescriptorProto.LABEL_OPTIONAL
        field.type = descriptor_pb2.FieldDescriptorProto.TYPE_UINT32
    path.write_bytes(descriptor_set.SerializeToString())


class ProtocolDiscoveryTests(unittest.TestCase):
    def test_classifies_current_descriptor_roles_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            hashes = Path(temporary)
            write_descriptor_set(hashes / "30.bin", ["client_table.proto"])
            write_descriptor_set(
                hashes / "20.bin", ["public.proto", "ike.proto", "player_login.proto"]
            )
            write_descriptor_set(hashes / "b0.bin", ["roguelike_tempData.proto"])
            write_descriptor_set(hashes / "a0.bin", ["roguelike_tempData.proto"])
            (hashes / "noise.bin").write_bytes(b"\x1bLua")

            candidates = find_descriptor_candidates(hashes)
            classified, errors = classify_descriptor_candidates(candidates)

            self.assertEqual([], errors)
            self.assertEqual("20.bin", classified["network"].source.name)
            self.assertEqual("30.bin", classified["data"].source.name)
            self.assertEqual("a0.bin", classified["runtime_v1"].source.name)
            self.assertEqual("b0.bin", classified["runtime_v2"].source.name)

    def test_reports_ambiguous_network_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            hashes = Path(temporary)
            for name in ("10.bin", "20.bin"):
                write_descriptor_set(
                    hashes / name, ["ike.proto", "player_login.proto"]
                )
            write_descriptor_set(hashes / "30.bin", ["client_table.proto"])
            write_descriptor_set(hashes / "40.bin", ["roguelike_tempData.proto"])
            write_descriptor_set(hashes / "50.bin", ["roguelike_tempData.proto"])

            classified, errors = classify_descriptor_candidates(
                find_descriptor_candidates(hashes)
            )

            self.assertEqual({}, classified)
            self.assertIn("expected 1 network descriptor set, found 2", errors)

    def test_resolves_netmsg_chunk_from_verified_catalog_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            hashes = Path(temporary)
            chunk = hashes / "af07dc65acd1507f.bin"
            chunk.write_bytes(b"test")
            catalog = {
                "files": [
                    {
                        "name_hash_hex": "af07dc65acd1507f",
                        "path": "GameCore/Network/NetMsgId.lua",
                    }
                ]
            }
            self.assertEqual(chunk.resolve(), find_netmsg_chunk(catalog, hashes))


if __name__ == "__main__":
    unittest.main()
