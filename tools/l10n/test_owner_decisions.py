"""Owner decisions survive the real decide and merge steps, or those steps stop (l10n/README.md, owner-decisions)."""

import contextlib
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import merge  # noqa: E402
import pack  # noqa: E402
import test_council  # noqa: E402
import translib  # noqa: E402

KEY = "common_not_reported"
TEXT = "Sin datos del dueño"
REASON = "owner decision 2026-11-08: synthesized for this test"


class OwnerDecisionFlowTest(test_council.Base):
    """Regenerating an area from its drafts and rulings, as the top-up does, keeps what the owner chose."""

    def setUp(self):
        super().setUp()
        self.draft_all()
        self.assertEqual(self.main("backtranslate", "google", "--tag", "es", "--area", "common"), 0)
        yes = dict((c, True) for c in council.SAFETY_CONFIRMATIONS)
        # The judge rules on everything the flow needs, except KEY: the drafts disagree on it and no judge ruled.
        self.judge([
            {"key": "common_move_widget", "verdict": "pick", "pick": "openai", "text": "Mover %1$s a la posición %2$d"},
            self.confirm("common_lock_waiting"),
            self.confirm("common_lock_waiting_short"),
            self.confirm("common_alarm_on", confirm=dict(yes, on_off_never_always_not_inverted=False)),
            {"key": "common_disclaimer", "verdict": "fallback", "reason": "softened"},
        ])

    def pin(self, key=KEY, text=TEXT, **changes):
        sources = dict((s.key, s) for s in translib.load_source(self.paths)[0]["common"])
        s = sources[key]
        row = dict(format=translib.OWNER_DECISION_FORMAT, area="common", key=key, text=text, decided="2026-11-08",
                   reason=REASON, english=s.items["other"], source_sha1=translib.source_sha1(s.kind, s.items))
        row.update(changes)
        self.repo.write("l10n/owner-decisions/es.jsonl", json.dumps(row, ensure_ascii=False) + "\n")

    def run_tool(self, tool, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tool.main(list(args))
        return code, out.getvalue() + err.getvalue()

    def repack(self):
        code, out = self.run_tool(pack, "--root", self.repo.root, "--no-java", "--tag", "es", "--area", "common")
        self.assertEqual(code, 0, out)

    def decide(self, *extra):
        out, err = self.out.tell(), self.err.tell()
        code = self.main("decide", "--tag", "es", "--area", "common", *extra)
        return code, self.out.getvalue()[out:] + self.err.getvalue()[err:]

    def merged(self, *extra):
        return self.run_tool(merge, "--root", self.repo.root, "--tag", "es", "--aapt", "off", *(extra or (
            "--area", "common")))

    def test_decide_and_merge_keep_the_owner_text_with_no_model_credited(self):
        self.pin()
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 0, out)  # KEY is not pending, though no judge ruled on it
        row = self.decisions()[KEY]
        self.assertEqual(row["outcome"], translib.OWNER_DECIDED)
        self.assertEqual(row["text"], TEXT)
        self.assertEqual(row["reason"], REASON)
        self.assertIsNone(row["judge"])
        self.assertIsNone(row["backtranslation"])
        self.assertIsNone(row["models"]["judge"])
        self.assertIsNone(row["models"]["backtranslator"])
        self.assertTrue(row["alternatives"])  # the council's drafts stay on the record
        first = self.repo.read("l10n/decisions/es/common.jsonl")
        self.assertEqual(self.decide()[0], 0)
        self.assertEqual(self.repo.read("l10n/decisions/es/common.jsonl"), first)  # a later decide is byte-identical
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        sha = translib.source_sha1("string", {"other": "Not reported"})
        self.assertIn('<!-- src:%s -->\n    <string name="%s">%s</string>' % (sha, KEY, TEXT),
                      self.repo.read("res/values-es/strings_common.xml"))
        code, out = self.merged("--check")
        self.assertEqual(code, 0, out)

    def test_a_row_without_the_owner_text_fails_the_merge_check(self):
        self.pin()
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        self.assertEqual(self.merged()[0], 0)
        rows = translib.read_jsonl(self.paths.decisions("es", "common"))
        for row in rows:
            if row["key"] == KEY:
                row["outcome"], row["text"] = "agreed", "No informado"
        translib.write_jsonl(self.paths.decisions("es", "common"), rows)
        self.assertEqual(self.merged()[0], 1)
        code, out = self.merged("--check")
        self.assertEqual(code, 1, out)
        self.assertIn("not the owner's text", out)

    def test_a_changed_english_stops_decide_and_keeps_the_old_decisions(self):
        self.pin()
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        before = self.repo.read("l10n/decisions/es/common.jsonl")
        source = "res/values/strings_common.xml"
        self.repo.write(source, self.repo.read(source).replace(">Not reported<", ">Not given<"))
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 2, out)
        self.assertIn("the English changed since the owner decided", out)
        self.assertEqual(self.repo.read("l10n/decisions/es/common.jsonl"), before)
        code, out = self.merged("--check")
        self.assertEqual(code, 1, out)
        self.assertIn("the English changed since the owner decided", out)

    def test_a_ladder_that_would_drop_the_owner_text_stops_decide(self):
        source = "res/values/strings_common.xml"
        self.repo.write(source, self.repo.read(source).replace("</resources>", (
            '    <string name="common_cruise" volt:ladder="full">Cruise control</string>\n'
            '    <string name="common_cruise_short" volt:ladder="short">Cruise</string>\n</resources>')))
        self.repack()
        self.pin("common_cruise", "Cruise Control")
        code, out = self.decide("--final")  # the short rung has no drafts, so its group falls back to English
        self.assertEqual(code, 2, out)
        self.assertIn("would discard the owner's decision", out)
        self.assertFalse(os.path.exists(self.paths.decisions("es", "common")))

    def test_a_lock_or_alarm_key_keeps_the_safety_path(self):
        self.pin("common_alarm_on", "Alarma encendida")
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 2, out)
        self.assertIn("safety path", out)

    def test_text_that_fails_the_checks_stops_decide(self):
        self.pin(KEY, "Sin datos %1$s")
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 2, out)
        self.assertIn("fails the checks", out)

    def test_malformed_lines_stop_decide(self):
        for changes, says in (({"format": "other"}, "format must be"), ({"decided": "soon"}, "a date"),
                              ({"area": "ble"}, "listed under area ble"), ({"reason": ""}, "filled in")):
            self.pin(**changes)
            self.repack()
            code, out = self.decide()
            self.assertEqual(code, 2, out)
            self.assertIn(says, out)

    def test_judge_requests_leave_out_the_owner_text(self):
        self.pin()
        self.repack()
        self.assertEqual(self.main("judge-requests", "--tag", "es", "--area", "common"), 0)
        requested = [r["key"] for r in translib.read_jsonl(self.repo.path("l10n/work/es/common/judge-requests.jsonl"))]
        self.assertNotIn(KEY, requested)
        self.assertIn("common_move_widget", requested)
