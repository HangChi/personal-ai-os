#!/usr/bin/env python3
"""复用原备份格式；所有Go目标显式注入，绝不读取.env或沿用Java容器默认值。"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("baseline_backup", ROOT / "scripts/dev/backup.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def configuration(source, command, allow_unencrypted=False):
    result = {}
    for target, key in (("DB_CONTAINER", "GO_BACKUP_DB_CONTAINER"), ("POSTGRES_USER", "GO_BACKUP_DB_USER"),
                        ("POSTGRES_DB", "GO_BACKUP_DB_NAME")):
        if not source.get(key):
            raise ValueError("必须显式设置 " + key)
        result[target] = source[key]
    if command != "backup":
        return result
    directory = Path(source.get("GO_BACKUP_DIR", ""))
    if not source.get("GO_BACKUP_DIR") or not directory.is_absolute():
        raise ValueError("GO_BACKUP_DIR 必须是显式绝对路径")
    result["BACKUP_DIR"] = str(directory)
    backend, uploads = source.get("GO_BACKUP_BACKEND_CONTAINER"), source.get("GO_BACKUP_UPLOAD_DIR")
    if bool(backend) == bool(uploads):
        raise ValueError("显式设置且只设置 GO_BACKUP_BACKEND_CONTAINER 或 GO_BACKUP_UPLOAD_DIR")
    if uploads and not Path(uploads).is_absolute():
        raise ValueError("GO_BACKUP_UPLOAD_DIR 必须是绝对路径")
    result.update(BACKUP_BACKEND_CONTAINER=backend or "", BACKUP_UPLOAD_DIR=uploads or "",
                  BACKUP_RETAIN=source.get("GO_BACKUP_RETAIN", "0"))
    recipient = source.get("GO_BACKUP_AGE_RECIPIENT", "")
    if not recipient and not allow_unencrypted:
        raise ValueError("默认要求 GO_BACKUP_AGE_RECIPIENT；隔离测试明文需显式 --allow-unencrypted")
    result["BACKUP_AGE_RECIPIENT"] = recipient
    for key in ("S3_ENABLED", "S3_ENDPOINT", "S3_BUCKET", "S3_NAMESPACE", "S3_REGION", "S3_ACCESS_KEY", "S3_SECRET_KEY",
                "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AI_CHAT_MAX_ATTACHMENT_BYTES"):
        if key in source:
            result[key] = source[key]
    if source.get("GO_BACKUP_COPY_DIR"):
        copy = Path(source["GO_BACKUP_COPY_DIR"])
        if not copy.is_absolute():
            raise ValueError("GO_BACKUP_COPY_DIR 必须是绝对路径")
        result["BACKUP_COPY_DIR"] = str(copy)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("backup", "verify", "drill"))
    parser.add_argument("artifact", nargs="?", type=Path)
    parser.add_argument("--identity", type=Path)
    parser.add_argument("--allow-unencrypted", action="store_true")
    args = parser.parse_args()
    values = configuration(os.environ, args.command, args.allow_unencrypted)
    if args.command == "backup":
        baseline.backup(values)
    elif args.artifact:
        baseline.verify(args.artifact, values, args.command == "drill", args.identity)
    else:
        parser.error("verify/drill 需要归档路径")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, KeyError):
        raise SystemExit("Go备份失败；检查显式目标、归档/附件完整性及Docker/age状态，未输出凭据")
