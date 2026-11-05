"""The council runner, with mocked HTTP only: no test reaches the network or reads a keys file."""

import contextlib
import email.message
import io
import json
import os
import re
import sys
import unittest
import unittest.mock
import urllib.error
import urllib.request
import urllib.response

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import providers  # noqa: E402
import translib  # noqa: E402

OPENAI_KEY = "sk-test-OPENAI-SECRET-0123456789abcdef"
GEMINI_KEY = "AIzaTEST-GEMINI-SECRET-0123456789abcdef"
# The owner's keys list these (2026-11-05), plus older and top-end ids that must not be chosen.
OPENAI_MODELS = ["gpt-4o", "gpt-4o-mini", "gpt-5", "gpt-5.1", "gpt-5.1-mini", "gpt-5.5", "gpt-6-luna", "gpt-6-astra",
                 "gpt-6.1-terra", "gpt-6.1-sol", "gpt-6.1-realtime", "gpt-6-audio-preview", "text-embedding-3-large"]
GOOGLE_MODELS = ["gemini-2.5-pro", "gemini-2.5-flash", "gemini-3-pro", "gemini-3.5-flash-lite", "gemini-3.7-flash",
                 "gemini-3.8-flash", "gemini-4-argon", "gemini-3.8-flash-preview-tts"]
BACK = {
    "common_lock_waiting": "Waiting for the scooter",
    "common_lock_waiting_short": "Waiting",
    "common_disclaimer": "Volt is not affiliated with NAVEE",
    "common_alarm_on": "Alarm activated",
}


def env():
    return unittest.mock.patch.dict(os.environ, {"OPENAI_API_KEY": OPENAI_KEY, "GEMINI_API_KEY": GEMINI_KEY})


