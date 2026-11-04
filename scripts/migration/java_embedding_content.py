"""Hash exact baseline Spring AI EMBED content without persisting or emitting source text."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def embedding_hashes(rows, fixture=False):
    report = ROOT / "backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml"
    classpath = next(item.attrib["value"] for item in ET.parse(report).find("properties")
                     if item.attrib.get("name") == "java.class.path")
    jars = [Path(path) for path in classpath.split(os.pathsep) if path.endswith(".jar")]
    cp = os.pathsep.join(str(path) for path in jars)
    environment = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
    source = ROOT / "scripts/migration/EmbeddingContentOracle.java"
    with tempfile.TemporaryDirectory(prefix="personalos-embedding-oracle-") as directory:
        compiled = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-cp", cp,
                                   "-d", directory, str(source)], env=environment, capture_output=True, timeout=60)
        if compiled.returncode:
            raise ValueError("Embedding content oracle compilation failed")
        result = subprocess.run(["java", "-cp", directory + os.pathsep + cp, "EmbeddingContentOracle",
                                 *(["--fixture"] if fixture else [])],
                                input=json.dumps(rows).encode(), env=environment, capture_output=True, timeout=60)
        if result.returncode:
            raise ValueError("Embedding content oracle failed; raw source/error withheld")
    hashes = json.loads(next(line[7:] for line in result.stdout.decode().splitlines() if line.startswith("ORACLE ")))
    if fixture:
        return hashes
    if len(hashes) != len(rows) or any(len(value) != 64 or any(c not in "0123456789abcdef" for c in value) for value in hashes):
        raise ValueError("Embedding content oracle hashes invalid")
    return hashes, dict(oracle_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                        jars_sha256={path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                     for path in jars if path.name.startswith(("spring-ai-commons-", "spring-ai-openai-"))})
