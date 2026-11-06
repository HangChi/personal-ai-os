"""功能验证脚本共用的 JDK 发现；优先显式 JAVA_HOME，其次使用 PATH。"""

import os
from pathlib import Path
import shutil


def java_commands(environment):
    home = environment.get("JAVA_HOME")
    if home:
        commands = tuple(str(Path(home) / "bin" / name) for name in ("java", "javac"))
        if not all(Path(command).is_file() and os.access(command, os.X_OK) for command in commands):
            raise RuntimeError("JAVA_HOME 必须指向包含 java 和 javac 的完整 JDK")
        environment["PATH"] = str(Path(home) / "bin") + os.pathsep + environment.get("PATH", "")
        return commands
    commands = tuple(shutil.which(name, path=environment.get("PATH", "")) for name in ("java", "javac"))
    if not all(commands):
        raise RuntimeError("未找到完整 JDK；请设置 JAVA_HOME 或将 java、javac 加入 PATH")
    return commands