class Base(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.paths = self.repo.paths
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        for area in ("common", "ble"):
            pack.write_pack(self.paths, pack.build_pack(self.paths, "es", area, measurer))
        self.network = cf.NoNetwork().__enter__()
        self.env = env()
        self.env.start()
        self.out, self.err = io.StringIO(), io.StringIO()

    def tearDown(self):
        self.env.stop()
        self.network.__exit__(None, None, None)
        self.repo.close()

    def transport(self, **replies):
        fake = cf.FakeTransport(OPENAI_MODELS, GOOGLE_MODELS, replies)
        previous = providers.set_transport(fake, sleep=lambda seconds: None)
        self.addCleanup(providers.set_transport, previous)
        return fake

    def main(self, *args):
        with contextlib.redirect_stdout(self.out), contextlib.redirect_stderr(self.err):
            return council.main(["--root", self.repo.root, "--no-java"] + list(args))

    def assert_no_secret(self, *texts):
        for text in texts + (self.out.getvalue(), self.err.getvalue(), self.repo.tree_text()):
            self.assertNotIn(OPENAI_KEY, text)
            self.assertNotIn(GEMINI_KEY, text)
            self.assertNotIn("SECRET", text)

    def sha(self, area="common"):
        return dict((e["key"], e["source_sha1"]) for e in pack.load_pack(self.paths, "es", area)["entries"])

    def claude_reply(self, texts, name="opus-context", model="claude-opus-5-5", redraft=False, src=None):
        """An agent's JSON reply; src (default: the current pack's) is what it copied from its prompt."""
        path = self.repo.path("reply-%s.json" % name)
        if src is None:
            sha = self.sha()
            src = dict((k, sha[k]) for k in texts if k in sha)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({"strings": texts, "src": src}, handle, ensure_ascii=False)
        args = ["import-claude", "--tag", "es", "--area", "common", "--name", name, "--model", model, "--from", path]
        return self.main(*(args + (["--redraft"] if redraft else [])))

    def backtranslation_prompt(self):
        """Write a back-translation prompt; returns its request id, as the agent reads it from the prompt."""
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--role", "backtranslate"), 0)
        text = self.repo.read("l10n/work/es/common/prompts/backtranslation.md")
        return re.search(r"Request id: `([0-9a-f]{40})`", text).group(1)

    def backtranslation_reply(self, request_id, texts=None, hashes=None, name="back"):
        """An agent's reply to request_id: texts (default BACK for the keys it showed) and, unless given,
        the target_sha1 of each shown text copied from the prompt's request."""
        shown = json.loads(self.repo.read(
            "l10n/work/es/common/prompts/backtranslation.request.%s.json" % request_id))["keys"]
        if texts is None:
            texts = dict((k, BACK[k]) for k in shown)
        if hashes is None:
            hashes = dict((k, shown[k]["target_sha1"]) for k in texts if k in shown)
        reply = {"request": request_id, "strings": texts, "target_sha1": hashes}
        path = self.repo.path("%s.json" % name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(reply, handle, ensure_ascii=False)
        return path

    def import_back(self, path, model="claude-haiku-5"):
        return self.main("import-backtranslation", "--tag", "es", "--area", "common", "--from", path, "--model", model)

    def stored_backs(self):
        return dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/backtranslation.jsonl")))

    def judge(self, rows):
        sha = self.sha()
        lines = []
        for row in rows:
            row = dict(row)
            row.setdefault("source_sha1", sha[row["key"]])
            row.setdefault("model", "claude-opus-5-5")
            row.setdefault("reason", "fits the screen")
            lines.append(json.dumps(row, ensure_ascii=False))
        self.repo.write("l10n/work/es/common/judge.jsonl", "\n".join(lines) + "\n")

    def confirm(self, key, **extra):
        """A judge's safety confirmation of the fixture's agreed text and its back-translation."""
        row = {"key": key, "verdict": "confirm", "confirm": dict((c, True) for c in council.SAFETY_CONFIRMATIONS),
               "backtranslation_meaning_kept": True, "text": cf.SPANISH[key], "back": BACK[key]}
        row.update(extra)
        return row

    def draft_all(self, google_overrides=None, claude_overrides=None, skip_claude=False):
        google_texts = dict(cf.SPANISH, common_move_widget="Mueve %1$s al hueco %2$d")
        google_texts.update(google_overrides or {})
        fake = self.transport(**{
            "gpt-6.1-terra": cf.drafting_reply(cf.SPANISH),
            "gemini-3.8-flash": cf.drafting_reply(google_texts),
            "gemini-3.7-flash": lambda prompt: {"strings": dict((k, BACK[k]) for k in cf.requested_keys(prompt))}})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common"), 0)
        self.assertEqual(self.main("draft", "google", "--tag", "es", "--area", "common"), 0)
        if not skip_claude:
            claude = dict(cf.SPANISH, common_not_reported="Sin informar")
            claude.update(claude_overrides or {})
            self.assertEqual(self.claude_reply(claude), 0)
        return fake

    def decisions(self, area="common"):
        return dict((r["key"], r) for r in translib.read_jsonl(self.paths.decisions("es", area)))


class ModelChoiceTest(Base):
    def test_choose_from_the_model_list(self):
        fake = self.transport()
        openai = providers.list_models("openai")
        google = providers.list_models("google")
        self.assertNotIn("text-embedding-9", google)
        # The owner's choice of 2026-11-05: modest current models, not the top end.
        self.assertEqual(providers.choose_model("openai", "draft", openai), "gpt-6.1-terra")
        self.assertEqual(providers.choose_model("google", "draft", google), "gemini-3.8-flash")
        self.assertEqual(providers.choose_model("openai", "backtranslate", openai), "gpt-6.1-sol")
        # The back-translator never drafted: each falls back to a model that did not.
        self.assertEqual(providers.choose_model("openai", "backtranslate", openai, avoid={"gpt-6.1-sol"}),
                         "gpt-6.1-terra")
        self.assertEqual(providers.choose_model("google", "backtranslate", google, avoid={"gemini-3.8-flash"}),
                         "gemini-3.7-flash")
        self.assertEqual(providers.choose_model("google", "backtranslate", google,
                                                avoid={"gemini-3.8-flash", "gemini-3.7-flash", "gemini-2.5-flash"}),
                         "gemini-3.5-flash-lite")
        with self.assertRaises(providers.ProviderError):  # never a top-end or older line instead
            providers.choose_model("openai", "backtranslate", openai, avoid={"gpt-6.1-sol", "gpt-6.1-terra"})
        # A newer version of the same line is chosen when it appears; a top-end line never is.
        self.assertEqual(providers.choose_model("openai", "draft", openai + ["gpt-6.2-terra", "gpt-7-astra"]),
                         "gpt-6.2-terra")
        self.assertEqual(providers.choose_model("openai", "backtranslate", openai + ["gpt-6.2-sol"]), "gpt-6.2-sol")
        self.assertEqual(providers.choose_model("google", "draft", google + ["gemini-3.9-flash", "gemini-4-pro",
                                                                             "gemini-4.1-argon"]), "gemini-3.9-flash")
        self.assertEqual(providers.choose_model("openai", "draft", openai, prefer=[r"^gpt-4o$"]), "gpt-4o")
        with self.assertRaises(providers.ProviderError):
            providers.choose_model("openai", "draft", ["gpt-5.1-realtime", "whisper-1"])
        # Each provider's key goes only in its own header, never in a URL.
        for method, url, headers, _ in fake.requests:
            self.assertNotIn(OPENAI_KEY, url)
            self.assertNotIn(GEMINI_KEY, url)
            if "openai" in url:
                self.assertEqual(headers["Authorization"], "Bearer " + OPENAI_KEY)
                self.assertNotIn("x-goog-api-key", headers)
            else:
                self.assertEqual(headers["x-goog-api-key"], GEMINI_KEY)
                self.assertNotIn("Authorization", headers)
        self.assertEqual(self.main("models", "openai"), 0)
        self.assertIn("draft: gpt-6.1-terra", self.out.getvalue())
        self.assertIn("backtranslate: gpt-6.1-sol", self.out.getvalue())
        self.assert_no_secret()


class SecretHandlingTest(Base):
    def test_http_error_echoing_the_key_is_redacted(self):
        fake = self.transport()
        fake.errors["/models"] = (401, json.dumps({"error": {"message": "Incorrect API key provided: %s. Sent "
                                                             "Authorization: Bearer %s, x-goog-api-key: %s" % (
                                                                 OPENAI_KEY, OPENAI_KEY, GEMINI_KEY)}}).encode())
        self.assertEqual(self.main("models", "openai"), 2)
        self.assertIn("HTTP 401", self.err.getvalue())
        self.assertIn("[redacted]", self.err.getvalue())
        self.assert_no_secret()

    def test_transport_exception_is_reduced(self):
        class Exploding(object):
            def send(self, method, url, headers, body):
                raise RuntimeError("failed with headers %r" % headers)
        previous = providers.set_transport(Exploding())
        self.addCleanup(providers.set_transport, previous)
        with self.assertRaises(providers.ProviderError) as caught:
            providers.list_models("google")
        self.assertNotIn(GEMINI_KEY, str(caught.exception))
        self.assertIsNone(caught.exception.__cause__)
        self.assertTrue(caught.exception.__suppress_context__)

    def test_urllib_transport_errors(self):
        transport = providers.UrllibTransport()

        def refuse(request, timeout=None):
            raise urllib.error.URLError("proxy rejected key=%s" % OPENAI_KEY)
        with unittest.mock.patch.object(providers, "_open", refuse):
            with self.assertRaises(providers.ProviderError) as caught:
                transport.send("GET", "https://api.openai.com/v1/models", {"Authorization": "Bearer " + OPENAI_KEY}, None)
        self.assertIn("URLError", str(caught.exception))
        self.assertNotIn(OPENAI_KEY, str(caught.exception))

        def http_error(request, timeout=None):
            raise urllib.error.HTTPError(request.full_url, 403, "Forbidden", {}, io.BytesIO(b'{"error": "denied"}'))
        with unittest.mock.patch.object(providers, "_open", http_error):
            status, data = transport.send("GET", "https://api.openai.com/v1/models", {}, None)
        self.assertEqual((status, data), (403, b'{"error": "denied"}'))

    def test_redirects_are_not_followed(self):
        """A 30x must not carry the key's header to the Location (another host, or plain http)."""
        seen = []

        class Redirecting(urllib.request.BaseHandler):
            handler_order = 100  # before urllib's own http handler: nothing leaves the process

            def fake_open(self, request):
                seen.append((request.full_url, request.get_header("Authorization")))
                if len(seen) > 1:
                    return self.response(request, 200, "OK", b"{}", {})
                return self.response(request, 302, "Found", b'{"error": "moved"}',
                                     {"Location": "http://other.invalid/x", "Content-Type": "application/json"})

            @staticmethod
            def response(request, code, msg, body, fields):
                headers = email.message.Message()
                for name, value in fields.items():
                    headers[name] = value
                response = urllib.response.addinfourl(io.BytesIO(body), headers, request.full_url, code)
                response.msg = msg
                return response

            def http_open(self, request):
                return self.fake_open(request)

            def https_open(self, request):
                return self.fake_open(request)

        # Plain urllib follows the redirect and copies the header to the other host.
        seen[:] = []
        urllib.request.build_opener(Redirecting()).open(
            urllib.request.Request("https://api.invalid/v1/models", headers={"Authorization": "Bearer " + OPENAI_KEY}))
        self.assertEqual(seen, [("https://api.invalid/v1/models", "Bearer " + OPENAI_KEY),
                                ("http://other.invalid/x", "Bearer " + OPENAI_KEY)])
        # The council's opener stops at the 302.
        seen[:] = []
        opener = providers.build_opener(Redirecting())
        with unittest.mock.patch.object(providers, "_open", lambda request, timeout: opener.open(request, timeout=timeout)):
            status, data = providers.UrllibTransport().send(
                "GET", "https://api.invalid/v1/models", {"Authorization": "Bearer " + OPENAI_KEY}, None)
        self.assertEqual(status, 302)
        self.assertEqual(seen, [("https://api.invalid/v1/models", "Bearer " + OPENAI_KEY)])

    def test_missing_key_names_the_variable_only(self):
        self.transport()
        with unittest.mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
            self.assertEqual(self.main("models", "google"), 2)
        self.assertIn("GEMINI_API_KEY is not set", self.err.getvalue())

    def test_redact(self):
        text = "Bearer abc.def x-goog-api-key: AIzaSyD-0123456789012345678901 key=hunter2 sk-proj-abcdefghij"
        cleaned = providers.redact(text)
        for secret in ("abc.def", "AIzaSyD", "hunter2", "sk-proj-abcdefghij"):
            self.assertNotIn(secret, cleaned)
        self.assertEqual(providers.redact("model gpt-6.1-terra is fine"), "model gpt-6.1-terra is fine")

    def test_tools_never_name_the_keys_file(self):
        here = os.path.dirname(os.path.abspath(__file__))
        for name in ("providers.py", "council.py", "pack.py", "check.py", "merge.py", "translib.py"):
            with open(os.path.join(here, name), encoding="utf-8") as handle:
                source = handle.read()
            self.assertNotIn("keys.env", source, name)
            self.assertNotIn("volt-council", source, name)
            self.assertNotIn("print(os.environ", source, name)


class DraftingTest(Base):
    def test_api_drafts_record_exact_models(self):
        fake = self.transport(**{"gpt-6.1-terra": cf.drafting_reply(cf.SPANISH), "gemini-3.8-flash": cf.drafting_reply(cf.SPANISH)})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common"), 0)
        self.assertEqual(self.main("draft", "google", "--tag", "es", "--area", "common", "--style", "glossary-first"), 0)
        openai = translib.read_draft(self.repo.path("l10n/work/es/common/drafts/openai.xml"))
        google = translib.read_draft(self.repo.path("l10n/work/es/common/drafts/google.xml"))
        self.assertEqual((openai.model, openai.model_reported), ("gpt-6.1-terra", "gpt-6.1-terra-2026-10-01"))
        self.assertEqual((google.model, google.model_reported, google.prompt), ("gemini-3.8-flash", "gemini-3.8-flash-001",
                                                                                "glossary-first"))
        self.assertEqual(openai.entries["common_scooters_skipped"].items["many"], "%1$d de patinetes omitidos")
        self.assertEqual(len(openai.entries), len(cf.SPANISH))
        posts = [(url, payload) for method, url, _, payload in fake.requests if method == "POST"]
        self.assertEqual(len(posts), 2)
        responses = [p for u, p in posts if u.endswith("/responses")][0]
        self.assertEqual(responses["text"], {"format": {"type": "json_object"}})
        self.assertFalse(responses["store"])
        self.assertIn("tú", responses["instructions"])
        self.assertIn("Bloq.", responses["instructions"])  # the style guide
        # Only UI strings and their context reach a model: no source sha, status or pack internals.
        sent = json.loads(responses["input"][responses["input"].index("["):])
        self.assertEqual(set().union(*[set(e) for e in sent]) - set(council.MODEL_FIELDS), set())
        self.assertNotIn("volt-internal-id", responses["input"])
        runs = translib.read_jsonl(self.repo.path("l10n/work/es/common/runs.jsonl"))
        self.assertEqual([(r["provider"], r["model"], r["model_reported"]) for r in runs],
                         [("openai", "gpt-6.1-terra", "gpt-6.1-terra-2026-10-01"), ("google", "gemini-3.8-flash", "gemini-3.8-flash-001")])
        self.assert_no_secret()

    def test_only_changed_strings_are_drafted_with_their_ladder(self):
        def current(key, english, text):
            return '    <!-- src:%s -->\n    <string name="%s">%s</string>\n' % (
                translib.source_sha1("string", {"other": english}), key, text)
        self.repo.write("res/values-es/strings_common.xml", "<resources>\n" + "".join([
            current("common_cancel", "Cancel", "Cancelar"),
            current("common_lock_waiting", "Waiting for the scooter", "Esperando al patinete"),
            current("common_lock_waiting_short", "Waiting (old English)", "Esperando")]) + "</resources>\n")
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        p = pack.build_pack(self.paths, "es", "common", measurer)
        keys = [e["key"] for e in council.needed_entries(p)]
        self.assertNotIn("common_cancel", keys)
        self.assertIn("common_lock_waiting_short", keys)  # stale
        self.assertIn("common_lock_waiting", keys)        # current, but its rung changed
        self.assertIn("common_cancel", [e["key"] for e in council.needed_entries(p, include_current=True)])

    def test_unknown_model_is_refused(self):
        self.transport()
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common", "--model", "gpt-9"), 2)
        self.assertIn("not in the openai model list", self.err.getvalue())

    def test_redraft_sends_only_failed_keys_once(self):
        texts = dict(cf.SPANISH, common_move_widget="Mover %1$s")
        fake = self.transport(**{"gpt-6.1-terra": cf.drafting_reply(texts)})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common"), 0)
        self.assertEqual(self.main("check", "--tag", "es", "--area", "common"), 1)
        fake.replies["gpt-6.1-terra"] = cf.drafting_reply(cf.SPANISH)
        self.assertEqual(self.main("redraft", "openai", "--tag", "es", "--area", "common"), 0)
        last = fake.requests[-1][3]
        self.assertEqual(cf.requested_keys(last["input"]), ["common_move_widget"])
        self.assertIn("failed_checks", last["input"])
        draft = translib.read_draft(self.repo.path("l10n/work/es/common/drafts/openai.xml"))
        self.assertEqual(draft.entries["common_move_widget"].attempt, 2)
        self.assertEqual(draft.entries["common_cancel"].attempt, 1)
        # Keys the redraft did not touch keep the src they were drafted with.
        sha = self.sha()
        self.assertEqual(draft.entries["common_cancel"].src, sha["common_cancel"])
        self.assertEqual(draft.entries["common_move_widget"].src, sha["common_move_widget"])
        self.assertEqual(self.main("check", "--tag", "es", "--area", "common"), 0)
        # A failed second attempt is not sent back again.
        self.repo.write("l10n/work/es/common/checks.jsonl", json.dumps(
            {"draft": "openai", "key": "common_cancel", "attempt": 2, "ok": False, "findings": []}) + "\n")
        self.assertEqual(self.main("redraft", "openai", "--tag", "es", "--area", "common"), 0)
        self.assertIn("no failed first attempts", self.out.getvalue())


class ClaudeImportTest(Base):
    def test_prompt_and_json_import(self):
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", "opus-context",
                                   "--style", "context-first"), 0)
        prompt = self.repo.read("l10n/work/es/common/prompts/claude-opus-context.md")
        self.assertIn("import-claude --tag es --area common --name opus-context", prompt)
        self.assertIn("Work alone", prompt)
        self.assertIn("common_lock_waiting_short", prompt)
        self.assertNotIn("source_sha1", prompt)
        # Each string carries its src for the agent to copy back (a draft's provenance).
        self.assertIn('"src": "%s"' % self.sha()["common_cancel"], prompt)
        self.assertIn("Copy every one unchanged", prompt)
        self.assertEqual(self.claude_reply(dict(cf.SPANISH)), 0)
        draft = translib.read_draft(self.repo.path("l10n/work/es/common/drafts/claude-opus-context.xml"))
        self.assertEqual((draft.provider, draft.model), ("claude", "claude-opus-5-5"))
        self.assertEqual(draft.entries["common_dont"].items["other"], "No borres el almacenamiento.")

    def test_agent_written_xml_is_validated(self):
        self.repo.write("l10n/work/es/common/drafts/claude-haiku.xml",
                        '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n" volt:provider="claude" '
                        'volt:model="claude-haiku-5">\n  <string name="common_cancel">Cancelar</string>\n'
                        '  <string name="common_unknown">x</string>\n'
                        '  <string name="common_scooters_skipped">%1$d patinetes</string>\n</resources>\n')
        self.assertEqual(self.main("import-claude", "--tag", "es", "--area", "common"), 1)
        self.assertIn("common_unknown is not in the pack", self.out.getvalue())
        self.assertIn("common_scooters_skipped: a string where English has a plural", self.out.getvalue())
        self.repo.write("l10n/work/es/common/drafts/claude-haiku.xml",
                        '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n" volt:model="gpt-6.1-terra">\n'
                        '  <string name="common_cancel">Cancelar</string>\n</resources>\n')
        self.assertEqual(self.main("import-claude", "--tag", "es", "--area", "common"), 1)
        self.assertIn("not a Claude model id", self.out.getvalue())


