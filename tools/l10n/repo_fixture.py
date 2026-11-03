"""A throwaway git repository for the tools' unit tests (not itself a test module)."""

import os
import shutil
import subprocess
import tempfile

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class TempRepo(object):
    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="l10n-test-repo-")
        self.git("init", "-q")
        self.git("checkout", "-q", "-b", "main")

    def git(self, *args):
        command = ["git", "-c", "user.name=L10n Test", "-c", "user.email=l10n-test@example.invalid",
                   "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + os.devnull,
                   "-c", "init.defaultBranch=main"] + list(args)
        result = subprocess.run(command, cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode != 0:
            raise AssertionError("git %s: %s" % (" ".join(args), result.stderr.decode("utf-8", "replace")))
        return result.stdout.decode("utf-8").strip()

    def path(self, relative):
        return os.path.join(self.root, relative)

    def write(self, relative, text):
        full = self.path(relative)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as handle:
            handle.write(text.encode("utf-8"))

    def remove(self, relative):
        os.remove(self.path(relative))

    def commit(self, message="change"):
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


def java_class(name, body):
    return "package com.jjricks.volt;\n\nfinal class %s {\n%s\n}\n" % (name, body)
