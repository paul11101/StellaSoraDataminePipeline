"""Format adapter registry.

Add a new adapter here when a client update changes archive or metadata
signatures.  The pipeline selects adapters by signatures, not resource number.
"""

from .bar102_il2cpp31 import Bar102Il2Cpp31Adapter


ADAPTERS = (Bar102Il2Cpp31Adapter(),)


def select_adapter(archive_probe: dict, metadata_probe: dict):
    matches = [
        adapter
        for adapter in ADAPTERS
        if adapter.supports(archive_probe, metadata_probe)
    ]
    if len(matches) != 1:
        return None
    return matches[0]


__all__ = ["ADAPTERS", "select_adapter", "Bar102Il2Cpp31Adapter"]
