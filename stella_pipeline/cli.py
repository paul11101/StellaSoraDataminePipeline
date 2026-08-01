from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path

from .diffing import diff_datasets
from .pipeline import Pipeline, doctor, load_settings
from .util import PipelineError, read_json


AUTOMATION_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = AUTOMATION_ROOT / "config.json"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="stella-pipeline",
        description="Stella Sora TW 本地逆向资料自动化流水线",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=DEFAULT_CONFIG,
        help=f"配置文件（默认：{DEFAULT_CONFIG}）",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("doctor", help="检查客户端、参考 schema、依赖和工具")

    run = commands.add_parser("run", help="运行完整资料挖掘流水线")
    run.add_argument(
        "--offline",
        action="store_true",
        help="使用配置中的已缓存官方清单和资源，不访问网络",
    )
    run.add_argument("--resume", action="store_true", help="从已有 run 状态安全续跑")
    run.add_argument("--run-dir", type=Path, help="指定本次运行目录")
    run.add_argument("--previous", type=Path, help="显式指定旧数据集用于差分")

    diff = commands.add_parser("diff", help="比较两个已经生成的数据集")
    diff.add_argument("old", type=Path)
    diff.add_argument("new", type=Path)
    diff.add_argument("--output", type=Path, help="差分输出目录")
    diff.add_argument("--max-examples", type=int, default=20)
    diff.add_argument(
        "--force",
        action="store_true",
        help="删除并重建已存在的差分输出目录",
    )

    status = commands.add_parser("status", help="查看最近一次或指定运行的状态")
    status.add_argument("run_dir", type=Path, nargs="?")
    return parser


def print_json(value: object) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2))


def command_status(settings, run_dir: Path | None) -> int:
    if run_dir is None:
        latest = settings.runs_root / "latest.json"
        if not latest.exists():
            raise PipelineError("还没有 latest.json；先运行一次流水线或指定运行目录")
        run_dir = Path(read_json(latest)["run_dir"])
    state_path = run_dir.resolve() / "state.json"
    if not state_path.exists():
        raise PipelineError(f"state.json 不存在：{state_path}")
    print_json(read_json(state_path))
    return 0


def command_diff(args: argparse.Namespace, settings) -> int:
    output = (
        args.output.resolve()
        if args.output
        else (
            settings.analysis_root
            / "automation"
            / "diffs"
            / datetime.now().strftime("%Y%m%d_%H%M%S")
        ).resolve()
    )
    if output.exists():
        if not args.force:
            raise PipelineError(f"diff output already exists: {output}; use --force")
        if output == Path(output.anchor) or len(output.parts) < 3:
            raise PipelineError(f"refusing to remove broad path: {output}")
        shutil.rmtree(output)
    report = diff_datasets(
        args.old.resolve(),
        args.new.resolve(),
        output,
        max_examples=max(1, args.max_examples),
    )
    print_json({"output": str(output), **report["summary"]})
    return 0


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
        sys.stderr.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        settings = load_settings(args.config)
        if args.command == "doctor":
            report = doctor(settings)
            print_json(report)
            return 0 if report["all_required_checks_passed"] else 1
        if args.command == "status":
            return command_status(settings, args.run_dir)
        if args.command == "diff":
            return command_diff(args, settings)
        if args.command == "run":
            pipeline = Pipeline(
                settings,
                offline=args.offline,
                resume=args.resume,
                run_dir=args.run_dir,
                previous_dataset=args.previous,
            )
            pipeline.run()
            return 0
        parser.error(f"unknown command: {args.command}")
    except (PipelineError, OSError, json.JSONDecodeError, KeyError, ValueError) as error:
        print(f"[失败] {error}", file=sys.stderr)
        return 1
    except Exception as error:
        print(f"[失败] 未预期错误：{type(error).__name__}: {error}", file=sys.stderr)
        return 1
    return 2