class CouncilFlowTest(Base):
    """Three providers draft; the checks, back-translation, judge and decisions follow plan 7.2."""

    def test_full_flow(self):
        fake = self.draft_all()
        self.main("check", "--tag", "es", "--area", "common")
        self.assertEqual(self.main("backtranslate", "google", "--tag", "es", "--area", "common"), 0)
        # The back-translator is a model that drafted nothing.
        backs = translib.read_jsonl(self.repo.path("l10n/work/es/common/backtranslation.jsonl"))
        self.assertEqual(sorted(r["key"] for r in backs), sorted(BACK))
        self.assertEqual(set(r["model"] for r in backs), {"gemini-3.7-flash"})
        back_prompt = fake.requests[-1][3]["contents"][0]["parts"][0]["text"]
        self.assertNotIn("Waiting for the scooter", back_prompt)  # it never sees the English
        # Without the judge, the run is incomplete and nothing is written.
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("waiting for", self.out.getvalue())
        self.assertFalse(os.path.exists(self.paths.decisions("es", "common")))

        self.assertEqual(self.main("judge-requests", "--tag", "es", "--area", "common"), 0)
        requests = dict((r["key"], r) for r in translib.read_jsonl(self.repo.path("l10n/work/es/common/judge-requests.jsonl")))
        self.assertEqual(requests["common_move_widget"]["reason"], "disagreement")
        self.assertEqual(requests["common_not_reported"]["reason"], "disagreement")
        self.assertEqual(requests["common_lock_waiting"]["reason"], "safety-confirmation")
        self.assertEqual(requests["common_lock_waiting_short"]["safety"], ["lock"])  # inherited from its full key
        self.assertEqual(requests["common_alarm_on"]["backtranslation"]["back"], "Alarm activated")
        self.assertNotIn("common_cancel", requests)
        self.assertIn("judge.jsonl", self.repo.read("l10n/work/es/common/prompts/judge.md"))

        yes = dict((c, True) for c in council.SAFETY_CONFIRMATIONS)
        self.judge([
            {"key": "common_move_widget", "verdict": "pick", "pick": "openai",
             "text": "Mover %1$s a la posición %2$d"},
            {"key": "common_not_reported", "verdict": "merge", "text": "No informado"},
            self.confirm("common_lock_waiting"),
            self.confirm("common_lock_waiting_short"),
            self.confirm("common_alarm_on", confirm=dict(yes, on_off_never_always_not_inverted=False)),
            {"key": "common_disclaimer", "verdict": "fallback", "reason": "softened"},
        ])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        d = self.decisions()
        self.assertEqual(d["common_cancel"]["outcome"], "agreed")
        self.assertEqual(d["common_cancel"]["text"], "Cancelar")
        self.assertEqual(d["common_move_widget"]["outcome"], "judged")
        self.assertEqual(d["common_move_widget"]["text"], "Mover %1$s a la posición %2$d")
        self.assertEqual(d["common_not_reported"]["outcome"], "judged")
        self.assertEqual(d["common_lock_waiting"]["outcome"], "safety-agreed")
        self.assertEqual(d["common_lock_waiting"]["models"], {
            "drafters": ["claude-opus-5-5", "gemini-3.8-flash", "gpt-6.1-terra"], "judge": "claude-opus-5-5",
            "backtranslator": "gemini-3.7-flash"})
        self.assertEqual(d["common_lock_waiting"]["backtranslation"]["back"], "Waiting for the scooter")
        self.assertEqual(d["common_alarm_on"]["outcome"], "fallback-english")
        self.assertIn("on_off_never_always_not_inverted", d["common_alarm_on"]["reason"])
        self.assertEqual(d["common_disclaimer"]["outcome"], "fallback-english")
        self.assertEqual(d["common_scooters_skipped"]["text"]["many"], "%1$d de patinetes omitidos")
        alternatives = d["common_move_widget"]["alternatives"]
        self.assertEqual(sorted(a["draft"] for a in alternatives), ["claude-opus-context", "google", "openai"])
        self.assertEqual([a["model_reported"] for a in alternatives if a["draft"] == "openai"], ["gpt-6.1-terra-2026-10-01"])
        for row in d.values():
            self.assertEqual(row["format"], council.DECISION_FORMAT)
            self.assertEqual(len(row["source_sha1"]), 40)
        self.assert_no_secret()

    def test_safety_needs_every_drafter_to_agree(self):
        self.draft_all(google_overrides={"common_lock_waiting": "Esperando el patinete"})
        self.main("backtranslate", "google", "--tag", "es", "--area", "common")
        yes = dict((c, True) for c in council.SAFETY_CONFIRMATIONS)
        self.judge([self.confirm(k) for k in ("common_lock_waiting_short", "common_alarm_on", "common_disclaimer")] +
                   [{"key": "common_move_widget", "verdict": "pick", "pick": "google",
                     "text": "Mueve %1$s al hueco %2$d"},
                    {"key": "common_not_reported", "verdict": "pick", "pick": "openai", "text": "No informado"}])
        # Since 2026-11-05 the judge may pick or merge a disagreeing safety key, so it waits for that ruling.
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("common_lock_waiting: waiting for the judge's pick or merge", self.out.getvalue())
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common", "--final"), 0)
        d = self.decisions()
        self.assertEqual(d["common_lock_waiting"]["outcome"], "fallback-english")
        self.assertIn("disagree", d["common_lock_waiting"]["reason"])
        self.assertIn("not judged", d["common_lock_waiting"]["reason"])
        # Its short rung was confirmed, but a half-translated ladder falls back as a whole.
        self.assertEqual(d["common_lock_waiting_short"]["outcome"], "fallback-english")
        self.assertIn("ladder group incomplete", d["common_lock_waiting_short"]["reason"])
        self.assertEqual(d["common_alarm_on"]["outcome"], "safety-agreed")

    def test_safety_needs_all_three_providers(self):
        self.draft_all(skip_claude=True)
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()
        self.assertEqual(d["common_alarm_on"]["outcome"], "fallback-english")
        self.assertIn("no draft from claude", d["common_alarm_on"]["reason"])
        # Two agreeing drafts are enough for an ordinary key.
        self.assertEqual(d["common_cancel"]["outcome"], "agreed")
        # --final lets a key still waiting for the judge fall back, and says so.
        self.assertIn("final run", d["common_move_widget"]["reason"])

    def test_backtranslator_must_not_have_drafted(self):
        self.draft_all()
        path = self.backtranslation_reply(self.backtranslation_prompt())
        self.assertEqual(self.import_back(path, "claude-opus-5-5"), 2)
        self.assertIn("model that drafted nothing", self.err.getvalue())
        self.assertEqual(self.import_back(path), 0)
        rows = translib.read_jsonl(self.repo.path("l10n/work/es/common/backtranslation.jsonl"))
        self.assertEqual(set(r["model"] for r in rows), {"claude-haiku-5"})
        # A back-translation by a drafter that is already on file does not count.
        self.repo.write("l10n/work/es/common/backtranslation.jsonl", "".join(
            json.dumps(dict(r, model="gpt-6.1-terra")) + "\n" for r in rows))
        self.judge([self.confirm("common_alarm_on")])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertIn("also drafted", self.decisions()["common_alarm_on"]["reason"])

    def test_safety_confirmation_names_the_text_and_back_translation(self):
        """An old confirm must not carry over to a new (here inverted) text with a fresh back-translation."""
        self.draft_all()
        self.assertEqual(self.main("backtranslate", "google", "--tag", "es", "--area", "common"), 0)
        self.judge([self.confirm("common_alarm_on")])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common", "--final"), 0)
        self.assertEqual(self.decisions()["common_alarm_on"]["outcome"], "safety-agreed")
        self.assertEqual(self.decisions()["common_alarm_on"]["judge"]["text"], "Alarma activada")
        # Every drafter now writes an inverted text the word checks cannot see ("Alarma desactivada"
        # is caught by the state check, so this uses "silenced"), with a fresh, faithful back-translation.
        for name in ("openai", "google"):
            path = "l10n/work/es/common/drafts/%s.xml" % name
            self.repo.write(path, self.repo.read(path).replace("Alarma activada", "Alarma silenciada"))
        self.assertEqual(self.claude_reply(dict(cf.SPANISH, common_not_reported="Sin informar",
                                                common_alarm_on="Alarma silenciada")), 0)
        path = "l10n/work/es/common/backtranslation.jsonl"
        rows = translib.read_jsonl(self.repo.path(path))
        rows = [dict(r, target="Alarma silenciada", back="Alarm silenced") if r["key"] == "common_alarm_on" else r
                for r in rows]
        translib.write_jsonl(self.repo.path(path), rows)
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("common_alarm_on: waiting for the judge's confirmation", self.out.getvalue())
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()["common_alarm_on"]
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIsNone(d["text"])
        self.assertIn("confirmed another text", d["reason"])
        # The judge-request now carries the new text and back-translation for a new ruling.
        self.main("judge-requests", "--tag", "es", "--area", "common")
        requests = dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/judge-requests.jsonl")))
        self.assertEqual(requests["common_alarm_on"]["text"], "Alarma silenciada")
        self.assertEqual(requests["common_alarm_on"]["backtranslation"]["back"], "Alarm silenced")
        # A confirm that names the right text but another back-translation does not count either.
        self.judge([self.confirm("common_alarm_on", text="Alarma silenciada")])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertEqual(self.decisions()["common_alarm_on"]["outcome"], "fallback-english")
        # A confirm without the texts (the old format) confirms nothing.
        self.judge([dict(self.confirm("common_alarm_on"), text=None, back=None)])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertEqual(self.decisions()["common_alarm_on"]["outcome"], "fallback-english")

    def test_safety_keys_wait_for_a_back_translation_before_the_judge(self):
        self.draft_all()
        self.assertEqual(self.main("judge-requests", "--tag", "es", "--area", "common"), 0)
        requests = dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/judge-requests.jsonl")))
        self.assertNotIn("common_alarm_on", requests)
        self.assertIn("common_move_widget", requests)
        self.main("backtranslate", "google", "--tag", "es", "--area", "common")
        self.main("judge-requests", "--tag", "es", "--area", "common")
        requests = dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/judge-requests.jsonl")))
        self.assertEqual(requests["common_alarm_on"]["text"], "Alarma activada")
        self.assertEqual(requests["common_alarm_on"]["backtranslation"]["back"], "Alarm activated")

    def test_judge_merge_is_checked_again(self):
        self.draft_all()
        self.judge([{"key": "common_move_widget", "verdict": "merge", "text": "Mover %1$s"},
                    {"key": "common_not_reported", "verdict": "pick", "pick": "nobody"}])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()
        self.assertEqual(d["common_move_widget"]["outcome"], "fallback-english")
        self.assertIn("failed the checks", d["common_move_widget"]["reason"])
        self.assertEqual(d["common_not_reported"]["outcome"], "fallback-english")

    def test_stale_judge_rulings_are_ignored(self):
        self.draft_all()
        self.judge([{"key": "common_move_widget", "verdict": "pick", "pick": "openai", "source_sha1": "0" * 40}])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("common_move_widget: waiting for a judge ruling", self.out.getvalue())


