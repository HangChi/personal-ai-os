"""Hash exact baseline Spring AI EMBED content without persisting or emitting source text."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CLASSPATH_REPORT = ROOT / "backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml"
PACKAGED_JAR = ROOT / "backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar"


def dependencies(directory):
    if CLASSPATH_REPORT.is_file():
        classpath = next(item.attrib["value"] for item in ET.parse(CLASSPATH_REPORT).find("properties")
                         if item.attrib.get("name") == "java.class.path")
        return [Path(path) for path in classpath.split(os.pathsep) if path.endswith(".jar")]
    # CI packages the unchanged Java baseline with tests skipped; use its actual bundled jars.
    jars = []
    with zipfile.ZipFile(PACKAGED_JAR) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename.startswith("BOOT-INF/lib/")
                   and entry.filename.endswith(".jar") and "/" not in entry.filename[len("BOOT-INF/lib/"):]]
        if not entries or sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError("Baseline packaged dependencies missing or oversized")
        for entry in entries:
            target = Path(directory) / Path(entry.filename).name
            with archive.open(entry) as source, target.open("xb") as output:
                output.write(source.read())
            jars.append(target)
    return jars


def embedding_hashes(rows, fixture=False):
    environment = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
    source = ROOT / "scripts/migration/EmbeddingContentOracle.java"
    with tempfile.TemporaryDirectory(prefix="personalos-embedding-oracle-") as directory:
        jars = dependencies(directory)
        cp = os.pathsep.join(str(path) for path in jars)
        jar_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in jars if path.name.startswith(("spring-ai-commons-", "spring-ai-openai-"))}
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
                        jars_sha256=jar_hashes)
