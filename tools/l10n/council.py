#!/usr/bin/env python3
"""The translation council runner (plan 7.2 steps 2-5, with the header's three providers).

Drafts come from three providers: Claude (Claude Code agents, which write draft files), OpenAI and
Google Gemini (called here over HTTPS). Keys come only from OPENAI_API_KEY and GEMINI_API_KEY in the
environment; see providers.py for how they are kept out of every output.

  council.py models openai|google                       list usable models and the ones the config picks
  council.py prompt --tag es --area common --name opus-context [--style context-first]
                                                        the prompt for a Claude Code drafting agent
  council.py import-claude --tag es --area common [--from reply.json --name N --model ID]
                                                        validate drafts/claude-*.xml (or convert JSON)
  council.py draft openai|google --tag es --area common  draft with the provider's API
  council.py check --tag es --area common               checks.jsonl (tools/l10n/check.py)
  council.py redraft openai|google --tag es --area common
                                                        send failed keys back once (attempt 2)
  council.py backtranslate openai|google --tag es --area common
                                                        back-translate agreed safety keys with a
                                                        model that drafted nothing
  council.py import-backtranslation --tag es --area common --from reply.json --model ID
                                                        (after prompt --role backtranslate; the
                                                        reply names that prompt's request id)
  council.py judge-requests --tag es --area common      judge-requests.jsonl and the judge's prompt
  council.py decide --tag es --area common [--final]    decisions/<tag>/<area>.jsonl
  council.py beta --tag es                              the owner's beta bar

Files, under l10n/work/<tag>/<area>/ (committed, staging only; never compiled):
  drafts/<name>.xml        claude-<config>.xml, openai.xml, google.xml: Android resource XML whose root
                           carries volt:provider, volt:model (the exact model id) and, when the API
                           reported a more specific one, volt:model-reported. Every entry carries
                           volt:src, the source hash of the English it translated (from the pack it
                           was drafted from); an entry without it or with another hash is stale and
                           ignored. A redrafted key carries volt:attempt="2".
  checks.jsonl             check.py's results per draft and key.
  backtranslation.jsonl    {"key", "source_sha1", "target", "back", "provider", "model",
                           "model_reported"}; target and back are a string or {quantity: text}.
  prompts/backtranslation.request.<id>.json
                           what one back-translation prompt showed: {"format", "id", "tag", "area",
                           "keys": {key: {"kind", "source_sha1", "target", "target_sha1"}}}; the id
                           hashes the rest, and the file is never overwritten. A Claude reply copies
                           the id ("request") and each key's target_sha1.
  judge-requests.jsonl     what the judge must rule on (written here).
  judge.jsonl              the judge's rulings (written by the Claude judge session):
                           {"key", "source_sha1", "model", "verdict", "pick", "text", "back", "reason",
                            "confirm", "backtranslation_meaning_kept"}
                           verdict: "pick" (a passing draft, named in pick, with its text copied into
                           text), "merge" (text), "confirm" (safety keys: accept the unanimous text)
                           or "fallback" (keep English). A pick counts only while the named draft
                           still has that text.
                           confirm (safety keys): every item of SAFETY_CONFIRMATIONS set to true,
                           text the confirmed text and back the back-translation the judge saw,
                           both copied from the request; they must match the current ones.
  runs.jsonl               one line per provider call or import: stage, provider, model ids, keys.

decisions/<tag>/<area>.jsonl: one line per key, the audit trail (see decide()).
"""

import argparse
import collections
import datetime
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check as checklib  # noqa: E402
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402
import pack as packlib  # noqa: E402
import providers  # noqa: E402
import translib  # noqa: E402

API_PROVIDERS = ("openai", "google")
CHUNK = 40
MAX_ATTEMPTS = 2
SAFETY_CONFIRMATIONS = (
    "lock_states_distinct",              # locked vs unlocked stays distinct
    "on_off_never_always_not_inverted",  # on/off and never/always are not inverted
    "unverified_never_confirmed",        # "unverified" or "unknown" never reads as confirmed
    "numbers_units_action_unchanged",    # numbers, units and the named action are unchanged
    "disclaimer_not_softened",           # "not affiliated with NAVEE" is not softened
)
VERDICTS = ("pick", "merge", "confirm", "fallback")
TRANSLATED = ("agreed", "judged", "safety-agreed")
DECISION_FORMAT = "volt-l10n-decision-v1"
STYLES = {
    "standard": "Translate each string with its context in mind.",
    "context-first": "Read each string's screen, purpose, note and neighbours first, decide what the user is "
                     "doing on that screen, then translate for that moment.",
    "glossary-first": "Fix every glossary term's translation first, then translate each string around those "
                      "terms, keeping them consistent across the whole set.",
    "draft-review": "Draft every string, then review your own draft against every rule below and the width "
                    "budgets, and fix what fails before you answer.",
}
# Pack fields a model sees. Everything else in the pack is for the tools.
MODEL_FIELDS = ("key", "kind", "english", "placeholders", "screen", "purpose", "note", "safety", "dnt",
                "ladder", "ladder_group", "budget_dp", "budget_sp", "english_width_dp", "glossary", "neighbours")


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def log_run(paths, tag, area, row):
    row = collections.OrderedDict([("when", now())] + list(row.items()))
    path = paths.work(tag, area, "runs.jsonl")
    previous = l10nlib.read_text(path) if os.path.exists(path) else ""
    l10nlib.write_text(path, previous + translib.dump_json_line(row) + "\n")


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------

def instructions(pack, style="standard"):
    lang = pack["language"]
    rules = [
        "You are one translator on a council translating Volt, an independent Android app for NAVEE electric "
        "scooters, from English into %s (%s). Other translators work independently; a judge compares the drafts. "
        "You see only UI strings and their context." % (lang["endonym"], lang["tag"]),
        STYLES.get(style, STYLES["standard"]),
        "Rules:",
        "- Address the user with %s." % lang["formality"],
        "- Keep every placeholder (%1$s, %2$d, %1$.1f) exactly as written; reorder them if the grammar needs it. "
        "Keep %% (a percent sign) as %%. The percent style is " + lang["percent_style"] + ".",
        "- Keep unchanged: the \"dnt\" tokens of each string, glossary terms marked dnt, file names, command-line "
        "flags, URLs, `code spans`, ** marks, {z}/{x}/{y}, the \ufffc character and the marks " +
        " ".join(sorted(ch + checklib.TEXT_STYLE if emoji else ch for ch, emoji in checklib.FALLBACK_SYMBOLS.items())) +
        " (with the invisible U+FE0E directly after the warning sign, exactly as the English has it).",
        "- Use each glossary term's approved translation (\"target\") when one is given.",
        "- Plurals: give every one of these categories: %s." % ", ".join(lang["plurals"]),
        "- Keys ending in _short and _compact are shorter rungs of the same label (\"ladder_group\"). Draft every "
        "rung. A string with budget_dp must fit that width at budget_sp in the Saira typeface; english_width_dp "
        "is the English width. Abbreviate per the style guide rather than overflow; never break a word.",
        "- Never change the meaning: locked and unlocked stay distinct; on/off and never/always are never "
        "inverted; \"unverified\" or \"unknown\" never reads as confirmed; numbers, units and the named action "
        "stay the same; \"not affiliated with NAVEE\" is never softened. Keep every negation.",
        "- Keep leading and trailing spaces and line breaks exactly as in the English.",
        "- Unit symbols (km/h, mph, V, A, W, Wh, Ah, °C) stay as written; unit words are translated. Drive-mode "
        "names (WALK, ECO, DRIVE, SPORT) stay as written.",
        "- Write the text a user sees: no XML or backslash escapes. Quote style: %s." % lang["quote_style"],
        "Answer with one JSON object only: {\"strings\": {\"<key>\": \"<text>\" or {\"<category>\": \"<text>\", ...}}, "
        "\"notes\": {\"<key>\": \"<optional short note for the judge>\"}}. Include every key you were given.",
    ]
    if pack.get("style_guide"):
        rules.append("Style guide for %s:\n%s" % (lang["tag"], pack["style_guide"].strip()))
    return "\n".join(rules)