class ProvenanceTest(Base):
    """Codex's PR #152 repros: a draft or a pick ruling must stay bound to what it was made from."""

    KEY = "common_backup_action"
    DRAFTS = "l10n/work/es/common/drafts/"

    def set_english(self, english):
        """Put KEY into the fixture's English with this text, and regenerate the common pack."""
        path = "res/values/strings_common.xml"
        text = self.repo.read(path)
        line = '    <string name="%s" volt:screen="Backup">' % self.KEY
        if line in text:
            start = text.index(line)
            text = text[:start] + text[text.index("\n", start) + 1:]
        text = text.replace("</resources>", "%s%s</string>\n</resources>" % (line, english))
        self.repo.write(path, text)
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        pack.write_pack(self.paths, pack.build_pack(self.paths, "es", "common", measurer))

    def draft(self, openai_text, google_text):
        self.transport(**{"gpt-6.1-terra": cf.drafting_reply(dict(cf.SPANISH, **{self.KEY: openai_text})),
                          "gemini-3.8-flash": cf.drafting_reply(dict(cf.SPANISH, **{self.KEY: google_text}))})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common"), 0)
        self.assertEqual(self.main("draft", "google", "--tag", "es", "--area", "common"), 0)

    def merge(self):
        import merge
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = merge.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--aapt", "off"])
        return code, out.getvalue()

    def test_drafts_record_the_english_they_translated(self):
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar copia")
        sha = self.sha()[self.KEY]
        for name in ("openai", "google"):
            draft = translib.read_draft(self.repo.path(self.DRAFTS + name + ".xml"))
            self.assertEqual(draft.entries[self.KEY].src, sha)
        self.assertIn('volt:src="%s"' % sha, self.repo.read(self.DRAFTS + "openai.xml"))
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        self.assertEqual(self.decisions()[self.KEY]["outcome"], "agreed")
        self.assertEqual(self.decisions()[self.KEY]["stale_drafts"], [])

    def test_stale_drafts_are_not_relabelled_with_new_english(self):
        """Repro 1: Save backup -> Guardar copia twice; English becomes Restore backup; drafts kept."""
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar copia")
        old_sha = self.sha()[self.KEY]
        self.set_english("Restore backup")
        new_sha = self.sha()[self.KEY]
        self.assertNotEqual(old_sha, new_sha)
        # check marks the stale entries failed (so a redraft sends them back)...
        self.assertEqual(self.main("check", "--tag", "es", "--area", "common"), 1)
        rows = [r for r in translib.read_jsonl(self.repo.path("l10n/work/es/common/checks.jsonl"))
                if r["key"] == self.KEY]
        self.assertEqual(sorted(r["draft"] for r in rows), ["google", "openai"])
        for r in rows:
            self.assertFalse(r["ok"])
            self.assertEqual((r["draft_src"], r["source_sha1"]), (old_sha, new_sha))
            self.assertEqual([f["check"] for f in r["findings"] if f["severity"] == "error"], ["source"])
        # ...and decide never agrees them under the new hash: the key falls back, and says why.
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        d = self.decisions()[self.KEY]
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIsNone(d["text"])
        self.assertEqual(d["english"], "Restore backup")
        self.assertEqual(d["source_sha1"], new_sha)
        self.assertIn("no current drafts", d["reason"])
        self.assertIn("drafted from other English", d["reason"])
        self.assertEqual(sorted(s["draft"] for s in d["stale_drafts"]), ["google", "openai"])
        self.assertEqual(set(s["src"] for s in d["stale_drafts"]), {old_sha})
        self.assertEqual(d["alternatives"], [])
        # The merged folder leaves the key out (English shows), and validates.
        code, out = self.merge()
        self.assertEqual(code, 0, out)
        self.assertNotIn(self.KEY, self.repo.read("res/values-es/strings_common.xml"))
        self.assertNotIn("Guardar copia", self.repo.read("res/values-es/strings_common.xml"))

    def test_stale_and_unstamped_claude_drafts_are_ignored(self):
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar copia")
        old_sha = self.sha()[self.KEY]
        self.set_english("Restore backup")
        # A Claude reply that copied the old src, or none, is stored as stale: never stamped current.
        self.assertEqual(self.claude_reply(dict(cf.SPANISH, **{self.KEY: "Guardar copia"}),
                                           src=dict(self.sha(), **{self.KEY: old_sha})), 1)
        self.assertIn("%s: src %s is not the current English's" % (self.KEY, old_sha[:12]), self.out.getvalue())
        draft = translib.read_draft(self.repo.path(self.DRAFTS + "claude-opus-context.xml"))
        self.assertEqual(draft.entries[self.KEY].src, old_sha)
        self.assertEqual(self.claude_reply(dict(cf.SPANISH, **{self.KEY: "Guardar copia"}), name="sonnet",
                                           model="claude-sonnet-5", src={}), 1)
        self.assertIn("%s: no src copied from the prompt" % self.KEY, self.out.getvalue())
        self.assertIsNone(translib.read_draft(self.repo.path(self.DRAFTS + "claude-sonnet.xml")).entries[self.KEY].src)
        # Agent-written XML without volt:src fails validation.
        self.repo.write(self.DRAFTS + "claude-haiku.xml",
                        '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n" volt:provider="claude" '
                        'volt:model="claude-haiku-5">\n  <string name="%s">Guardar copia</string>\n</resources>\n'
                        % self.KEY)
        self.assertEqual(self.main("import-claude", "--tag", "es", "--area", "common", "--name", "haiku"), 1)
        self.assertIn("%s: no volt:src" % self.KEY, self.out.getvalue())
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()[self.KEY]
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertEqual(sorted(s["draft"] for s in d["stale_drafts"]),
                         ["claude-haiku", "claude-opus-context", "claude-sonnet", "google", "openai"])
        # Agent-written XML with the current src validates and counts.
        self.repo.write(self.DRAFTS + "claude-haiku.xml",
                        '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n" volt:provider="claude" '
                        'volt:model="claude-haiku-5">\n  <string name="%s" volt:src="%s">Restaurar copia</string>\n'
                        '</resources>\n' % (self.KEY, self.sha()[self.KEY]))
        self.out.truncate(0)
        self.assertEqual(self.main("import-claude", "--tag", "es", "--area", "common", "--name", "haiku"), 0,
                         self.out.getvalue())

    def test_fresh_replacement_drafts_agree(self):
        """Control for repro 1: drafts made again from the new English agree as usual."""
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar copia")
        self.set_english("Restore backup")
        self.draft("Restaurar copia", "Restaurar copia")
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        d = self.decisions()[self.KEY]
        self.assertEqual((d["outcome"], d["text"], d["english"]), ("agreed", "Restaurar copia", "Restore backup"))
        self.assertEqual(d["stale_drafts"], [])
        code, out = self.merge()
        self.assertEqual(code, 0, out)
        self.assertIn('<string name="%s">Restaurar copia</string>' % self.KEY,
                      self.repo.read("res/values-es/strings_common.xml"))

    def test_pick_ruling_is_bound_to_the_text_it_reviewed(self):
        """Repro 2: the judge picks openai's Guardar copia; openai is redrafted as Restaurar copia."""
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar respaldo")
        old_ruling = {"key": self.KEY, "verdict": "pick", "pick": "openai", "text": "Guardar copia",
                      "reason": "Guardar copia is the shorter, usual phrase for saving a backup"}
        self.judge([old_ruling])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        self.assertEqual((self.decisions()[self.KEY]["outcome"], self.decisions()[self.KEY]["text"]),
                         ("judged", "Guardar copia"))
        # Only openai's draft is replaced; the old ruling stays in judge.jsonl.
        self.transport(**{"gpt-6.1-terra": cf.drafting_reply(dict(cf.SPANISH, **{self.KEY: "Restaurar copia"}))})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common"), 0)
        self.out.truncate(0)
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("%s: waiting for a ruling on openai's current text" % self.KEY, self.out.getvalue())
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()[self.KEY]
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIsNone(d["text"])
        self.assertIn("another text", d["reason"])
        code, out = self.merge()
        self.assertEqual(code, 0, out)
        self.assertNotIn("Restaurar copia", self.repo.read("res/values-es/strings_common.xml"))
        # A pick without the text (the old format) binds nothing either.
        self.judge([dict(old_ruling, text=None)])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertEqual(self.decisions()[self.KEY]["outcome"], "fallback-english")
        # The judge request shows the new candidate; a fresh ruling naming its text counts (control).
        self.main("judge-requests", "--tag", "es", "--area", "common")
        requests = dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/judge-requests.jsonl")))
        self.assertIn("Restaurar copia", [c["text"] for c in requests[self.KEY]["candidates"]])
        self.assertIn("copy that candidate's text", self.repo.read("l10n/work/es/common/prompts/judge.md"))
        self.judge([dict(old_ruling, text="Restaurar copia", reason="hmm")])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 0)
        d = self.decisions()[self.KEY]
        self.assertEqual((d["outcome"], d["text"]), ("judged", "Restaurar copia"))
        self.assertEqual(d["judge"]["text"], "Restaurar copia")

    def test_pick_of_a_stale_draft_waits_for_a_new_ruling(self):
        self.set_english("Save backup")
        self.draft("Guardar copia", "Guardar respaldo")
        ruling = {"key": self.KEY, "verdict": "pick", "pick": "openai", "text": "Guardar copia"}
        self.set_english("Restore backup")
        # The old ruling is on older English and the drafts are stale: nothing is judged.
        self.judge([ruling])  # stamped with the new hash, as a careless resumed judge might
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        d = self.decisions()[self.KEY]
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIn("no current drafts", d["reason"])

    def set_alarm_text(self, old, new):
        for name in ("openai", "google", "claude-opus-context"):
            path = self.DRAFTS + name + ".xml"
            self.repo.write(path, self.repo.read(path).replace(old, new))

    def test_claude_backtranslation_is_bound_to_the_prompted_text(self):
        self.draft_all()
        path = self.backtranslation_reply(self.backtranslation_prompt())
        # Every drafter now writes another alarm text after the prompt was made.
        self.set_alarm_text("Alarma activada", "Alarma silenciada")
        self.out.truncate(0)
        self.assertEqual(self.import_back(path), 0)
        self.assertIn("common_alarm_on: the English or text changed since request", self.out.getvalue())
        rows = self.stored_backs()
        self.assertNotIn("common_alarm_on", rows)
        self.assertEqual(rows["common_disclaimer"]["target"], "Volt no está afiliada a NAVEE")

    def test_reply_to_an_earlier_prompt_is_not_bound_to_a_later_one(self):
        """Codex's repro: prompt A, the text changes, prompt B, then A's unchanged reply is imported."""
        self.draft_all()
        request_a = self.backtranslation_prompt()
        reply_a = self.backtranslation_reply(request_a, {"common_alarm_on": "Alarm activated"}, name="reply-a")
        self.set_alarm_text("Alarma activada", "Alarma silenciada")
        self.assertEqual(self.import_back(reply_a), 0)
        self.assertEqual(self.stored_backs(), {})
        request_b = self.backtranslation_prompt()
        self.assertNotEqual(request_a, request_b)
        # Prompt B did not overwrite A's record: the reply is still checked against what A showed.
        record_a = json.loads(self.repo.read(
            "l10n/work/es/common/prompts/backtranslation.request.%s.json" % request_a))
        self.assertEqual(record_a["keys"]["common_alarm_on"]["target"], "Alarma activada")
        self.out.truncate(0)
        self.assertEqual(self.import_back(reply_a), 0)
        self.assertIn("common_alarm_on: the English or text changed since request %s" % request_a[:12],
                      self.out.getvalue())
        self.assertEqual(self.stored_backs(), {})
        state = council.analyze(self.paths, pack.load_pack(self.paths, "es", "common"), use_java=False)
        self.assertIsNone(council.current_backtranslation(state["common_alarm_on"], None))
        # A's reply relabelled with B's id still carries the hash of A's text: refused whole.
        relabelled = self.backtranslation_reply(
            request_b, {"common_alarm_on": "Alarm activated"},
            {"common_alarm_on": record_a["keys"]["common_alarm_on"]["target_sha1"]}, name="relabelled")
        self.assertEqual(self.import_back(relabelled), 2)
        self.assertIn("common_alarm_on: target_sha1", self.err.getvalue())
        self.assertIn("nothing was stored", self.err.getvalue())
        self.assertEqual(self.stored_backs(), {})
        # Control: a fresh reply to B is stored for the new text and counts as current.
        reply_b = self.backtranslation_reply(request_b, {"common_alarm_on": "Alarm silenced"}, name="reply-b")
        self.assertEqual(self.import_back(reply_b), 0)
        back = self.stored_backs()["common_alarm_on"]
        self.assertEqual((back["target"], back["back"]), ("Alarma silenciada", "Alarm silenced"))
        state = council.analyze(self.paths, pack.load_pack(self.paths, "es", "common"), use_java=False)
        self.assertEqual(council.current_backtranslation(state["common_alarm_on"], back), back)

    def test_reply_with_a_wrong_target_hash_is_refused(self):
        self.draft_all()
        request_id = self.backtranslation_prompt()
        path = self.backtranslation_reply(request_id, hashes=dict((k, "0" * 40) for k in BACK))
        self.assertEqual(self.import_back(path), 2)
        self.assertIn("target_sha1 000000000000 is not the hash", self.err.getvalue())
        # A missing hash, or a key the request did not show, is refused the same way.
        shown = json.loads(self.repo.read(
            "l10n/work/es/common/prompts/backtranslation.request.%s.json" % request_id))["keys"]
        self.assertIn("common_alarm_on", shown)
        self.assertEqual(self.import_back(self.backtranslation_reply(request_id, hashes={})), 2)
        self.assertNotIn("common_cancel", shown)
        extra = dict(((k, BACK[k]) for k in shown), common_cancel="Cancel")
        self.assertEqual(self.import_back(self.backtranslation_reply(request_id, extra, name="extra")), 2)
        self.assertIn("common_cancel: not in request", self.err.getvalue())
        self.assertFalse(os.path.exists(self.repo.path("l10n/work/es/common/backtranslation.jsonl")))

    def test_reply_without_a_known_request_id_is_refused(self):
        self.draft_all()
        request_id = self.backtranslation_prompt()
        self.assertEqual(self.import_back(self.write_reply_without_id(request_id)), 2)
        self.assertIn("the reply has no request id", self.err.getvalue())
        unknown = self.write_reply_without_id(request_id, request="f" * 40)
        self.assertEqual(self.import_back(unknown), 2)
        self.assertIn("names request %s, which" % ("f" * 40), self.err.getvalue())
        traversal = self.write_reply_without_id(request_id, request="../../../../etc/passwd")
        self.assertEqual(self.import_back(traversal), 2)
        # An edited request record no longer matches its id.
        record = "l10n/work/es/common/prompts/backtranslation.request.%s.json" % request_id
        self.repo.write(record, self.repo.read(record).replace("Alarma activada", "Alarma silenciada"))
        self.assertEqual(self.import_back(self.backtranslation_reply(request_id, name="edited")), 2)
        self.assertIn("does not match its id", self.err.getvalue())
        self.assertFalse(os.path.exists(self.repo.path("l10n/work/es/common/backtranslation.jsonl")))

    def write_reply_without_id(self, request_id, request=None):
        path = self.backtranslation_reply(request_id, name="noid")
        with open(path, encoding="utf-8") as handle:
            reply = json.load(handle)
        del reply["request"]
        if request is not None:
            reply["request"] = request
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(reply, handle, ensure_ascii=False)
        return path


