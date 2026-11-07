"""Top-ups of an existing draft merge into it (mocked HTTP only: no test reaches the network or reads a keys file).

A run of the draft's model in its style replaces the keys it drafts and keeps the rest; another model or
style replaces the draft only when it covers every key, and --all starts the draft over.
"""

import contextlib
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import test_council  # noqa: E402
import translib  # noqa: E402

DRAFT = "l10n/work/es/common/drafts/openai.xml"
STALE_KEY = "common_cancel"  # its English changes in these tests
TOPPED = "NUEVO"


class TopUpBase(test_council.Base):
    def setUp(self):
        super().setUp()
        self.fake = self.transport(**{"gpt-6.1-terra": cf.drafting_reply(cf.SPANISH),
                                      "gpt-6.1-sol": cf.drafting_reply(cf.SPANISH)})

    def repack(self):
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        with contextlib.redirect_stdout(io.StringIO()):
            pack.write_pack(self.paths, pack.build_pack(self.paths, "es", "common", measurer))

    def settle_strings(self):
        """res/values-es holds a current translation of every plain string, so only the plurals (no
        translation) are new: a later draft is a top-up of those."""
        entries = pack.load_pack(self.paths, "es", "common")["entries"]
        rows = ['    <!-- src:%s -->\n    <string name="%s">Hecho</string>\n' % (e["source_sha1"], e["key"])
                for e in entries if e["kind"] == "string"]
        self.repo.write("res/values-es/strings_common.xml", "<resources>\n" + "".join(rows) + "</resources>\n")
        self.repack()

    def change_english(self, old, new):
        path = "res/values/strings_common.xml"
        self.assertIn(old, self.repo.read(path))
        self.repo.write(path, self.repo.read(path).replace(old, new))
        self.repack()

    def draft(self, *extra, code=0):
        self.fake.requests[:] = []
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common", *extra), code,
                         self.err.getvalue())

    def requested(self):
        """The keys this run's draft prompts asked for."""
        keys = []
        for method, url, _, payload in self.fake.requests:
            if method == "POST":
                keys.extend(cf.requested_keys(payload["input"]))
        return keys

    def read(self):
        return translib.read_draft(self.repo.path(DRAFT))

    def text(self, draft, key):
        return list(draft.entries[key].items.values())[0]

    def topped_reply(self, model="gpt-6.1-terra"):
        self.fake.replies[model] = cf.drafting_reply(cf.SPANISH, overrides=dict(
            (k, TOPPED if isinstance(v, str) else dict((q, TOPPED + " %1$d") for q in v)) for k, v in cf.SPANISH.items()))

    def set_attempt(self, key):
        path = self.repo.path(DRAFT)
        text = self.repo.read(DRAFT)
        changed = re.sub(r'(<(?:string|plurals) name="%s")' % key, r'\1 volt:attempt="2"', text, count=1)
        self.assertNotEqual(text, changed)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(changed)


