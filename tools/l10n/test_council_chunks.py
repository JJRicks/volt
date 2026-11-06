"""Chunked Claude prompts: prompt --chunk-keys and import-claude --from-chunks."""

import contextlib
import io
import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import pack  # noqa: E402
import test_council  # noqa: E402
import translib  # noqa: E402

MODEL = "claude-opus-5-5"


class ChunkBase(test_council.Base):
    def prompts(self, *extra, name="opus", keys="4", code=0):
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", name, "--chunk-keys", keys,
                                   *extra), code, self.err.getvalue())
        if code:
            return []
        directory = self.paths.work("es", "common", "prompts")
        found = [f for f in os.listdir(directory) if re.match(r"claude-%s\.chunk-\d+-of-\d+\.md$" % name, f)]
        return sorted((os.path.join(directory, f) for f in found),
                      key=lambda p: int(re.search(r"chunk-(\d+)-of", p).group(1)))

    def body(self, path):
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        return text, json.loads(text.split("## Strings\n\n", 1)[1].split("\n\n", 1)[1])

    def answer(self, path, texts=None, overrides=None, extra=None):
        """Answer one prompt as an agent would: reply where it says, with the keys it shows."""
        text, body = self.body(path)
        reply = re.search(r"write the JSON object to `([^`]+)`", text).group(1)
        strings = dict((e["key"], (overrides or {}).get(e["key"], (texts or cf.SPANISH)[e["key"]])) for e in body)
        value = {"strings": strings, "notes": {}}
        value.update(extra or {})
        self.repo.write(reply, json.dumps(value, ensure_ascii=False))
        return reply

    def answer_all(self, paths, **kwargs):
        return [self.answer(p, **kwargs) for p in paths]

    def do_import(self, *extra, name="opus", model=MODEL):
        return self.main("import-claude", "--tag", "es", "--area", "common", "--name", name, "--model", model,
                         "--from-chunks", *extra)

    def draft(self, name="opus"):
        return translib.read_draft(self.paths.work("es", "common", "drafts", "claude-%s.xml" % name))

    def change_english(self, old, new):
        path = "res/values/strings_common.xml"
        self.repo.write(path, self.repo.read(path).replace(old, new))

    def repack(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"]), 0)


class ChunkPromptTest(ChunkBase):
    def test_prompt_files_and_sidecar(self):
        paths = self.prompts()
        self.assertGreater(len(paths), 1)
        for index, path in enumerate(paths, 1):
            self.assertTrue(path.endswith("claude-opus.chunk-%d-of-%d.md" % (index, len(paths))), path)
        directory = self.paths.work("es", "common", "prompts")
        set_id = self.repo.read("l10n/work/es/common/prompts/claude-opus.chunks.current").strip()
        with open(os.path.join(directory, "claude-opus.chunks.%s.json" % set_id), encoding="utf-8") as handle:
            sidecar = json.load(handle)
        sha = self.sha()
        recorded = {}
        for chunk in sidecar["chunks"]:
            recorded.update(chunk)
        self.assertEqual(recorded, sha)

    def test_ladder_groups_stay_together_and_every_key_appears_once(self):
        keys = []
        for path in self.prompts(keys="1"):
            chunk = [e["key"] for e in self.body(path)[1]]
            keys.extend(chunk)
            self.assertEqual("common_lock_waiting" in chunk, "common_lock_waiting_short" in chunk)
        self.assertEqual(sorted(keys), sorted(self.sha()))

    def test_same_rules_as_a_single_prompt_and_no_src(self):
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", "one",
                                   "--style", "context-first"), 0)
        single = self.repo.read("l10n/work/es/common/prompts/claude-one.md")
        rules = single.split("Each string below has a `src`")[0].split("l10n/decisions/.\n\n", 1)[1].strip()
        self.assertIn("Abbreviate per the style guide", rules)
        for path in self.prompts("--style", "context-first"):
            text, body = self.body(path)
            self.assertIn(rules, text)
            for entry in body:
                self.assertNotIn("src", entry)
            for sha in self.sha().values():
                self.assertNotIn(sha, text)

    def test_the_prompt_says_where_the_reply_goes_and_the_import_command(self):
        text = self.body(self.prompts()[0])[0]
        self.assertRegex(text, r"write the JSON object to `l10n/work/es/common/replies/claude-opus\.[0-9a-f]{12}/"
                               r"chunk-1-of-\d+\.json`")
        self.assertIn("python3 tools/l10n/council.py import-claude --tag es --area common --name opus "
                      "--model <your exact model id> --from-chunks", text)

    def test_a_smaller_set_removes_the_old_prompt_files(self):
        many = self.prompts(keys="1")
        few = self.prompts(keys="100")
        self.assertEqual(len(few), 1)
        self.assertGreater(len(many), 1)
        self.assertFalse(os.path.exists(many[1]))

    def test_refuses_a_pack_older_than_the_english(self):
        self.change_english("Cancel", "Dismiss")
        self.prompts(code=2)
        self.assertIn("the pack is out of date", self.err.getvalue())

    def test_refuses_a_pack_older_than_the_glossary(self):
        self.repo.write("l10n/glossary/es.tsv", self.repo.read("l10n/glossary/es.tsv").replace("patinete |", "monopatín |"))
        self.prompts(code=2)
        self.assertIn("the glossary changed: scooter", self.err.getvalue())

    def test_refuses_a_pack_that_never_matched_a_new_glossary_term(self):
        self.repo.write("l10n/glossary/en.tsv", self.repo.read("l10n/glossary/en.tsv") +
                        "waiting\tWaiting\tWaiting.\tverb\tno\t-\tfixture\n")
        self.repo.write("l10n/glossary/es.tsv", self.repo.read("l10n/glossary/es.tsv") +
                        "waiting\tEsperando\tWaiting.\tverb\tno\t-\tfixture\n")
        self.prompts(code=2)
        self.assertIn("the glossary changed: waiting", self.err.getvalue())
        self.repack()
        self.assertIn("Esperando", self.body(self.prompts(keys="100")[0])[0])

    def test_refuses_a_pack_older_than_the_language_settings(self):
        path = "l10n/lang/es.tsv"
        self.repo.write(path, self.repo.read(path).replace("one,many,other", "one,other"))
        self.prompts(code=2)
        self.assertIn("the language settings changed: plurals", self.err.getvalue())
        self.repack()
        self.assertTrue(self.prompts())
        self.repo.write(path, self.repo.read(path).replace("formality\ttú", "formality\tusted"))
        self.prompts(code=2)
        self.assertIn("the language settings changed: formality", self.err.getvalue())

    def test_nothing_to_draft(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", "opus", "--chunk-keys", "4",
                                   "--top-up"), 0)
        self.assertIn("nothing to draft", self.out.getvalue())

    def test_argument_combinations(self):
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", "x", "--top-up"), 2)
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--name", "x", "--chunk-keys", "4",
                                   "--top-up", "--redraft"), 2)
        self.assertEqual(self.main("prompt", "--tag", "es", "--area", "common", "--role", "judge", "--chunk-keys", "4"), 2)