def model_view(entry, findings=None, with_src=False):
    view = collections.OrderedDict((f, entry[f]) for f in MODEL_FIELDS if entry.get(f) not in (None, [], {}))
    if with_src:
        view["src"] = entry["source_sha1"]  # a Claude drafter copies it into its draft (volt:src)
    current = entry.get("current")
    if current and current.get("stale"):
        view["previous_translation"] = current["text"]
    if findings:
        view["failed_checks"] = [f["message"] for f in findings if f["severity"] == checklib.ERROR]
    return view


def user_prompt(pack, entries, findings_by_key=None, with_src=False):
    findings_by_key = findings_by_key or {}
    head = ("Translate these %d strings from %s (area %s)." % (len(entries), pack["source_file"], pack["area"]))
    if findings_by_key:
        head += (" Your earlier draft of these strings failed the automated checks listed under failed_checks; "
                 "fix them.")
    body = [model_view(e, findings_by_key.get(e["key"]), with_src) for e in entries]
    return head + "\n\n" + json.dumps(body, ensure_ascii=False, indent=1)


def chunks(pack, entries, size=CHUNK):
    """Chunks of entries that keep each ladder group together."""
    out, current, seen = [], [], set()
    by_key = dict((e["key"], e) for e in entries)
    for entry in entries:
        if entry["key"] in seen:
            continue
        group = [by_key[k] for k in (entry.get("ladder_group") or [entry["key"]]) if k in by_key and k not in seen]
        if entry not in group:
            group.insert(0, entry)
        if current and len(current) + len(group) > size:
            out.append(current)
            current = []
        current.extend(group)
        seen.update(e["key"] for e in group)
    if current:
        out.append(current)
    return out


def needed_entries(pack, include_current=False):
    """Entries to draft: new and stale keys, plus every rung of a ladder with one (rungs go together)."""
    needed = set(e["key"] for e in pack["entries"] if include_current or e["status"] != "current")
    for e in pack["entries"]:
        if e["key"] in needed:
            needed.update(e.get("ladder_group") or [])
    return [e for e in pack["entries"] if e["key"] in needed]


# ---------------------------------------------------------------------------
# Reading replies into drafts
# ---------------------------------------------------------------------------

def reply_strings(value):
    strings = value.get("strings") if isinstance(value.get("strings"), dict) else value
    return strings


def replies_to_entries(pack, strings, keys, srcs=None):
    """[(key, kind, items, attempt, src)] for the requested keys; unknown keys and bad shapes are reported.

    src is the source hash of the English the text translates: the pack's, for a reply to a prompt
    made from this pack (the API drafts), or srcs[key] when the drafter copied it from its prompt
    (Claude). A key srcs lacks keeps no src and is stale: it is never stamped with the current hash.
    """
    by_key = dict((e["key"], e) for e in pack["entries"])
    out, problems = [], []
    for key in keys:
        if key not in strings:
            problems.append("%s: missing from the reply" % key)
            continue
        entry = by_key[key]
        items = translib.items_of(entry["kind"], strings[key])
        if items is None:
            problems.append("%s: expected %s" % (key, "a string" if entry["kind"] == "string" else
                                                 "an object of plural categories"))
            continue
        if srcs is None:
            src = entry["source_sha1"]
        else:
            src = srcs.get(key) if isinstance(srcs.get(key), str) else None
            if src is None:
                problems.append("%s: no src copied from the prompt; the entry is stale until redrafted" % key)
            elif src != entry["source_sha1"]:
                problems.append("%s: src %s is not the current English's (%s); the entry is stale" % (
                    key, src[:12], entry["source_sha1"][:12]))
        out.append((key, entry["kind"], items, 1, src))
    for key in strings:
        if key not in by_key:
            problems.append("%s: not in the pack; ignored" % key)
    return out, problems


def existing_draft_entries(path):
    if not os.path.exists(path):
        return collections.OrderedDict()
    draft = translib.read_draft(path)
    return collections.OrderedDict((k, (k, e.kind, e.items, e.attempt, e.src)) for k, e in draft.entries.items())


def save_draft(paths, pack, name, model, model_reported, new_entries, prompt, replace=False, attempt=None):
    """Write or update drafts/<name>.xml in pack order. attempt marks the replaced keys (redraft)."""
    path = os.path.join(paths.drafts(pack["tag"], pack["area"]), name + ".xml")
    merged = collections.OrderedDict() if replace else existing_draft_entries(path)
    for key, kind, items, _, src in new_entries:
        merged[key] = (key, kind, items, attempt or 1, src)
    order = [e["key"] for e in pack["entries"]]
    ordered = [merged[k] for k in order if k in merged]
    translib.write_draft(path, name, model, ordered, model_reported, prompt)
    return path


# ---------------------------------------------------------------------------
# API drafting
# ---------------------------------------------------------------------------

def pick_model(provider, role, model=None, prefer=None, avoid=()):
    available = providers.list_models(provider)
    if model:
        if model not in available:
            raise providers.ProviderError("%s is not in the %s model list" % (model, provider))
        if model in avoid:
            raise providers.ProviderError("%s drafted this area; the back-translator must be another model" % model)
        return model
    return providers.choose_model(provider, role, available, prefer=prefer, avoid=avoid)


def api_draft(paths, provider, tag, area, model=None, prefer=None, style="standard", name=None,
              include_current=False, out=None):
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    name = name or provider
    if translib.draft_provider(name) != provider:
        raise translib.L10nError("draft name %s does not belong to %s" % (name, provider))
    entries = needed_entries(pack, include_current)
    if not entries:
        out.write("%s/%s: nothing to draft (every key is current)\n" % (tag, area))
        return None
    model = pick_model(provider, "draft", model, prefer)
    system = instructions(pack, style)
    collected, reported, problems = [], None, []
    for index, chunk in enumerate(chunks(pack, entries)):
        text, reported = providers.generate(provider, model, system, user_prompt(pack, chunk))
        strings = reply_strings(providers.parse_json_reply(text))
        got, chunk_problems = replies_to_entries(pack, strings, [e["key"] for e in chunk])
        collected.extend(got)
        problems.extend(chunk_problems)
        log_run(paths, tag, area, collections.OrderedDict([
            ("stage", "draft"), ("draft", name), ("provider", provider), ("model", model),
            ("model_reported", reported), ("chunk", index), ("keys", len(chunk)), ("received", len(got))]))
    path = save_draft(paths, pack, name, model, reported, collected, style, replace=True)
    out.write("%s: %d of %d keys drafted by %s%s\n" % (paths.rel(path), len(collected), len(entries), model,
                                                       " (reported %s)" % reported if reported != model else ""))
    for problem in problems:
        out.write("  %s\n" % problem)
    return path


def failed_keys(paths, tag, area, name):
    """{key: findings} of a draft's failed first attempts, from checks.jsonl."""
    out = collections.OrderedDict()
    for row in translib.read_jsonl(paths.work(tag, area, "checks.jsonl")):
        if row.get("draft") == name and not row.get("ok") and row.get("attempt", 1) < MAX_ATTEMPTS:
            out[row["key"]] = row.get("findings", [])
    return out


