"""Shared engine of the Terraform mutation checks (demo-lite/tests/mutation-check.py, bootstrap/tests/mutation-check.py).

A mutation is (id, edits, expected) where edits is [(file, old, new), ...] (or the short form file, old, new) and `expected`
is text the failing run must contain; several acceptable texts are separated by '|||'. For each mutation the module is copied
once, edited, `terraform test` is run with the given filter, and the files are restored. A mutation is CAUGHT only if the run
is red AND carries the expected message: a rule that stays green (or fails for another reason) is dead weight.
The repository is never modified.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile


def _norm(text):
    text = re.sub(r"\x1b\[[0-9;]*m", "", text)
    return re.sub(r"[\s│╷╵]+", " ", text)


def _run_tests(workdir, test_filter):
    proc = subprocess.run(["terraform", "test", "-no-color", f"-filter={test_filter}"], cwd=workdir, capture_output=True, text=True, timeout=900)
    return proc.returncode, _norm(proc.stdout + proc.stderr)


def _edits(entry):
    """(id, file, old, new, expect) or (id, [(file, old, new), ...], expect) -> (id, edits, expect)."""
    if isinstance(entry[1], str):
        return entry[0], [(entry[1], entry[2], entry[3])], entry[4]
    return entry[0], entry[1], entry[2]


def run(module_dir, test_filter, mutations, argv, repo_root=None, extra_dirs=()):
    """repo_root/extra_dirs: copy the module to the same place below a temporary root and copy these repo-relative directories
    next to it (for tests that read files outside the module, e.g. deploy/compose); edit them with file names like
    '../../../compose/docker-compose.yml' (relative to the module)."""
    entries = [_edits(m) for m in mutations]
    wanted = set(argv)
    unknown = wanted - {e[0] for e in entries}
    if unknown:
        print("unknown rule id(s): " + ", ".join(sorted(unknown)), file=sys.stderr)
        return 2
    work = tempfile.mkdtemp(prefix="saiman-mutation.")
    try:
        if repo_root:
            mod = os.path.join(work, os.path.relpath(module_dir, repo_root))
            for extra in extra_dirs:
                shutil.copytree(os.path.join(repo_root, extra), os.path.join(work, extra))
        else:
            mod = os.path.join(work, os.path.basename(module_dir))
        shutil.copytree(module_dir, mod, ignore=shutil.ignore_patterns(".terraform", "terraform.tfstate*", "*.tfplan", "backend.tf", "backend.hcl", "terraform.tfvars", "__pycache__"))
        if os.path.isdir(os.path.join(module_dir, ".terraform")):
            os.symlink(os.path.join(module_dir, ".terraform"), os.path.join(mod, ".terraform"))
        else:
            subprocess.run(["terraform", "init", "-backend=false", "-input=false"], cwd=mod, check=True, capture_output=True)
        code, _ = _run_tests(mod, test_filter)
        if code != 0:
            print("baseline is red: fix the tests before mutating", file=sys.stderr)
            return 1
        survived = 0
        for rule, edits, expect in entries:
            if wanted and rule not in wanted:
                continue
            originals = {}
            for fname, old, new in edits:
                path = os.path.join(mod, fname)
                originals.setdefault(fname, open(path, encoding="utf-8").read())
                text = open(path, encoding="utf-8").read()
                if old not in text:
                    print(f"SETUP {rule}: the mutation anchor is not in {fname} (update the mutation table)", file=sys.stderr)
                    return 2
                open(path, "w", encoding="utf-8").write(text.replace(old, new))
            try:
                code, out = _run_tests(mod, test_filter)
            finally:
                for fname, text in originals.items():
                    open(os.path.join(mod, fname), "w", encoding="utf-8").write(text)
            if code != 0 and any(_norm(alt) in out for alt in expect.split("|||")):
                print(f"caught   {rule}")
            else:
                survived += 1
                why = "stayed green" if code == 0 else f"failed, but without the message '{expect}'"
                print(f"SURVIVED {rule}: {why}", file=sys.stderr)
        print("mutation-check: all mutations caught" if survived == 0 else f"mutation-check: {survived} mutation(s) survived")
        return 0 if survived == 0 else 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
