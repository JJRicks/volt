"""The council's stuck-key paths (2026-11-06): all-failed and short-for-full, on a synthetic pack.

FakeMeasurer measures ten units a character, so every width here can be worked out by hand. The
full rungs have volt:max 64dp@12sp*2 (two lines of whole words) and the short rungs 90dp@12.5sp.
"""

import collections
import io
import json
import os
import sys
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402

TAG, AREA = "pt-PT", "qa"
DRAFTS = (("claude-opus", "claude", "claude-opus-5-5"), ("google", "google", "gemini-3.8-flash"),
          ("openai", "openai", "gpt-6.1-terra"))
BACK_MODEL = "gpt-6.1-sol"  # drafted nothing

STRINGS = (
    '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">'
    # A safety pair: the full rung falls back on its short rung where it does not fit.
    '<string name="qa_pin" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="full" '
    'volt:safety="pin" volt:max="64dp@12sp*2" volt:overflow="short">Pin prox</string>'
    '<string name="qa_pin_short" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="short" '
    'volt:safety="pin" volt:max="90dp@12.5sp">Pin prox</string>'
    # The same without safety.
    '<string name="qa_dash" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="full" '
    'volt:max="64dp@12sp*2" volt:overflow="short">Dash snd</string>'
    '<string name="qa_dash_short" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="short" '
    'volt:max="90dp@12.5sp">Dash snd</string>'
    # A short rung every draft overflows.
    '<string name="qa_star" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="full" '
    'volt:max="64dp@12sp*2">Stars</string>'
    '<string name="qa_star_short" volt:screen="Home Quick access" volt:purpose="shortcut name" volt:ladder="short" '
    'volt:max="90dp@12.5sp">Stars</string>'
    '</resources>')

# Each draft's texts. "proximid" (80) fits its own 90dp line but not the full's 64dp lines, so the full
# rungs "Pin e proximidade" and "Dash e proximidade" (110 for "proximidade") fail only because of it.
# "Luzes de estrela" (160) overflows the 90dp short budget.
TEXTS = {
    "qa_pin": "Pin e proximidade", "qa_pin_short": "proximid",
    "qa_dash": "Dash e proximidade", "qa_dash_short": "proximid",
    "qa_star": "Luzes", "qa_star_short": "Luzes de estrela",
}


class FakeMeasurer(object):
    method = "fake"

    def __init__(self, *args, **kwargs):
        pass

    def measure(self, requests):
        return [max(len(line) for line in text.split("\n")) * 10.0 for _size, text in requests]


def confirm_row(key, text, back, **extra):
    row = {"key": key, "verdict": "confirm", "confirm": dict((c, True) for c in council.SAFETY_CONFIRMATIONS),
           "backtranslation_meaning_kept": True, "text": text, "back": back}
    row.update(extra)
    return row