class ChunkSetIdentityTest(ChunkBase):
    """A set's replies answer that set's prompts, against the draft as it then was."""

    def set_id(self):
        return self.repo.read("l10n/work/es/common/prompts/claude-opus.chunks.current").strip()

    def test_the_same_prompts_keep_the_set_and_its_replies(self):
        paths = self.prompts()
        first = self.set_id()
        self.answer(paths[0])
        self.prompts()
        self.assertEqual(self.set_id(), first)
        self.assertEqual(self.do_import(), 2)
        self.assertEqual(self.err.getvalue().count("no reply"), len(paths) - 1)

    def test_a_guide_change_is_a_new_set(self):
        self.answer_all(self.prompts())
        first = self.set_id()
        self.repo.write("l10n/style/es.md", self.repo.read("l10n/style/es.md") + "\nNEW RULE\n")
        self.repack()
        paths = self.prompts()
        self.assertNotEqual(self.set_id(), first)
        self.assertIn("NEW RULE", self.body(paths[0])[0])
        # The replies to the old rules are not filed as answers to the new ones.
        self.answer(paths[0])
        self.assertEqual(self.do_import(), 2)
        self.assertIn("no reply", self.err.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "claude-opus.xml")))
        self.assertEqual(self.do_import("--partial"), 1)
        self.assertIn("belongs to an earlier prompt set", self.out.getvalue())
        # Adopting them stays possible, by name.
        self.assertEqual(self.do_import("--set", first), 0)

    def test_a_glossary_change_is_a_new_set(self):
        self.answer_all(self.prompts())
        first = self.set_id()
        self.repo.write("l10n/glossary/es.tsv", self.repo.read("l10n/glossary/es.tsv").replace("patinete |", "monopatin |"))
        self.repack()
        self.prompts()
        self.assertNotEqual(self.set_id(), first)
        self.assertEqual(self.do_import(), 2)

    def test_a_new_tool_rule_is_a_new_set(self):
        self.prompts()
        first = self.set_id()
        original = council.instructions
        self.addCleanup(setattr, council, "instructions", original)
        council.instructions = lambda pack, style="standard": original(pack, style) + "\n- One more rule."
        self.prompts()
        self.assertNotEqual(self.set_id(), first)

    def test_other_findings_are_a_new_set(self):
        self.answer_all(self.prompts(keys="100"), overrides={"common_not_reported": "Informado %1$s"})
        self.assertEqual(self.do_import(), 0)
        self.main("check", "--tag", "es", "--area", "common")
        self.prompts("--redraft")
        first = self.set_id()
        self.prompts("--redraft")
        self.assertEqual(self.set_id(), first)
        check = self.paths.work("es", "common", "checks.jsonl")
        rows = translib.read_jsonl(check)
        for row in rows:
            if row["key"] == "common_not_reported":
                row["findings"] = [{"severity": "error", "message": "Another finding."}]
        self.repo.write(os.path.relpath(check, self.repo.root), "".join(translib.dump_json_line(r) + "\n" for r in rows))
        self.prompts("--redraft")
        self.assertNotEqual(self.set_id(), first)

    def test_prompting_again_after_a_redraft_does_not_undo_it(self):
        self.answer_all(self.prompts(), overrides={"common_cancel": "Cancelar %1$s"})
        first = self.set_id()
        self.assertEqual(self.do_import(), 0)
        self.main("check", "--tag", "es", "--area", "common")
        self.answer_all(self.prompts("--redraft"))
        self.assertEqual(self.do_import("--redraft"), 0)
        before = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.assertEqual(self.draft().entries["common_cancel"].attempt, 2)
        self.prompts()
        self.assertNotEqual(self.set_id(), first)
        self.assertEqual(self.do_import(), 2)
        self.assertIn("no reply", self.err.getvalue())
        self.assertEqual(self.repo.read("l10n/work/es/common/drafts/claude-opus.xml"), before)

    def test_a_top_up_keeps_a_redrafted_keys_attempt(self):
        self.answer_all(self.prompts(keys="50"), overrides={"common_lock_waiting": "Esperando %1$s"})
        self.assertEqual(self.do_import(), 0)
        self.main("check", "--tag", "es", "--area", "common")
        self.answer_all(self.prompts("--redraft", keys="50"))
        self.assertEqual(self.do_import("--redraft"), 0)
        self.assertEqual(self.draft().entries["common_lock_waiting"].attempt, 2)
        path = "l10n/work/es/common/drafts/claude-opus.xml"
        self.repo.write(path, re.sub(r'    <string name="common_lock_waiting_short".*\n', "", self.repo.read(path)))
        paths = self.prompts("--top-up", keys="50")
        self.assertEqual(sorted(e["key"] for e in self.body(paths[0])[1]),
                         ["common_lock_waiting", "common_lock_waiting_short"])
        self.answer_all(paths)
        self.assertEqual(self.do_import(), 0)
        entries = self.draft().entries
        self.assertEqual((entries["common_lock_waiting"].attempt, entries["common_lock_waiting_short"].attempt), (2, 1))
        # New English is a new first attempt.
        self.change_english(">Waiting for the scooter<", ">Waiting for your scooter<")
        self.repack()
        self.answer_all(self.prompts("--top-up", keys="50"))
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.draft().entries["common_lock_waiting"].attempt, 1)

    def test_partial_with_no_reply_at_all_is_a_failure(self):
        self.prompts()
        self.assertEqual(self.do_import("--partial"), 1)
        self.assertIn("0 keys from 0 of", self.out.getvalue())
        self.assertIn("no draft written", self.out.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "claude-opus.xml")))