def api_redraft(paths, provider, tag, area, name=None, out=None):
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    name = name or provider
    path = os.path.join(paths.drafts(tag, area), name + ".xml")
    draft = translib.read_draft(path)
    failed = failed_keys(paths, tag, area, name)
    if not failed:
        out.write("%s: no failed first attempts to send back (run check first)\n" % name)
        return None
    by_key = dict((e["key"], e) for e in pack["entries"])
    entries = [by_key[k] for k in failed if k in by_key]
    collected, reported = [], draft.model_reported
    for chunk in chunks(pack, entries):
        text, reported = providers.generate(provider, draft.model, instructions(pack, draft.prompt or "standard"),
                                            user_prompt(pack, chunk, failed))
        got, problems = replies_to_entries(pack, reply_strings(providers.parse_json_reply(text)),
                                           [e["key"] for e in chunk])
        collected.extend(got)
        for problem in problems:
            out.write("  %s\n" % problem)
    save_draft(paths, pack, name, draft.model, reported, collected, draft.prompt, attempt=MAX_ATTEMPTS)
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "redraft"), ("draft", name), ("provider", provider), ("model", draft.model),
        ("model_reported", reported), ("keys", len(entries)), ("received", len(collected))]))
    out.write("%s: redrafted %d keys (attempt %d); run check again\n" % (name, len(collected), MAX_ATTEMPTS))
    return path


# ---------------------------------------------------------------------------
# Claude Code agents
# ---------------------------------------------------------------------------

def claude_prompt(paths, tag, area, name, style, redraft=False):
    pack = packlib.load_pack(paths, tag, area)
    draft_name = "claude-" + name
    draft_path = os.path.join(paths.drafts(tag, area), draft_name + ".xml")
    findings = failed_keys(paths, tag, area, draft_name) if redraft else {}
    if redraft and not findings:
        raise translib.L10nError("%s has no failed first attempts to redraft" % draft_name)
    by_key = dict((e["key"], e) for e in pack["entries"])
    entries = [by_key[k] for k in findings] if redraft else needed_entries(pack)
    reply = paths.work(tag, area, "replies", draft_name + (".redraft" if redraft else "") + ".json")
    text = "\n\n".join([
        "# Volt translation draft: %s, area %s, %s" % (tag, area, draft_name),
        "Work alone: do not open other drafts, judge files or decisions under l10n/work/ or l10n/decisions/.",
        instructions(pack, style),
        "Each string below has a `src`: the hash of the English you translate. Copy every one unchanged "
        "into your reply, as a top-level `\"src\": {\"<key>\": \"<src>\"}` object next to `strings`. It "
        "records which English each text translates; a text without its src, or with another English's, is "
        "ignored.",
        "When done, write the JSON object to `%s`, then run:\n\n"
        "    python3 tools/l10n/council.py import-claude --tag %s --area %s --name %s --model <your exact model id>"
        " --from %s%s" % (paths.rel(reply), tag, area, name, paths.rel(reply), " --redraft" if redraft else ""),
        "It converts the reply into `%s` with the correct escaping. You may instead write that XML file directly "
        "(root `<resources xmlns:volt=\"%s\" volt:provider=\"claude\" volt:model=\"<model id>\">`, each "
        "`<string>` or `<plurals>` with `volt:src=\"<its src>\"`, Android resource escaping) and run "
        "import-claude without --from to validate it." % (
            paths.rel(draft_path), gen_catalog.VOLT_NS),
        "## Strings\n\n" + user_prompt(pack, entries, findings, with_src=True),
    ])
    path = paths.work(tag, area, "prompts", draft_name + (".redraft" if redraft else "") + ".md")
    l10nlib.write_text(path, text + "\n")
    return path


def import_claude(paths, tag, area, source=None, name=None, model=None, redraft=False, out=None):
    """Validate drafts/claude-*.xml; with source, first convert a JSON reply into drafts/claude-<name>.xml."""
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    if source:
        if not name or not model:
            raise translib.L10nError("--from needs --name and --model")
        draft_name = "claude-" + name
        value = json.loads(l10nlib.read_text(source))
        if not isinstance(value, dict):
            raise translib.L10nError("%s: not a JSON object" % source)
        strings = reply_strings(value)
        srcs = value.get("src") if isinstance(value.get("src"), dict) else {}
        path = os.path.join(paths.drafts(tag, area), draft_name + ".xml")
        if redraft:
            failed = failed_keys(paths, tag, area, draft_name)
            got, problems = replies_to_entries(pack, strings, [k for k in strings if k in failed], srcs)
            if not os.path.exists(path):
                raise translib.L10nError("%s does not exist; --redraft replaces keys of an existing draft" % path)
            previous = translib.read_draft(path)
            save_draft(paths, pack, draft_name, previous.model, previous.model_reported, got, previous.prompt,
                       attempt=MAX_ATTEMPTS)
        else:
            got, problems = replies_to_entries(
                pack, strings, [e["key"] for e in pack["entries"] if e["key"] in strings], srcs)
            problems.extend("%s: needed but missing from the reply" % e["key"] for e in needed_entries(pack)
                            if e["key"] not in strings)
            save_draft(paths, pack, draft_name, model, model, got, value.get("style"), replace=True)
        for problem in problems:
            out.write("  %s\n" % problem)
    errors = 0
    by_key = dict((e["key"], e) for e in pack["entries"])
    directory = paths.drafts(tag, area)
    names = sorted(f for f in os.listdir(directory) if f.startswith("claude-") and f.endswith(".xml")) \
        if os.path.isdir(directory) else []
    if not names:
        raise translib.L10nError("no drafts/claude-*.xml in %s" % paths.rel(directory))
    for filename in names:
        if name and filename != "claude-%s.xml" % name:
            continue
        draft = translib.read_draft(os.path.join(directory, filename))
        problems = []
        if not draft.model.startswith("claude"):
            problems.append("volt:model %r is not a Claude model id" % draft.model)
        for key, entry in draft.entries.items():
            if key not in by_key:
                problems.append("%s is not in the pack" % key)
            elif entry.kind != by_key[key]["kind"]:
                problems.append("%s: a %s where English has a %s" % (key, entry.kind, by_key[key]["kind"]))
            else:
                stale = checklib.stale_source(entry.src, by_key[key]["source_sha1"])
                if stale:
                    problems.append("%s: %s; redraft it from the current prompt" % (key, stale))
        errors += len(problems)
        out.write("%s (%s): %d keys%s\n" % (filename, draft.model, len(draft.entries),
                                             "" if not problems else ", %d problems" % len(problems)))
        for problem in problems:
            out.write("  %s\n" % problem)
        log_run(paths, tag, area, collections.OrderedDict([
            ("stage", "import"), ("draft", draft.name), ("provider", "claude"), ("model", draft.model),
            ("keys", len(draft.entries)), ("problems", len(problems))]))
    return errors


# ---------------------------------------------------------------------------
# Agreement (shared by back-translation, judge requests and decisions)
# ---------------------------------------------------------------------------

Candidate = collections.namedtuple("Candidate", "draft provider model model_reported attempt items findings ok")
# stale: [{draft, provider, model, src, why}] for draft entries of other English (or of unknown English).
KeyState = collections.namedtuple("KeyState", "entry candidates passing agreed unanimous providers stale")