class StuckBase(unittest.TestCase):
    def setUp(self):
        self.repo = council_fixture.FixtureRepo()
        self.paths = self.repo.paths
        patcher = unittest.mock.patch.object(fontmetrics, "Measurer", FakeMeasurer)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.repo.close)
        self.repo.write("res/values/strings_qa.xml", STRINGS)
        pack.write_pack(self.paths, pack.build_pack(self.paths, TAG, AREA, FakeMeasurer()))
        self.sha = dict((e["key"], e["source_sha1"]) for e in pack.load_pack(self.paths, TAG, AREA)["entries"])

    def write_drafts(self, texts=None, attempts=None):
        """One draft per provider with texts (default TEXTS); attempts {key: attempt} (default 2: both
        attempts used)."""
        texts = dict(TEXTS, **(texts or {}))
        for name, provider, model in DRAFTS:
            lines = ['<?xml version="1.0" encoding="utf-8"?>',
                     '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n" volt:provider="%s" volt:model="%s">'
                     % (provider, model)]
            for key, text in texts.items():
                attempt = (attempts or {}).get(key, 2)
                lines.append('    <string name="%s" volt:src="%s"%s>%s</string>' % (
                    key, self.sha[key], ' volt:attempt="2"' if attempt >= 2 else "", text))
            lines.append("</resources>")
            self.repo.write("l10n/work/%s/%s/drafts/%s.xml" % (TAG, AREA, name), "\n".join(lines) + "\n")

    def judge(self, *rows):
        path = "l10n/work/%s/%s/judge.jsonl" % (TAG, AREA)
        old = self.repo.read(path) if os.path.exists(self.repo.path(path)) else ""
        lines = []
        for row in rows:
            row = dict(row)
            row.setdefault("source_sha1", self.sha[row["key"]])
            row.setdefault("model", "claude-opus-5-5")
            row.setdefault("reason", "test ruling")
            lines.append(json.dumps(row, ensure_ascii=False))
        self.repo.write(path, old + "\n".join(lines) + "\n")

    def back(self, key, target, back, model=BACK_MODEL):
        path = self.repo.path("l10n/work/%s/%s/backtranslation.jsonl" % (TAG, AREA))
        rows = collections.OrderedDict((r["key"], r) for r in translib.read_jsonl(path))
        rows[key] = collections.OrderedDict([("key", key), ("source_sha1", self.sha[key]), ("target", target),
                                             ("back", back), ("provider", "openai"), ("model", model),
                                             ("model_reported", model)])
        translib.write_jsonl(path, list(rows.values()))

    def requests(self):
        return dict((r["key"], r) for r in council.judge_requests(self.paths, TAG, AREA, use_java=False)[1])

    def decide(self):
        rows, pending, problems = council.decide(self.paths, TAG, AREA, use_java=False)
        self.assertEqual(problems, [])
        return dict((r["key"], r) for r in rows), dict(pending)

    def back_targets(self):
        p = pack.load_pack(self.paths, TAG, AREA)
        drafts = translib.list_drafts(self.paths, TAG, AREA)
        states, shorts = council.council_states(self.paths, p, drafts, use_java=False)
        judged = council.judged_safety_texts(self.paths, p, states, False, shorts=shorts)
        targets = council.backtranslation_targets(states, judged, council.load_backtranslations(self.paths, p),
                                                  council.all_drafter_models(drafts))
        return dict((k, translib.text_value(s.entry["kind"], s.agreed)) for k, s in targets.items())

    def settle_pin(self):
        """The safety pair as pt-BR had it: both rungs unanimous, back-translated and confirmed."""
        self.back("qa_pin", "Pin e proximidade", "Pin and proximity")
        self.back("qa_pin_short", "proximid", "proximity")
        self.judge(confirm_row("qa_pin", "Pin e proximidade", "Pin and proximity"),
                   confirm_row("qa_pin_short", "proximid", "proximity"))


