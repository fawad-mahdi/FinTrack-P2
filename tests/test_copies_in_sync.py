"""
Guards the desktop <-> Android mirroring rules.

Repo-root Python/JS files and android/app/src/main/python/ copies must
stay byte-identical for the MIRRORED set below. server.py, database.py,
auth_gmail.py and dashboard.js are intentionally divergent (documented
in CLAUDE.md) and are NOT compared.

Also gates all Android-side Python (and the mirrored root files) on
Python 3.9 syntax so a desktop-only construct can't crash Chaquopy.
"""
import ast
import hashlib
from pathlib import Path

import pytest

ROOT = Path(__file__).parent.parent
ANDROID_PY = ROOT / "android" / "app" / "src" / "main" / "python"

# Byte-identical pairs: (desktop path, android path), relative to ROOT.
MIRRORED_FILES = [
    "parsers.py",
    "merchants.py",
    "bank_registry.py",
    "generic_parser.py",
    "static/js/views/sync.js",
    "static/js/views/activity.js",
    "static/js/views/profile.js",
    "static/js/api.js",
    "static/js/router.js",
    "static/js/components/quickAdd.js",
]

# Python files that must be 3.9-safe (everything that runs on Android).
_PY39_FILES = sorted(ANDROID_PY.glob("*.py")) + [
    ROOT / "parsers.py",
    ROOT / "merchants.py",
    ROOT / "bank_registry.py",
    ROOT / "generic_parser.py",
]

# X | Y unions and builtin generics parse fine but crash at runtime on 3.9.
_BUILTIN_GENERICS = {"dict", "list", "tuple", "set", "frozenset", "type"}


def _annotation_violations(tree):
    """Yield (lineno, label) for 3.10+-only constructs inside annotations."""
    annotations = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            args = node.args
            for arg in args.args + args.posonlyargs + args.kwonlyargs:
                if arg.annotation:
                    annotations.append(arg.annotation)
            if args.vararg and args.vararg.annotation:
                annotations.append(args.vararg.annotation)
            if args.kwarg and args.kwarg.annotation:
                annotations.append(args.kwarg.annotation)
            if node.returns:
                annotations.append(node.returns)
        elif isinstance(node, ast.AnnAssign):
            annotations.append(node.annotation)

    for annotation in annotations:
        for sub in ast.walk(annotation):
            if isinstance(sub, ast.BinOp) and isinstance(sub.op, ast.BitOr):
                yield sub.lineno, "X | Y union annotation"
            if (
                isinstance(sub, ast.Subscript)
                and isinstance(sub.value, ast.Name)
                and sub.value.id in _BUILTIN_GENERICS
            ):
                yield sub.lineno, f"builtin generic {sub.value.id}[...] annotation"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize("rel", MIRRORED_FILES)
def test_mirrored_file_is_identical(rel):
    desktop = ROOT / rel
    android = ANDROID_PY / rel
    assert desktop.exists(), f"missing desktop copy: {rel}"
    assert android.exists(), f"missing Android copy: {rel} — copy it to {android}"
    assert _sha256(desktop) == _sha256(android), (
        f"{rel} has drifted between the desktop and Android copies. "
        f"Copy the intended version over the other (see CLAUDE.md mirroring rule)."
    )


@pytest.mark.parametrize("path", _PY39_FILES, ids=lambda p: str(p.relative_to(ROOT)))
def test_python39_compatible(path):
    source = path.read_text()
    # Catches match statements and 3.10+-only grammar.
    tree = ast.parse(source, filename=str(path), feature_version=(3, 9))
    # Catches annotations that parse everywhere but crash at runtime on 3.9.
    violations = list(_annotation_violations(tree))
    assert not violations, (
        f"{path.relative_to(ROOT)} uses 3.10+-only annotations "
        f"(use typing.Optional/Union/Dict/List — Python 3.9 rule): "
        + ", ".join(f"line {ln}: {label}" for ln, label in violations)
    )