def analyze(paths, pack, drafts=None, use_java=True):
    """{key: KeyState} for every pack entry, with the checks recomputed on every draft.

    A draft entry whose volt:src is not the current English's hash (or is missing) is stale: it is
    no candidate, it is not checked, and its draft counts as missing that key (so a ladder it no
    longer covers fails the ladder check). It is listed in KeyState.stale, never relabelled.
    """
    drafts = translib.list_drafts(paths, pack["tag"], pack["area"]) if drafts is None else drafts
    font = fontmetrics.Font(paths.font())
    measurer = fontmetrics.Measurer(paths.font(), use_java=use_java)
    results = {}
    for draft in drafts:
        results[draft.name] = checklib.check_targets(pack, checklib.draft_targets(draft, pack), font, measurer)
    states = collections.OrderedDict()
    for entry in pack["entries"]:
        key = entry["key"]
        candidates, stale = [], []
        for draft in drafts:
            if key in draft.entries:
                d = draft.entries[key]
                why = checklib.stale_source(d.src, entry["source_sha1"])
                if why:
                    stale.append(collections.OrderedDict([
                        ("draft", draft.name), ("provider", draft.provider), ("model", draft.model),
                        ("src", d.src), ("why", why)]))
                    continue
                findings = results[draft.name].get(key, [])
                candidates.append(Candidate(draft.name, draft.provider, draft.model, draft.model_reported, d.attempt,
                                            d.items, findings, checklib.is_ok(findings)))
        passing = [c for c in candidates if c.ok]
        agreed = None
        values = set(translib.normalized_value(c.items) for c in passing)
        if len(passing) >= 2 and len(values) == 1:
            agreed = representative(passing)
        unanimous = (bool(candidates) and len(passing) == len(candidates) and len(values) == 1
                     and set(c.provider for c in candidates) >= set(translib.PROVIDERS))
        states[key] = KeyState(entry, candidates, passing, agreed, unanimous,
                               sorted(set(c.provider for c in candidates)), stale)
    return states


def representative(candidates):
    """The exact spelling most candidates used (ties: provider order, then draft name)."""
    counts = collections.Counter(json.dumps(c.items, sort_keys=True, ensure_ascii=False) for c in candidates)
    order = dict((p, i) for i, p in enumerate(translib.PROVIDERS))
    best = sorted(candidates, key=lambda c: (-counts[json.dumps(c.items, sort_keys=True, ensure_ascii=False)],
                                             order[c.provider], c.draft))
    return best[0].items


def all_drafter_models(drafts):
    return set(d.model for d in drafts) | set(d.model_reported for d in drafts)


# ---------------------------------------------------------------------------
# Back-translation
# ---------------------------------------------------------------------------

def backtranslation_targets(states):
    """Safety keys whose drafts are unanimous: the text to back-translate."""
    return collections.OrderedDict((k, s) for k, s in states.items() if s.entry.get("safety") and s.unanimous)


def backtranslate_instructions(pack):
    return ("Translate each %s string back into English as literally as you can, keeping placeholders such as "
            "%%1$s exactly. Do not guess what the original English was; translate what is written, including "
            "every negation. Answer with one JSON object only: {\"strings\": {\"<key>\": \"<English>\" or "
            "{\"<category>\": \"<English>\", ...}}}." % pack["language"]["endonym"])


def backtranslate_prompt(pack, targets):
    body = [collections.OrderedDict([("key", k), ("kind", s.entry["kind"]),
                                     ("text", translib.text_value(s.entry["kind"], s.agreed))])
            for k, s in targets.items()]
    return "Back-translate these %d strings.\n\n%s" % (len(body), json.dumps(body, ensure_ascii=False, indent=1))


def store_backtranslation(paths, pack, targets, strings, provider, model, model_reported, drafters):
    if model in drafters or model_reported in drafters:
        raise translib.L10nError("%s drafted this area; a back-translation must come from a model that drafted "
                                 "nothing" % model)
    path = paths.work(pack["tag"], pack["area"], "backtranslation.jsonl")
    rows = collections.OrderedDict((r["key"], r) for r in translib.read_jsonl(path))
    stored = 0
    for key, state in targets.items():
        if key not in strings:
            continue
        back = translib.items_of(state.entry["kind"], strings[key])
        if back is None:
            continue
        rows[key] = collections.OrderedDict([
            ("key", key), ("source_sha1", state.entry["source_sha1"]),
            ("target", translib.text_value(state.entry["kind"], state.agreed)),
            ("back", translib.text_value(state.entry["kind"], back)), ("provider", provider), ("model", model),
            ("model_reported", model_reported)])
        stored += 1
    translib.write_jsonl(path, list(rows.values()))
    return stored


def api_backtranslate(paths, provider, tag, area, model=None, prefer=None, use_java=True, out=None):
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    drafts = translib.list_drafts(paths, tag, area)
    states = analyze(paths, pack, drafts, use_java)
    targets = backtranslation_targets(states)
    if not targets:
        out.write("no unanimous safety keys to back-translate\n")
        return 0
    drafters = all_drafter_models(drafts)
    model = pick_model(provider, "backtranslate", model, prefer, avoid=drafters)
    stored, reported = 0, model
    keys = list(targets)
    for start in range(0, len(keys), CHUNK):
        part = collections.OrderedDict((k, targets[k]) for k in keys[start:start + CHUNK])
        text, reported = providers.generate(provider, model, backtranslate_instructions(pack),
                                            backtranslate_prompt(pack, part))
        stored += store_backtranslation(paths, pack, part, reply_strings(providers.parse_json_reply(text)),
                                        provider, model, reported, drafters)
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "backtranslate"), ("provider", provider), ("model", model), ("model_reported", reported),
        ("keys", len(keys)), ("received", stored)]))
    out.write("back-translated %d of %d safety keys with %s\n" % (stored, len(keys), model))
    return stored


BACKTRANSLATION_REQUEST_FORMAT = "volt-l10n-backtranslation-request-v1"
REQUEST_ID = re.compile(r"^[0-9a-f]{40}$")


def text_sha1(kind, items):
    """The hash of a text as a prompt shows it (its JSON form), which a back-translator copies back."""
    return hashlib.sha1(translib.dump_json_line(translib.text_value(kind, items)).encode("utf-8")).hexdigest()


def backtranslation_request(tag, area, targets):
    """The immutable record of one back-translation prompt: what it showed, and its id.

    The id is the SHA-1 of the tag, area, and each key's kind, source hash and target text, so two
    prompts share an id only when they showed exactly the same texts of the same English.
    """
    keys = collections.OrderedDict(
        (k, collections.OrderedDict([("kind", s.entry["kind"]), ("source_sha1", s.entry["source_sha1"]),
                                     ("target", translib.text_value(s.entry["kind"], s.agreed)),
                                     ("target_sha1", text_sha1(s.entry["kind"], s.agreed))]))
        for k, s in targets.items())
    return collections.OrderedDict([("format", BACKTRANSLATION_REQUEST_FORMAT),
                                    ("id", backtranslation_request_id(tag, area, keys)),
                                    ("tag", tag), ("area", area), ("keys", keys)])


def backtranslation_request_id(tag, area, keys):
    shown = collections.OrderedDict([("format", BACKTRANSLATION_REQUEST_FORMAT), ("tag", tag), ("area", area),
                                     ("keys", dict((k, [v.get("kind"), v.get("source_sha1"), v.get("target")])
                                                   for k, v in keys.items()))])
    return hashlib.sha1(translib.dump_json_line(shown).encode("utf-8")).hexdigest()


def backtranslation_request_path(paths, tag, area, request_id):
    return paths.work(tag, area, "prompts", "backtranslation.request.%s.json" % request_id)