class ApiTopUpTest(TopUpBase):
    def test_first_draft_is_unchanged(self):
        self.draft()
        draft = self.read()
        self.assertEqual(sorted(self.requested()), sorted(cf.SPANISH))
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))
        self.assertEqual((draft.model, draft.prompt), ("gpt-6.1-terra", "standard"))
        self.assertEqual(set(e.attempt for e in draft.entries.values()), {1})
        runs = translib.read_jsonl(self.repo.path("l10n/work/es/common/runs.jsonl"))
        self.assertFalse(any(r.get("top_up") for r in runs))

    def test_top_up_keeps_the_other_keys(self):
        self.draft()
        self.settle_strings()
        self.set_attempt("common_dont")  # a kept key that had its redraft
        self.topped_reply()
        self.draft()
        asked = self.requested()
        self.assertTrue(asked)
        self.assertLess(len(asked), len(cf.SPANISH))
        draft = self.read()
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))
        for key in cf.SPANISH:
            if key in asked:
                self.assertTrue(self.text(draft, key).startswith(TOPPED), key)
            else:
                self.assertEqual(draft.entries[key].items, translib.items_of(
                    draft.entries[key].kind, cf.SPANISH[key]), key)
        self.assertNotIn("common_dont", asked)
        self.assertEqual(draft.entries["common_dont"].attempt, 2)
        self.assertEqual((draft.model, draft.prompt), ("gpt-6.1-terra", "standard"))
        self.assertIn("keys in the draft", self.out.getvalue())
        runs = translib.read_jsonl(self.repo.path("l10n/work/es/common/runs.jsonl"))
        self.assertTrue([r for r in runs if r["stage"] == "draft"][-1]["top_up"])

    def test_top_up_keeps_the_drafts_style_and_redraft_marker(self):
        self.draft("--style", "glossary-first")
        self.settle_strings()
        asked_before = [e["key"] for e in pack.load_pack(self.paths, "es", "common")["entries"]
                        if e["status"] != "current"]
        self.set_attempt(asked_before[0])  # same English: its redraft is spent
        self.topped_reply()
        self.draft()  # no --style: the draft's
        draft = self.read()
        self.assertEqual(draft.prompt, "glossary-first")
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))
        self.assertEqual(draft.entries[asked_before[0]].attempt, 2)
        self.assertTrue(self.text(draft, asked_before[0]).startswith(TOPPED))

    def test_top_up_keeps_what_the_redraft_missed(self):
        self.draft()
        self.settle_strings()
        needed = [e["key"] for e in pack.load_pack(self.paths, "es", "common")["entries"] if e["status"] != "current"]
        missed = {needed[0]: self.sha()[needed[0]]}
        path = self.repo.path("l10n/work/es/common/runs.jsonl")
        translib.write_jsonl(path, translib.read_jsonl(path) + [
            {"stage": "redraft", "draft": "openai", "provider": "openai", "missing": missed}])
        self.topped_reply()
        self.draft()
        self.assertEqual(council.retried_missing(self.paths, "es", "common")["openai"], missed)
        self.assertEqual(self.read().entries[needed[0]].attempt, 2)  # it has had both attempts

    def test_stale_key_is_replaced(self):
        self.draft()
        self.settle_strings()
        old_src = self.read().entries[STALE_KEY].src
        self.change_english(">Cancel<", ">Dismiss<")
        self.topped_reply()
        self.draft()
        self.assertIn(STALE_KEY, self.requested())
        draft = self.read()
        entry = draft.entries[STALE_KEY]
        self.assertEqual(self.text(draft, STALE_KEY), TOPPED)
        self.assertNotEqual(entry.src, old_src)
        self.assertEqual(entry.src, self.sha()[STALE_KEY])
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))

    def test_stale_key_left_out_of_the_reply_is_dropped(self):
        self.draft()
        self.settle_strings()
        self.change_english(">Cancel<", ">Dismiss<")
        self.fake.replies["gpt-6.1-terra"] = cf.drafting_reply(
            dict((k, v) for k, v in cf.SPANISH.items() if k != STALE_KEY))
        self.draft()
        self.assertIn("%s: missing from the reply" % STALE_KEY, self.out.getvalue())
        draft = self.read()
        self.assertNotIn(STALE_KEY, draft.entries)  # redraft sends it as lacking
        self.assertEqual(len(draft.entries), len(cf.SPANISH) - 1)

    def test_other_model_or_style_is_refused_unless_it_starts_over(self):
        self.draft()
        self.settle_strings()
        before = self.repo.read(DRAFT)
        for extra in (("--model", "gpt-6.1-sol"), ("--style", "glossary-first")):
            self.err.truncate(0), self.err.seek(0)
            self.draft(*extra, code=2)
            self.assertIn("a draft names one model and one style", self.err.getvalue())
            self.assertIn("top up with --model gpt-6.1-terra --style standard, or pass --all", self.err.getvalue())
            self.assertEqual(self.requested(), [])  # refused before any prompt was sent
            self.assertEqual(self.repo.read(DRAFT), before)
        self.topped_reply("gpt-6.1-sol")
        self.draft("--model", "gpt-6.1-sol", "--all")
        self.assertEqual(sorted(self.requested()), sorted(cf.SPANISH))
        draft = self.read()
        self.assertEqual(draft.model, "gpt-6.1-sol")
        self.assertTrue(all(list(e.items.values())[0].startswith(TOPPED) for e in draft.entries.values()))

    def test_other_model_covering_every_key_relabels(self):
        self.draft()  # nothing settled: every key is needed again
        self.topped_reply("gpt-6.1-sol")
        self.draft("--model", "gpt-6.1-sol")
        draft = self.read()
        self.assertEqual(draft.model, "gpt-6.1-sol")
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))


class ClaudeImportTopUpTest(TopUpBase):
    PATH = "l10n/work/es/common/drafts/claude-opus-context.xml"

    def test_partial_reply_merges(self):
        self.assertEqual(self.claude_reply(dict(cf.SPANISH)), 0)
        self.claude_reply({"common_cancel": "Anular"})
        draft = translib.read_draft(self.repo.path(self.PATH))
        self.assertEqual(sorted(draft.entries), sorted(cf.SPANISH))
        self.assertEqual(self.text(draft, "common_cancel"), "Anular")
        self.assertEqual(self.text(draft, "common_dont"), cf.SPANISH["common_dont"])

    def test_partial_reply_of_another_model_is_refused(self):
        self.assertEqual(self.claude_reply(dict(cf.SPANISH)), 0)
        before = self.repo.read(self.PATH)
        self.assertEqual(self.claude_reply({"common_cancel": "Anular"}, model="claude-sonnet-5"), 2)
        self.assertIn("a draft names one model and one style", self.err.getvalue())
        self.assertEqual(self.repo.read(self.PATH), before)
        # Every key: the draft is replaced, as before.
        self.assertEqual(self.claude_reply(dict(cf.SPANISH), model="claude-sonnet-5"), 0)
        self.assertEqual(translib.read_draft(self.repo.path(self.PATH)).model, "claude-sonnet-5")


if __name__ == "__main__":
    unittest.main()