class SafetyJudgedTest(Base):
    """The owner's rule of 2026-11-05: a safety key whose drafts disagree may take the judge's picked or
    merged text, only once a model that drafted nothing back-translated exactly that text and the judge
    then confirmed that text and that back-translation."""

    KEY = "common_alarm_on"
    YES = dict((c, True) for c in council.SAFETY_CONFIRMATIONS)

    def requests(self):
        self.assertEqual(self.main("judge-requests", "--tag", "es", "--area", "common"), 0)
        return dict((r["key"], r) for r in translib.read_jsonl(self.repo.path(
            "l10n/work/es/common/judge-requests.jsonl")))

    def pick_google(self):
        return {"key": self.KEY, "verdict": "pick", "pick": "google", "text": "Alarma encendida"}

    def judged_confirm(self, text="Alarma encendida", back="Alarm activated", **extra):
        row = {"key": self.KEY, "verdict": "confirm", "confirm": dict(self.YES), "backtranslation_meaning_kept": True,
               "text": text, "back": back, "reason": "the judged text keeps the meaning"}
        row.update(extra)
        return row

    def backtranslate(self):
        self.assertEqual(self.main("backtranslate", "google", "--tag", "es", "--area", "common"), 0)

    def decide_final(self):
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common", "--final"), 0)
        return self.decisions()[self.KEY]

    def setUp(self):
        super().setUp()
        self.fake = self.draft_all(google_overrides={self.KEY: "Alarma encendida"})
        self.main("check", "--tag", "es", "--area", "common")

    def test_judged_text_confirmed_after_its_back_translation(self):
        self.backtranslate()
        self.assertNotIn(self.KEY, self.stored_backs())  # nothing judged yet: nothing to back-translate
        request = self.requests()[self.KEY]
        self.assertEqual(request["reason"], "safety-disagreement")
        self.assertEqual(request["verdicts"], ["pick", "merge", "fallback"])
        self.assertIsNone(request["backtranslation"])
        self.judge([self.pick_google()])
        # The judged text waits for its back-translation, and the judge is not asked to confirm it before.
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("%s: waiting for a back-translation of the judged safety text" % self.KEY, self.out.getvalue())
        self.assertNotIn(self.KEY, self.requests())
        self.backtranslate()
        back = self.stored_backs()[self.KEY]
        self.assertEqual((back["target"], back["model"]), ("Alarma encendida", "gemini-3.7-flash"))
        self.assertNotIn("Alarma activada", self.fake.requests[-1][3]["contents"][0]["parts"][0]["text"])
        # A back-translated judged text is not sent again.
        calls = len(self.fake.requests)
        self.backtranslate()
        self.assertNotIn("Alarma encendida", self.fake.requests[-1][3]["contents"][0]["parts"][0]["text"])
        self.assertGreater(len(self.fake.requests), calls)  # the unanimous keys are, as before
        request = self.requests()[self.KEY]
        self.assertEqual(request["reason"], "safety-judged-confirmation")
        self.assertEqual(request["text"], "Alarma encendida")
        self.assertEqual(request["backtranslation"]["back"], "Alarm activated")
        self.assertEqual(request["verdicts"], ["confirm", "fallback"])
        self.assertEqual(request["confirm"], list(council.SAFETY_CONFIRMATIONS))
        self.judge([self.pick_google(), self.judged_confirm()])
        d = self.decide_final()
        self.assertEqual(d["outcome"], "safety-judged")
        self.assertIn("safety-judged", council.TRANSLATED)
        self.assertEqual(d["text"], "Alarma encendida")
        self.assertEqual(d["models"]["backtranslator"], "gemini-3.7-flash")
        self.assertEqual(d["judge"]["verdict"], "confirm")
        self.assertIn("picked", d["reason"])
        # A merge works the same way once its own text is back-translated and confirmed.
        merge = {"key": self.KEY, "verdict": "merge", "text": "Alarma conectada"}
        self.judge([self.pick_google(), self.judged_confirm(), merge])
        self.assertEqual(self.decide_final()["outcome"], "fallback-english")
        self.backtranslate()
        self.assertEqual(self.stored_backs()[self.KEY]["target"], "Alarma conectada")
        self.judge([merge, self.judged_confirm(text="Alarma conectada")])
        d = self.decide_final()
        self.assertEqual((d["outcome"], d["text"]), ("safety-judged", "Alarma conectada"))
        self.assertIn("merged", d["reason"])

    def test_judged_text_rejected_stays_english(self):
        self.judge([self.pick_google()])
        self.backtranslate()
        for confirm in (self.judged_confirm(backtranslation_meaning_kept=False),
                        self.judged_confirm(confirm=dict(self.YES, on_off_never_always_not_inverted=False)),
                        {"key": self.KEY, "verdict": "fallback", "reason": "inverted"}):
            self.judge([self.pick_google(), confirm])
            self.assertEqual(self.main("decide", "--tag", "es", "--area", "common", "--final"), 0)
            d = self.decisions()[self.KEY]
            self.assertEqual(d["outcome"], "fallback-english")
            self.assertIsNone(d["text"])
            self.assertNotIn("final run", d["reason"])  # a ruling, not a missing step
        # A merged text that fails the checks is never back-translated.
        self.judge([{"key": self.KEY, "verdict": "merge", "text": "Alarma %1$s"}])
        self.backtranslate()
        self.assertEqual(self.stored_backs()[self.KEY]["target"], "Alarma encendida")
        self.assertIn("failed the checks", self.decide_final()["reason"])
        # Two drafts with one provider missing still fall back as before: no judging.
        os.remove(self.repo.path("l10n/work/es/common/drafts/google.xml"))
        self.assertNotIn(self.KEY, self.requests())
        self.assertIn("no draft from google", self.decide_final()["reason"])

    def test_backtranslator_that_drafted_is_refused(self):
        self.judge([self.pick_google()])
        self.assertEqual(self.main("backtranslate", "google", "--tag", "es", "--area", "common",
                                   "--model", "gemini-3.8-flash"), 2)
        self.assertIn("drafted this area", self.err.getvalue())
        self.assertNotIn(self.KEY, self.stored_backs())
        # A Claude agent on a drafting model is refused too.
        self.assertEqual(self.import_back(self.backtranslation_reply(
            self.backtranslation_prompt(), dict((k, BACK[k]) for k in ("common_alarm_on",))), "claude-opus-5-5"), 2)
        self.assertIn("model that drafted nothing", self.err.getvalue())
        # A drafter's back-translation already on file does not count, even with the judge's confirm.
        self.backtranslate()
        path = "l10n/work/es/common/backtranslation.jsonl"
        rows = [dict(r, model="gpt-6.1-terra") if r["key"] == self.KEY else r
                for r in translib.read_jsonl(self.repo.path(path))]
        translib.write_jsonl(self.repo.path(path), rows)
        self.judge([self.pick_google(), self.judged_confirm()])
        d = self.decide_final()
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIn("also drafted", d["reason"])

    def test_text_edited_after_back_translation_is_refused(self):
        # A confirm written before any back-translation exists confirms nothing.
        self.judge([self.pick_google(), self.judged_confirm()])
        d = self.decide_final()
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIn("no back-translation", d["reason"])
        self.backtranslate()
        # The judge then merges another text: the old back-translation and confirm do not carry over.
        self.judge([self.pick_google(), self.judged_confirm(),
                    {"key": self.KEY, "verdict": "merge", "text": "Alarma puesta"},
                    self.judged_confirm(text="Alarma puesta")])
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("%s: waiting for a back-translation of the judged safety text" % self.KEY, self.out.getvalue())
        self.assertIn("no back-translation", self.decide_final()["reason"])
        self.assertNotIn(self.KEY, self.requests())
        # Someone edits the stored back-translation's target to the new text: the confirm still names
        # the old back-translation, so it confirms nothing.
        path = "l10n/work/es/common/backtranslation.jsonl"
        rows = [dict(r, target="Alarma puesta", back="Alarm set") if r["key"] == self.KEY else r
                for r in translib.read_jsonl(self.repo.path(path))]
        translib.write_jsonl(self.repo.path(path), rows)
        d = self.decide_final()
        self.assertEqual(d["outcome"], "fallback-english")
        self.assertIn("confirmed another text or back-translation", d["reason"])
        # The picked draft changes after its back-translation: the pick, and so the confirm, lapse.
        self.judge([self.pick_google(), self.judged_confirm()])
        self.backtranslate()
        self.assertEqual(self.decide_final()["outcome"], "safety-judged")
        draft = "l10n/work/es/common/drafts/google.xml"
        self.repo.write(draft, self.repo.read(draft).replace("Alarma encendida", "Alarma prendida"))
        self.assertEqual(self.main("decide", "--tag", "es", "--area", "common"), 1)
        self.assertIn("%s: waiting for a new pick or merge" % self.KEY, self.out.getvalue())
        self.assertEqual(self.decide_final()["outcome"], "fallback-english")
        self.assertEqual(self.requests()[self.KEY]["reason"], "safety-disagreement")

    def test_unanimous_safety_keys_keep_their_path(self):
        self.backtranslate()
        requests = self.requests()
        self.assertEqual(requests["common_disclaimer"]["reason"], "safety-confirmation")
        self.assertEqual(requests["common_disclaimer"]["text"], "Volt no está afiliada a NAVEE")
        # A pick or merge is no way into a unanimous safety key: only the confirm counts.
        self.judge([{"key": "common_disclaimer", "verdict": "pick", "pick": "openai",
                     "text": "Volt no está afiliada a NAVEE"}])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertEqual(self.decisions()["common_disclaimer"]["outcome"], "fallback-english")
        self.judge([self.confirm("common_disclaimer")])
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        self.assertEqual(self.decisions()["common_disclaimer"]["outcome"], "safety-agreed")