def load_backtranslation_request(paths, tag, area, request_id):
    """The request a reply names, checked against its own id; L10nError if there is none."""
    if not isinstance(request_id, str) or not REQUEST_ID.match(request_id):
        raise translib.L10nError("the reply has no request id: copy `request` from the prompt you answered "
                                 "(prompt --role backtranslate); nothing was stored")
    path = backtranslation_request_path(paths, tag, area, request_id)
    if not os.path.exists(path):
        raise translib.L10nError("the reply names request %s, which %s has no record of; nothing was stored" % (
            request_id, paths.rel(paths.work(tag, area, "prompts"))))
    request = json.loads(l10nlib.read_text(path))
    keys = request.get("keys") if isinstance(request, dict) else None
    if (not isinstance(keys, dict) or request.get("format") != BACKTRANSLATION_REQUEST_FORMAT
            or request.get("id") != request_id or request.get("tag") != tag or request.get("area") != area
            or not all(isinstance(v, dict) for v in keys.values())
            or backtranslation_request_id(tag, area, keys) != request_id):
        raise translib.L10nError("%s does not match its id; write the prompt again" % paths.rel(path))
    return request


def import_backtranslation(paths, tag, area, source, model, use_java=True, out=None):
    """Store a Claude agent's back-translations of the texts its prompt showed.

    The reply carries the id of the prompt it answers (`request`) and, per key, the hash of the
    text it back-translated (`target_sha1`), both copied from that prompt. The request is loaded by
    that id (prompts/backtranslation.request.<id>.json, never overwritten), so a later prompt does
    not change what an earlier reply is bound to. A reply without an id, with an unknown one, or
    with a key or text hash its request did not show is refused whole. A key is stored only while
    the request's English and text are still the current English and agreed text; otherwise it is
    dropped, never stored as a back-translation of the new text.
    """
    out = out or sys.stdout
    value = json.loads(l10nlib.read_text(source))
    if not isinstance(value, dict):
        raise translib.L10nError("%s: not a JSON object" % source)
    request = load_backtranslation_request(paths, tag, area, value.get("request"))
    shown = request["keys"]
    strings = value.get("strings") if isinstance(value.get("strings"), dict) else {}
    hashes = value.get("target_sha1") if isinstance(value.get("target_sha1"), dict) else {}
    problems = []
    for key in strings:
        if key not in shown:
            problems.append("%s: not in request %s" % (key, request["id"][:12]))
        elif hashes.get(key) != shown[key].get("target_sha1"):
            problems.append("%s: target_sha1 %s is not the hash of the text request %s showed" % (
                key, str(hashes.get(key))[:12], request["id"][:12]))
    if problems:
        raise translib.L10nError("the reply does not match its request; nothing was stored:\n  " +
                                 "\n  ".join(problems))
    pack = packlib.load_pack(paths, tag, area)
    drafts = translib.list_drafts(paths, tag, area)
    states = analyze(paths, pack, drafts, use_java)
    current = backtranslation_targets(states)
    targets = collections.OrderedDict()
    for key in strings:
        state = current.get(key)
        if (state is None or shown[key].get("source_sha1") != state.entry["source_sha1"]
                or not same_text(state.entry["kind"], shown[key].get("target"), state.agreed)):
            out.write("  %s: the English or text changed since request %s; back-translate it again\n" % (
                key, request["id"][:12]))
            continue
        targets[key] = state
    provider = "claude" if model.startswith("claude") else "other"
    stored = store_backtranslation(paths, pack, targets, strings, provider, model, model,
                                   all_drafter_models(drafts))
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "import-backtranslation"), ("provider", provider), ("model", model),
        ("request", request["id"]), ("received", stored)]))
    out.write("stored %d back-translations by %s\n" % (stored, model))
    return stored


def backtranslation_prompt_file(paths, tag, area, use_java=True):
    """A prompt for a Claude Code agent whose model drafted nothing in this area.

    Each prompt records what it showed in prompts/backtranslation.request.<id>.json, which is never
    overwritten; the agent copies the id and each text's hash into its reply.
    """
    pack = packlib.load_pack(paths, tag, area)
    drafts = translib.list_drafts(paths, tag, area)
    targets = backtranslation_targets(analyze(paths, pack, drafts, use_java))
    request = backtranslation_request(tag, area, targets)
    request_path = backtranslation_request_path(paths, tag, area, request["id"])
    if not os.path.exists(request_path):  # the same id is the same contents: never rewrite one
        translib.write_json(request_path, request)
    reply = paths.work(tag, area, "replies", "backtranslation.%s.json" % request["id"][:12])
    body = [collections.OrderedDict([("key", k), ("kind", v["kind"]), ("text", v["target"]),
                                     ("target_sha1", v["target_sha1"])]) for k, v in request["keys"].items()]
    text = "\n\n".join([
        "# Volt back-translation: %s, area %s" % (tag, area),
        "Request id: `%s`" % request["id"],
        "Your model must not be one of the drafters: %s." % ", ".join(sorted(all_drafter_models(drafts))),
        "Do not open the English source, the pack or the drafts.",
        backtranslate_instructions(pack),
        "Your JSON object must also carry this prompt's request id and, for every key, the `target_sha1` of the "
        "text you back-translated, both copied unchanged from this prompt:\n\n"
        "    {\"request\": \"%s\", \"strings\": {\"<key>\": ...}, \"target_sha1\": {\"<key>\": \"<its target_sha1>\"}}"
        "\n\nThey bind your answer to the texts shown here: a reply without them, or with another prompt's, is "
        "refused." % request["id"],
        "Write the JSON object to `%s`, then run:\n\n    python3 tools/l10n/council.py import-backtranslation "
        "--tag %s --area %s --from %s --model <your exact model id>" % (paths.rel(reply), tag, area, paths.rel(reply)),
        "## Strings\n\nBack-translate these %d strings.\n\n%s" % (
            len(body), json.dumps(body, ensure_ascii=False, indent=1))])
    path = paths.work(tag, area, "prompts", "backtranslation.md")
    l10nlib.write_text(path, text + "\n")
    return path, len(targets)


# ---------------------------------------------------------------------------
# Judge
# ---------------------------------------------------------------------------

def load_backtranslations(paths, pack):
    rows = {}
    for row in translib.read_jsonl(paths.work(pack["tag"], pack["area"], "backtranslation.jsonl")):
        rows[row.get("key")] = row
    return rows


def current_backtranslation(state, back):
    """back if it is of the current English and of the text every drafter agreed on, else None."""
    entry = state.entry
    if not back or back.get("source_sha1") != entry["source_sha1"] or state.agreed is None:
        return None
    target = translib.items_of(entry["kind"], back.get("target"))
    if target is None or translib.normalized_value(target) != translib.normalized_value(state.agreed):
        return None
    return back


def same_text(kind, value, items):
    """True when value (a judge's copy of a text) is items after normalizing."""
    copied = translib.items_of(kind, value) if value is not None else None
    return copied is not None and items is not None and (
        translib.normalized_value(copied) == translib.normalized_value(items))


