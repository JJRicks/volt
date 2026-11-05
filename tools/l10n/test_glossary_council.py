"""The glossary round (glossary_council.py), with fake providers only: no test reaches the network."""

import contextlib
import io
import json
import os
import re
import sys
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import glossary_council as gc  # noqa: E402
import pack  # noqa: E402
import providers  # noqa: E402
import translib  # noqa: E402

OPENAI_KEY = "sk-test-OPENAI-SECRET-0123456789abcdef"
GEMINI_KEY = "AIzaTEST-GEMINI-SECRET-0123456789abcdef"
CLAUDE = "claude-opus-5-5"

EN = """# Test English glossary.
id\tterm\tdefinition\tpos\tdnt\tsafety\tsource
volt\tVolt\tThe app's name.\tproper noun\tyes\t-\tfixture
scooter\tscooter\tThe connected e-scooter.\tnoun\tno\t-\tfixture
battery\tbattery\tThe scooter's battery.\tnoun\tno\t-\tfixture
charger\tcharger\tThe charging brick.\tnoun\tno\t-\tfixture
lock\tlock\tLock the scooter.\tverb\tno\tlock\tfixture
"""

# Claude's proposal, in the shape the language branches have it: a header that says so, no labels.
ES = """# Spanish glossary. Claude-only proposal, drafted alone by Claude, pending the three-vendor debate; not final.
id\tterm\tdefinition\tpos\tdnt\tsafety\tsource
scooter\tpatinete | patinetes\tThe connected e-scooter.\tnoun\tno\t-\tthroughout
battery\tbateríaclaude | bateríasclaude\tThe scooter's battery.\tnoun\tno\t-\tBatteryCard.java:10
charger\tcargador | cargadores\tThe charging brick.\tnoun\tno\t-\tChargeCard.java:20
lock\tbloquearclaude | bloqueoclaude\tLock the scooter.\tverb\tno\tlock\tLockButton.java:30
"""

STYLE = """# Volt style guide: Spanish (es)

## 1. Register and address

Use tú. REGISTER-RULE-MARKER.

## 6. Safety wording

Use exactly bloquearclaude for lock. They match the glossary.

## 7. Scooter vocabulary

Say bateríasclaude, never pila.

## 8. Regional choices

REGIONAL-RULE-MARKER.
"""

# What each vendor proposes. Both agree with Claude on scooter (wording) and charger (only after the
# same normalisation council.py uses); they disagree on battery; everyone agrees on the safety row lock.
OPENAI_TERMS = {"scooter": "patinete | patinetes", "battery": "batería | baterías",
                "charger": "cargador  |  cargadores", "lock": "bloquearclaude | bloqueoclaude"}
GOOGLE_TERMS = {"scooter": "patinete | patinetes", "battery": "pila | pilas",
                "charger": "cargador | cargadores", "lock": "bloquearclaude | bloqueoclaude"}


class FakeProviders(object):
    """Model lists and JSON-mode replies with token usage; records every prompt it is sent."""

    def __init__(self, terms_by_model):
        self.terms_by_model = terms_by_model
        self.prompts = []

    def send(self, method, url, headers, body):
        payload = json.loads(body.decode("utf-8")) if body else None
        if url.startswith("https://api.openai.com/v1/models"):
            return 200, json.dumps({"data": [{"id": m} for m in ("gpt-6.1-terra", "gpt-6.1", "gpt-5.1")]}).encode()
        if url.startswith("https://generativelanguage.googleapis.com/v1beta/models?"):
            return 200, json.dumps({"models": [{"name": "models/" + m, "supportedGenerationMethods": [
                "generateContent"]} for m in ("gemini-3.8-flash", "gemini-3.8-pro")]}).encode()
        if url == "https://api.openai.com/v1/responses":
            model, prompt = payload["model"], payload["input"]
            self.prompts.append((model, payload["instructions"], prompt))
            return 200, json.dumps({"model": model + "-2026-11-01", "output_text": json.dumps(self.reply(model, prompt)),
                                    "usage": {"input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
                                              "output_tokens_details": {"reasoning_tokens": 5}}}).encode()
        if ":generateContent" in url:
            model = url.split("/models/")[1].split(":")[0]
            prompt = payload["contents"][0]["parts"][0]["text"]
            self.prompts.append((model, payload["systemInstruction"]["parts"][0]["text"], prompt))
            return 200, json.dumps({"modelVersion": model + "-001", "candidates": [
                {"content": {"parts": [{"text": json.dumps(self.reply(model, prompt))}]}}],
                "usageMetadata": {"promptTokenCount": 90, "candidatesTokenCount": 30,
                                  "totalTokenCount": 120}}).encode()
        return 404, b'{"error": {"message": "no such endpoint"}}'

    def reply(self, model, prompt):
        rows = json.loads(prompt[prompt.index("["):])
        terms = self.terms_by_model[model]
        return {"terms": dict((r["id"], terms[r["id"]]) for r in rows if r["id"] in terms), "notes": {}}


