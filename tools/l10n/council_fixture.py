"""A throwaway copy of fixtures/council/ for the translation tools' tests (not itself a test module).

It adds res/font/saira_medium.ttf from the real repository, so widths and glyph coverage use the
font the app ships. No test reads a keys file or reaches the network: NoNetwork makes any real
urlopen call fail the test.
"""

import collections
import json
import os
import shutil
import tempfile
import unittest.mock
import urllib.request

import providers
import translib

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixtures", "council")
REAL_FONT = os.path.join(translib.REPO_ROOT, "res", "font", "saira_medium.ttf")


class FixtureRepo(object):
    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="l10n-council-")
        shutil.rmtree(self.root)
        shutil.copytree(FIXTURE, self.root)
        os.makedirs(os.path.join(self.root, "res", "font"))
        shutil.copy(REAL_FONT, os.path.join(self.root, "res", "font", "saira_medium.ttf"))
        self.paths = translib.Paths(self.root)

    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def write(self, relative, text):
        full = self.path(relative)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as handle:
            handle.write(text.encode("utf-8"))

    def read(self, relative):
        with open(self.path(relative), "rb") as handle:
            return handle.read().decode("utf-8")

    def tree_text(self):
        """Every file under the root as one string (for "no secret was written anywhere" checks)."""
        parts = []
        for directory, _, files in os.walk(self.root):
            for name in files:
                if name.endswith(".ttf"):
                    continue
                with open(os.path.join(directory, name), "rb") as handle:
                    parts.append(handle.read().decode("utf-8", "replace"))
        return "\n".join(parts)

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


def no_network(*args, **kwargs):
    raise AssertionError("a test tried to reach the network")


class NoNetwork(object):
    """Context manager: any real urllib request fails the test."""

    def __enter__(self):
        self.patches = [unittest.mock.patch.object(urllib.request, "urlopen", no_network),
                        unittest.mock.patch.object(providers, "_open", no_network)]
        for patch in self.patches:
            patch.start()
        return self

    def __exit__(self, *exc):
        for patch in reversed(self.patches):
            patch.stop()
        return False


# Spanish texts every fixture drafter can agree on (common area), and the ble area.
SPANISH = collections.OrderedDict([
    ("common_cancel", "Cancelar"),
    ("common_lock_waiting", "Esperando al patinete"),
    ("common_lock_waiting_short", "Esperando"),
    ("common_move_widget", "Mover %1$s a la posición %2$d"),
    ("common_battery_percent", "%1$s %%"),
    ("common_not_reported", "No informado"),
    ("common_scooters_skipped", collections.OrderedDict([
        ("one", "%1$d patinete omitido"), ("many", "%1$d de patinetes omitidos"),
        ("other", "%1$d patinetes omitidos")])),
    ("common_capture", "Guarda navee-owner-setup.json en Descargas"),
    ("common_disclaimer", "Volt no está afiliada a NAVEE"),
    ("common_alarm_on", "Alarma activada"),
    ("common_dont", "No borres el almacenamiento."),
])


# The real Responses API refuses text.format json_object unless the input itself mentions JSON; the
# instructions don't count. The fakes refuse the same way, so a prompt that says JSON only there fails here too.
OPENAI_JSON_INPUT_ERROR = (400, json.dumps({"error": {"message": "Response input messages must contain the word "
                                                      "'json' in some form to use 'text.format' of type "
                                                      "'json_object'."}}).encode("utf-8"))


def json_mode_without_json(payload):
    return ((payload.get("text") or {}).get("format") or {}).get("type") == "json_object" and \
        "json" not in str(payload.get("input", "")).lower()


# The token counts every fake reply reports, in each provider's shape.
OPENAI_USAGE = {"input_tokens": 100, "input_tokens_details": {"cached_tokens": 40}, "output_tokens": 20,
                "output_tokens_details": {"reasoning_tokens": 5}, "total_tokens": 120}
GOOGLE_USAGE = {"promptTokenCount": 90, "candidatesTokenCount": 15, "thoughtsTokenCount": 7, "totalTokenCount": 112}


class FakeTransport(object):
    """Answers provider requests from a table; records each request (headers included, for assertions)."""

    def __init__(self, openai_models=(), google_models=(), replies=None, errors=None):
        self.openai_models = list(openai_models)
        self.google_models = list(google_models)
        self.replies = replies or {}      # model id -> function(prompt text) -> dict reply
        self.errors = errors or {}        # url fragment -> (status, body bytes)
        self.requests = []
        self.openai_versions = []         # the version each next OpenAI response reports (default 2026-10-01)

    def send(self, method, url, headers, body):
        payload = json.loads(body.decode("utf-8")) if body else None
        self.requests.append((method, url, dict(headers), payload))
        for fragment, (status, data) in self.errors.items():
            if fragment in url:
                return status, data
        if url.startswith("https://api.openai.com/v1/models"):
            return 200, json.dumps({"data": [{"id": m} for m in self.openai_models]}).encode("utf-8")
        if url.startswith("https://generativelanguage.googleapis.com/v1beta/models?"):
            models = [{"name": "models/" + m, "supportedGenerationMethods": ["generateContent"]}
                      for m in self.google_models]
            models.append({"name": "models/text-embedding-9", "supportedGenerationMethods": ["embedContent"]})
            return 200, json.dumps({"models": models}).encode("utf-8")
        if url == "https://api.openai.com/v1/responses":
            if json_mode_without_json(payload):
                return OPENAI_JSON_INPUT_ERROR
            model = payload["model"]
            reply = self.replies[model](payload["input"])
            version = self.openai_versions.pop(0) if self.openai_versions else "2026-10-01"
            return 200, json.dumps({"model": model + "-" + version, "status": "completed", "output": [
                {"type": "message", "content": [{"type": "output_text", "text": json.dumps(reply)}]}],
                "usage": OPENAI_USAGE}).encode("utf-8")
        if ":generateContent" in url:
            model = url.split("/models/")[1].split(":")[0]
            reply = self.replies[model](payload["contents"][0]["parts"][0]["text"])
            return 200, json.dumps({"modelVersion": model + "-001", "candidates": [
                {"content": {"parts": [{"text": "```json\n%s\n```" % json.dumps(reply)}]}}],
                "usageMetadata": GOOGLE_USAGE}).encode("utf-8")
        return 404, b'{"error": {"message": "no such endpoint"}}'


def requested_keys(prompt):
    """The keys a draft prompt asks for."""
    body = prompt[prompt.index("["):]
    return [e["key"] for e in json.loads(body)]


def drafting_reply(texts, overrides=None):
    overrides = overrides or {}

    def reply(prompt):
        return {"strings": dict((k, overrides.get(k, texts[k])) for k in requested_keys(prompt) if k in texts)}
    return reply
