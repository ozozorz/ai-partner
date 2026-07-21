#!/usr/bin/env python3
"""固化 AI Partner v0.4 实验日志并生成可核验快照。

脚本验证冻结指纹和完成状态，把论文分析所需的小型结果复制到 ``curated``，
同时将完整运行日志压缩为原始证据包。它不会修改 ``run/logs`` 中的任何源文件。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BASE_CURATED_FILES = (
    "frozen-v0.4.json",
    "batches/pretest-v04-final/summary.json",
    "batches/pretest-v04-final/results.jsonl",
    "batches/main-v04-162/summary.json",
    "batches/main-v04-162/results.jsonl",
    "evaluation/model-runs/offline-v04-main/manifest.json",
    "evaluation/model-runs/offline-v04-main/checkpoint.json",
    "evaluation/model-runs/offline-v04-main/metrics.json",
    "evaluation/model-runs/offline-v04-main/predictions.jsonl",
)


def parse_args() -> argparse.Namespace:
    """解析日志源、快照目标和覆盖策略。"""

    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=repository)
    parser.add_argument(
        "--logs-root",
        type=Path,
        default=repository / "run" / "logs" / "ai-partner",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=repository / "artifacts" / "experiments" / "v0.4-primary",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="覆盖已经存在的快照目录；源日志始终保持只读。",
    )
    parser.add_argument(
        "--a2-batch",
        default=None,
        help="可选的已完成 A2 批次 ID；提供后会校验并归档其汇总与逐 episode 结果。",
    )
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    """读取 UTF-8 JSON 对象。"""

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def sha256_file(path: Path) -> str:
    """流式计算大日志或归档的 SHA-256。"""

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_metadata(repository: Path) -> tuple[str, bool]:
    """读取提交 ID 和工作树是否干净；分析产物本身可能尚未提交。"""

    safe_option = f"safe.directory={repository.as_posix()}"
    try:
        commit = subprocess.run(
            ["git", "-c", safe_option, "rev-parse", "HEAD"],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        ).stdout.strip()
        status = subprocess.run(
            ["git", "-c", safe_option, "status", "--porcelain"],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return "unknown", False
    return commit, not bool(status.strip())


def validate_protocol(logs_root: Path, a2_batch: str | None = None) -> dict[str, Any]:
    """验证预实验、冻结文件、主实验、离线运行和可选 A2 使用同一协议。"""

    frozen = read_json(logs_root / "frozen-v0.4.json")
    pretest = read_json(logs_root / "batches" / "pretest-v04-final" / "summary.json")
    main = read_json(logs_root / "batches" / "main-v04-162" / "summary.json")
    offline_manifest = read_json(
        logs_root / "evaluation" / "model-runs" / "offline-v04-main" / "manifest.json"
    )
    offline_checkpoint = read_json(
        logs_root / "evaluation" / "model-runs" / "offline-v04-main" / "checkpoint.json"
    )
    fingerprint = frozen["snapshot"]["fingerprint"]
    fingerprints = {
        fingerprint,
        pretest.get("protocolFingerprint"),
        main.get("protocolFingerprint"),
        offline_manifest.get("protocolFingerprint"),
    }
    a2_summary: dict[str, Any] | None = None
    if a2_batch:
        a2_summary = read_json(logs_root / "batches" / a2_batch / "summary.json")
        fingerprints.add(a2_summary.get("protocolFingerprint"))
    if len(fingerprints) != 1:
        raise ValueError(f"实验协议指纹不一致：{fingerprints}")
    if pretest.get("status") != "COMPLETED":
        raise ValueError("冻结预实验未完成")
    if main.get("status") != "COMPLETED" or main.get("completedEpisodes") != 162:
        raise ValueError("主实验不是已完成的 162-episode 批次")
    if offline_checkpoint.get("status") != "COMPLETED" or offline_checkpoint.get("processedCases") != 72:
        raise ValueError("离线模型评测不是已完成的 72-case 运行")
    integrity = {
        "protocol_fingerprint": fingerprint,
        "pretest_batch": pretest["batchId"],
        "pretest_episodes": pretest["completedEpisodes"],
        "main_batch": main["batchId"],
        "main_episodes": main["completedEpisodes"],
        "offline_run": offline_checkpoint["runId"],
        "offline_cases": offline_checkpoint["processedCases"],
    }
    if a2_summary is not None:
        a2_variant = a2_summary.get("variants", {}).get("MAID_IBC_A2_NO_RUNTIME_MONITORING", {})
        if (
            a2_summary.get("batchKind") != "ABLATION_A2"
            or a2_summary.get("status") != "COMPLETED"
            or a2_summary.get("plannedEpisodes") != 54
            or a2_summary.get("completedEpisodes") != 54
            or a2_variant.get("total") != 54
        ):
            raise ValueError("A2 不是已完成的 54-episode 冻结消融批次")
        integrity.update(
            {
                "a2_batch": a2_summary["batchId"],
                "a2_episodes": a2_summary["completedEpisodes"],
            }
        )
    return integrity


def collect_source_manifest(logs_root: Path) -> tuple[list[dict[str, Any]], int]:
    """为完整日志树记录相对路径、字节数和 SHA-256。"""

    records: list[dict[str, Any]] = []
    total_bytes = 0
    for path in sorted(item for item in logs_root.rglob("*") if item.is_file()):
        relative = path.relative_to(logs_root).as_posix()
        size = path.stat().st_size
        total_bytes += size
        records.append({"path": relative, "size_bytes": size, "sha256": sha256_file(path)})
    return records, total_bytes


def create_raw_archive(logs_root: Path, archive_path: Path) -> None:
    """把完整日志树压缩为单一 ZIP，便于离线备份和校验。"""

    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(item for item in logs_root.rglob("*") if item.is_file()):
            archive.write(path, arcname=path.relative_to(logs_root).as_posix())


def copy_curated(logs_root: Path, output: Path, a2_batch: str | None = None) -> None:
    """复制论文复核常用的小型结构化结果，并保持原相对路径。"""

    curated_files = list(BASE_CURATED_FILES)
    if a2_batch:
        curated_files.extend(
            (
                f"batches/{a2_batch}/summary.json",
                f"batches/{a2_batch}/results.jsonl",
            )
        )
    for relative in curated_files:
        source = logs_root / Path(relative)
        if not source.is_file():
            raise FileNotFoundError(f"缺少应归档文件：{source}")
        destination = output / "curated" / Path(relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


def render_readme(manifest: dict[str, Any]) -> str:
    """生成快照内容、复核方式和隐私边界说明。"""

    integrity = manifest["integrity"]
    a2_batch = integrity.get("a2_batch")
    title = "主实验与 A2 消融快照" if a2_batch else "主实验快照"
    scope = (
        f"162-episode 主实验和 54-episode A2 消融（批次 `{a2_batch}`）"
        if a2_batch
        else "162-episode 最低规模主实验"
    )
    analyze_command = (
        f"python analysis\\analyze_v04.py --a2-batch {a2_batch}"
        if a2_batch
        else "python analysis\\analyze_v04.py"
    )
    snapshot_command = (
        f"python analysis\\snapshot_v04.py --output artifacts\\experiments\\v0.4-with-a2 --a2-batch {a2_batch} --force"
        if a2_batch
        else "python analysis\\snapshot_v04.py --force"
    )
    version_note = (
        "本快照显式包含 A2；`v0.4-primary` 仍作为不含消融的原始主实验快照保留。"
        if a2_batch
        else "若 A2 消融完成，应使用新的快照版本归档，不能无说明地覆盖本 v0.4 主实验快照。"
    )
    return f"""# AI Partner v0.4 {title}