def spanish_shaped(s):
    """The English as decision text with every plural form Spanish needs, which merge.py requires."""
    return s.items["other"] if s.kind == "string" else dict(s.items, many=s.items["other"])


class BetaBarTest(Base):
    def write_bulk(self, count):
        lines = ['<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">']
        lines += ['    <string name="bulk_item_%d">Item number</string>' % i for i in range(count)]
        lines.append("</resources>")
        self.repo.write("res/values/strings_bulk.xml", "\n".join(lines) + "\n")

    def decide_all(self, fallback=(), area_skip=()):
        sources, _ = translib.load_source(self.paths)
        for area, rows in sources.items():
            if area in area_skip:
                continue
            out = []
            safety = translib.effective_safety(rows)
            for s in rows:
                if not s.translatable:
                    continue
                blocking = set(safety[s.key]) & set(translib.SAFETY_BLOCKING)
                translated = s.key not in fallback
                outcome = "fallback-english" if not translated else "safety-agreed" if blocking else "agreed"
                out.append({"key": s.key, "source_sha1": translib.source_sha1(s.kind, s.items), "outcome": outcome,
                            "text": spanish_shaped(s) if translated else None})
            translib.write_jsonl(self.paths.decisions("es", area), out)

    def test_two_percent_and_no_lock_or_alarm(self):
        self.write_bulk(87)  # 100 translatable keys with common (11) and ble (2)
        self.decide_all(fallback=("bulk_item_1", "bulk_item_2"))
        passes, lines = council.beta_report(self.paths, "es")
        self.assertTrue(passes, lines)
        self.assertIn("2 of 100 keys", lines[0])
        self.decide_all(fallback=("bulk_item_1", "bulk_item_2", "bulk_item_3"))
        self.assertFalse(council.beta_report(self.paths, "es")[0])
        # One lock key (a rung inherits its full key's safety) blocks the beta on its own.
        self.decide_all(fallback=("common_lock_waiting_short",))
        passes, lines = council.beta_report(self.paths, "es")
        self.assertFalse(passes)
        self.assertIn("common_lock_waiting_short (lock)", "\n".join(lines))
        self.decide_all(fallback=("common_alarm_on",))
        self.assertFalse(council.beta_report(self.paths, "es")[0])
        # A decision made for older English counts as a fallback.
        self.decide_all()
        self.repo.write("res/values/strings_ble.xml", self.repo.read("res/values/strings_ble.xml").replace(
            "Searching for 2 scooters", "Searching for two scooters"))
        passes, lines = council.beta_report(self.paths, "es")
        self.assertIn("1 of 100 keys", lines[0])
        self.assertEqual(self.main("beta", "--tag", "es"), 0)
        self.assertIn("beta bar: PASS", self.out.getvalue())
        # A judged, back-translated and confirmed safety key (2026-11-05) counts as translated.
        self.decide_all()
        path = self.paths.decisions("es", "common")
        translib.write_jsonl(path, [dict(r, outcome="safety-judged") if r["key"] == "common_alarm_on" else r
                                    for r in translib.read_jsonl(path)])
        passes, lines = council.beta_report(self.paths, "es")
        self.assertTrue(passes, lines)
        self.assertIn("0 of 100 keys", lines[0])


if __name__ == "__main__":
    unittest.main()
