"""drafts-status and the safety audit of Claude-only drafts, which no council step ever reads."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import test_council_chunks as chunks  # noqa: E402
import translib  # noqa: E402

READER = "claude-sonnet-5"
BACKS = {
    "common_lock_waiting": "Waiting for the scooter",
    "common_lock_waiting_short": "Waiting",
    "common_disclaimer": "Volt is not affiliated with NAVEE",
    "common_alarm_on": "Alarm activated",
}
SAFETY = sorted(BACKS)
PLURAL_BACK = {"one": "%1$d scooter skipped", "many": "%1$d of scooters skipped", "other": "%1$d scooters skipped"}


def tree(root):
    """{path: content hash} of every file under root but the font."""
    out = {}
    for directory, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".ttf"):
                path = os.path.join(directory, name)
                with open(path, "rb") as handle:
                    out[path] = hashlib.sha1(handle.read()).hexdigest()
    return out


class AuditBase(chunks.ChunkBase):
    AREA = ("--tag", "es", "--area", "common", "--name", "opus")

    def drafted(self, overrides=None):
        self.answer_all(self.prompts(keys="100"), overrides=overrides)
        self.assertEqual(self.do_import(), 0)

    def audit_prompts(self, *extra, code=0):
        self.assertEqual(self.main("audit-prompt", *(self.AREA + extra)), code, self.err.getvalue())
        if code:
            return []
        directory = self.paths.work("es", "common", "audit", "prompts")
        return sorted(os.path.join(directory, f) for f in os.listdir(directory) if f.endswith(".md"))

    def sidecar(self):
        current = self.repo.read("l10n/work/es/common/audit/prompts/claude-opus.current").strip()
        return json.loads(self.repo.read("l10n/work/es/common/audit/prompts/claude-opus.%s.json" % current))

    def back(self, path, overrides=None):
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        body = json.loads(text.split("## Texts\n\n", 1)[1])
        items = self.sidecar()["items"]
        strings = dict((e["id"], (overrides or {}).get(items[e["id"]]["key"], BACKS.get(items[e["id"]]["key"]))) for e in body)
        reply = re.search(r"Write the JSON object to `([^`]+)`", text).group(1)
        self.repo.write(reply, json.dumps({"strings": strings}))
        return reply

    def compare(self, *extra, model="claude-opus-5-5"):
        return self.main("audit-compare", *(self.AREA + ("--from-chunks", "--model", model) + extra))

    def compared(self):
        return council.read_audit_table(self.paths.work("es", "common", "audit", "compare.tsv"),
                                        council.AUDIT_COMPARE_HEADER)

    def record(self, value, *extra):
        path = self.repo.path("verdicts.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False)
        return self.main("audit-record", *(self.AREA + ("--from", path) + extra))

    def audited(self, overrides=None):
        for path in self.audit_prompts():
            self.back(path, overrides)
        self.assertEqual(self.compare(), 0, self.out.getvalue())

    def status(self, *extra):
        self.out.truncate(0)
        self.out.seek(0)
        self.assertEqual(self.main("drafts-status", "--tag", "es", "--json", *extra), 0, self.err.getvalue())
        return json.loads(self.out.getvalue())


class AuditTest(AuditBase):
    def test_the_prompt_holds_only_translations(self):
        self.drafted()
        paths = self.audit_prompts()
        self.assertEqual(len(paths), 1)
        self.assertTrue(paths[0].endswith("audit/prompts/claude-opus.chunk-1-of-1.md"))
        text = self.repo.read(os.path.relpath(paths[0], self.repo.root))
        self.assertIn("Esperando al patinete", text)
        self.assertIn("Alarma activada", text)
        self.assertNotIn("Cancelar", text)  # not a safety key
        # No English, and no key name, which would say what the English means.
        sources, _ = translib.load_source(self.paths)
        for s in sources["common"]:
            self.assertNotIn(s.key, text)
            if s.key in BACKS and s.key != "common_lock_waiting_short":
                self.assertNotIn(s.items["other"], text)
        body = json.loads(text.split("## Texts\n\n", 1)[1])
        for item in body:
            self.assertEqual(set(item), {"id", "kind", "screen", "text"})
        screens = dict((self.sidecar()["items"][e["id"]]["key"], e["screen"]) for e in body)
        self.assertEqual(screens["common_lock_waiting"], "Home lock row")
        self.assertEqual(screens["common_lock_waiting_short"], "Home lock row")  # its ladder's
        self.assertEqual(screens["common_alarm_on"], "common")  # no screen: the area
        self.assertIn("audit-compare --tag es --area common --name opus --from-chunks --model", text)

    def test_chunking(self):
        self.drafted()
        paths = self.audit_prompts("--chunk-keys", "1")
        self.assertEqual(len(paths), 3)  # the ladder stays together
        self.assertEqual(self.main("audit-compare", *(self.AREA + ("--from-chunks", "--model", READER))), 2)
        self.assertIn("nothing was written", self.err.getvalue())
        self.back(paths[0])
        self.assertEqual(self.compare("--partial"), 1)  # filed, and not complete
        self.assertLess(len(self.compared()), 4)
        self.assertGreater(len(self.compared()), 0)

    def test_compare_joins_the_english(self):
        self.drafted()
        self.audited()
        rows = self.compared()
        self.assertEqual(sorted(k for _, k in rows), SAFETY)
        row = rows[("claude-opus", "common_alarm_on")]
        self.assertEqual((row["key"], row["english"], row["translation"], row["back-translation"]),
                         ("common_alarm_on", "Alarm on", "Alarma activada", "Alarm activated"))
        head = [l for l in self.repo.read("l10n/work/es/common/audit/compare.tsv").splitlines()
                if not l.startswith("#")][0].split("\t")
        self.assertEqual(head[:4], ["key", "english", "translation", "back-translation"])

    def test_a_rerun_writes_the_same_bytes_and_special_characters_survive(self):
        self.drafted(overrides={"common_alarm_on": "Alarma\tactivada \\n"})
        self.audited(overrides={"common_alarm_on": 'Line\nbreak "and" tab\t'})
        path = "l10n/work/es/common/audit/compare.tsv"
        first = self.repo.read(path)
        self.assertEqual(self.compare(), 0)
        self.assertEqual(self.repo.read(path), first)
        row = self.compared()[("claude-opus", "common_alarm_on")]
        self.assertEqual(row["back-translation"], 'Line\nbreak "and" tab\t')
        self.assertEqual(row["translation"], "Alarma\tactivada \\n")

    def test_a_changed_english_or_text_is_left_out(self):
        self.drafted()
        paths = self.audit_prompts()
        self.back(paths[0])
        self.change_english(">Alarm on<", ">Alarm enabled<")
        self.assertEqual(self.compare(), 1)
        self.assertIn("common_alarm_on: the English changed", self.out.getvalue())
        self.assertNotIn(("claude-opus", "common_alarm_on"), self.compared())
        self.change_english(">Alarm enabled<", ">Alarm on<")
        # A redraft between prompt and compare: the back-translation read the old text.
        draft = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.repo.write("l10n/work/es/common/drafts/claude-opus.xml", draft.replace("Alarma activada", "Alarma apagada"))
        self.assertEqual(self.compare(), 1)
        self.assertIn("common_alarm_on: the draft's text changed", self.out.getvalue())
        self.assertNotIn(("claude-opus", "common_alarm_on"), self.compared())

    def test_a_stale_or_missing_key_is_not_audited(self):
        self.drafted()
        self.change_english(">Alarm on<", ">Alarm enabled<")
        self.repack()
        self.audit_prompts()
        self.assertIn("common_alarm_on: the draft's text is of other English", self.out.getvalue())

    def test_a_same_model_back_translation_is_noted_not_refused(self):
        self.drafted()
        self.audited()
        self.assertIn("claude-opus-5-5 also drafted", self.out.getvalue())
        self.assertEqual(self.compared()[("claude-opus", "common_alarm_on")]["model"], "claude-opus-5-5")

    def test_top_up_only_changed_keys(self):
        self.drafted()
        self.audited()
        self.assertEqual(self.main("audit-prompt", *(self.AREA + ("--top-up",))), 0)
        self.assertIn("no safety keys to audit", self.out.getvalue())
        draft = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.repo.write("l10n/work/es/common/drafts/claude-opus.xml", draft.replace("Alarma activada", "Alarma encendida"))
        paths = self.audit_prompts("--top-up")
        self.assertEqual(list(self.sidecar()["items"].values())[0]["key"], "common_alarm_on")
        self.assertEqual(len(self.sidecar()["items"]), 1)
        self.back(paths[0], {"common_alarm_on": "Alarm lit"})
        self.assertEqual(self.compare(), 0)
        rows = self.compared()
        self.assertEqual(rows[("claude-opus", "common_alarm_on")]["back-translation"], "Alarm lit")
        self.assertEqual(rows[("claude-opus", "common_disclaimer")]["back-translation"], BACKS["common_disclaimer"])

    def test_record_and_status_counts(self):
        self.drafted()
        self.audited()
        self.assertEqual(self.record({"verdicts": [
            {"key": "common_alarm_on", "ok": True, "problem": ""},
            {"key": "common_disclaimer", "ok": False, "problem": "softened: \"not\ttied\""}], "model": READER}), 0)
        rows = council.read_audit_table(self.paths.work("es", "common", "audit", "verdicts.tsv"),
                                        council.AUDIT_VERDICT_HEADER)
        flagged = rows[("claude-opus", "common_disclaimer")]
        self.assertEqual((flagged["ok"], flagged["problem"], flagged["reader"]),
                         ("no", "softened: \"not\ttied\"", READER))
        status = self.status()["areas"]["common"]
        self.assertEqual((status["safety"], status["audited"], status["flagged"]), (4, 2, 1))
        # A redraft of the flagged key takes its verdict out of the counts.
        draft = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.repo.write("l10n/work/es/common/drafts/claude-opus.xml",
                        draft.replace("no está afiliada", "no tiene afiliación"))
        status = self.status()["areas"]["common"]
        self.assertEqual((status["audited"], status["flagged"]), (1, 0))

    def test_record_refuses_a_bad_file_whole(self):
        self.drafted()
        self.audited()
        good = {"key": "common_alarm_on", "ok": True}
        path = self.paths.work("es", "common", "audit", "verdicts.tsv")
        for bad, message in (
                ({"key": "common_cancel", "ok": True}, "no row of claude-opus"),  # not audited
                ({"key": "common_disclaimer", "ok": "yes"}, "ok must be true or false"),
                ({"key": "common_disclaimer", "ok": False}, "a false verdict needs a problem"),
                (dict(good), "two verdicts"),
                ({"ok": True}, "a verdict needs a key")):
            self.assertEqual(self.record({"verdicts": [good, bad]}), 2, bad)
            self.assertIn(message, self.err.getvalue())
            self.assertFalse(os.path.exists(path))
        self.assertEqual(self.record({"key": "x"}), 2)
        self.assertEqual(self.record({"verdicts": [good], "compare_sha1": "0" * 40}), 2)
        self.assertIn("compare.tsv changed", self.err.getvalue())
        sha = council.file_sha1(self.paths.work("es", "common", "audit", "compare.tsv"))
        self.assertEqual(self.record({"verdicts": [good], "compare_sha1": sha}), 0)
        self.assertEqual(self.record([good]), 0)  # a bare list works too

    def test_a_stale_pack_refuses(self):
        self.drafted()
        self.change_english('name="common_cancel"', 'name="common_cancel" volt:safety="alarm"')
        self.audit_prompts(code=2)
        self.assertIn("the safety marks changed: common_cancel", self.err.getvalue())
        self.repack()
        self.audit_prompts()
        self.assertIn("common_cancel", [i["key"] for i in self.sidecar()["items"].values()])

    def test_a_new_reader_never_gets_the_last_readers_reply(self):
        self.drafted()
        self.audited()
        first = self.sidecar()["id"]
        rows = self.repo.read("l10n/work/es/common/audit/compare.tsv")
        # The same set under another model: refused.
        self.assertEqual(self.compare(model="gpt-6.1-terra"), 2)
        self.assertIn("filed only under the model that wrote it", self.err.getvalue())
        # A new audit-prompt has its own reply folder, so nothing is there to adopt.
        paths = self.audit_prompts()
        self.assertNotEqual(self.sidecar()["id"], first)
        self.assertEqual(self.compare(model="gpt-6.1-terra"), 2)
        self.assertIn("no usable reply", self.err.getvalue())
        self.assertEqual(self.repo.read("l10n/work/es/common/audit/compare.tsv"), rows)
        self.back(paths[0], {"common_alarm_on": "Alarm on, says the second reader"})
        self.assertEqual(self.compare(model="gpt-6.1-terra"), 0)
        row = self.compared()[("claude-opus", "common_alarm_on")]
        self.assertEqual((row["model"], row["back-translation"]), ("gpt-6.1-terra", "Alarm on, says the second reader"))

    def test_rewriting_an_unanswered_audit_prompt_keeps_the_set(self):
        self.drafted()
        paths = self.audit_prompts()
        first = self.sidecar()["id"]
        self.back(paths[0])
        self.audit_prompts()
        self.assertEqual(self.sidecar()["id"], first)
        self.assertEqual(self.compare(), 0)

    def test_another_drafts_rows_do_not_change_the_set(self):
        self.drafted()
        self.audit_prompts()
        first = self.sidecar()["id"]
        rows = self.compared()
        rows[("claude-other", "common_alarm_on")] = dict.fromkeys(council.AUDIT_COMPARE_HEADER, "x")
        rows[("claude-other", "common_alarm_on")].update(key="common_alarm_on", draft="claude-other")
        council.write_audit_table(self.paths.work("es", "common", "audit", "compare.tsv"),
                                  council.AUDIT_COMPARE_HEADER, rows, ["common_alarm_on"])
        self.audit_prompts()
        self.assertEqual(self.sidecar()["id"], first)

    def test_the_run_log_does_not_call_another_vendor_claude(self):
        self.drafted()
        for path in self.audit_prompts():
            self.back(path)
        self.assertEqual(self.compare(model="gpt-6.1-terra"), 0)
        last = translib.read_jsonl(self.paths.work("es", "common", "runs.jsonl"))[-1]
        self.assertEqual((last["stage"], last["provider"], last["model"]), ("audit-compare", "other", "gpt-6.1-terra"))

    def test_a_lone_surrogate_leaves_the_record_whole(self):
        self.drafted()
        self.audited()
        self.back(self.audit_prompts()[0], {"common_alarm_on": "bad\ud83d"})
        self.assertEqual(self.compare(), 1)
        self.assertIn("common_alarm_on (s", self.out.getvalue())
        self.assertIn("a lone surrogate", self.out.getvalue())
        rows = self.compared()
        self.assertEqual(sorted(k for _, k in rows), SAFETY)
        self.assertEqual(rows[("claude-opus", "common_alarm_on")]["back-translation"], BACKS["common_alarm_on"])

    def test_write_text_leaves_the_file_when_it_cannot_encode(self):
        path = self.repo.path("record.txt")
        translib.l10nlib.write_text(path, "whole\n")
        with self.assertRaises(UnicodeEncodeError):
            translib.l10nlib.write_text(path, "bad\ud83d")
        self.assertEqual(translib.l10nlib.read_text(path), "whole\n")

    def test_a_verdict_is_on_one_back_translation(self):
        self.drafted()
        self.audited()
        self.assertEqual(self.record({"verdicts": [{"key": k, "ok": True} for k in SAFETY]}), 0)
        self.assertEqual(self.status()["areas"]["common"]["audited"], 4)
        paths = self.audit_prompts()
        self.back(paths[0], {"common_alarm_on": "OPPOSITE MEANING"})
        self.out.truncate(0)
        self.out.seek(0)
        self.assertEqual(self.compare(model=READER), 0)
        self.assertIn("1 verdicts judged another back-translation", self.out.getvalue())
        self.assertIn("common_alarm_on", self.out.getvalue())
        status = self.status()["areas"]["common"]
        self.assertEqual((status["audited"], status["flagged"]), (3, 0))

    def plural_safety_key(self):
        self.change_english('<plurals name="common_scooters_skipped"',
                            '<plurals name="common_scooters_skipped" volt:safety="lock"')
        self.repack()
        self.drafted()

    def test_a_plural_safety_key_keeps_its_verdict(self):
        self.plural_safety_key()
        self.audited({"common_scooters_skipped": PLURAL_BACK})
        keys = SAFETY + ["common_scooters_skipped"]
        self.assertEqual(self.record({"verdicts": [{"key": k, "ok": True} for k in keys]}), 0)
        self.assertEqual(self.status()["areas"]["common"]["audited"], 5)
        # Filing the same reply again and filing another reader's: both rows are new, one reads the same.
        self.out.truncate(0)
        self.out.seek(0)
        self.assertEqual(self.compare(), 0, self.err.getvalue())
        self.assertIn("compare_sha1:", self.out.getvalue())
        self.assertNotIn("verdicts judged another", self.out.getvalue())
        self.assertEqual(self.status()["areas"]["common"]["audited"], 5)
        self.back(self.audit_prompts()[0], {"common_scooters_skipped": dict(PLURAL_BACK, one="1 scooter kept")})
        self.out.truncate(0)
        self.out.seek(0)
        self.assertEqual(self.compare(model=READER), 0, self.err.getvalue())
        self.assertIn("1 verdicts judged another back-translation", self.out.getvalue())
        self.assertIn("common_scooters_skipped", self.out.getvalue())
        self.assertEqual(self.status()["areas"]["common"]["audited"], 4)

    def test_an_incomplete_back_translation_is_left_out(self):
        self.plural_safety_key()
        self.audit_prompts()
        self.back(self.audit_prompts()[0], {"common_scooters_skipped": {"other": "others"}, "common_lock_waiting": " "})
        self.assertEqual(self.compare(), 1)
        self.assertIn("the reply covers other and the text has one, many, other", self.out.getvalue())
        self.assertIn("common_lock_waiting (s", self.out.getvalue())
        self.assertIn("an empty back-translation", self.out.getvalue())
        self.assertEqual(sorted(k for _, k in self.compared()), sorted(set(SAFETY) - {"common_lock_waiting"}))

    def test_never_the_councils_back_translation(self):
        self.transport(**{"gpt-6.1-terra": cf.drafting_reply(cf.SPANISH), "gemini-3.8-flash": cf.drafting_reply(cf.SPANISH)})
        self.drafted()
        self.main("draft", "openai", "--tag", "es", "--area", "common", "--model", "gpt-6.1-terra")
        self.main("draft", "google", "--tag", "es", "--area", "common", "--model", "gemini-3.8-flash")

        def outcomes():
            rows, pending, _ = council.decide(self.paths, "es", "common", use_java=False)
            return json.dumps(rows, sort_keys=True), pending, council.beta_report(self.paths, "es")

        before = outcomes()
        self.audited()
        self.record({"verdicts": [{"key": k, "ok": True} for k in SAFETY]})
        work = self.paths.work("es", "common")
        self.assertFalse(os.path.exists(os.path.join(work, "backtranslation.jsonl")))
        self.assertFalse(os.path.exists(os.path.join(work, "judge.jsonl")))
        self.assertEqual(outcomes(), before)
        # Safety keys still wait for the council's own back-translation, whatever the audit says.
        self.assertIn(("common_alarm_on", "a back-translation"), before[1])
        self.assertEqual(council.judge_requests(self.paths, "es", "common", use_java=False)[1],
                         [r for r in council.judge_requests(self.paths, "es", "common", use_java=False)[1]
                          if r["reason"] != "safety-confirmation"])
        # Unreadable audit files change nothing either: decide and beta do not open them.
        for name in ("compare.tsv", "verdicts.tsv"):
            self.repo.write("l10n/work/es/common/audit/" + name, "not a table")
        self.assertEqual(outcomes(), before)
        self.assertFalse(os.path.exists(self.paths.res("values-es")))

    def test_what_git_keeps(self):
        root = tempfile.mkdtemp(prefix="l10n-ignore-")
        self.addCleanup(shutil.rmtree, root, True)
        subprocess.run(["git", "init", "-q", root], check=True)
        os.makedirs(os.path.join(root, "l10n"))
        shutil.copy(os.path.join(translib.REPO_ROOT, "l10n", ".gitignore"), os.path.join(root, "l10n", ".gitignore"))

        def ignored(path):
            return subprocess.run(["git", "-C", root, "check-ignore", "-q", "--no-index", path]).returncode == 0

        base = "l10n/work/es/common/"
        for path in ("prompts/claude-x.chunk-1-of-2.md", "prompts/claude-x.chunks.abc.json", "replies/claude-x.abc/chunk-1-of-2.json",
                     "audit/prompts/claude-x.chunk-1-of-1.md", "audit/prompts/claude-x.abc.json",
                     "audit/replies/claude-x.abc/chunk-1-of-1.json"):
            self.assertTrue(ignored(base + path), path)
        for path in ("drafts/claude-x.xml", "audit/compare.tsv", "audit/verdicts.tsv", "checks.jsonl", "runs.jsonl"):
            self.assertFalse(ignored(base + path), path)


class DraftsStatusTest(AuditBase):
    def test_no_drafts(self):
        status = self.status()
        self.assertEqual(sorted(status["areas"]), ["ble", "common"])
        common = status["areas"]["common"]
        self.assertEqual((common["total"], common["drafted"], common["missing"]), (11, 0, 11))
        self.assertEqual(status["totals"]["total"], status["areas"]["ble"]["total"] + 11)
        self.assertEqual(status["glossary_problems"], [])

    def test_counts(self):
        self.drafted(overrides={"common_not_reported": "Informado %1$s"})
        self.change_english(">Cancel<", ">Dismiss<")
        common = self.status()["areas"]["common"]
        self.assertEqual((common["total"], common["drafted"], common["stale"], common["missing"], common["failing"]),
                         (11, 10, 1, 0, 1))
        draft = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.repo.write("l10n/work/es/common/drafts/claude-opus.xml", re.sub(
            r'    <string name="common_dont".*\n', "", draft))
        common = self.status()["areas"]["common"]
        self.assertEqual((common["drafted"], common["missing"]), (9, 1))

    def test_a_stale_ladder_rung_fails_its_sibling(self):
        self.drafted()
        self.change_english(">Waiting<", ">Hold on<")
        common = self.status()["areas"]["common"]
        self.assertEqual((common["stale"], common["failing"]), (1, 1))

    def test_name_filter_and_only_claude_drafts(self):
        self.drafted()
        self.assertEqual(self.status("--name", "other")["areas"]["common"]["drafted"], 0)
        self.assertEqual(self.status("--name", "opus")["areas"]["common"]["drafted"], 11)
        translib.write_draft(self.paths.work("es", "ble", "drafts", "openai.xml"), "openai", "gpt-6.1-terra", [])
        self.assertEqual(self.status()["areas"]["ble"]["drafted"], 0)

    def test_it_never_writes(self):
        self.drafted()
        self.audited()
        before = tree(self.repo.root)
        self.status()
        self.assertEqual(self.main("drafts-status", "--tag", "es"), 0)
        self.assertEqual(tree(self.repo.root), before)
        self.assertRegex(self.out.getvalue(), r"common\s+11\s+11\s+0\s+0\s+0\s+4\s+0\s+0")
        self.assertRegex(self.out.getvalue(), r"total\s+\d+")

    def test_one_unreadable_draft_hides_no_other_area(self):
        self.drafted()
        self.repo.write("l10n/work/es/ble/drafts/claude-x.xml", "<resources")
        self.assertEqual(self.main("drafts-status", "--tag", "es"), 1)
        output = self.out.getvalue()
        self.assertRegex(output, r"common\s+11\s+\d+")
        self.assertRegex(output, r"ble\s+unreadable")
        self.assertIn("l10n/work/es/ble/drafts/claude-x.xml", output)
        self.assertIn("line 1", output)
        self.assertNotIn("[redacted]", output)
        self.out.truncate(0)
        self.out.seek(0)
        self.assertEqual(self.main("drafts-status", "--tag", "es", "--json"), 1)
        value = json.loads(self.out.getvalue())
        self.assertEqual(sorted(value["errors"]), ["ble"])
        self.assertEqual(value["areas"]["common"]["total"], 11)
        self.assertEqual(value["totals"]["total"], 11)

    def test_an_invalid_glossary_is_shown_not_fatal(self):
        self.repo.write("l10n/glossary/es.tsv", self.repo.read("l10n/glossary/es.tsv") + "typo\tx\tX.\tnoun\tno\t-\tf\n")
        self.assertTrue(self.status()["glossary_problems"])
        self.assertEqual(self.main("drafts-status", "--tag", "es"), 0)
        self.assertIn("glossary: 1 problems", self.out.getvalue())


if __name__ == "__main__":
    unittest.main()
