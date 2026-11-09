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

    def pin(self, key=KEY, text=TEXT, append=False, **changes):
        sources = dict((s.key, s) for s in translib.load_source(self.paths)[0]["common"])
        s = sources[key]
        row = dict(format=translib.OWNER_DECISION_FORMAT, area="common", key=key, text=text, decided="2026-11-08",
                   reason=REASON, english=s.items["other"], source_sha1=translib.source_sha1(s.kind, s.items))
        row.update(changes)
        path, old = "l10n/owner-decisions/es.jsonl", ""
        if append:
            old = self.repo.read(path)
        self.repo.write(path, old + json.dumps(row, ensure_ascii=False) + "\n")

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

    def merged_with_pin(self, key=KEY, text=TEXT):
        """Pin key, decide and merge, so a later change has a current resource to harm; (resource path, its bytes)."""
        self.pin(key, text)
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        return "res/values-es/strings_common.xml", self.repo.read("res/values-es/strings_common.xml")

    def edit_decision(self, key=KEY, **changes):
        rows = translib.read_jsonl(self.paths.decisions("es", "common"))
        for row in rows:
            if row["key"] == key:
                row.update(changes)
        translib.write_jsonl(self.paths.decisions("es", "common"), rows)

    def change_english(self):
        source = "res/values/strings_common.xml"
        self.repo.write(source, self.repo.read(source).replace(">Not reported<", ">Not given<"))

    def assert_refused(self, resource, before, says, *merge_args):
        """Both the merge and its --check fail saying so, and the merge left the resource as it was."""
        code, out = self.merged(*merge_args)
        self.assertEqual(code, 1, out)
        self.assertIn(says, out)
        self.assertIn("nothing was written", out)
        self.assertEqual(self.repo.read(resource), before)
        code, out = self.merged("--check")
        self.assertEqual(code, 1, out)
        self.assertIn(says, out)

    def test_deleting_the_owner_file_leaves_a_row_that_fails_both_merges(self):
        resource, before = self.merged_with_pin()
        os.remove(self.repo.path("l10n/owner-decisions/es.jsonl"))
        self.assert_refused(resource, before, "owner-decided but l10n/owner-decisions/es.jsonl has no line for it")

    def test_removing_one_line_is_caught_while_the_file_stays(self):
        self.pin("common_move_widget", "Mover %1$s al sitio %2$d")
        self.pin(append=True)
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        self.assertEqual(self.merged()[0], 0)
        before = self.repo.read("res/values-es/strings_common.xml")
        self.pin()  # rewrites the file with KEY's line alone
        self.assert_refused("res/values-es/strings_common.xml", before, "common_move_widget is owner-decided but")

    def test_a_withdrawn_decision_goes_through_decide(self):
        self.pin("common_move_widget", "Mover %1$s al sitio %2$d")
        self.pin(append=True)  # KEY has no ruling, so it needs its pin for decide to finish
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        self.assertEqual(self.merged()[0], 0)
        self.pin()  # the owner withdrew the other decision
        self.assertEqual(self.merged("--check")[0], 1)
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        self.assertEqual(self.decisions()["common_move_widget"]["outcome"], "judged")
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        code, out = self.merged("--check")
        self.assertEqual(code, 0, out)

    def assert_withdrawal_falls_back(self, key, text, reason, withdraw):
        """With key's line gone, decide writes its usual fallback and both merge forms pass, without the owner's text."""
        resource = "res/values-es/strings_common.xml"
        self.assertEqual(self.decisions()[key]["outcome"], translib.OWNER_DECIDED)
        self.assertIn(text, self.repo.read(resource))
        withdraw()
        self.assertEqual(self.merged("--check")[0], 1)  # removing the line alone gets past nothing
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 0, out)
        row = self.decisions()[key]
        self.assertEqual(row["outcome"], "fallback-english")
        self.assertIsNone(row["text"])
        self.assertTrue(row["reason"].startswith(reason), row["reason"])
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        code, out = self.merged("--check")
        self.assertEqual(code, 0, out)
        self.assertNotIn(text, self.repo.read(resource))

    def test_withdrawing_a_key_with_no_drafts_falls_back(self):
        key, text = "common_owner_only", "Solo del dueño"
        source = "res/values/strings_common.xml"
        self.repo.write(source, self.repo.read(source).replace(
            "</resources>", '    <string name="%s">Only the owner</string>\n</resources>' % key))
        self.pin()  # KEY has no ruling, so it keeps its pin for decide to finish
        self.pin(key, text, append=True)
        self.repack()
        self.assertEqual(self.decide()[0], 0)
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        self.assertEqual(self.decisions()[key]["alternatives"], [])
        self.assertEqual(self.decisions()[key]["stale_drafts"], [])
        self.assert_withdrawal_falls_back(key, text, "no drafts", self.pin)  # self.pin() rewrites the file without key

    def test_withdrawing_a_key_with_only_stale_drafts_falls_back(self):
        self.change_english()  # every draft of KEY now translates an older English
        self.merged_with_pin()
        self.assertTrue(self.decisions()[KEY]["stale_drafts"])
        self.assert_withdrawal_falls_back(KEY, TEXT, "no current drafts", lambda: os.remove(
            self.repo.path("l10n/owner-decisions/es.jsonl")))

    def test_updating_the_english_and_pin_before_decide_keeps_the_key(self):
        resource, before = self.merged_with_pin()
        self.change_english()
        self.pin()  # the owner kept their text for the new English; decide has not run yet
        self.assert_refused(resource, before, "older English than the owner's line")
        self.assertEqual(self.decisions()[KEY]["outcome"], translib.OWNER_DECIDED)
        self.repack()
        code, out = self.decide()
        self.assertEqual(code, 0, out)
        code, out = self.merged()
        self.assertEqual(code, 0, out)
        sha = translib.source_sha1("string", {"other": "Not given"})
        self.assertIn('<!-- src:%s -->\n    <string name="%s">%s</string>' % (sha, KEY, TEXT), self.repo.read(resource))
        code, out = self.merged("--check")
        self.assertEqual(code, 0, out)

    def test_a_failed_merge_leaves_the_files_unchanged(self):
        resource, before = self.merged_with_pin()
        self.edit_decision(text="Otro texto")
        for args in (("--area", "common"), ("--all",)):
            code, out = self.merged(*args)
            self.assertEqual(code, 1, out)
            self.assertIn("not the owner's text", out)
            self.assertIn("nothing was written", out)
            self.assertEqual(self.repo.read(resource), before)

    def test_a_failed_merge_does_not_create_a_missing_file(self):
        resource = self.merged_with_pin()[0]
        os.remove(self.repo.path(resource))
        self.edit_decision(outcome="agreed", text="No informado")
        code, out = self.merged("--all")
        self.assertEqual(code, 1, out)
        self.assertFalse(os.path.exists(self.repo.path(resource)))

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