def judge_requests(paths, tag, area, use_java=True):
    """Rows for judge-requests.jsonl: disagreements, single drafts and safety confirmations.

    A safety key is listed only once a current back-translation of the agreed text exists, and the
    request carries both texts: the judge copies them into its ruling, which then confirms exactly
    that text and that back-translation (decide() checks both).
    """
    pack = packlib.load_pack(paths, tag, area)
    states = analyze(paths, pack, use_java=use_java)
    backs = load_backtranslations(paths, pack)
    rows = []
    for key, state in states.items():
        entry = state.entry
        safety = entry.get("safety") or []
        if not state.candidates:
            continue
        back = None
        if safety:
            if not state.unanimous:
                continue  # falls back to English: the strict safety rule allows no reconciliation
            back = current_backtranslation(state, backs.get(key))
            if back is None:
                continue  # back-translate first: the judge confirms the text and its back-translation
            reason = "safety-confirmation"
        elif state.agreed is not None or not state.passing:
            continue
        else:
            reason = "disagreement" if len(state.passing) > 1 else "single-draft"
        rows.append(collections.OrderedDict([
            ("key", key), ("source_sha1", entry["source_sha1"]), ("reason", reason), ("kind", entry["kind"]),
            ("english", entry["english"]), ("safety", safety), ("context", model_view(entry)),
            ("text", translib.text_value(entry["kind"], state.agreed) if safety else None),
            ("candidates", [collections.OrderedDict([
                ("draft", c.draft), ("provider", c.provider), ("model", c.model),
                ("text", translib.text_value(entry["kind"], c.items)), ("ok", c.ok),
                ("findings", [f for f in c.findings if f["severity"] != checklib.INFO])]) for c in state.candidates]),
            ("backtranslation", back),
            ("verdicts", ["confirm", "fallback"] if safety else ["pick", "merge", "fallback"]),
            ("confirm", list(SAFETY_CONFIRMATIONS) if safety else [])]))
    return pack, rows


def write_judge_requests(paths, tag, area, use_java=True):
    pack, rows = judge_requests(paths, tag, area, use_java)
    translib.write_jsonl(paths.work(tag, area, "judge-requests.jsonl"), rows)
    judge_path = paths.work(tag, area, "judge.jsonl")
    text = "\n\n".join([
        "# Volt translation judge: %s, area %s" % (tag, area),
        "You are the council's judge, in a session separate from every drafter. Rule on each line of `%s`. "
        "Append one JSON object per key, on its own line, to `%s`:" % (
            paths.rel(paths.work(tag, area, "judge-requests.jsonl")), paths.rel(judge_path)),
        "    {\"key\": ..., \"source_sha1\": <copied from the request>, \"model\": <your exact model id>,\n"
        "     \"verdict\": \"pick\" | \"merge\" | \"confirm\" | \"fallback\", \"pick\": <draft name, for pick>,\n"
        "     \"text\": <for pick: the picked candidate's text, copied exactly; for merge: your text; a string or\n"
        "              {category: text}>, \"reason\": <why, one or two sentences>,\n"
        "     \"confirm\": {<each item of the request's confirm list>: true}, \"backtranslation_meaning_kept\": true,\n"
        "     \"back\": <for confirm: the request's backtranslation.back, copied exactly>}",
        "- disagreement / single-draft: pick the best passing draft or merge a better text from them. For pick, "
        "copy that candidate's text into your text field exactly: a pick counts only while that draft still has "
        "that text, so a redrafted candidate needs a new ruling. A merged text is checked again; if it fails, the "
        "key stays English.",
        "- safety-confirmation: every drafter wrote the same text (the request's text field). For confirm, copy "
        "that text into your text field and the request's backtranslation.back into back, exactly: a confirm "
        "counts only for the text and back-translation it names. Compare the text with the English and the "
        "back-translation. Answer confirm only when each item holds: locked and unlocked stay distinct; on/off and "
        "never/always are not inverted; \"unverified\" or \"unknown\" never reads as confirmed; numbers, units and "
        "the named action are unchanged; \"not affiliated with NAVEE\" is not softened; and the back-translation "
        "keeps the meaning. Otherwise answer fallback: the English stays, which is safe.",
        "- Use the language rules below; the context field of each request shows the screen, purpose and budget.",
        instructions(pack, "standard"),
    ])
    path = paths.work(tag, area, "prompts", "judge.md")
    l10nlib.write_text(path, text + "\n")
    return path, rows


def load_judge(paths, pack):
    """{key: ruling} for rulings whose source_sha1 is current; later lines win."""
    rulings = {}
    problems = []
    current = dict((e["key"], e["source_sha1"]) for e in pack["entries"])
    for row in translib.read_jsonl(paths.work(pack["tag"], pack["area"], "judge.jsonl")):
        key = row.get("key")
        if key not in current:
            problems.append("judge.jsonl: %s is not in the pack" % key)
            continue
        if row.get("source_sha1") != current[key]:
            continue  # a ruling on older English
        if row.get("verdict") not in VERDICTS or not row.get("model") or not row.get("reason"):
            problems.append("judge.jsonl: %s needs verdict (%s), model and reason" % (key, "/".join(VERDICTS)))
            continue
        rulings[key] = row
    return rulings, problems


# ---------------------------------------------------------------------------
# Decisions
# ---------------------------------------------------------------------------

def _alternatives(entry, state):
    return [collections.OrderedDict([
        ("draft", c.draft), ("provider", c.provider), ("model", c.model), ("model_reported", c.model_reported),
        ("attempt", c.attempt), ("text", translib.text_value(entry["kind"], c.items)), ("ok", c.ok),
        ("findings", [f for f in c.findings if f["severity"] != checklib.INFO])]) for c in state.candidates]