class AllFailedTest(StuckBase):
    def test_listed_only_when_every_attempt_is_used(self):
        self.write_drafts(attempts={"qa_star_short": 1})
        self.assertNotIn("qa_star_short", self.requests())
        rows, pending = self.decide()
        self.assertEqual(rows["qa_star_short"]["reason"], "no draft passed the checks")
        self.assertNotIn("qa_star_short", pending)

        self.write_drafts()
        request = self.requests()["qa_star_short"]
        self.assertEqual(request["reason"], "all-failed")
        self.assertEqual(request["verdicts"], ["merge", "fallback"])
        self.assertEqual(request["confirm"], [])
        self.assertEqual(len(request["candidates"]), 3)
        self.assertTrue(all(not c["ok"] and c["findings"][0]["check"] == "width" for c in request["candidates"]))
        self.assertEqual(request["stuck"], {"merge": None, "merge_problem": None, "merge_findings": []})
        rows, pending = self.decide()
        self.assertIn("ruling", pending["qa_star_short"])  # pending_only.py keeps it
        self.assertEqual(rows["qa_star_short"]["outcome"], "fallback-english")

    def test_a_passing_merge_is_accepted(self):
        self.write_drafts()
        self.judge({"key": "qa_star_short", "verdict": "merge", "text": "Luz"})
        self.assertNotIn("qa_star_short", self.requests())
        rows, pending = self.decide()
        self.assertNotIn("qa_star_short", pending)
        self.assertEqual((rows["qa_star_short"]["outcome"], rows["qa_star_short"]["text"]), ("judged", "Luz"))
        self.assertEqual((rows["qa_star"]["outcome"], rows["qa_star"]["text"]), ("agreed", "Luzes"))

    def test_a_failing_merge_is_listed_again(self):
        self.write_drafts()
        self.judge({"key": "qa_star_short", "verdict": "merge", "text": "Luz de estrelinha"})
        request = self.requests()["qa_star_short"]
        self.assertEqual(request["reason"], "all-failed")
        self.assertEqual(request["stuck"]["merge"], "Luz de estrelinha")
        self.assertEqual(request["stuck"]["merge_problem"], "the judge's merged text failed the checks")
        self.assertEqual([f["check"] for f in request["stuck"]["merge_findings"]], ["width"])
        rows, pending = self.decide()
        self.assertIn("ruling", pending["qa_star_short"])
        self.assertEqual(rows["qa_star_short"]["outcome"], "fallback-english")
        # A pick names no passing draft: listed again too.
        self.judge({"key": "qa_star_short", "verdict": "pick", "pick": "google", "text": "Luzes de estrela"})
        self.assertIn("pick", self.requests()["qa_star_short"]["stuck"]["merge_problem"])

    def test_a_wrong_shaped_merge_waits(self):
        self.write_drafts()
        self.judge({"key": "qa_star_short", "verdict": "merge", "text": None})
        request = self.requests()["qa_star_short"]
        self.assertEqual(request["stuck"]["merge_problem"], "the judge's merged text has the wrong shape")
        rows, pending = self.decide()
        self.assertEqual(rows["qa_star_short"]["outcome"], "fallback-english")
        self.assertIn("ruling", pending["qa_star_short"])
        # Every other key settled: a non-final write still waits instead of writing English.
        self.judge({"key": "qa_dash_short", "verdict": "fallback"},
                   {"key": "qa_pin_short", "verdict": "fallback"})
        _, pending = self.decide()
        self.assertEqual(list(pending), ["qa_star_short"])
        out = io.StringIO()
        self.assertIsNone(council.write_decisions(self.paths, TAG, AREA, use_java=False, out=out))
        self.assertIn("qa_star_short", out.getvalue())
        self.assertFalse(os.path.exists(self.paths.decisions(TAG, AREA)))

    def test_a_fallback_ends_it(self):
        self.write_drafts()
        self.judge({"key": "qa_star_short", "verdict": "fallback"})
        self.assertNotIn("qa_star_short", self.requests())
        rows, pending = self.decide()
        self.assertNotIn("qa_star_short", pending)
        self.assertEqual(rows["qa_star_short"]["reason"], "judge: test ruling")
        self.assertEqual(rows["qa_star"]["outcome"], "fallback-english")  # its ladder group with it

    def test_safety_keys_never_take_it(self):
        self.write_drafts({"qa_pin": "Pin e proximidadezinha", "qa_pin_short": "Pin e proximidadezinha"})
        requests = self.requests()
        self.assertNotIn("qa_pin", requests)
        self.assertNotIn("qa_pin_short", requests)
        rows, pending = self.decide()
        self.assertTrue(rows["qa_pin_short"]["reason"].startswith("safety: no draft passed the checks"))
        self.assertNotIn("qa_pin_short", pending)
        # A full rung that fails only because of its short rung is not all-failed: its short is listed.
        self.assertNotIn("qa_dash", requests)
        self.assertEqual(requests["qa_dash_short"]["reason"], "short-for-full")


