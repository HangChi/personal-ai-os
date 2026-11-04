"""Explicit writable caches for disposable integration runners; never import app config."""
import os
from pathlib import Path


def cache_environment(values=None):
    values = os.environ if values is None else values
    defaults = {"GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go"}
    result = {}
    for name, default in defaults.items():
        value = values.get("GO_TEST_" + name, default)
        if not value or not Path(value).is_absolute() or "\x00" in value:
            raise ValueError("GO_TEST_" + name + " must be an absolute cache directory")
        result[name] = value
    return result