def decide(paths, tag, area, final=False, use_java=True):
    """(rows, pending, problems). rows are the decisions in pack order.

    Per key: outcome is agreed (two or more passing drafts agree after normalizing), judged (the
    judge picked or merged; a merge passes the checks again), safety-agreed (a volt:safety key that
    every drafter, from all three providers, wrote the same way, whose drafts all pass, whose
    back-translation by a model that drafted nothing was made from that text, and which the judge
    confirmed item by item, naming that text and that back-translation), or fallback-english (the key is left out of values-<qual>). A ladder
    group falls back as a whole when any rung does. Keys still waiting for a judge ruling or a
    back-translation are pending; with final=True they fall back instead.

    Only draft entries of the current English count (volt:src, see analyze()); stale ones are listed
    in stale_drafts and named in a fallback's reason. A pick counts only while the picked draft has
    the text the ruling copied; otherwise the key waits for a new ruling.
    """
    pack = packlib.load_pack(paths, tag, area)
    drafts = translib.list_drafts(paths, tag, area)
    states = analyze(paths, pack, drafts, use_java)
    rulings, problems = load_judge(paths, pack)
    backs = load_backtranslations(paths, pack)
    drafters = all_drafter_models(drafts)
    previous = dict((r.get("key"), r) for r in translib.read_jsonl(paths.decisions(tag, area)))
    font = fontmetrics.Font(paths.font())
    measurer = fontmetrics.Measurer(paths.font(), use_java=use_java)
    rows, pending = collections.OrderedDict(), []

    def row(entry, state, outcome, reason, items=None, judge=None, back=None, checks=None):
        drafter_ids = sorted(set(c.model for c in state.candidates)) if state else []
        stale = list(state.stale) if state else []
        if stale and outcome == "fallback-english":
            reason = "%s; stale drafts ignored: %s" % (reason, ", ".join(
                "%s (%s)" % (s["draft"], s["why"]) for s in stale))
        return collections.OrderedDict([
            ("format", DECISION_FORMAT), ("key", entry["key"]), ("kind", entry["kind"]),
            ("source_sha1", entry["source_sha1"]), ("english", entry["english"]), ("safety", entry.get("safety") or []),
            ("outcome", outcome), ("text", translib.text_value(entry["kind"], items) if items else None),
            ("reason", reason), ("alternatives", _alternatives(entry, state) if state else []),
            ("stale_drafts", stale),
            ("models", collections.OrderedDict([
                ("drafters", drafter_ids), ("judge", judge.get("model") if judge else None),
                ("backtranslator", back.get("model") if back else None)])),
            ("checks", checks or []),
            ("judge", collections.OrderedDict((k, judge.get(k)) for k in (
                "verdict", "pick", "text", "back", "reason", "confirm", "backtranslation_meaning_kept"))
             if judge else None),
            ("backtranslation", collections.OrderedDict([
                ("provider", back.get("provider")), ("model", back.get("model")), ("back", back.get("back"))])
             if back else None)])

    for key, state in states.items():
        entry = state.entry
        ruling = rulings.get(key)
        if not state.candidates:
            old = previous.get(key)
            if old and old.get("source_sha1") == entry["source_sha1"]:
                rows[key] = old
            else:
                rows[key] = row(entry, state, "fallback-english", "no current drafts" if state.stale else "no drafts")
            continue
        if entry.get("safety"):
            if not state.unanimous:
                missing = set(translib.PROVIDERS) - set(state.providers)
                why = ("no draft from %s" % ", ".join(sorted(missing)) if missing else
                       "a draft failed the checks" if len(state.passing) < len(state.candidates) else
                       "the drafters disagree")
                rows[key] = row(entry, state, "fallback-english", "safety: %s" % why)
                continue
            back = backs.get(key)
            if back and back.get("source_sha1") != entry["source_sha1"]:
                back = None
            if back is None:
                pending.append((key, "a back-translation"))
                rows[key] = row(entry, state, "fallback-english", "safety: no back-translation")
                continue
            if current_backtranslation(state, back) is None:
                pending.append((key, "a back-translation of the current text"))
                rows[key] = row(entry, state, "fallback-english", "safety: the back-translation is of another text")
                continue
            if back.get("model") in drafters or back.get("model_reported") in drafters:
                rows[key] = row(entry, state, "fallback-english",
                                "safety: the back-translator %s also drafted" % back.get("model"), back=back)
                continue
            if ruling is None:
                pending.append((key, "the judge's safety confirmation"))
                rows[key] = row(entry, state, "fallback-english", "safety: not confirmed by the judge", back=back)
                continue
            confirm = ruling.get("confirm") or {}
            failed = [c for c in SAFETY_CONFIRMATIONS if confirm.get(c) is not True]
            if ruling["verdict"] != "confirm":
                rows[key] = row(entry, state, "fallback-english", "safety: the judge ruled %s: %s" % (
                    ruling["verdict"], ruling.get("reason")), judge=ruling, back=back)
            elif failed or ruling.get("backtranslation_meaning_kept") is not True:
                rows[key] = row(entry, state, "fallback-english", "safety: not confirmed: %s" % ", ".join(
                    failed + ([] if ruling.get("backtranslation_meaning_kept") is True else
                              ["backtranslation_meaning_kept"])), judge=ruling, back=back)
            elif not (same_text(entry["kind"], ruling.get("text"), state.agreed) and
                      same_text(entry["kind"], ruling.get("back"), translib.items_of(entry["kind"], back.get("back")))):
                # The confirmation names another text or back-translation (or none): it confirms nothing here.
                pending.append((key, "the judge's confirmation of the current text and back-translation"))
                rows[key] = row(entry, state, "fallback-english", "safety: the judge confirmed another text or "
                                "back-translation", judge=ruling, back=back)
            else:
                checks = [f for f in _chosen_findings(state, state.agreed) if f["severity"] != checklib.INFO]
                rows[key] = row(entry, state, "safety-agreed", "every drafter agreed; back-translation kept the "
                                "meaning; judge confirmed: %s" % ruling.get("reason"), state.agreed, ruling, back, checks)
            continue
        if state.agreed is not None:
            checks = [f for f in _chosen_findings(state, state.agreed) if f["severity"] != checklib.INFO]
            rows[key] = row(entry, state, "agreed", "%d of %d drafts agree" % (
                len(state.passing), len(state.candidates)), state.agreed, checks=checks)
            continue
        if not state.passing:
            rows[key] = row(entry, state, "fallback-english", "no draft passed the checks")
            continue
        if ruling is None:
            pending.append((key, "a judge ruling"))
            rows[key] = row(entry, state, "fallback-english", "not judged")
            continue
        verdict = ruling["verdict"]
        if verdict == "fallback":
            rows[key] = row(entry, state, "fallback-english", "judge: %s" % ruling["reason"], judge=ruling)
        elif verdict == "pick":
            # A pick names a draft and copies its text: it counts only while that draft has that text
            # (as a safety confirm counts only for the text it names), so a redrafted candidate needs a
            # new ruling and falls back until it has one.
            chosen = [c for c in state.passing if c.draft == ruling.get("pick")]
            if not chosen:
                pending.append((key, "a ruling on the current candidates"))
                rows[key] = row(entry, state, "fallback-english", "judge picked %r, which is not a passing draft" %
                                ruling.get("pick"), judge=ruling)
            elif not same_text(entry["kind"], ruling.get("text"), chosen[0].items):
                pending.append((key, "a ruling on %s's current text" % chosen[0].draft))
                rows[key] = row(entry, state, "fallback-english", "judge picked %s with another text (or none); "
                                "the draft changed since the ruling" % chosen[0].draft, judge=ruling)
            else:
                checks = [f for f in chosen[0].findings if f["severity"] != checklib.INFO]
                rows[key] = row(entry, state, "judged", "judge picked %s: %s" % (chosen[0].draft, ruling["reason"]),
                                chosen[0].items, ruling, checks=checks)
        elif verdict == "merge":
            items = translib.items_of(entry["kind"], ruling.get("text"))
            if items is None:
                rows[key] = row(entry, state, "fallback-english", "judge's merged text has the wrong shape",
                                judge=ruling)
            else:
                findings = checklib.check_targets(pack, {key: (entry["kind"], items, [])}, font, measurer,
                                                  ladders=False)[key]
                findings = [f for f in findings if f["severity"] != checklib.INFO]
                if checklib.is_ok(findings):
                    rows[key] = row(entry, state, "judged", "judge merged: %s" % ruling["reason"], items, ruling,
                                    checks=findings)
                else:
                    rows[key] = row(entry, state, "fallback-english", "judge's merged text failed the checks",
                                    judge=ruling, checks=findings)
        else:
            rows[key] = row(entry, state, "fallback-english", "verdict %s is only for safety keys" % verdict,
                            judge=ruling)

    # A ladder translated in part would mix languages on one label: the whole group falls back.
    for entry in pack["entries"]:
        group = entry.get("ladder_group")
        if not group or entry["key"] != group[0]:
            continue
        outcomes = [rows[k]["outcome"] for k in group if k in rows]
        if any(o in TRANSLATED for o in outcomes) and any(o not in TRANSLATED for o in outcomes):
            for k in group:
                if k in rows and rows[k]["outcome"] in TRANSLATED:
                    rows[k] = collections.OrderedDict(rows[k])
                    rows[k]["outcome"] = "fallback-english"
                    rows[k]["reason"] = "ladder group incomplete (%s fell back); was: %s" % (
                        ", ".join(g for g in group if rows.get(g, {}).get("outcome") not in TRANSLATED),
                        rows[k]["reason"])
                    rows[k]["text"] = None
    if pending and not final:
        return list(rows.values()), pending, problems
    for key, what in pending:
        rows[key] = collections.OrderedDict(rows[key])
        rows[key]["reason"] = "%s (final run: still waiting for %s)" % (rows[key]["reason"], what)
    return list(rows.values()), [] if final else pending, problems