class Base(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.paths = self.repo.paths
        self.repo.write("l10n/glossary/en.tsv", EN)
        self.repo.write("l10n/glossary/es.tsv", ES)
        self.repo.write("l10n/style/es.md", STYLE)
        self.network = cf.NoNetwork().__enter__()
        self.env = unittest.mock.patch.dict(os.environ, {"OPENAI_API_KEY": OPENAI_KEY, "GEMINI_API_KEY": GEMINI_KEY})
        self.env.start()
        self.fake = FakeProviders({"gpt-6.1-terra": OPENAI_TERMS, "gemini-3.8-flash": GOOGLE_TERMS,
                                   "gpt-6.1": OPENAI_TERMS})
        previous = providers.set_transport(self.fake, sleep=lambda seconds: None)
        self.addCleanup(providers.set_transport, previous)
        self.out, self.err = io.StringIO(), io.StringIO()

    def tearDown(self):
        self.env.stop()
        self.network.__exit__(None, None, None)
        self.repo.close()

    def main(self, *args):
        with contextlib.redirect_stdout(self.out), contextlib.redirect_stderr(self.err):
            return gc.main(["--root", self.repo.root] + list(args))

    def run_ok(self, *args):
        self.assertEqual(self.main(*args), 0, self.err.getvalue())

    def glossary(self):
        return self.repo.read("l10n/glossary/es.tsv")

    def rows(self):
        return dict((f[0], f) for _, f in translib._glossary_rows(self.repo.path("l10n/glossary/es.tsv"), "es")[0])

    def through_requests(self):
        self.run_ok("relabel", "--tag", "es")
        self.run_ok("draft", "openai", "--tag", "es")
        self.run_ok("draft", "google", "--tag", "es")
        self.run_ok("judge-requests", "--tag", "es")
        return json.loads(self.repo.read("l10n/work/es/glossary/judge-requests.json"))

    def prompt_files(self):
        directory = self.paths.work("es", "glossary", "prompts")
        return sorted(os.path.join(directory, f) for f in os.listdir(directory) if f.startswith("judge.chunk-"))

    def answer(self, prompt_path, rulings, model=CLAUDE, set_id=None):
        """Answer a judge prompt as the agent would, choosing by term (the options are shuffled)."""
        with open(prompt_path, encoding="utf-8") as handle:
            text = handle.read()
        reply = re.search(r"Write one JSON object to `([^`]+)`", text).group(1)
        shown = json.loads(text.split("## Rows\n\n", 1)[1])
        out = {}
        for row in shown:
            if row["id"] not in rulings:
                continue
            wanted = rulings[row["id"]]
            letter = next((o["option"] for o in row["options"] if o["term"] == wanted), "new")
            out[row["id"]] = {"choice": letter, "term": wanted, "reason": "the common word"}
        extra = rulings.get("_extra", {})
        out.update(extra)
        set_id = set_id or re.search(r"\(set ([0-9a-f]{12})\)", text).group(1)
        self.repo.write(reply, json.dumps({"set": set_id, "model": model, "rulings": out}, ensure_ascii=False))


class RelabelTest(Base):
    def test_labels_every_row_and_is_idempotent(self):
        self.assertEqual(translib.claude_only_glossary_rows(self.paths, "es"), [])  # the guard cannot see it yet
        self.run_ok("relabel", "--tag", "es")
        rows = self.rows()
        self.assertEqual(rows["battery"][6], "claude-only claude-opus-5-5; BatteryCard.java:10")
        self.assertEqual(sorted(translib.claude_only_glossary_rows(self.paths, "es")),
                         ["battery", "charger", "lock", "scooter"])
        once = self.glossary()
        self.run_ok("relabel", "--tag", "es")
        self.assertEqual(self.glossary(), once)
        self.assertIn("0 rows labelled", self.out.getvalue())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(council.main(["--root", self.repo.root, "glossary-check", "--tag", "es"]), 0)

    def test_leaves_judged_rows_alone(self):
        judged = "council claude:claude-opus-5-5 openai:gpt-6.1-terra google:gemini-3.8-flash judge:claude-x; ChargeCard"
        self.repo.write("l10n/glossary/es.tsv", ES.replace("ChargeCard.java:20", judged))
        self.run_ok("relabel", "--tag", "es")
        self.assertEqual(self.rows()["charger"][6], judged)
        self.assertNotIn("charger", translib.claude_only_glossary_rows(self.paths, "es"))

    def test_refuses_a_file_that_is_not_a_claude_only_proposal(self):
        self.repo.write("l10n/glossary/es.tsv", ES.replace("Claude-only proposal", "Approved list"))
        before = self.glossary()
        self.assertEqual(self.main("relabel", "--tag", "es"), 2)
        self.assertIn("does not say it is a Claude-only proposal", self.err.getvalue())
        self.assertEqual(self.glossary(), before)

    def test_round_refuses_an_unlabelled_proposal(self):
        self.assertEqual(self.main("draft", "openai", "--tag", "es"), 2)
        self.assertIn("relabel --tag es first", self.err.getvalue())
        self.assertEqual(self.fake.prompts, [])


class DraftTest(Base):
    def test_prompts_exclude_claudes_terms(self):
        self.run_ok("relabel", "--tag", "es")
        self.run_ok("draft", "openai", "--tag", "es")
        self.run_ok("draft", "google", "--tag", "es")
        self.assertEqual([p[0] for p in self.fake.prompts], ["gpt-6.1-terra", "gemini-3.8-flash"])
        for _, system, prompt in self.fake.prompts:
            sent = system + prompt
            for claude_word in ("bateríaclaude", "bateríasclaude", "bloquearclaude", "bloqueoclaude", "claude-only"):
                self.assertNotIn(claude_word, sent)
            for english in ("The scooter's battery.", "The charging brick.", '"safety": "lock"', '"dnt": "no"'):
                self.assertIn(english, prompt)
            self.assertNotIn('"volt"', prompt)  # do-not-translate rows take no term
            self.assertIn("REGISTER-RULE-MARKER", system)
            self.assertIn("REGIONAL-RULE-MARKER", system)
            self.assertIn('"formality": "tú"', system)
        self.assert_no_secret()

    def test_records_model_usage_and_terms(self):
        self.run_ok("relabel", "--tag", "es")
        self.run_ok("draft", "openai", "--tag", "es")
        draft = json.loads(self.repo.read("l10n/work/es/glossary/drafts/openai.json"))
        self.assertEqual(draft["model"], "gpt-6.1-terra")
        self.assertEqual(draft["model_reported"], "gpt-6.1-terra-2026-11-01")
        self.assertEqual(draft["usage"]["input_tokens"], 100)
        self.assertEqual(draft["usage"]["output_tokens_details.reasoning_tokens"], 5)
        self.assertEqual(draft["rows"]["charger"]["term"], "cargador | cargadores")  # spaces around | tidied
        runs = translib.read_jsonl(self.paths.work("es", "glossary", "runs.jsonl"))
        self.assertEqual(runs[-1]["stage"], "glossary-draft")
        self.assertEqual(runs[-1]["usage"]["total_tokens"], 120)
        self.run_ok("draft", "google", "--tag", "es", "--model", "gemini-3.8-flash")
        runs = translib.read_jsonl(self.paths.work("es", "glossary", "runs.jsonl"))
        self.assertEqual(runs[-1]["usage"]["promptTokenCount"], 90)
        self.assertEqual(runs[-1]["model_reported"], "gemini-3.8-flash-001")
        self.assertIs(providers.call.__name__, "call")  # the usage recorder is removed again
        self.assert_no_secret()

    def test_records_what_the_style_guide_still_names(self):
        self.run_ok("relabel", "--tag", "es")
        self.run_ok("draft", "openai", "--tag", "es")
        self.assertEqual(json.loads(self.repo.read("l10n/work/es/glossary/drafts/openai.json"))["style_guide_overlap"],
                         {})
        self.repo.write("l10n/style/es.md", STYLE.replace("Use tú.", "Use tú: tu patinete."))
        self.run_ok("draft", "openai", "--tag", "es")
        draft = json.loads(self.repo.read("l10n/work/es/glossary/drafts/openai.json"))
        self.assertEqual(draft["style_guide_overlap"], {"scooter": ["patinete"]})
        self.assertIn("still names 1 of Claude's proposed terms (scooter)", self.out.getvalue())

    def test_model_override_and_bad_terms(self):
        self.run_ok("relabel", "--tag", "es")
        self.fake.terms_by_model["gpt-6.1"] = dict(OPENAI_TERMS, battery="batería (pila)")
        self.run_ok("draft", "openai", "--tag", "es", "--model", "gpt-6.1")
        draft = json.loads(self.repo.read("l10n/work/es/glossary/drafts/openai.json"))
        self.assertEqual(draft["model"], "gpt-6.1")
        self.assertIsNone(draft["rows"]["battery"]["term"])
        self.assertTrue(any("battery" in p for p in draft["problems"]))
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--model", "gpt-9"), 2)
        self.assertIn("not in the openai model list", self.err.getvalue())

    def assert_no_secret(self):
        for text in (self.out.getvalue(), self.err.getvalue(), self.repo.tree_text()):
            self.assertNotIn(OPENAI_KEY, text)
            self.assertNotIn(GEMINI_KEY, text)


class JudgeRequestsTest(Base):
    def test_agree_disagree_and_safety_rows(self):
        requests = self.through_requests()
        rows = dict((r["id"], r) for r in requests["rows"])
        self.assertEqual(rows["scooter"]["status"], "agreed")
        self.assertEqual(rows["charger"]["status"], "agreed")  # equal after normalisation
        self.assertEqual((rows["battery"]["status"], rows["battery"]["reason"]), ("judge", "disagreement"))
        self.assertEqual((rows["lock"]["status"], rows["lock"]["reason"]), ("judge", "safety"))  # all three agree
        self.assertEqual(len(rows["battery"]["options"]), 3)
        self.assertEqual(len(rows["lock"]["options"]), 1)
        self.assertEqual(rows["lock"]["options"][0]["drafters"], 3)
        self.assertNotIn("volt", rows)
        prompts = self.prompt_files()
        self.assertEqual(len(prompts), 1)
        with open(prompts[0], encoding="utf-8") as handle:
            text = handle.read()
        shown = json.loads(text.split("## Rows\n\n", 1)[1])
        self.assertEqual([r["id"] for r in shown], ["battery", "lock"])
        for word in ("openai", "google", "claude:", "gpt-6.1-terra", "gemini", "providers"):
            self.assertNotIn(word, text.split("## Rows", 1)[1])
        self.assertIn("The scooter's battery.", text)
        self.assertIn("REGISTER-RULE-MARKER", text)
        self.assertNotIn("Use exactly bloquearclaude", text)  # the withheld sections would name Claude's word

    def test_options_are_shuffled_and_stable(self):
        orders = set()
        for battery in ("batería | baterías", "acumulador", "batería eléctrica", "celda", "pila nueva", "betería"):
            self.fake.terms_by_model["gpt-6.1-terra"] = dict(OPENAI_TERMS, battery=battery)
            requests = self.through_requests()
            row = next(r for r in requests["rows"] if r["id"] == "battery")
            orders.add(tuple(o["providers"][0] for o in row["options"]))
            again = self.through_requests()
            self.assertEqual(again["set"], requests["set"])
        self.assertGreater(len(orders), 1)

    def test_refuses_without_both_drafts(self):
        self.run_ok("relabel", "--tag", "es")
        self.run_ok("draft", "openai", "--tag", "es")
        self.assertEqual(self.main("judge-requests", "--tag", "es"), 2)
        self.assertIn("no google glossary draft", self.err.getvalue())

    def test_chunks_of_about_eighty_rows(self):
        extra_en, extra_es, terms = [], [], {}
        for n in range(170):
            extra_en.append("term-%d\tterm %d\tA term.\tnoun\tno\t-\tfixture" % (n, n))
            extra_es.append("term-%d\ttérmino %d\tA term.\tnoun\tno\t-\tfixture" % (n, n))
            terms["term-%d" % n] = "vocablo %d" % n
        self.repo.write("l10n/glossary/en.tsv", EN + "\n".join(extra_en) + "\n")
        self.repo.write("l10n/glossary/es.tsv", ES + "\n".join(extra_es) + "\n")
        self.fake.terms_by_model["gpt-6.1-terra"] = dict(OPENAI_TERMS, **terms)
        self.fake.terms_by_model["gemini-3.8-flash"] = dict(GOOGLE_TERMS, **terms)
        requests = self.through_requests()
        self.assertEqual(requests["chunks"], 3)
        self.assertEqual([len(c) for c in requests["chunk_rows"]], [80, 80, 12])
        self.assertEqual(len(self.prompt_files()), 3)
        self.assertEqual(len([p for p in self.fake.prompts if p[0] == "gpt-6.1-terra"]), 3)  # 174 rows, 80 a call


class DecideTest(Base):
    def test_all_rows_ruled(self):
        self.through_requests()
        self.answer(self.prompt_files()[0], {"battery": "batería | baterías", "lock": "bloquear | bloqueo"})
        self.run_ok("decide", "--tag", "es")
        rows = self.rows()
        self.assertEqual(translib.claude_only_glossary_rows(self.paths, "es"), [])
        self.assertEqual(rows["battery"][1], "batería | baterías")
        self.assertEqual(rows["lock"][1], "bloquear | bloqueo")  # the judge's own term
        self.assertEqual(rows["scooter"][1], "patinete | patinetes")
        self.assertEqual(rows["battery"][6], "council claude:claude-opus-5-5 openai:gpt-6.1-terra "
                                             "google:gemini-3.8-flash judge:claude-opus-5-5; BatteryCard.java:10")
        self.assertTrue(rows["scooter"][6].startswith("council claude:claude-opus-5-5 openai:gpt-6.1-terra "
                                                      "google:gemini-3.8-flash agreed; throughout"))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(council.main(["--root", self.repo.root, "glossary-check", "--tag", "es"]), 0)
        decisions = translib.read_jsonl(self.paths.work("es", "glossary", "decisions.jsonl"))
        self.assertEqual(sorted((d["id"], d["outcome"]) for d in decisions),
                         [("battery", "judged"), ("charger", "agreed"), ("lock", "judged"), ("scooter", "agreed")])
        self.assertIn("0 rows claude-only", self.out.getvalue())
        # Nothing is left for the round, and relabel finds nothing to label.
        self.run_ok("relabel", "--tag", "es")
        self.assertEqual(self.main("draft", "openai", "--tag", "es"), 0)
        self.assertIn("nothing to draft", self.out.getvalue())

    def test_some_rows_ruled_keeps_claude_only_and_the_guard(self):
        self.through_requests()
        self.answer(self.prompt_files()[0], {"battery": "pila | pilas"})
        self.run_ok("decide", "--tag", "es")
        self.assertEqual(translib.claude_only_glossary_rows(self.paths, "es"), ["lock"])
        self.assertEqual(self.rows()["lock"][1], "bloquearclaude | bloqueoclaude")
        self.assertEqual(self.rows()["battery"][1], "pila | pilas")
        self.assertIn("1 rows claude-only (draft openai|google still refuses)", self.out.getvalue())
        # council.py's string draft still refuses, on the real fixture area.
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"]), 0)
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            code = council.main(["--root", self.repo.root, "--no-java", "draft", "openai", "--tag", "es", "--area",
                                 "common", "--model", "gpt-6.1"])
        self.assertEqual(code, 2)
        self.assertIn("1 claude-only rows (lock)", err.getvalue())
        # decide again is idempotent, and a later ruling finishes the row.
        before = self.glossary()
        self.run_ok("decide", "--tag", "es")
        self.assertEqual(self.glossary(), before)
        self.answer(self.prompt_files()[0], {"battery": "pila | pilas", "lock": "bloquearclaude | bloqueoclaude"})
        self.run_ok("decide", "--tag", "es")
        self.assertEqual(translib.claude_only_glossary_rows(self.paths, "es"), [])
        self.assertTrue(self.rows()["lock"][6].endswith("judge:claude-opus-5-5; LockButton.java:30"))

    def test_no_reply_leaves_every_disputed_row_claude_only(self):
        self.through_requests()
        self.run_ok("decide", "--tag", "es")
        self.assertEqual(sorted(translib.claude_only_glossary_rows(self.paths, "es")), ["battery", "lock"])
        self.assertIn("no reply yet", self.out.getvalue())

    def test_a_ruling_for_an_unknown_id_is_refused(self):
        self.through_requests()
        before = self.glossary()
        for extra in ({"nonsense": {"choice": "new", "term": "x", "reason": "r"}},
                      {"scooter": {"choice": "new", "term": "monopatín", "reason": "r"}}):  # agreed: not asked
            self.answer(self.prompt_files()[0], {"battery": "pila | pilas", "_extra": extra})
            self.assertEqual(self.main("decide", "--tag", "es"), 2)
            self.assertIn("is not a row this set asked the judge about", self.err.getvalue())
            self.assertEqual(self.glossary(), before)

    def test_bad_rulings_are_refused(self):
        self.through_requests()
        before = self.glossary()
        cases = [({"_extra": {"battery": {"choice": "A", "term": "otra cosa", "reason": "r"}}}, None, "not that option's"),
                 ({"_extra": {"battery": {"choice": "new", "term": "pila (seca)", "reason": "r"}}}, None, "no \";\""),
                 ({"_extra": {"battery": {"choice": "new", "term": "pila", "reason": ""}}}, None, "needs choice"),
                 ({"battery": "pila | pilas"}, "gpt-6.1", "not a Claude model id")]
        for rulings, model, message in cases:
            self.err.truncate(0)
            self.answer(self.prompt_files()[0], rulings, model=model or CLAUDE)
            self.assertEqual(self.main("decide", "--tag", "es"), 2)
            self.assertIn(message, self.err.getvalue())
            self.assertEqual(self.glossary(), before)

    def test_a_stale_english_term_is_refused(self):
        self.through_requests()
        self.answer(self.prompt_files()[0], {"battery": "pila | pilas", "lock": "bloquear | bloqueo"})
        self.repo.write("l10n/glossary/en.tsv", EN.replace("The scooter's battery.", "The scooter's main battery."))
        before = self.glossary()
        self.assertEqual(self.main("decide", "--tag", "es"), 2)
        self.assertIn("battery (openai)", self.err.getvalue())
        self.assertIn("drafted from other English", self.err.getvalue())
        self.assertEqual(self.glossary(), before)
        # Drafting again makes a new set: the old rulings are not adopted.
        self.run_ok("draft", "openai", "--tag", "es")
        self.run_ok("draft", "google", "--tag", "es")
        self.assertEqual(self.main("decide", "--tag", "es"), 2)
        self.assertIn("battery (the English changed)", self.err.getvalue())
        self.run_ok("judge-requests", "--tag", "es")
        self.run_ok("decide", "--tag", "es")
        self.assertEqual(sorted(translib.claude_only_glossary_rows(self.paths, "es")), ["battery", "lock"])

    def test_a_redrafted_candidate_needs_judge_requests_again(self):
        self.through_requests()
        self.answer(self.prompt_files()[0], {"battery": "pila | pilas"})
        self.fake.terms_by_model["gemini-3.8-flash"] = dict(GOOGLE_TERMS, battery="acumulador")
        self.run_ok("draft", "google", "--tag", "es")
        self.assertEqual(self.main("decide", "--tag", "es"), 2)
        self.assertIn("battery (a candidate changed)", self.err.getvalue())


if __name__ == "__main__":
    unittest.main()
