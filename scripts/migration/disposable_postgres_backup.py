"""仅在本脚本新建临时容器内，用合成数据执行pg_dump/pg_restore。"""

from pathlib import Path
import subprocess


def verify_backup_fixture(container):
    if len(container) < 12 or any(char not in "0123456789abcdef" for char in container):
        raise ValueError("备份测试只能使用调用方验证的新容器ID")
    prefix = ["docker", "exec", "--interactive", container]
    seed = Path(__file__).with_name("backup_seed.sql").read_bytes()
    subprocess.run(prefix + ["psql", "--no-psqlrc", "--username=go_fixture", "--dbname=personalos_go_test",
                             "--set=ON_ERROR_STOP=1"], input=seed, capture_output=True, check=True, timeout=30)
    dump = subprocess.run(prefix + ["pg_dump", "--format=custom", "--no-owner", "--no-acl",
                                    "--username=go_fixture", "--dbname=personalos_go_test"],
                          capture_output=True, check=True, timeout=60).stdout
    if not dump.startswith(b"PGDMP"):
        raise RuntimeError("未得到有效custom格式备份")
    subprocess.run(prefix + ["createdb", "--username=go_fixture", "personalos_go_restore"],
                   capture_output=True, check=True, timeout=30)
    subprocess.run(prefix + ["pg_restore", "--exit-on-error", "--no-owner", "--no-acl",
                             "--username=go_fixture", "--dbname=personalos_go_restore"],
                   input=dump, capture_output=True, check=True, timeout=60)
    # 备份只驻留当前进程内存，不输出或留在仓库；目标库随本容器一起finally清理。
    print("合成数据 pg_dump custom → 同一临时集群的独立空库 pg_restore 通过", flush=True)