def _chosen_findings(state, items):
    for c in state.passing:
        if translib.normalized_value(c.items) == translib.normalized_value(items):
            return c.findings
    return []


def write_decisions(paths, tag, area, final=False, use_java=True, out=None):
    out = out or sys.stdout
    rows, pending, problems = decide(paths, tag, area, final, use_java)
    for problem in problems:
        out.write("%s\n" % problem)
    if pending:
        out.write("%d keys are waiting; nothing written (use --final to let them fall back to English):\n" % len(pending))
        for key, what in pending:
            out.write("  %s: waiting for %s\n" % (key, what))
        return None
    path = paths.decisions(tag, area)
    translib.write_jsonl(path, rows)
    counts = collections.Counter(r["outcome"] for r in rows)
    out.write("%s: %s\n" % (paths.rel(path), ", ".join("%d %s" % (counts[o], o) for o in sorted(counts))))
    return path


# ---------------------------------------------------------------------------
# The beta bar
# ---------------------------------------------------------------------------

BETA_MAX_FALLBACK = 0.02


def beta_report(paths, tag):
    """(passes, lines). Fallback counts every translatable key without a current translated decision."""
    lang = translib.load_language(paths, tag)
    sources, _ = translib.load_source(paths)
    total = fallback = 0
    blocking, lines = [], []
    for area, rows in sources.items():
        decided = dict((r.get("key"), r) for r in translib.read_jsonl(paths.decisions(tag, area)))
        area_total = area_fallback = 0
        safety = translib.effective_safety(rows)
        for s in rows:
            if not s.translatable:
                continue
            area_total += 1
            sha = translib.source_sha1(s.kind, s.items)
            d = decided.get(s.key)
            if d and d.get("outcome") in TRANSLATED and d.get("source_sha1") == sha:
                continue
            area_fallback += 1
            hit = [x for x in safety[s.key] if x in translib.SAFETY_BLOCKING]
            if hit:
                blocking.append("%s (%s)" % (s.key, ", ".join(hit)))
        total += area_total
        fallback += area_fallback
        lines.append("  %-16s %4d keys, %4d English fallback" % (area, area_total, area_fallback))
    ratio = float(fallback) / total if total else 0.0
    passes = total > 0 and ratio <= BETA_MAX_FALLBACK and not blocking
    lines.insert(0, "%s (%s): %d of %d keys fall back to English (%.2f%%; the bar is %.0f%%)" % (
        tag, lang.folder, fallback, total, 100 * ratio, 100 * BETA_MAX_FALLBACK))
    if blocking:
        lines.append("lock or alarm keys falling back (each blocks the beta): %s" % ", ".join(blocking))
    lines.append("beta bar: %s" % ("PASS" if passes else "FAIL"))
    return passes, lines


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", help="repository root (default: this checkout)")
    parser.add_argument("--no-java", action="store_true", help="measure widths with the hmtx approximation")
    sub = parser.add_subparsers(dest="command", required=True)

    def area_args(p):
        p.add_argument("--tag", required=True)
        p.add_argument("--area", required=True)

    p = sub.add_parser("models", help="list a provider's usable models and the configured picks")
    p.add_argument("provider", choices=API_PROVIDERS)
    p = sub.add_parser("prompt", help="write a prompt for a Claude Code agent")
    area_args(p)
    p.add_argument("--role", choices=("draft", "backtranslate", "judge"), default="draft")
    p.add_argument("--name", help="the draft's config name: drafts/claude-<name>.xml")
    p.add_argument("--style", choices=sorted(STYLES), default="standard")
    p.add_argument("--redraft", action="store_true", help="only the keys whose first attempt failed")
    p = sub.add_parser("import-claude", help="validate drafts/claude-*.xml, or convert a JSON reply into one")
    area_args(p)
    p.add_argument("--from", dest="source")
    p.add_argument("--name")
    p.add_argument("--model")
    p.add_argument("--redraft", action="store_true")
    for command in ("draft", "redraft", "backtranslate"):
        p = sub.add_parser(command)
        p.add_argument("provider", choices=API_PROVIDERS)
        area_args(p)
        p.add_argument("--name", help="draft name (default: the provider)")
        if command != "redraft":
            p.add_argument("--model", help="an exact model id from the provider's list")
            p.add_argument("--prefer", action="append", help="a model-id pattern that replaces the config's")
        if command == "draft":
            p.add_argument("--style", choices=sorted(STYLES), default="standard")
            p.add_argument("--all", action="store_true", help="also keys whose translation is current")
    p = sub.add_parser("import-backtranslation")
    area_args(p)
    p.add_argument("--from", dest="source", required=True)
    p.add_argument("--model", required=True)
    p = sub.add_parser("check")
    area_args(p)
    p = sub.add_parser("judge-requests")
    area_args(p)
    p = sub.add_parser("decide")
    area_args(p)
    p.add_argument("--final", action="store_true", help="keys still waiting fall back to English")
    p = sub.add_parser("beta")
    p.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    use_java = not args.no_java
    try:
        if hasattr(args, "tag"):
            translib.check_tag_area(args.tag, getattr(args, "area", None))
        if args.command == "models":
            available = providers.list_models(args.provider)
            print("%d %s models" % (len(available), args.provider))
            for role in ("draft", "backtranslate"):
                try:
                    print("  %s: %s" % (role, providers.choose_model(args.provider, role, available)))
                except providers.ProviderError as error:
                    print("  %s: %s" % (role, error))
            return 0
        if args.command == "prompt":
            if args.role == "draft":
                if not args.name or not translib.DRAFT_NAME.match(args.name):
                    raise translib.L10nError("--name is required: lower-case letters, digits and dashes")
                path = claude_prompt(paths, args.tag, args.area, args.name, args.style, args.redraft)
            elif args.role == "backtranslate":
                path, count = backtranslation_prompt_file(paths, args.tag, args.area, use_java)
                print("%d keys to back-translate" % count)
            else:
                path, rows = write_judge_requests(paths, args.tag, args.area, use_java)
                print("%d judge requests" % len(rows))
            print(paths.rel(path))
            return 0
        if args.command == "import-claude":
            return 1 if import_claude(paths, args.tag, args.area, args.source, args.name, args.model,
                                      args.redraft) else 0
        if args.command == "draft":
            api_draft(paths, args.provider, args.tag, args.area, args.model, args.prefer, args.style, args.name,
                      args.all)
            return 0
        if args.command == "redraft":
            api_redraft(paths, args.provider, args.tag, args.area, args.name)
            return 0
        if args.command == "backtranslate":
            api_backtranslate(paths, args.provider, args.tag, args.area, args.model, args.prefer, use_java)
            return 0
        if args.command == "import-backtranslation":
            import_backtranslation(paths, args.tag, args.area, args.source, args.model, use_java)
            return 0
        if args.command == "check":
            rows = checklib.run(paths, args.tag, args.area, use_java=use_java)
            return 0 if all(r["ok"] for r in rows) else 1
        if args.command == "judge-requests":
            path, rows = write_judge_requests(paths, args.tag, args.area, use_java)
            print("%d judge requests; prompt: %s" % (len(rows), paths.rel(path)))
            return 0
        if args.command == "decide":
            return 0 if write_decisions(paths, args.tag, args.area, args.final, use_java) else 1
        if args.command == "beta":
            passes, lines = beta_report(paths, args.tag)
            print("\n".join(lines))
            return 0 if passes else 1
    except providers.ProviderError as error:
        print("council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    except (translib.L10nError, gen_catalog.CatalogError, fontmetrics.FontError, ValueError) as error:
        print("council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
