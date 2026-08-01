from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from stella_pipeline.diffing import diff_datasets
from stella_pipeline.util import write_json


class DatasetDiffTests(unittest.TestCase):
    def make_dataset(self, root: Path, version: int, table: dict) -> None:
        write_json(root / "index.json", {"schema_version": "1.0.0"})
        write_json(
            root / "source.json",
            {
                "installed_client": {"version": "1.0.0"},
                "resource_snapshot": {"resource_version": version},
            },
        )
        write_json(root / "bin" / "Example.json", table)
        write_json(
            root / "catalog" / "tables.json",
            {"tables": [{"table": "Example", "categories": ["combat"]}]},
        )

    def test_reports_added_removed_changed_rows_and_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old = root / "old"
            new = root / "new"
            output = root / "diff"
            self.make_dataset(
                old,
                1,
                {
                    "1": {"Id": 1, "Power": 10, "Nullable": None},
                    "2": {"Id": 2, "Power": 20},
                    "3": {"Id": 3, "Power": 30},
                },
            )
            self.make_dataset(
                new,
                2,
                {
                    "1": {"Id": 1, "Power": 11},
                    "2": {"Id": 2, "Power": 20},
                    "4": {"Id": 4, "Power": 40},
                },
            )
            report = diff_datasets(old, new, output)
            binary = report["scopes"][0]
            self.assertEqual(1, binary["changed_tables"])
            table = binary["tables"][0]
            self.assertEqual(1, table["added_rows"])
            self.assertEqual(1, table["removed_rows"])
            self.assertEqual(1, table["changed_rows"])
            self.assertEqual(
                {"Nullable": 1, "Power": 1}, table["changed_field_occurrences"]
            )
            self.assertEqual({"combat": 1}, binary["impacted_categories"])
            self.assertTrue((output / "README.md").exists())
            self.assertTrue((output / "diff.json").exists())


if __name__ == "__main__":
    unittest.main()