class ChunkImportTest(ChunkBase):
    def test_import_stamps_src_from_the_sidecar(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(), 0, self.out.getvalue())
        draft = self.draft()
        sha = self.sha()
        self.assertEqual(draft.provider, "claude")
        self.assertEqual(draft.model, MODEL)
        self.assertEqual(sorted(draft.entries), sorted(sha))
        for key, entry in draft.entries.items():
            self.assertEqual(entry.src, sha[key])
            self.assertEqual(entry.attempt, 1)
        self.assertEqual(draft.entries["common_cancel"].items["other"], "Cancelar")

    def test_a_src_in_a_reply_is_ignored(self):
        paths = self.prompts()
        self.answer_all(paths, extra={"src": dict((k, "0" * 40) for k in cf.SPANISH)})
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.draft().entries["common_cancel"].src, self.sha()["common_cancel"])

    def test_missing_chunks_refuse_unless_partial(self):
        paths = self.prompts()
        self.answer(paths[0])
        self.assertEqual(self.do_import(), 2)
        self.assertIn("nothing was written", self.err.getvalue())
        self.assertIn("chunk 2 of", self.err.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "claude-opus.xml")))
        self.assertEqual(self.do_import("--partial"), 1)  # written, and not complete
        self.assertIn("chunk 2 of", self.out.getvalue())
        self.assertEqual(sorted(self.draft().entries), sorted(e["key"] for e in self.body(paths[0])[1]))

    def test_an_unusable_reply_counts_as_missing(self):
        paths = self.prompts()
        replies = self.answer_all(paths)
        self.repo.write(replies[1], "not json")
        self.assertEqual(self.do_import(), 2)
        self.assertIn("is not JSON", self.err.getvalue())
        self.repo.write(replies[1], '{"common_cancel": "Cancelar"}')
        self.assertEqual(self.do_import(), 2)
        self.assertIn('no "strings" object', self.err.getvalue())

    def test_importing_twice_is_safe(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(), 0)
        first = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.repo.read("l10n/work/es/common/drafts/claude-opus.xml"), first)

    def test_english_changed_between_prompt_and_import(self):
        self.answer_all(self.prompts())
        self.change_english("Cancel", "Dismiss")
        # Whether or not the pack was rebuilt, the old text is never filed against the new English.
        for repack in (False, True):
            if repack:
                self.repack()
            self.assertEqual(self.do_import(), 1)
            self.assertIn("common_cancel: the English changed since the prompt", self.out.getvalue())
            self.assertNotIn("common_cancel", self.draft().entries)
            self.assertIn("common_not_reported", self.draft().entries)

    def test_a_key_gone_from_the_english_is_reported(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(), 0)
        self.change_english('<string name="common_not_reported">Not reported</string>', "")
        self.assertEqual(self.do_import(), 1)
        self.assertIn("common_not_reported: no longer in the English", self.out.getvalue())
        self.assertNotIn("common_not_reported", self.draft().entries)

    def test_keys_outside_the_chunk_and_bad_shapes(self):
        paths = self.prompts(keys="100")
        reply = self.answer(paths[0], overrides={"common_cancel": {"other": "x"}})
        value = json.loads(self.repo.read(reply))
        value["strings"]["common_made_up"] = "x"
        del value["strings"]["common_dont"]
        self.repo.write(reply, json.dumps(value))
        self.assertEqual(self.do_import(), 1)
        output = self.out.getvalue()
        self.assertIn("common_made_up: not in chunk 1; ignored", output)
        self.assertIn("common_cancel: expected a string", output)
        self.assertIn("common_dont: missing from the reply of chunk 1", output)
        entries = self.draft().entries
        self.assertNotIn("common_made_up", entries)
        self.assertNotIn("common_cancel", entries)

    def test_top_up_replaces_only_what_it_covers(self):
        self.answer_all(self.prompts(keys="100"), overrides={"common_lock_waiting_short": "Esperando"})
        self.assertEqual(self.do_import(), 0)
        before = self.draft().entries
        self.change_english(">Waiting for the scooter<", ">Waiting for your scooter<")
        self.repack()
        paths = self.prompts("--top-up")
        keys = [e["key"] for p in paths for e in self.body(p)[1]]
        # The stale key with the rest of its ladder, nothing else.
        self.assertEqual(sorted(keys), ["common_lock_waiting", "common_lock_waiting_short"])
        self.answer_all(paths, overrides={"common_lock_waiting": "Esperando a tu patinete"})
        self.assertEqual(self.do_import(), 0)
        after = self.draft().entries
        self.assertEqual(after["common_lock_waiting"].items["other"], "Esperando a tu patinete")
        self.assertEqual(after["common_lock_waiting"].src, self.sha()["common_lock_waiting"])
        self.assertEqual(sorted(after), sorted(before))
        self.assertEqual(after["common_cancel"], before["common_cancel"])
        self.assertEqual([k for k in after], [k for k in self.sha()])  # pack order

    def test_top_up_after_a_partial_import(self):
        paths = self.prompts()
        self.answer(paths[0])
        self.assertEqual(self.do_import("--partial"), 1)
        done = set(self.draft().entries)
        paths = self.prompts("--top-up", keys="100")
        self.assertEqual(sorted(e["key"] for e in self.body(paths[0])[1]), sorted(set(self.sha()) - done))

    def test_redraft_of_failed_keys(self):
        self.answer_all(self.prompts(keys="100"), overrides={"common_not_reported": "Informado %1$s"})
        self.assertEqual(self.do_import(), 0)
        self.main("check", "--tag", "es", "--area", "common")
        paths = self.prompts("--redraft")
        text, body = self.body(paths[0])
        self.assertEqual([e["key"] for e in body], ["common_not_reported"])
        self.assertTrue(body[0]["failed_checks"])
        self.assertIn("--from-chunks --redraft", text)
        self.answer_all(paths)
        # The set says it is a redraft, and the flag must agree.
        self.assertEqual(self.do_import(), 2)
        self.assertIn("pass --redraft", self.err.getvalue())
        self.assertEqual(self.do_import("--redraft"), 0)
        draft = self.draft()
        self.assertEqual(draft.entries["common_not_reported"].items["other"], "No informado")
        self.assertEqual(draft.entries["common_not_reported"].attempt, 2)
        self.assertEqual(draft.entries["common_cancel"].attempt, 1)
        self.assertEqual(sorted(draft.entries), sorted(self.sha()))
        self.assertEqual(self.do_import("--redraft"), 0)  # safe twice

    def test_a_key_gets_one_redraft_even_if_check_has_not_run_since(self):
        self.answer_all(self.prompts(keys="100"), overrides={"common_not_reported": "Informado %1$s"})
        self.assertEqual(self.do_import(), 0)
        self.main("check", "--tag", "es", "--area", "common")
        self.answer_all(self.prompts("--redraft"))
        self.assertEqual(self.do_import("--redraft"), 0)
        self.prompts("--redraft", code=2)
        self.assertIn("no failed first attempts", self.err.getvalue())

    def test_a_lone_surrogate_is_left_out(self):
        for reply in self.answer_all(self.prompts(keys="100")):
            self.repo.write(reply, self.repo.read(reply).replace("Alarma activada", "Alarma \\ud83d activada"))
        self.assertEqual(self.do_import(), 1)
        self.assertIn("common_alarm_on: a lone surrogate", self.out.getvalue())
        self.assertNotIn("common_alarm_on", self.draft().entries)
        self.assertIn("common_cancel", self.draft().entries)
        got, problems = council.replies_to_entries(pack.load_pack(self.paths, "es", "common"),
                                                   {"common_cancel": "Can\ud83d"}, ["common_cancel"])
        self.assertEqual(got, [])
        self.assertIn("a lone surrogate", problems[0])

    def test_a_whole_set_in_another_style_relabels_the_draft(self):
        self.answer_all(self.prompts(keys="100"))
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.draft().prompt, "standard")
        self.answer_all(self.prompts("--style", "context-first", keys="100"))
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.draft().prompt, "context-first")

    def test_a_draft_does_not_mix_styles(self):
        self.answer_all(self.prompts(keys="4"))
        self.assertEqual(self.do_import(), 0)
        before = self.repo.read("l10n/work/es/common/drafts/claude-opus.xml")
        paths = self.prompts("--style", "context-first", keys="4")
        self.answer(paths[0])
        self.assertEqual(self.do_import("--partial"), 2)
        self.assertIn("records the style standard, this set is in context-first", self.err.getvalue())
        self.assertEqual(self.repo.read("l10n/work/es/common/drafts/claude-opus.xml"), before)
        for extra in ("--top-up", "--redraft"):
            self.err.truncate(0)
            self.err.seek(0)
            self.prompts(extra, "--style", "context-first", code=2)
            self.assertIn("a draft records one", self.err.getvalue())

    def test_a_claude_only_glossary_gives_way_to_the_councils(self):
        path = "l10n/glossary/es.tsv"
        council_glossary = self.repo.read(path)
        self.repo.write(path, council_glossary.replace("\tfixture", "\tclaude-only " + MODEL))
        self.repack()
        self.answer_all(self.prompts(keys="100"))
        self.assertEqual(self.do_import(), 0)
        draft = self.draft()
        self.assertEqual(self.main("glossary-check", "--tag", "es"), 0)
        self.assertIn("3 rows are claude-only", self.out.getvalue())
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common", "--model", "gpt-6.1-terra"), 2)
        self.assertIn("3 claude-only rows", self.err.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "openai.xml")))
        # The judged glossary differs on a safety term and on an ordinary one.
        self.repo.write(path, council_glossary.replace("no está afiliada a NAVEE", "sin afiliación a NAVEE")
                        .replace("patinete | patinetes", "monopatín | monopatines"))
        self.prompts(code=2)
        self.assertIn("the pack is out of date", self.err.getvalue())
        self.repack()
        self.assertEqual(self.main("check", "--tag", "es", "--area", "common"), 1)
        self.assertEqual(self.draft().entries, draft.entries)  # the draft itself is untouched, and still current
        self.assertEqual(dict((k, e.src) for k, e in draft.entries.items()), self.sha())
        # Only the safety key that used the old word is redrafted, in the same file under the same model.
        paths = self.prompts("--redraft", keys="100")
        self.assertEqual([e["key"] for e in self.body(paths[0])[1]], ["common_disclaimer"])
        self.answer_all(paths, overrides={"common_disclaimer": "Volt sin afiliación a NAVEE"})
        self.assertEqual(self.do_import("--redraft"), 0)
        after = self.draft()
        self.assertEqual((after.model, after.name), (draft.model, draft.name))
        self.assertEqual(after.entries["common_disclaimer"].attempt, 2)
        self.assertEqual([k for k in draft.entries if after.entries[k].items != draft.entries[k].items],
                         ["common_disclaimer"])

    def test_a_redraft_flag_on_a_first_pass_refuses(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import("--redraft"), 2)

    def test_an_earlier_set_keeps_its_replies_and_never_mixes_with_the_latest(self):
        first = self.prompts(keys="100")
        self.answer_all(first)
        set_id = self.repo.read("l10n/work/es/common/prompts/claude-opus.chunks.current").strip()
        # New prompts, with the same chunk numbering but other English, while the old replies still sit there.
        self.change_english("Cancel", "Dismiss")
        self.repack()
        self.prompts(keys="100")
        self.assertEqual(self.do_import(), 2)
        self.assertIn("no reply", self.err.getvalue())
        self.assertEqual(self.do_import("--partial"), 1)
        self.assertIn("belongs to an earlier prompt set", self.out.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "claude-opus.xml")))
        # The earlier set can be imported by name; its stale key is left out.
        self.assertEqual(self.do_import("--set", set_id), 1)
        self.assertNotIn("common_cancel", self.draft().entries)
        self.assertIn("common_not_reported", self.draft().entries)

    def test_a_tampered_sidecar_refuses(self):
        self.answer_all(self.prompts())
        set_id = self.repo.read("l10n/work/es/common/prompts/claude-opus.chunks.current").strip()
        path = "l10n/work/es/common/prompts/claude-opus.chunks.%s.json" % set_id
        self.repo.write(path, self.repo.read(path).replace(self.sha()["common_cancel"], "0" * 40))
        self.assertEqual(self.do_import(), 2)
        self.assertIn("does not match its id", self.err.getvalue())

    def test_provider_identity(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(model="gpt-6.1-terra"), 2)
        self.assertIn("not a Claude model id", self.err.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "drafts", "claude-opus.xml")))
        self.assertEqual(self.do_import(), 0)
        # One draft names one model, so another model's text does not join under it.
        self.assertEqual(self.do_import(model="claude-sonnet-5"), 2)
        self.assertIn("was drafted by claude-opus-5-5", self.err.getvalue())
        self.assertIn("two claude-* drafts that agree", self.err.getvalue())  # what the next --name risks
        self.assertEqual(self.draft().model, MODEL)

    def test_from_and_from_chunks_are_alternatives(self):
        self.assertEqual(self.do_import("--from", "x.json"), 2)

    def test_style_kept_and_the_draft_usable_in_the_debate(self):
        self.answer_all(self.prompts("--style", "glossary-first"))
        self.assertEqual(self.do_import(), 0)
        self.assertEqual(self.draft().prompt, "glossary-first")
        self.assertEqual(self.main("check", "--tag", "es", "--area", "common"), 0, self.out.getvalue())
        # A chunked draft is a Claude drafter like any other: with API drafts it reaches agreement.
        self.transport(**{"gpt-6.1-terra": cf.drafting_reply(cf.SPANISH), "gemini-3.8-flash": cf.drafting_reply(cf.SPANISH)})
        self.assertEqual(self.main("draft", "openai", "--tag", "es", "--area", "common", "--model", "gpt-6.1-terra"), 0)
        self.assertEqual(self.main("draft", "google", "--tag", "es", "--area", "common", "--model", "gemini-3.8-flash"), 0)
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        rows = dict((r["key"], r) for r in translib.read_jsonl(self.paths.decisions("es", "common")))
        self.assertEqual(rows["common_cancel"]["outcome"], "agreed")
        self.assertIn(MODEL, rows["common_cancel"]["models"]["drafters"])

    def test_claude_alone_decides_nothing_for_a_safety_key(self):
        self.answer_all(self.prompts())
        self.assertEqual(self.do_import(), 0)
        self.main("decide", "--tag", "es", "--area", "common", "--final")
        rows = dict((r["key"], r) for r in translib.read_jsonl(self.paths.decisions("es", "common")))
        self.assertEqual(rows["common_alarm_on"]["outcome"], "fallback-english")
        self.assertEqual(self.main("beta", "--tag", "es"), 1)
        self.assertFalse(os.path.exists(self.paths.res("values-es")))


if __name__ == "__main__":
    unittest.main()
