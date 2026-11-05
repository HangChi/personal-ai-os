#!/usr/bin/env python3
"""Transparent stdio fault fixture around an installed external filesystem MCP server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--sandbox", type=Path, required=True)
    parser.add_argument("--journal", type=Path, required=True)
    parser.add_argument("--fault-marker", type=Path, required=True)
    args = parser.parse_args()
    if args.sandbox.is_symlink() or not args.sandbox.is_dir() or not args.server.is_file():
        raise ValueError("Explicit sandbox and installed server are required")
    sandbox = args.sandbox.resolve(strict=True)
    child = subprocess.Popen([args.node, str(args.server), str(sandbox)],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    pending = {}
    lock = threading.Lock()

    def forward_requests():
        try:
            for raw in sys.stdin.buffer:
                message = json.loads(raw)
                if message.get("method") == "tools/call":
                    params = message["params"]
                    arguments = params.get("arguments", {})
                    identity = params.get("_meta", {}).get("personal-ai-os/idempotency-key")
                    with args.journal.open("a") as log:
                        log.write(json.dumps({"tool": params["name"], "operationId": identity,
                            "argumentsDigest": hashlib.sha256(json.dumps(arguments, sort_keys=True).encode()).hexdigest()}) + "\n")
                    # Only synthetic fixture files opt into response loss.
                    target = arguments.get("path", arguments.get("source", ""))
                    with lock:
                        pending[message["id"]] = (Path(target).name.startswith("lose-response-"), params["name"])
                child.stdin.write(raw)
                child.stdin.flush()
        except (BrokenPipeError, OSError):
            pass
        finally:
            try:
                child.stdin.close()
            except OSError:
                pass

    threading.Thread(target=forward_requests, daemon=True).start()
    try:
        for raw in child.stdout:
            message = json.loads(raw)
            with lock:
                lose, tool = pending.pop(message.get("id"), (False, ""))
            if lose and "result" in message and not message["result"].get("isError", False):
                try:
                    marker = args.fault_marker.with_name(args.fault_marker.name + "-" + tool)
                    fd = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                except FileExistsError:
                    pass
                else:
                    os.close(fd)
                    # A genuine server has committed its side effect. Lose only the reply.
                    return
            sys.stdout.buffer.write(raw)
            sys.stdout.buffer.flush()
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)


if __name__ == "__main__":
    main()
