from __future__ import annotations

import traceback
from pathlib import Path
from typing import Any, Callable

from .util import PipelineError, now_iso, read_json, stable_signature, write_json


class StateStore:
    def __init__(self, path: Path, initial: dict[str, Any] | None = None):
        self.path = path
        if path.exists():
            self.value = read_json(path)
        elif initial is not None:
            self.value = initial
            self.save()
        else:
            raise PipelineError(f"state file does not exist: {path}")

    def save(self) -> None:
        self.value["updated_at"] = now_iso()
        write_json(self.path, self.value)

    def begin(self, name: str, signature: str) -> None:
        self.value["status"] = "running"
        self.value.setdefault("stages", {})[name] = {
            "status": "running",
            "signature": signature,
            "started_at": now_iso(),
        }
        self.save()

    def complete(self, name: str, result: Any) -> None:
        stage = self.value["stages"][name]
        stage.update({"status": "complete", "completed_at": now_iso(), "result": result})
        self.save()

    def fail(self, name: str, error: BaseException) -> None:
        stage = self.value["stages"][name]
        stage.update(
            {
                "status": "failed",
                "failed_at": now_iso(),
                "error": str(error),
                "traceback": "".join(
                    traceback.format_exception(type(error), error, error.__traceback__)
                ),
            }
        )
        self.value["status"] = "failed"
        self.save()

    def finish(self, result: dict[str, Any]) -> None:
        self.value["status"] = "complete"
        self.value["completed_at"] = now_iso()
        self.value["result"] = result
        self.save()

    def run_stage(
        self,
        name: str,
        signature_payload: Any,
        action: Callable[[], Any],
        validator: Callable[[], bool],
        *,
        resume: bool,
    ) -> Any:
        signature = stable_signature(signature_payload)
        previous = self.value.get("stages", {}).get(name, {})
        if (
            resume
            and previous.get("status") == "complete"
            and previous.get("signature") == signature
            and validator()
        ):
            print(f"[跳过] {name}（状态和产物校验通过）", flush=True)
            return previous.get("result")

        print(f"[运行] {name}", flush=True)
        self.begin(name, signature)
        try:
            result = action()
            if not validator():
                raise PipelineError(f"stage output validation failed: {name}")
        except BaseException as error:
            self.fail(name, error)
            raise
        self.complete(name, result)
        print(f"[完成] {name}", flush=True)
        return result