class ShortForFullTest(StuckBase):
    def test_full_fails_only_because_of_its_short(self):
        self.write_drafts()
        self.settle_pin()
        rows, _ = self.decide()
        self.assertEqual(rows["qa_pin"]["outcome"], "fallback-english")
        self.assertIn("does not fit either", rows["qa_pin"]["alternatives"][0]["findings"][0]["message"])

    def test_listed_despite_an_earlier_confirm(self):
        self.write_drafts()
        self.settle_pin()
        request = self.requests()["qa_pin_short"]
        self.assertEqual(request["reason"], "short-for-full")
        self.assertEqual(request["verdicts"], ["merge", "fallback"])
        self.assertEqual(request["confirm"], [])
        self.assertEqual(request["text"], "proximid")
        self.assertEqual(request["backtranslation"]["back"], "proximity")
        stuck = request["stuck"]
        self.assertEqual((stuck["full"], stuck["full_text"]), ("qa_pin", ["Pin e proximidade"]))
        self.assertEqual(stuck["full_budget"], {"dp": 64.0, "sp": 12.0, "lines": 2})
        self.assertEqual(stuck["budget"], {"dp": 90.0, "sp": 12.5, "lines": 1})
        self.assertIn("does not fit either", stuck["why"])
        rows, pending = self.decide()
        self.assertIn("ruling", pending["qa_pin_short"])
        self.assertEqual(rows["qa_pin_short"]["outcome"], "fallback-english")
        # The non-safety pair, which nobody ruled on, too.
        self.assertEqual(self.requests()["qa_dash_short"]["reason"], "short-for-full")

    def test_a_merge_must_fit_both_budgets(self):
        self.write_drafts()
        self.settle_pin()
        # Fits its own 90dp line (80) but not the full's 64dp lines.
        self.judge({"key": "qa_dash_short", "verdict": "merge", "text": "sondinha"},
                   {"key": "qa_pin_short", "verdict": "merge", "text": "proxim."})
        requests = self.requests()
        for key in ("qa_dash_short", "qa_pin_short"):
            self.assertEqual(requests[key]["reason"], "short-for-full")
            self.assertIn("does not fit qa_", requests[key]["stuck"]["merge_problem"])
            self.assertIn("where the screen shows this short rung instead",
                          requests[key]["stuck"]["merge_findings"][-1]["message"])
        # Fits the full's lines, not its own 90dp line (100): listed again too.
        self.judge({"key": "qa_dash_short", "verdict": "merge", "text": "Dash e som"})
        stuck = self.requests()["qa_dash_short"]["stuck"]
        self.assertEqual(stuck["merge_problem"], "the judge's merged text failed the checks")
        self.assertIn("exceeds the 90dp budget", stuck["merge_findings"][0]["message"])
        rows, pending = self.decide()
        self.assertEqual(rows["qa_dash"]["outcome"], "fallback-english")
        self.assertIn("ruling", pending["qa_dash_short"])

    def test_non_safety_merge_is_accepted_and_the_full_passes_with_a_warning(self):
        self.write_drafts()
        self.judge({"key": "qa_dash_short", "verdict": "pick", "pick": "google", "text": "proximid"},
                   {"key": "qa_dash_short", "verdict": "merge", "text": "Dash som"})
        self.assertNotIn("qa_dash_short", self.requests())
        rows, pending = self.decide()
        self.assertNotIn("qa_dash_short", pending)
        self.assertNotIn("qa_dash", pending)
        self.assertEqual((rows["qa_dash_short"]["outcome"], rows["qa_dash_short"]["text"]), ("judged", "Dash som"))
        self.assertEqual((rows["qa_dash"]["outcome"], rows["qa_dash"]["text"]), ("agreed", "Dash e proximidade"))
        self.assertEqual([f["severity"] for f in rows["qa_dash"]["checks"]], ["warn"])
        self.assertIn("«Dash som»", rows["qa_dash"]["checks"][0]["message"])

    def test_fallback_declines(self):
        self.write_drafts()
        self.judge({"key": "qa_dash_short", "verdict": "fallback"})
        self.assertNotIn("qa_dash_short", self.requests())
        rows, pending = self.decide()
        self.assertNotIn("qa_dash_short", pending)
        self.assertEqual((rows["qa_dash_short"]["outcome"], rows["qa_dash"]["outcome"]),
                         ("fallback-english", "fallback-english"))

    def test_safety_merge_needs_the_backtranslation_and_confirm(self):
        self.write_drafts()
        self.settle_pin()
        self.judge({"key": "qa_pin_short", "verdict": "merge", "text": "Pin prox."})
        # Not listed, and not accepted, until a model that drafted nothing back-translates exactly that text.
        self.assertNotIn("qa_pin_short", self.requests())
        self.assertEqual(self.back_targets(), {"qa_pin_short": "Pin prox."})
        rows, pending = self.decide()
        self.assertEqual(rows["qa_pin_short"]["outcome"], "fallback-english")
        self.assertEqual(pending["qa_pin_short"], "a back-translation of the judged safety text")
        # A drafter's back-translation does not count.
        self.back("qa_pin_short", "Pin prox.", "Pin prox.", model="gpt-6.1-terra")
        rows, pending = self.decide()
        self.assertEqual(rows["qa_pin_short"]["outcome"], "fallback-english")
        self.assertIn("model that drafted nothing", pending["qa_pin_short"])
        # An independent one: now the judge confirms it.
        self.back("qa_pin_short", "Pin prox.", "Pin prox.")
        request = self.requests()["qa_pin_short"]
        self.assertEqual((request["reason"], request["text"]), ("safety-judged-confirmation", "Pin prox."))
        self.assertEqual(request["verdicts"], ["confirm", "fallback"])
        rows, pending = self.decide()
        self.assertEqual(rows["qa_pin_short"]["outcome"], "fallback-english")
        self.assertIn("confirmation", pending["qa_pin_short"])
        # The old confirm of the long text confirms nothing here; a confirm of the merged text does.
        self.judge(confirm_row("qa_pin_short", "Pin prox.", "Pin prox."))
        rows, pending = self.decide()
        self.assertNotIn("qa_pin_short", pending)
        self.assertNotIn("qa_pin", pending)
        self.assertEqual((rows["qa_pin_short"]["outcome"], rows["qa_pin_short"]["text"]),
                         ("safety-judged", "Pin prox."))
        # The full is checked with the decided short: it passes with the overflow warning, and its own
        # back-translation and confirm (from before) make it safety-agreed.
        self.assertEqual((rows["qa_pin"]["outcome"], rows["qa_pin"]["text"]), ("safety-agreed", "Pin e proximidade"))
        self.assertEqual([f["severity"] for f in rows["qa_pin"]["checks"]], ["warn"])
        self.assertIn("«Pin prox.»", rows["qa_pin"]["checks"][0]["message"])
        self.assertNotIn("qa_pin_short", self.back_targets())

    def test_an_earlier_refusal_then_merge_is_replayed(self):
        # unanimous_refusals()' recovery, on a short rung: the judge refused the agreed text, then
        # merged a text that fits both budgets; it still needs its back-translation and a confirm.
        self.write_drafts()
        self.back("qa_pin", "Pin e proximidade", "Pin and proximity")
        self.back("qa_pin_short", "proximid", "proximity")
        self.judge(confirm_row("qa_pin", "Pin e proximidade", "Pin and proximity"),
                   {"key": "qa_pin_short", "verdict": "fallback"})
        self.assertNotIn("qa_pin_short", self.requests())  # declined, for now
        self.judge({"key": "qa_pin_short", "verdict": "merge", "text": "Pin prox."})
        self.assertNotIn("qa_pin_short", self.requests())
        rows, pending = self.decide()
        self.assertEqual(pending["qa_pin_short"], "a back-translation of the judged safety text")
        self.assertEqual(self.back_targets(), {"qa_pin_short": "Pin prox."})
        self.back("qa_pin_short", "Pin prox.", "Pin prox.")
        self.assertEqual(self.requests()["qa_pin_short"]["reason"], "safety-judged-confirmation")
        rows, pending = self.decide()
        self.assertIn("confirmation", pending["qa_pin_short"])
        self.assertEqual(rows["qa_pin_short"]["outcome"], "fallback-english")
        self.judge(confirm_row("qa_pin_short", "Pin prox.", "Pin prox."))
        rows, pending = self.decide()
        self.assertNotIn("qa_pin_short", pending)
        self.assertEqual((rows["qa_pin_short"]["outcome"], rows["qa_pin_short"]["text"]),
                         ("safety-judged", "Pin prox."))
        self.assertEqual(rows["qa_pin"]["outcome"], "safety-agreed")

    def test_safety_merged_text_refused_keeps_english(self):
        self.write_drafts()
        self.settle_pin()
        self.judge({"key": "qa_pin_short", "verdict": "merge", "text": "Pin prox."})
        self.back("qa_pin_short", "Pin prox.", "Pin prox.")
        self.judge({"key": "qa_pin_short", "verdict": "fallback"})
        self.assertNotIn("qa_pin_short", self.requests())
        rows, pending = self.decide()
        self.assertNotIn("qa_pin_short", pending)
        self.assertEqual((rows["qa_pin_short"]["outcome"], rows["qa_pin"]["outcome"]),
                         ("fallback-english", "fallback-english"))
        self.assertIn("refused its merged short rung", rows["qa_pin_short"]["reason"])

    def test_no_change_when_the_short_fits(self):
        self.write_drafts({"qa_pin_short": "Pin prox.", "qa_dash_short": "Dash som"})
        self.back("qa_pin", "Pin e proximidade", "Pin and proximity")
        self.back("qa_pin_short", "Pin prox.", "Pin prox.")
        self.judge(confirm_row("qa_pin", "Pin e proximidade", "Pin and proximity"),
                   confirm_row("qa_pin_short", "Pin prox.", "Pin prox."))
        requests = self.requests()
        self.assertEqual([k for k, r in requests.items() if "stuck" in r], ["qa_star_short"])
        self.assertNotIn("qa_dash_short", requests)  # agreed
        self.assertEqual(requests["qa_pin_short"]["reason"], "safety-confirmation")
        rows, pending = self.decide()
        self.assertEqual(list(pending), ["qa_star_short"])
        self.assertEqual(rows["qa_pin"]["outcome"], "safety-agreed")
        self.assertEqual(rows["qa_pin_short"]["outcome"], "safety-agreed")
        self.assertEqual(rows["qa_dash"]["outcome"], "agreed")


if __name__ == "__main__":
    unittest.main()