本目录固化提交 `{manifest['git_commit']}` 对应的预实验、72 条离线模型评测和
{scope}。协议指纹为：

```text
{integrity['protocol_fingerprint']}
```

## 内容

- `curated/`：论文分析直接使用的冻结文件、汇总、逐 episode 结果和离线预测；
- `raw-logs-v0.4.zip`：生成快照时 `run/logs/ai-partner/` 的完整副本；
- `snapshot_manifest.json`：每个源文件与原始归档的 SHA-256、大小和生成环境。

完整归档包含本地玩家 UUID、模型原始输出和运行日志，不应直接公开发布。公开复现包应先做
隐私检查，并优先发布 `curated/`、分析脚本及必要的去标识化事件字段。

## 复核

```powershell
{analyze_command}
{snapshot_command}
```

重新生成快照不会改变 `run/logs` 源文件。{version_note}
"""


def main() -> None:
    """验证并原子替换完整实验快照目录。"""

    args = parse_args()
    repository = args.repository.resolve()
    logs_root = args.logs_root.resolve()
    output = args.output.resolve()
    if not logs_root.is_dir():
        raise FileNotFoundError(f"找不到实验日志目录：{logs_root}")
    if output.exists() and not args.force:
        raise FileExistsError(f"快照已存在：{output}；如需重建请显式添加 --force")

    integrity = validate_protocol(logs_root, args.a2_batch)
    source_files, total_bytes = collect_source_manifest(logs_root)
    commit, clean_before_snapshot = git_metadata(repository)
    generated_at = datetime.now(timezone.utc).isoformat()

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ai-partner-v04-snapshot-", dir=output.parent) as temporary:
        staging = Path(temporary) / output.name
        staging.mkdir(parents=True)
        copy_curated(logs_root, staging, args.a2_batch)
        archive_path = staging / "raw-logs-v0.4.zip"
        create_raw_archive(logs_root, archive_path)
        manifest = {
            "schema_version": "0.4-snapshot-2",
            "generated_at": generated_at,
            "git_commit": commit,
            "git_clean_before_snapshot": clean_before_snapshot,
            "source_root": "run/logs/ai-partner",
            "source_file_count": len(source_files),
            "source_total_bytes": total_bytes,
            "integrity": integrity,
            "source_files": source_files,
            "raw_archive": {
                "path": archive_path.name,
                "size_bytes": archive_path.stat().st_size,
                "sha256": sha256_file(archive_path),
            },
        }
        (staging / "snapshot_manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        (staging / "README.md").write_text(render_readme(manifest), encoding="utf-8", newline="\n")
        if output.exists():
            shutil.rmtree(output)
        shutil.move(staging, output)

    print(f"快照完成：{output}")
    print(f"源文件={len(source_files)}，源字节={total_bytes}，指纹={integrity['protocol_fingerprint']}")


if __name__ == "__main__":
    main()
