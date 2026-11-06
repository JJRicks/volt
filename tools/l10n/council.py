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
  council.py prompt --tag es --area more --name opus [--style S] --chunk-keys 150 [--redraft | --top-up]
                                                        one prompt per chunk, whose replies carry no src
  council.py import-claude --tag es --area more --name opus --model ID --from-chunks [--partial] [--redraft]
                                                        merge the chunks' replies into drafts/claude-opus.xml
  council.py draft openai|google --tag es --area common  draft with the provider's API
  council.py check --tag es --area common               checks.jsonl (tools/l10n/check.py)
  council.py redraft openai|google --tag es --area common
                                                        send failed keys back once (attempt 2)
  council.py backtranslate openai|google --tag es --area common
                                                        back-translate agreed safety keys, and judged
                                                        safety texts not yet back-translated, with a
                                                        model that drafted nothing
  council.py import-backtranslation --tag es --area common --from reply.json --model ID
                                                        (after prompt --role backtranslate; the
                                                        reply names that prompt's request id)
  council.py judge-requests --tag es --area common      judge-requests.jsonl and the judge's prompt
  council.py decide --tag es --area common [--final]    decisions/<tag>/<area>.jsonl
  council.py beta --tag es                              the owner's beta bar
  council.py usage --tag es [--area A] [--since 2026-11-05T00:00:00Z] [--json]
                                                        the providers' reported tokens, summed per model
  council.py drafts-status --tag es [--name N] [--json]  per area: keys, drafted, stale, missing, failing checks,
                                                        safety, audited, flagged (reads; never writes)
  council.py audit-prompt --tag es --area common --name N [--chunk-keys K] [--top-up]
                                                        a blind back-translation prompt of the safety keys
                                                        (a QA record of a Claude-only draft; never read by
                                                        decide or beta, and no council back-translation)
  council.py audit-compare --tag es --area common --name N --from-chunks --model ID [--partial]
                                                        audit/compare.tsv: English beside each back-translation
  council.py audit-record --tag es --area common --name N --from verdicts.json [--model ID]
                                                        audit/verdicts.tsv: an independent reader's verdicts
  council.py glossary-check --tag es | --english        validate a glossary (exit 1 on a problem); --english
                                                        validates en.tsv and lists terms no string uses

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
                           text), "merge" (text), "confirm" (safety keys: accept the unanimous or
                           judged text) or "fallback" (keep English). A pick counts only while the
                           named draft still has that text. A safety key whose drafts disagree
                           (owner decision of 2026-11-05) takes two rulings: a pick or merge, then,
                           after a model that drafted nothing back-translated that text, a confirm
                           on a later line; the latest pick or merge is the judged text.
                           confirm (safety keys): every item of SAFETY_CONFIRMATIONS set to true,
                           text the confirmed text and back the back-translation the judge saw,
                           both copied from the request; they must match the current ones.
  runs.jsonl               one line per provider call or import: stage, provider, model ids, keys.
  audit/compare.tsv, audit/verdicts.tsv
                           the safety audit's QA record of a Claude-only draft (see audit_prompt());
                           only drafts-status reads them.

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
TRANSLATED = translib.TRANSLATED
# The outcomes a lock or alarm key may ship with: every drafter agreed, or (owner, 2026-11-05) the judge's
# text was back-translated by a model that drafted nothing and then confirmed. Both are in TRANSLATED.
SAFETY_TRANSLATED = ("safety-agreed", "safety-judged")
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
    guide = (pack.get("style_guide") or "").strip()
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
        "is the English width. %s rather than overflow; never break a word." % (
            "Abbreviate per the style guide" if guide else "Abbreviate"),
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
    if guide:
        rules.append("Style guide for %s:\n%s" % (lang["tag"], guide))
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


def has_lone_surrogate(items):
    """JSON can spell half a character (\\ud83d). No file can hold it, and no font can draw it."""
    return any(0xD800 <= ord(ch) <= 0xDFFF for text in items.values() for ch in text)


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
        if has_lone_surrogate(items):
            problems.append("%s: a lone surrogate in the reply; left out" % key)
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
    unjudged = translib.claude_only_glossary_rows(paths, tag)
    if unjudged:
        raise translib.L10nError(
            "l10n/glossary/%s.tsv has %d claude-only rows (%s): the check would hold %s's safety keys to Claude's "
            "word. The council replaces that glossary first (l10n/README.md, Drafting a whole language with Claude "
            "alone)" % (tag, len(unjudged), ", ".join(unjudged[:5]), provider))
    entries = needed_entries(pack, include_current)
    if not entries:
        out.write("%s/%s: nothing to draft (every key is current)\n" % (tag, area))
        return None
    model = pick_model(provider, "draft", model, prefer)
    system = instructions(pack, style)
    collected, reported, problems = [], None, []
    for index, chunk in enumerate(chunks(pack, entries)):
        with providers.recorded_responses() as responses:
            text, reported = providers.generate(provider, model, system, user_prompt(pack, chunk))
        strings = reply_strings(providers.parse_json_reply(text))
        got, chunk_problems = replies_to_entries(pack, strings, [e["key"] for e in chunk])
        collected.extend(got)
        problems.extend(chunk_problems)
        log_run(paths, tag, area, collections.OrderedDict([
            ("stage", "draft"), ("draft", name), ("provider", provider), ("model", model),
            ("model_reported", reported), ("chunk", index), ("keys", len(chunk)), ("received", len(got)),
            ("usage", providers.usage_of(responses[-1]) if responses else None)]))
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
    collected, reported, usage = [], draft.model_reported, {}
    for chunk in chunks(pack, entries):
        with providers.recorded_responses() as responses:
            text, reported = providers.generate(provider, draft.model,
                                                instructions(pack, draft.prompt or "standard"),
                                                user_prompt(pack, chunk, failed))
        providers.add_usage(usage, providers.usage_of(responses[-1]) if responses else None)
        got, problems = replies_to_entries(pack, reply_strings(providers.parse_json_reply(text)),
                                           [e["key"] for e in chunk])
        collected.extend(got)
        for problem in problems:
            out.write("  %s\n" % problem)
    save_draft(paths, pack, name, draft.model, reported, collected, draft.prompt, attempt=MAX_ATTEMPTS)
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "redraft"), ("draft", name), ("provider", provider), ("model", draft.model),
        ("model_reported", reported), ("keys", len(entries)), ("received", len(collected)),
        ("usage", usage or None)]))
    out.write("%s: redrafted %d keys (attempt %d); run check again\n" % (name, len(collected), MAX_ATTEMPTS))
    return path


# ---------------------------------------------------------------------------
# Claude Code agents
# ---------------------------------------------------------------------------

def prompt_gate(paths, tag, area, no_style_guide=False):
    """What a translator's prompt needs (not a judge's): a valid glossary, and a style guide unless waived.

    The pack holds the guide the prompt quotes, so a guide written, changed or deleted after pack.py
    also refuses: the prompt would tell the translator about a guide it does not show, or quote one
    that is gone. A blank file is no guide.
    """
    translib.require_valid_glossary(paths, tag)
    path = paths.style(tag)
    guide = l10nlib.read_text(path) if os.path.exists(path) else None
    if guide is None or not guide.strip():
        if not no_style_guide:
            raise translib.L10nError("%s %s: write it first (l10n/style/README.md), or pass --no-style-guide to "
                                     "draft without one" % (paths.rel(path), "is empty" if guide is not None
                                                            else "does not exist"))
        guide = None
    shown = packlib.load_pack(paths, tag, area).get("style_guide")
    if guide != (shown if shown and shown.strip() else None):
        raise translib.L10nError("%s changed after the pack was built; run tools/l10n/pack.py --tag %s --area %s "
                                 "again" % (paths.rel(path), tag, area))


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
# Claude Code agents, in chunks (whole languages)
# ---------------------------------------------------------------------------

CHUNK_SET_FORMAT = "volt-l10n-chunk-set-v1"
SET_ID = re.compile(r"^[0-9a-f]{12}$")


def current_english(paths, area):
    """{key: (kind, source hash)} of an area's translatable keys in file order, from res/values/, not a pack."""
    sources, _ = translib.load_source(paths)
    if area not in sources:
        raise translib.L10nError("no res/values/strings_%s.xml" % area)
    return collections.OrderedDict((s.key, (s.kind, translib.source_sha1(s.kind, s.items)))
                                   for s in sources[area] if s.translatable)


def pack_out_of_date(paths, pack):
    """Why a pack no longer shows the current English, safety marks, language settings or glossary, or None."""
    english = dict((k, v[1]) for k, v in current_english(paths, pack["area"]).items())
    shown = dict((e["key"], e["source_sha1"]) for e in pack["entries"])
    if english != shown:
        return "the English changed: %s" % ", ".join(sorted(k for k in set(english) | set(shown)
                                                            if english.get(k) != shown.get(k))[:5])
    sources, _ = translib.load_source(paths)
    safety = translib.effective_safety([s for s in sources[pack["area"]] if s.translatable])
    marked = sorted(e["key"] for e in pack["entries"] if safety.get(e["key"]) != e["safety"])
    if marked:
        return "the safety marks changed: %s" % ", ".join(marked[:5])
    settings = packlib.language_settings(translib.load_language(paths, pack["tag"]))
    changed = sorted(f for f in settings if settings[f] != pack["language"].get(f))
    if changed:
        return "the language settings changed: %s" % ", ".join(changed)
    terms = translib.load_glossary(paths, pack["tag"])
    changed = set()
    source_of = dict((s.key, s) for s in sources[pack["area"]])
    for entry in pack["entries"]:
        text = " ".join(source_of[entry["key"]].items.values())
        now = dict((g["id"], g) for g in packlib.glossary_hits(terms, text))
        was = dict((g["id"], g) for g in entry["glossary"])
        changed.update(i for i in set(now) | set(was) if now.get(i) != was.get(i))
    return "the glossary changed: %s" % ", ".join(sorted(changed)[:5]) if changed else None


def draft_file(paths, tag, area, draft_name):
    return os.path.join(paths.drafts(tag, area), draft_name + ".xml")


def lacking_entries(pack, entries, draft):
    """The entries a draft has no text of the current English for, with the rest of their ladders."""
    lacking = set()
    for e in entries:
        have = draft.entries.get(e["key"]) if draft else None
        if have is None or checklib.stale_source(have.src, e["source_sha1"]):
            lacking.add(e["key"])
            lacking.update(e.get("ladder_group") or [])
    return [e for e in pack["entries"] if e["key"] in lacking]


def file_sha1(path):
    return hashlib.sha1(l10nlib.read_text(path).encode("utf-8")).hexdigest()


def chunk_set_id(fields):
    """The id of a prompt set: the hash of what it showed and of the draft it was written against.

    Other rules, another guide or glossary, or other findings are other prompts, whose replies are
    not these. A draft that changed since (an import, a redraft) makes a rewrite a new set too: its
    replies are already filed, and adopting them again would undo the later work. Until then the
    same command keeps the same set and the replies so far.
    """
    shown = dict((f, fields.get(f)) for f in ("format", "tag", "area", "draft", "style", "redraft", "chunks",
                                              "prompts_sha1", "draft_sha1"))
    return hashlib.sha1(translib.dump_json_line(shown).encode("utf-8")).hexdigest()[:12]


def chunk_sidecar_path(paths, tag, area, draft_name, set_id):
    return paths.work(tag, area, "prompts", "%s.chunks.%s.json" % (draft_name, set_id))


def chunk_pointer_path(paths, tag, area, draft_name):
    return paths.work(tag, area, "prompts", "%s.chunks.current" % draft_name)


def chunk_reply_path(paths, tag, area, draft_name, set_id, index, count):
    return paths.work(tag, area, "replies", "%s.%s" % (draft_name, set_id), "chunk-%d-of-%d.json" % (index, count))


def remove_chunk_prompts(directory, prefix):
    """Delete an earlier set's prompt files, whose numbering may not match the new set's."""
    pattern = re.compile(r"^%s\.chunk-\d+-of-\d+\.md$" % re.escape(prefix))
    if os.path.isdir(directory):
        for filename in os.listdir(directory):
            if pattern.match(filename):
                os.remove(os.path.join(directory, filename))


def chunked_prompts(paths, tag, area, name, style, chunk_keys, redraft=False, top_up=False):
    """Write prompts/claude-<name>.chunk-<i>-of-<n>.md, one per chunk, and the sidecar of the set.

    A chunk gets the same rules as a single prompt, but its reply carries no src: the sidecar records
    each key's source hash as the prompt showed it, and import-claude --from-chunks reads the hash
    from there. Returns (set id, [prompt paths]); (None, []) when there is nothing to draft.
    """
    pack = packlib.load_pack(paths, tag, area)
    stale = pack_out_of_date(paths, pack)
    if stale:
        raise translib.L10nError("the pack is out of date (%s); run tools/l10n/pack.py --tag %s --area %s" % (
            stale, tag, area))
    draft_name = "claude-" + name
    path = draft_file(paths, tag, area, draft_name)
    draft = translib.read_draft(path) if os.path.exists(path) else None
    if draft and draft.prompt and style and style != draft.prompt and (redraft or top_up):
        raise translib.L10nError("%s records the style %s, and a draft records one: a %s in %s would mix them. Leave "
                                 "out --style, or prompt every key without %s" % (
                                     draft_name, draft.prompt, "redraft" if redraft else "top-up", style,
                                     "--redraft" if redraft else "--top-up"))
    style = style or (draft.prompt if draft else None) or "standard"
    by_key = dict((e["key"], e) for e in pack["entries"])
    findings = {}
    if redraft:
        # checks.jsonl can be older than the draft: a key that has had its redraft gets no other.
        findings = collections.OrderedDict(
            (k, v) for k, v in failed_keys(paths, tag, area, draft_name).items()
            if not (draft and k in by_key and k in draft.entries and draft.entries[k].attempt >= MAX_ATTEMPTS
                    and not checklib.stale_source(draft.entries[k].src, by_key[k]["source_sha1"])))
        if not findings:
            raise translib.L10nError("%s has no failed first attempts to redraft" % draft_name)
        entries = [by_key[k] for k in findings if k in by_key]
    else:
        entries = needed_entries(pack)
        if top_up:
            entries = lacking_entries(pack, entries, draft)
    parts = chunks(pack, entries, chunk_keys)
    if not parts:
        return None, []
    rules = instructions(pack, style)
    bodies = [user_prompt(pack, part, dict((k, findings[k]) for k in findings if k in set(e["key"] for e in part)))
              for part in parts]
    fields = collections.OrderedDict([
        ("format", CHUNK_SET_FORMAT), ("tag", tag), ("area", area), ("draft", draft_name), ("style", style),
        ("redraft", bool(redraft)),
        ("chunks", [collections.OrderedDict((e["key"], e["source_sha1"]) for e in part) for part in parts]),
        ("prompts_sha1", hashlib.sha1(translib.dump_json_line([rules] + bodies).encode("utf-8")).hexdigest()),
        ("draft_sha1", file_sha1(path) if draft else None)])
    set_id = chunk_set_id(fields)
    fields["id"] = set_id
    translib.write_json(chunk_sidecar_path(paths, tag, area, draft_name, set_id), fields)
    l10nlib.write_text(chunk_pointer_path(paths, tag, area, draft_name), set_id + "\n")
    directory = paths.work(tag, area, "prompts")
    remove_chunk_prompts(directory, draft_name)
    command = "python3 tools/l10n/council.py import-claude --tag %s --area %s --name %s --model <your exact model id> " \
              "--from-chunks%s" % (tag, area, name, " --redraft" if redraft else "")
    written = []
    for index, body in enumerate(bodies, 1):
        reply = chunk_reply_path(paths, tag, area, draft_name, set_id, index, len(parts))
        os.makedirs(os.path.dirname(reply), exist_ok=True)
        text = "\n\n".join([
            "# Volt translation draft: %s, area %s, %s, chunk %d of %d (set %s)" % (
                tag, area, draft_name, index, len(parts), set_id),
            "Work alone: do not open other chunks, drafts, judge files or decisions under l10n/work/ or "
            "l10n/decisions/.",
            rules,
            "Your object holds only `strings` and `notes`, with no hashes: the tools recorded which English this "
            "chunk shows.",
            "When done, write the JSON object to `%s`. Once every chunk of this set has its reply, this imports them "
            "into `%s` (it refuses while a chunk is missing; --partial imports the ones that are there):\n\n    %s" % (
                paths.rel(reply), paths.rel(path), command),
            "## Strings\n\n" + body,
        ])
        prompt = os.path.join(directory, "%s.chunk-%d-of-%d.md" % (draft_name, index, len(parts)))
        l10nlib.write_text(prompt, text + "\n")
        written.append(prompt)
    return set_id, written


def load_chunk_set(paths, tag, area, draft_name, set_id=None):
    """A prompt set's sidecar, by id or the latest one, checked against its id."""
    if set_id is None:
        pointer = chunk_pointer_path(paths, tag, area, draft_name)
        if not os.path.exists(pointer):
            raise translib.L10nError("%s: no chunked prompts were written (prompt --chunk-keys)" % paths.rel(pointer))
        set_id = l10nlib.read_text(pointer).strip()
    if not SET_ID.match(set_id or ""):
        raise translib.L10nError("%r is not a prompt set id" % set_id)
    path = chunk_sidecar_path(paths, tag, area, draft_name, set_id)
    if not os.path.exists(path):
        raise translib.L10nError("%s does not exist; write the prompts again" % paths.rel(path))
    fields = json.loads(l10nlib.read_text(path))
    if (not isinstance(fields, dict) or fields.get("format") != CHUNK_SET_FORMAT or fields.get("id") != set_id
            or (fields.get("tag"), fields.get("area"), fields.get("draft")) != (tag, area, draft_name)
            or not isinstance(fields.get("chunks"), list) or chunk_set_id(fields) != set_id):
        raise translib.L10nError("%s does not match its id; write the prompts again" % paths.rel(path))
    return fields


def read_chunk_replies(paths, directory, count):
    """({1-based index: the reply's strings}, ["why a chunk has no usable reply"]) from one set's folder."""
    replies, missing = {}, []
    for index in range(1, count + 1):
        reply = os.path.join(directory, "chunk-%d-of-%d.json" % (index, count))
        where = paths.rel(reply)
        if not os.path.exists(reply):
            missing.append("chunk %d of %d: no reply at %s" % (index, count, where))
            continue
        try:
            value = json.loads(l10nlib.read_text(reply))
        except ValueError as error:
            missing.append("chunk %d of %d: %s is not JSON: %s" % (index, count, where, error))
            continue
        strings = value.get("strings") if isinstance(value, dict) else None
        if not isinstance(strings, dict):
            missing.append("chunk %d of %d: %s has no \"strings\" object" % (index, count, where))
            continue
        replies[index] = strings
    return replies, missing


def other_reply_sets(paths, tag, area, prefix, set_id):
    """Reply folders of other prompt sets, which an import does not read."""
    directory = paths.work(tag, area, "replies")
    if not os.path.isdir(directory):
        return []
    mine = "%s.%s" % (prefix, set_id)
    pattern = re.compile(r"^%s\.[0-9a-f]{12}$" % re.escape(prefix))
    return sorted(f for f in os.listdir(directory) if pattern.match(f) and f != mine and
                  os.listdir(os.path.join(directory, f)))


def import_chunks(paths, tag, area, name, model, partial=False, redraft=False, set_id=None, out=None):
    """Merge a chunk set's replies into drafts/claude-<name>.xml; returns the number of keys left out.

    Each key's src is the one the sidecar recorded when its prompt was written, whatever a reply
    says. A key whose English changed since then is reported and left out, so it can never be filed
    against the new English. Keys the set does not cover stay as they are, and a second run of the
    same set writes the same file. A key that had its redraft keeps volt:attempt="2" for as long as
    its English stands, so a top-up of its ladder does not buy it another. The count is keys left
    out plus chunks with no usable reply, so an incomplete --partial import is not a success.
    """
    out = out or sys.stdout
    if not name or not model:
        raise translib.L10nError("--from-chunks needs --name and --model")
    if not model.startswith("claude"):
        raise translib.L10nError("%r is not a Claude model id; a Claude draft is filed only under its own model" % model)
    draft_name = "claude-" + name
    fields = load_chunk_set(paths, tag, area, draft_name, set_id)
    if bool(redraft) != fields["redraft"]:
        raise translib.L10nError("prompt set %s is %sa redraft; %s --redraft" % (
            fields["id"], "" if fields["redraft"] else "not ", "pass" if fields["redraft"] else "do not pass"))
    path = draft_file(paths, tag, area, draft_name)
    previous = translib.read_draft(path) if os.path.exists(path) else None
    if previous is not None and previous.model != model:
        raise translib.L10nError(
            "%s was drafted by %s, not %s, and a draft names one model. Another --name takes another model, but two "
            "claude-* drafts that agree are enough for decide to write agreed, and merge.py then writes res/: read "
            "\"Drafting a whole language with Claude alone\" in l10n/README.md first" % (
                paths.rel(path), previous.model, model))
    if redraft and previous is None:
        raise translib.L10nError("%s does not exist; a redraft replaces keys of an existing draft" % paths.rel(path))
    count = len(fields["chunks"])
    replies, missing = read_chunk_replies(paths, os.path.dirname(chunk_reply_path(
        paths, tag, area, draft_name, fields["id"], 1, count)), count)
    if missing and not partial:
        raise translib.L10nError("%d of %d chunks have no usable reply; nothing was written (--partial imports the "
                                 "rest):\n  %s" % (len(missing), count, "\n  ".join(missing)))
    for line in missing:
        out.write("  %s\n" % line)
    for other in other_reply_sets(paths, tag, area, draft_name, fields["id"]):
        out.write("  note: replies/%s belongs to an earlier prompt set and was not read (--set)\n" % other)
    english = current_english(paths, area)
    collected, problems = {}, []
    merged = existing_draft_entries(path) if previous is not None else collections.OrderedDict()
    for index, strings in sorted(replies.items()):
        shown = fields["chunks"][index - 1]
        for key in strings:
            if key not in shown:
                problems.append("%s: not in chunk %d; ignored" % (key, index))
        for key, src in shown.items():
            if key not in strings:
                problems.append("%s: missing from the reply of chunk %d" % (key, index))
            elif key not in english:
                problems.append("%s: no longer in the English; left out" % key)
            elif english[key][1] != src:
                problems.append("%s: the English changed since the prompt (was %s, now %s); left out, redraft it" % (
                    key, src[:12], english[key][1][:12]))
            else:
                items = translib.items_of(english[key][0], strings[key])
                if items is None:
                    problems.append("%s: expected %s" % (key, "a string" if english[key][0] == "string" else
                                                         "an object of plural categories"))
                elif has_lone_surrogate(items):
                    problems.append("%s: a lone surrogate in the reply; left out" % key)
                else:
                    before = merged.get(key)
                    attempt = MAX_ATTEMPTS if redraft or (before and before[4] == src and before[3] >= MAX_ATTEMPTS) else 1
                    collected[key] = (key, english[key][0], items, attempt, src)
    for key in merged:
        if key not in english:
            problems.append("%s: no longer in the English; dropped from the draft" % key)
    # A draft records one style: this set's only if it holds the whole draft from now on.
    others = [k for k in merged if k in english and k not in collected]
    if previous is not None and previous.prompt and previous.prompt != fields["style"] and others:
        raise translib.L10nError(
            "%s records the style %s, this set is in %s, and %d keys would keep the old one (%s); nothing was written. "
            "Answer every key of the set, or prompt again with --style %s" % (
                paths.rel(path), previous.prompt, fields["style"], len(others), ", ".join(others[:5]), previous.prompt))
    merged.update(collected)
    if merged:
        reported = previous.model_reported if previous is not None else model
        prompt = previous.prompt if previous is not None and others else fields["style"]
        translib.write_draft(path, draft_name, model, [merged[k] for k in english if k in merged], reported, prompt)
    for problem in problems:
        out.write("  %s\n" % problem)
    out.write("%s (%s): %d keys from %d of %d chunks, %d keys in the draft%s; %s\n" % (
        paths.rel(path), model, len(collected), len(replies), count, sum(1 for k in merged if k in english),
        ", %d problems" % len(problems) if problems else "", "run check" if merged else "no draft written"))
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "import-chunks"), ("draft", draft_name), ("provider", "claude"), ("model", model),
        ("set", fields["id"]), ("chunks", len(replies)), ("keys", len(collected)), ("problems", len(problems))]))
    return len(problems) + len(missing)


# ---------------------------------------------------------------------------
# Safety audit of Claude-only drafts: a QA record, never the council's back-translation
#
# Nothing else reads l10n/work/<tag>/<area>/audit/ except drafts-status. backtranslation.jsonl,
# judge.jsonl, decide and beta know nothing of it, and it takes the place of none of their rules.
# ---------------------------------------------------------------------------

AUDIT_SET_FORMAT = "volt-l10n-audit-set-v1"
AUDIT_NOTE = "# Safety audit of a Claude-only draft: a QA record, not the council's back-translation."
AUDIT_COMPARE_HEADER = ("key", "english", "translation", "back-translation", "draft", "model", "source_sha1",
                        "target_sha1")
AUDIT_VERDICT_HEADER = ("key", "ok", "problem", "draft", "reader", "source_sha1", "target_sha1", "back_sha1")


def cell_text(value):
    """A cell's text: a row just built holds a plural as a dict, one read back from a file as this JSON."""
    return value if isinstance(value, str) else translib.dump_json_line(value)


def tsv_cell(value):
    """One TSV cell: a plural as compact JSON, tabs and line breaks escaped so a row stays one line."""
    return cell_text(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def tsv_uncell(text):
    return re.sub(r"\\(.)", lambda m: {"t": "\t", "n": "\n", "r": "\r"}.get(m.group(1), m.group(1)), text)


def read_audit_table(path, header):
    """{(draft, key): {column: cell}} of a compare.tsv or verdicts.tsv; empty when it does not exist."""
    if not os.path.exists(path):
        return collections.OrderedDict()
    try:
        _, rows = l10nlib.read_tsv(l10nlib.read_text(path), header)
    except ValueError as error:
        raise translib.L10nError("%s: %s" % (path, error))
    table = collections.OrderedDict()
    for _, fields in rows:
        row = dict(zip(header, (tsv_uncell(f) for f in fields)))
        table[(row["draft"], row["key"])] = row
    return table


def write_audit_table(path, header, table, order):
    """Rows in English key order, so a rerun with the same content writes the same bytes."""
    position = dict((k, i) for i, k in enumerate(order))
    rows = sorted(table.values(), key=lambda r: (position.get(r["key"], len(position)), r["key"], r["draft"]))
    lines = [AUDIT_NOTE, "\t".join(header)] + ["\t".join(tsv_cell(r[c]) for c in header) for r in rows]
    l10nlib.write_text(path, "\n".join(lines) + "\n")


def audit_row_is_current(row, entry, draft_entry):
    """Whether a compare or verdict row is of this draft text of this English: a redraft needs a new one."""
    return (row is not None and row["source_sha1"] == entry["source_sha1"]
            and row["target_sha1"] == text_sha1(entry["kind"], draft_entry.items))


def back_sha1(compare_row):
    """The hash of the back-translation a compare.tsv row shows, which a verdict is a verdict on."""
    return hashlib.sha1(cell_text(compare_row["back-translation"]).encode("utf-8")).hexdigest()


def compared_sha1(compared, draft_name):
    """The hash of one draft's compare.tsv rows."""
    rows = [compared[k] for k in sorted(compared) if k[0] == draft_name]
    return hashlib.sha1(translib.dump_json_line(rows).encode("utf-8")).hexdigest()


def audit_set_id(fields):
    """The id of an audit set: what it showed, and the draft's compare.tsv rows when it was written.

    Once a set's replies are filed, a new audit-prompt is a new reading with its own reply folder, so
    one reader's back-translation can't be filed under the next reader's --model.
    """
    shown = dict((f, fields.get(f)) for f in ("format", "tag", "area", "draft", "items", "chunks", "compared_sha1"))
    return hashlib.sha1(translib.dump_json_line(shown).encode("utf-8")).hexdigest()[:12]


def audit_paths(paths, tag, area, draft_name, set_id):
    base = paths.work(tag, area, "audit")
    return (os.path.join(base, "prompts"), os.path.join(base, "prompts", "%s.%s.json" % (draft_name, set_id)),
            os.path.join(base, "prompts", "%s.current" % draft_name), os.path.join(base, "replies"))


def audit_reader_path(paths, tag, area, draft_name, set_id):
    """Names the model a set's replies were filed under."""
    return os.path.join(audit_paths(paths, tag, area, draft_name, set_id)[0], "%s.%s.reader" % (draft_name, set_id))


def audit_screen(entry, by_key, area):
    """The only context a back-translator gets: the key's screen, its ladder's, or else the area."""
    group = entry.get("ladder_group") or []
    return entry.get("screen") or (by_key[group[0]].get("screen") if group and group[0] in by_key else None) or area


def audit_prompt(paths, tag, area, name, chunk_keys=None, top_up=False, out=None):
    """Write audit/prompts/claude-<name>.chunk-<i>-of-<n>.md: the draft's safety texts and nothing else.

    Keys are shown under opaque ids, because a key name such as ble_lock_waiting says what the
    English means. The sidecar next to the prompts maps them back; the agent never opens it.
    """
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    stale = pack_out_of_date(paths, pack)
    if stale:
        raise translib.L10nError("the pack is out of date (%s); run tools/l10n/pack.py --tag %s --area %s" % (
            stale, tag, area))
    draft_name = "claude-" + name
    path = draft_file(paths, tag, area, draft_name)
    if not os.path.exists(path):
        raise translib.L10nError("%s does not exist" % paths.rel(path))
    draft = translib.read_draft(path)
    english = current_english(paths, area)
    compared = read_audit_table(paths.work(tag, area, "audit", "compare.tsv"), AUDIT_COMPARE_HEADER)
    by_key = dict((e["key"], e) for e in pack["entries"])
    entries = []
    for entry in pack["entries"]:
        key = entry["key"]
        if not entry.get("safety"):
            continue
        have = draft.entries.get(key)
        if have is None:
            out.write("  %s: a safety key the draft lacks; not audited\n" % key)
        elif key not in english or checklib.stale_source(have.src, english[key][1]):
            out.write("  %s: the draft's text is of other English; redraft it, then audit it\n" % key)
        elif not (top_up and audit_row_is_current(compared.get((draft_name, key)), entry, have)):
            entries.append(entry)
    if not entries:
        return None, []
    parts = chunks(pack, entries, chunk_keys or len(entries))
    items, ordered, number = collections.OrderedDict(), [], 0
    for part in parts:
        ids = []
        for entry in part:
            number += 1
            item_id = "s%04d" % number
            ids.append(item_id)
            items[item_id] = collections.OrderedDict([
                ("key", entry["key"]), ("kind", entry["kind"]), ("source_sha1", english[entry["key"]][1]),
                ("screen", audit_screen(entry, by_key, area)),
                ("target", translib.text_value(entry["kind"], draft.entries[entry["key"]].items)),
                ("target_sha1", text_sha1(entry["kind"], draft.entries[entry["key"]].items))])
        ordered.append(ids)
    fields = collections.OrderedDict([("format", AUDIT_SET_FORMAT), ("tag", tag), ("area", area), ("draft", draft_name),
                                      ("items", items), ("chunks", ordered),
                                      ("compared_sha1", compared_sha1(compared, draft_name))])
    set_id = audit_set_id(fields)
    fields["id"] = set_id
    directory, sidecar, pointer, replies = audit_paths(paths, tag, area, draft_name, set_id)
    translib.write_json(sidecar, fields)
    l10nlib.write_text(pointer, set_id + "\n")
    remove_chunk_prompts(directory, draft_name)
    written = []
    for index, ids in enumerate(ordered, 1):
        reply = os.path.join(replies, "%s.%s" % (draft_name, set_id), "chunk-%d-of-%d.json" % (index, len(ordered)))
        os.makedirs(os.path.dirname(reply), exist_ok=True)
        body = [collections.OrderedDict([("id", i), ("kind", items[i]["kind"]), ("screen", items[i]["screen"]),
                                         ("text", items[i]["target"])]) for i in ids]
        text = "\n\n".join([
            "# Volt safety audit: %s, area %s, part %d of %d" % (tag, area, index, len(ordered)),
            "You are checking what these %s texts from a scooter app really say. Work alone, and open no other file "
            "under this repository: not the English source, the packs, the drafts, or anything else in this folder." %
            pack["language"]["endonym"],
            "Translate each text into English as literally as you can, even where it reads badly or seems wrong. Do "
            "not guess what the original was, and do not repair anything: if a text says the opposite of what you "
            "would expect, write what it says. Keep every negation, number and unit. Keep placeholders such as %1$s "
            "and %% exactly. `screen` only says where the text appears.",
            "Answer with one JSON object only: {\"strings\": {\"<id>\": \"<English>\" or {\"<category>\": "
            "\"<English>\", ...}}}. Include every id.",
            "Write the JSON object to `%s`. Once every part has its reply, this files them (it refuses while a part "
            "is missing; --partial files the ones that are there):\n\n    python3 tools/l10n/council.py audit-compare "
            "--tag %s --area %s --name %s --from-chunks --model <your exact model id>" % (
                paths.rel(reply), tag, area, name),
            "## Texts\n\n" + json.dumps(body, ensure_ascii=False, indent=1)])
        prompt = os.path.join(directory, "%s.chunk-%d-of-%d.md" % (draft_name, index, len(ordered)))
        l10nlib.write_text(prompt, text + "\n")
        written.append(prompt)
    return set_id, written


def load_audit_set(paths, tag, area, draft_name):
    _, _, pointer, _ = audit_paths(paths, tag, area, draft_name, "")
    if not os.path.exists(pointer):
        raise translib.L10nError("%s: run audit-prompt first" % paths.rel(pointer))
    set_id = l10nlib.read_text(pointer).strip()
    sidecar = audit_paths(paths, tag, area, draft_name, set_id)[1]
    if not os.path.exists(sidecar):
        raise translib.L10nError("%s does not exist; run audit-prompt again" % paths.rel(sidecar))
    fields = json.loads(l10nlib.read_text(sidecar))
    if (not isinstance(fields, dict) or fields.get("format") != AUDIT_SET_FORMAT or fields.get("id") != set_id
            or (fields.get("tag"), fields.get("area"), fields.get("draft")) != (tag, area, draft_name)
            or audit_set_id(fields) != set_id):
        raise translib.L10nError("%s does not match its id; run audit-prompt again" % paths.rel(sidecar))
    return fields


def audit_compare(paths, tag, area, name, model, partial=False, out=None):
    """Join each back-translation with the English into audit/compare.tsv; returns the keys left out.

    A row is written only while the English and the draft's text are still the ones the prompt
    showed, so a back-translation is never set beside a text it did not read. Rows of other keys stay.
    A set's replies are filed under one model: a new reader starts from a new audit-prompt.
    """
    out = out or sys.stdout
    draft_name = "claude-" + name
    fields = load_audit_set(paths, tag, area, draft_name)
    reader_path = audit_reader_path(paths, tag, area, draft_name, fields["id"])
    filed_under = l10nlib.read_text(reader_path).strip() if os.path.exists(reader_path) else None
    if filed_under not in (None, model):
        raise translib.L10nError("audit set %s was filed under %s, and a back-translation is filed only under the "
                                 "model that wrote it; for another reader, run audit-prompt and answer its prompts" % (
                                     fields["id"], filed_under))
    count = len(fields["chunks"])
    replies, missing = read_chunk_replies(paths, os.path.join(
        audit_paths(paths, tag, area, draft_name, fields["id"])[3], "%s.%s" % (draft_name, fields["id"])), count)
    if missing and not partial:
        raise translib.L10nError("%d of %d chunks have no usable reply; nothing was written (--partial files the "
                                 "rest):\n  %s" % (len(missing), count, "\n  ".join(missing)))
    for line in missing:
        out.write("  %s\n" % line)
    if not os.path.exists(draft_file(paths, tag, area, draft_name)):
        raise translib.L10nError("%s does not exist" % paths.rel(draft_file(paths, tag, area, draft_name)))
    draft = translib.read_draft(draft_file(paths, tag, area, draft_name))
    if model in (draft.model, draft.model_reported):
        out.write("  note: %s also drafted this text; this record shows that\n" % model)
    sources, _ = translib.load_source(paths)
    english = dict((s.key, s) for s in sources.get(area, []) if s.translatable)
    path = paths.work(tag, area, "audit", "compare.tsv")
    table = read_audit_table(path, AUDIT_COMPARE_HEADER)
    verdicts = read_audit_table(paths.work(tag, area, "audit", "verdicts.tsv"), AUDIT_VERDICT_HEADER)
    problems, stored = [], 0
    for index, strings in sorted(replies.items()):
        ids = fields["chunks"][index - 1]
        for item_id in strings:
            if item_id not in ids:
                problems.append("%s: not in part %d; ignored" % (item_id, index))
        for item_id in ids:
            item = fields["items"][item_id]
            key, source = item["key"], english.get(item["key"])
            have = draft.entries.get(key)
            back = translib.items_of(item["kind"], strings[item_id]) if item_id in strings else None
            if item_id not in strings:
                problems.append("%s (%s): missing from the reply of part %d" % (key, item_id, index))
            elif back is None:
                problems.append("%s (%s): expected %s" % (key, item_id, "a string" if item["kind"] == "string" else
                                                          "an object of plural categories"))
            elif has_lone_surrogate(back):
                problems.append("%s (%s): a lone surrogate in the reply; left out" % (key, item_id))
            elif not all(text.strip() for text in back.values()):
                problems.append("%s (%s): an empty back-translation; left out" % (key, item_id))
            elif item["kind"] != "string" and set(back) != set(item["target"]):
                problems.append("%s (%s): the reply covers %s and the text has %s; left out" % (
                    key, item_id, ", ".join(back), ", ".join(item["target"])))
            elif source is None or translib.source_sha1(source.kind, source.items) != item["source_sha1"]:
                problems.append("%s: the English changed since the prompt; left out" % key)
            elif have is None or text_sha1(item["kind"], have.items) != item["target_sha1"]:
                problems.append("%s: the draft's text changed since the prompt; left out, audit it again" % key)
            else:
                table[(draft_name, key)] = {
                    "key": key, "english": translib.text_value(source.kind, source.items), "translation": item["target"],
                    "back-translation": translib.text_value(item["kind"], back), "draft": draft_name, "model": model,
                    "source_sha1": item["source_sha1"], "target_sha1": item["target_sha1"]}
                stored += 1
    if table:
        write_audit_table(path, AUDIT_COMPARE_HEADER, table, list(english))
    if stored:
        l10nlib.write_text(reader_path, model + "\n")
    outdated = sorted(k for (d, k), v in verdicts.items() if d == draft_name and (d, k) in table
                      and v["back_sha1"] != back_sha1(table[(d, k)]))
    if outdated:
        out.write("  note: %d verdicts judged another back-translation and no longer count; the reader must judge "
                  "these again: %s\n" % (len(outdated), ", ".join(outdated[:5])))
    for problem in problems:
        out.write("  %s\n" % problem)
    out.write("%s: %d rows from %d of %d parts%s\n" % (
        paths.rel(path), stored, len(replies), count, ", %d problems" % len(problems) if problems else ""))
    if table:
        out.write("compare_sha1: %s\n" % file_sha1(path))
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "audit-compare"), ("draft", draft_name),
        ("provider", "claude" if model.startswith("claude") else "other"), ("model", model),
        ("set", fields["id"]), ("keys", stored), ("problems", len(problems))]))
    return len(problems) + len(missing)


def audit_record(paths, tag, area, name, source, reader=None, out=None):
    """Store an independent reader's verdicts in audit/verdicts.tsv; refuses a bad file whole.

    The reply is {"verdicts": [{"key", "ok", "problem"}, ...]}, optionally with "compare_sha1" (what
    audit-compare printed) and "model". Each verdict takes the English, text and back-translation
    hashes of its compare.tsv row, so drafts-status stops counting it once any of them changes.
    """
    out = out or sys.stdout
    draft_name = "claude-" + name
    value = json.loads(l10nlib.read_text(source))
    verdicts = value.get("verdicts") if isinstance(value, dict) else value
    if not isinstance(verdicts, list):
        raise translib.L10nError("%s: expected {\"verdicts\": [{\"key\", \"ok\", \"problem\"}, ...]}" % source)
    compare_path = paths.work(tag, area, "audit", "compare.tsv")
    compared = read_audit_table(compare_path, AUDIT_COMPARE_HEADER)
    copied = value.get("compare_sha1") if isinstance(value, dict) else None
    if copied is not None and (not compared or copied != file_sha1(compare_path)):
        raise translib.L10nError("compare.tsv changed since the reader read it (compare_sha1); nothing was stored")
    problems, seen = [], set()
    for verdict in verdicts:
        key = verdict.get("key") if isinstance(verdict, dict) else None
        if not isinstance(key, str):
            problems.append("%r: a verdict needs a key" % (verdict,))
            continue
        if key in seen:
            problems.append("%s: two verdicts" % key)
        seen.add(key)
        if (draft_name, key) not in compared:
            problems.append("%s: no row of %s in compare.tsv" % (key, draft_name))
        if not isinstance(verdict.get("ok"), bool):
            problems.append("%s: ok must be true or false" % key)
        elif not verdict["ok"] and not (isinstance(verdict.get("problem"), str) and verdict["problem"].strip()):
            problems.append("%s: a false verdict needs a problem" % key)
    if problems:
        raise translib.L10nError("the verdicts do not match compare.tsv; nothing was stored:\n  " + "\n  ".join(problems))
    reader = reader or (value.get("model") if isinstance(value, dict) else None) or "-"
    path = paths.work(tag, area, "audit", "verdicts.tsv")
    table = read_audit_table(path, AUDIT_VERDICT_HEADER)
    for verdict in verdicts:
        row = compared[(draft_name, verdict["key"])]
        table[(draft_name, verdict["key"])] = {
            "key": verdict["key"], "ok": "yes" if verdict["ok"] else "no", "problem": verdict.get("problem") or "",
            "draft": draft_name, "reader": reader, "source_sha1": row["source_sha1"], "target_sha1": row["target_sha1"],
            "back_sha1": back_sha1(row)}
    write_audit_table(path, AUDIT_VERDICT_HEADER, table, list(current_english(paths, area)))
    flagged = sum(1 for v in verdicts if not v["ok"])
    out.write("%s: %d verdicts, %d flagged\n" % (paths.rel(path), len(verdicts), flagged))
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "audit-record"), ("draft", draft_name), ("reader", reader), ("keys", len(verdicts)),
        ("flagged", flagged)]))
    return flagged


# ---------------------------------------------------------------------------
# Draft status (reads; never writes)
# ---------------------------------------------------------------------------

STATUS_COUNTS = ("total", "drafted", "stale", "missing", "failing", "safety", "audited", "flagged")


def area_status(paths, tag, area, sources, names, font, measurer, approx):
    """One area's counts for the claude-* drafts (or the named ones). Writes nothing.

    A key is drafted when a draft has a text of the current English, stale when it has only texts of
    other English, missing when it has none and no current translation, and failing when a current
    text fails check.py (recomputed, so checks.jsonl need not be up to date). audited and flagged
    count safety keys with a verdict on the draft's current text and its current back-translation.
    """
    directory = paths.drafts(tag, area)
    wanted = ["claude-%s.xml" % n for n in names] if names else [
        f for f in sorted(os.listdir(directory)) if f.startswith("claude-") and f.endswith(".xml")
    ] if os.path.isdir(directory) else []
    drafts = [translib.read_draft(os.path.join(directory, f)) for f in wanted
              if os.path.exists(os.path.join(directory, f))]
    pack = packlib.build_pack(paths, tag, area, measurer if drafts else approx, sources)
    failing, verdicts = set(), read_audit_table(paths.work(tag, area, "audit", "verdicts.tsv"), AUDIT_VERDICT_HEADER)
    compared = read_audit_table(paths.work(tag, area, "audit", "compare.tsv"), AUDIT_COMPARE_HEADER)
    for draft in drafts:
        results = checklib.check_targets(pack, checklib.draft_targets(draft, pack), font, measurer)
        failing.update(k for k, findings in results.items() if not checklib.is_ok(findings))
    row = collections.OrderedDict((c, 0) for c in STATUS_COUNTS)
    for entry in pack["entries"]:
        key = entry["key"]
        row["total"] += 1
        row["safety"] += 1 if entry.get("safety") else 0
        have = [d for d in drafts if key in d.entries]
        current = [d for d in have if not checklib.stale_source(d.entries[key].src, entry["source_sha1"])]
        if current:
            row["drafted"] += 1
            row["failing"] += 1 if key in failing else 0
        elif have:
            row["stale"] += 1
        elif entry["status"] != "current":
            row["missing"] += 1
        if entry.get("safety"):
            judged = [verdicts[(d.name, key)] for d in current
                      if audit_row_is_current(verdicts.get((d.name, key)), entry, d.entries[key])
                      and (d.name, key) in compared
                      and verdicts[(d.name, key)]["back_sha1"] == back_sha1(compared[(d.name, key)])]
            row["audited"] += 1 if judged else 0
            row["flagged"] += 1 if any(v["ok"] != "yes" for v in judged) else 0
    return row


def usage_summary(paths, tag, area=None, since=None):
    """{"<provider> <model>": {"runs", "unrecorded", <summed token counts>}} from the runs.jsonl files of a
    language (every area, the glossary round included), or of one area; since is an ISO time (UTC)."""
    base = os.path.dirname(paths.work(tag, "x"))
    areas = [area] if area else sorted(a for a in os.listdir(base) if os.path.isdir(os.path.join(base, a))) \
        if os.path.isdir(base) else []
    totals = {}
    for name in areas:
        for row in translib.read_jsonl(paths.work(tag, name, "runs.jsonl")):
            if row.get("provider") not in providers.KEY_ENV or (since and row.get("when", "") < since):
                continue
            total = totals.setdefault("%s %s" % (row["provider"], row.get("model")),
                                      collections.OrderedDict([("runs", 0), ("unrecorded", 0)]))
            total["runs"] += 1
            if isinstance(row.get("usage"), dict):
                providers.add_usage(total, row["usage"])
            else:
                total["unrecorded"] += 1
    return collections.OrderedDict(sorted(totals.items()))


def print_usage(totals, out=None):
    out = out or sys.stdout
    if not totals:
        out.write("no API runs\n")
    for name, total in totals.items():
        out.write("%s: %d runs%s\n" % (name, total["runs"], " (%d logged no usage)" % total["unrecorded"]
                                      if total["unrecorded"] else ""))
        for field, value in total.items():
            if field not in ("runs", "unrecorded"):
                out.write("  %s %d\n" % (field, value))


def drafts_status(paths, tag, names=None, use_java=True):
    translib.load_language(paths, tag)
    sources, _ = translib.load_source(paths)
    font = fontmetrics.Font(paths.font())
    measurer = fontmetrics.Measurer(paths.font(), use_java=use_java)
    approx = fontmetrics.Measurer(paths.font(), use_java=False)
    areas, errors = collections.OrderedDict(), collections.OrderedDict()
    for area in sources:  # one unreadable area hides no other's progress
        try:
            areas[area] = area_status(paths, tag, area, sources, names, font, measurer, approx)
        except (translib.L10nError, gen_catalog.CatalogError) as error:
            areas[area] = collections.OrderedDict((c, 0) for c in STATUS_COUNTS)
            errors[area] = providers.redact(error)
    totals = collections.OrderedDict((c, sum(r[c] for r in areas.values())) for c in STATUS_COUNTS)
    return collections.OrderedDict([("tag", tag), ("names", list(names or [])), ("areas", areas), ("totals", totals),
                                    ("errors", errors), ("glossary_problems", translib.glossary_problems(paths, tag))])


def print_drafts_status(status, out=None):
    out = out or sys.stdout
    width = max([len(a) for a in status["areas"]] + [len("total")])
    head = " ".join("%7s" % c for c in STATUS_COUNTS)
    out.write("%-*s %s\n" % (width, "area", head))
    for area, row in list(status["areas"].items()) + [("total", status["totals"])]:
        counts = "unreadable, left out of the total" if area in status["errors"] else \
            " ".join("%7d" % row[c] for c in STATUS_COUNTS)
        out.write("%-*s %s\n" % (width, area, counts))
    for area, error in status["errors"].items():
        out.write("%s: %s\n" % (area, error))
    if status["glossary_problems"]:
        out.write("glossary: %d problems (glossary-check --tag %s)\n" % (len(status["glossary_problems"]), status["tag"]))


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


def independent_backtranslator(model, model_reported, drafters):
    """True when neither the model asked nor the model the provider reported drafted in this area
    (drafters: all_drafter_models()). A back-translation counts only while this holds."""
    return model not in drafters and model_reported not in drafters


# ---------------------------------------------------------------------------
# Back-translation
# ---------------------------------------------------------------------------

def backtranslation_targets(states, judged=None, backs=None, drafters=frozenset()):
    """The safety keys to back-translate, as KeyStates whose agreed field is the text to back-translate.

    Every unanimous safety key, with its agreed text (as before 2026-11-05). Then, for a safety key
    whose drafts disagree, the text the judge picked or merged (judged: judged_safety_texts()), with
    the state's agreed field replaced by that text; when backs (load_backtranslations()) is given, a
    judged text that already has a current back-translation of exactly that text is left out, but only
    while that back-translation's model is still independent of drafters (all_drafter_models()): one
    whose model drafted in the area since is queued again, for a model that drafted nothing to replace.
    """
    out = collections.OrderedDict()
    for key, state in states.items():
        if not state.entry.get("safety"):
            continue
        if state.unanimous:
            out[key] = state
            continue
        items = (judged or {}).get(key, (None, None))[0]
        if items is None:
            continue
        target = state._replace(agreed=items)
        back = current_backtranslation(target, backs.get(key)) if backs is not None else None
        if back is not None and independent_backtranslator(back.get("model"), back.get("model_reported"),
                                                           drafters):
            continue
        out[key] = target
    return out


def judgeable_safety(state):
    """A safety key whose drafts disagree (or one failed), with drafts from every provider and at least
    one passing: the judge may pick or merge its text (owner decision of 2026-11-05)."""
    return bool(state.entry.get("safety") and state.candidates and not state.unanimous and state.passing
                and set(state.providers) >= set(translib.PROVIDERS))


def load_selections(paths, pack):
    """{key: the latest pick or merge ruling} for rulings of the current English (later lines win)."""
    current = dict((e["key"], e["source_sha1"]) for e in pack["entries"])
    selections = {}
    for row in translib.read_jsonl(paths.work(pack["tag"], pack["area"], "judge.jsonl")):
        key = row.get("key")
        if (key in current and row.get("source_sha1") == current[key] and row.get("verdict") in ("pick", "merge")
                and row.get("model") and row.get("reason")):
            selections[key] = row
    return selections


def judged_safety_texts(paths, pack, states, use_java=True):
    """{key: (items or None, why, selection, findings)} for each judgeable_safety() key.

    items is the text of the judge's latest pick or merge for that key: a pick counts only while the
    named draft passes and still has the copied text, and a merge only while it passes the checks.
    A later fallback ruling, or none, gives no text. selection is the pick or merge ruling (or None),
    and findings the checks' findings on the text.
    The text still needs a back-translation by a model that drafted nothing and the judge's confirm.
    """
    rulings, _ = load_judge(paths, pack)
    selections = load_selections(paths, pack)
    font = measurer = None
    out = collections.OrderedDict()
    for key, state in states.items():
        if not judgeable_safety(state):
            continue
        entry, latest, selection = state.entry, rulings.get(key), selections.get(key)
        if latest is not None and latest["verdict"] == "fallback":
            out[key] = (None, "the judge ruled fallback: %s" % latest.get("reason"), None, [])
        elif selection is None:
            out[key] = (None, None, None, [])
        elif selection["verdict"] == "pick":
            chosen = [c for c in state.passing if c.draft == selection.get("pick")]
            if not chosen:
                out[key] = (None, "the judge picked %r, which is not a passing draft" % selection.get("pick"),
                            selection, [])
            elif not same_text(entry["kind"], selection.get("text"), chosen[0].items):
                out[key] = (None, "the judge picked %s with another text (or none)" % chosen[0].draft, selection, [])
            else:
                out[key] = (chosen[0].items, None, selection, chosen[0].findings)
        else:
            items = translib.items_of(entry["kind"], selection.get("text"))
            if items is None:
                out[key] = (None, "the judge's merged text has the wrong shape", selection, [])
                continue
            if font is None:
                font = fontmetrics.Font(paths.font())
                measurer = fontmetrics.Measurer(paths.font(), use_java=use_java)
            findings = checklib.check_targets(pack, {key: (entry["kind"], items, [])}, font, measurer,
                                              ladders=False)[key]
            if checklib.is_ok(findings):
                out[key] = (items, None, selection, findings)
            else:
                out[key] = (None, "the judge's merged text failed the checks", selection, findings)
    return out


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
    if not independent_backtranslator(model, model_reported, drafters):
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
    drafters = all_drafter_models(drafts)
    targets = backtranslation_targets(states, judged_safety_texts(paths, pack, states, use_java),
                                      load_backtranslations(paths, pack), drafters)
    if not targets:
        out.write("no safety keys to back-translate\n")
        return 0
    model = pick_model(provider, "backtranslate", model, prefer, avoid=drafters)
    stored, reported, usage = 0, model, {}
    keys = list(targets)
    for start in range(0, len(keys), CHUNK):
        part = collections.OrderedDict((k, targets[k]) for k in keys[start:start + CHUNK])
        with providers.recorded_responses() as responses:
            text, reported = providers.generate(provider, model, backtranslate_instructions(pack),
                                                backtranslate_prompt(pack, part))
        providers.add_usage(usage, providers.usage_of(responses[-1]) if responses else None)
        stored += store_backtranslation(paths, pack, part, reply_strings(providers.parse_json_reply(text)),
                                        provider, model, reported, drafters)
    log_run(paths, tag, area, collections.OrderedDict([
        ("stage", "backtranslate"), ("provider", provider), ("model", model), ("model_reported", reported),
        ("keys", len(keys)), ("received", stored), ("usage", usage or None)]))
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
    the request's English and text are still the current English and agreed (or judged) text;
    otherwise it is dropped, never stored as a back-translation of the new text.
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
    current = backtranslation_targets(states, judged_safety_texts(paths, pack, states, use_java))
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
    states = analyze(paths, pack, drafts, use_java)
    targets = backtranslation_targets(states, judged_safety_texts(paths, pack, states, use_java),
                                      load_backtranslations(paths, pack), all_drafter_models(drafts))
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
    """back if it is of the current English and of state.agreed, else None.

    state.agreed is the text every drafter agreed on or, for a state from backtranslation_targets()
    for a disagreeing safety key, the judge's picked or merged text."""
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

    A unanimous safety key is listed (safety-confirmation) only once a current back-translation of
    the agreed text exists, and the request carries both texts: the judge copies them into its
    ruling, which then confirms exactly that text and that back-translation (decide() checks both).
    A safety key whose drafts disagree (owner decision of 2026-11-05) is listed first as
    safety-disagreement, for a pick or merge; once a model that drafted nothing has back-translated
    that judged text, it is listed as safety-judged-confirmation, carrying the judged text and its
    back-translation for a confirm in the same way. Between the two it is not listed.
    """
    pack = packlib.load_pack(paths, tag, area)
    states = analyze(paths, pack, use_java=use_java)
    backs = load_backtranslations(paths, pack)
    judged = judged_safety_texts(paths, pack, states, use_java)
    rows = []
    for key, state in states.items():
        entry = state.entry
        safety = entry.get("safety") or []
        if not state.candidates:
            continue
        back, text, confirming = None, None, bool(safety)
        if safety and state.unanimous:
            back = current_backtranslation(state, backs.get(key))
            if back is None:
                continue  # back-translate first: the judge confirms the text and its back-translation
            reason, text = "safety-confirmation", state.agreed
        elif safety:
            if key not in judged:
                continue  # a provider's draft is missing or none passes: English stays
            items = judged[key][0]
            if items is None:
                reason, confirming = "safety-disagreement", False
            else:
                back = current_backtranslation(state._replace(agreed=items), backs.get(key))
                if back is None:
                    continue  # back-translate the judged text first, then the judge confirms it
                reason, text = "safety-judged-confirmation", items
        elif state.agreed is not None or not state.passing:
            continue
        else:
            reason = "disagreement" if len(state.passing) > 1 else "single-draft"
        rows.append(collections.OrderedDict([
            ("key", key), ("source_sha1", entry["source_sha1"]), ("reason", reason), ("kind", entry["kind"]),
            ("english", entry["english"]), ("safety", safety), ("context", model_view(entry)),
            ("text", translib.text_value(entry["kind"], text) if text is not None else None),
            ("candidates", [collections.OrderedDict([
                ("draft", c.draft), ("provider", c.provider), ("model", c.model),
                ("text", translib.text_value(entry["kind"], c.items)), ("ok", c.ok),
                ("findings", [f for f in c.findings if f["severity"] != checklib.INFO])]) for c in state.candidates]),
            ("backtranslation", back),
            ("verdicts", ["confirm", "fallback"] if confirming else ["pick", "merge", "fallback"]),
            ("confirm", list(SAFETY_CONFIRMATIONS) if confirming else [])]))
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
        "- safety-disagreement: a safety key whose drafters disagree. Pick the passing draft that keeps the "
        "English meaning exactly, or merge a better text from the drafts, as for a disagreement; or answer "
        "fallback. Your text is not used yet: a model that drafted nothing back-translates it, and the key comes "
        "back to you as safety-judged-confirmation.",
        "- safety-judged-confirmation: the request's text is the text you picked or merged, and backtranslation "
        "is its back-translation. Confirm or fall back exactly as for safety-confirmation, copying the request's "
        "text and backtranslation.back. A confirm of any other text, or of a text without that back-translation, "
        "counts for nothing, and a new pick or merge needs a new back-translation and a new confirm.",
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
    confirmed item by item, naming that text and that back-translation), safety-judged (a volt:safety
    key whose drafts, from all three providers, disagree, where the judge picked a passing draft or
    merged a text that passes the checks, a model that drafted nothing back-translated exactly that
    text, and the judge then confirmed that text and that back-translation item by item: the owner's
    decision of 2026-11-05), or fallback-english (the key is left out of values-<qual>). A ladder
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
    judged = judged_safety_texts(paths, pack, states, use_java)
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
                       "no draft passed the checks" if not state.passing else
                       "a draft failed the checks" if len(state.passing) < len(state.candidates) else
                       "the drafters disagree")
                if key not in judged:
                    rows[key] = row(entry, state, "fallback-english", "safety: %s" % why)
                else:
                    rows[key] = decide_judged_safety(entry, state, why, judged[key], ruling, backs.get(key),
                                                     drafters, pending, row)
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
            if not independent_backtranslator(back.get("model"), back.get("model_reported"), drafters):
                pending.append((key, "a back-translation by a model that drafted nothing"))
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


def decide_judged_safety(entry, state, why, judged, ruling, back, drafters, pending, row):
    """The decision row for a safety key whose drafts disagree (owner decision of 2026-11-05).

    It is safety-judged only when the judge picked or merged a text (judged: see
    judged_safety_texts()), a model that drafted nothing back-translated exactly that text, and the
    judge's latest ruling confirms that text and that back-translation item by item. Anything else
    keeps English; a missing step is pending.
    """
    key, kind = entry["key"], entry["kind"]
    items, problem, selection, findings = judged
    if items is None:
        if problem is None:
            pending.append((key, "the judge's pick or merge of the safety text"))
            return row(entry, state, "fallback-english", "safety: %s; not judged" % why)
        if selection is not None:  # a pick of a changed draft, or a merge that fails: a new ruling may fix it
            pending.append((key, "a new pick or merge of the safety text"))
        return row(entry, state, "fallback-english", "safety: %s; %s" % (why, problem),
                   judge=selection or ruling, checks=[f for f in findings if f["severity"] != checklib.INFO])
    target = state._replace(agreed=items)
    if back and back.get("source_sha1") != entry["source_sha1"]:
        back = None
    if current_backtranslation(target, back) is None:
        pending.append((key, "a back-translation of the judged safety text"))
        return row(entry, state, "fallback-english", "safety: %s; the judged text has no back-translation" % why,
                   judge=selection)
    if not independent_backtranslator(back.get("model"), back.get("model_reported"), drafters):
        pending.append((key, "a back-translation of the judged safety text by a model that drafted nothing"))
        return row(entry, state, "fallback-english",
                   "safety: the back-translator %s also drafted" % back.get("model"), judge=selection, back=back)
    if ruling is None or ruling["verdict"] != "confirm":
        pending.append((key, "the judge's confirmation of the judged safety text and its back-translation"))
        return row(entry, state, "fallback-english", "safety: the judged text is not confirmed by the judge",
                   judge=ruling or selection, back=back)
    confirm = ruling.get("confirm") or {}
    failed = [c for c in SAFETY_CONFIRMATIONS if confirm.get(c) is not True]
    if ruling.get("backtranslation_meaning_kept") is not True:
        failed.append("backtranslation_meaning_kept")
    if failed:
        return row(entry, state, "fallback-english", "safety: the judged text is not confirmed: %s" % (
            ", ".join(failed)), judge=ruling, back=back)
    if not (same_text(kind, ruling.get("text"), items) and
            same_text(kind, ruling.get("back"), translib.items_of(kind, back.get("back")))):
        pending.append((key, "the judge's confirmation of the current judged text and back-translation"))
        return row(entry, state, "fallback-english", "safety: the judge confirmed another text or "
                   "back-translation", judge=ruling, back=back)
    checks = [f for f in findings if f["severity"] != checklib.INFO]
    return row(entry, state, "safety-judged", "%s; the judge %s a text (%s); back-translation kept the meaning; "
               "judge confirmed: %s" % (why, "picked" if selection["verdict"] == "pick" else "merged",
                                         selection.get("reason"), ruling.get("reason")),
               items, ruling, back, checks)


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
    """(passes, lines). Fallback counts every translatable key merge.py would leave to English.

    A lock or alarm key must also be safety-agreed, the only translated outcome decide() gives one: all
    three providers, a back-translation and the judge's confirmation. Claude's drafts alone never reach it.
    """
    lang = translib.load_language(paths, tag)
    categories = translib.plural_categories(lang)
    sources, _ = translib.load_source(paths)
    total = fallback = 0
    blocking, unconfirmed, refused, lines = [], [], [], []
    for area, rows in sources.items():
        decided = dict((r.get("key"), r) for r in translib.read_jsonl(paths.decisions(tag, area)))
        area_total = area_fallback = 0
        safety = translib.effective_safety(rows)
        for s in rows:
            if not s.translatable:
                continue
            area_total += 1
            d = decided.get(s.key)
            why = translib.why_english(s, d, categories)
            hit = [x for x in safety[s.key] if x in translib.SAFETY_BLOCKING]
            if not why:
                if hit and d.get("outcome") not in SAFETY_TRANSLATED:
                    unconfirmed.append("%s (%s)" % (s.key, ", ".join(hit)))
                continue
            area_fallback += 1
            if d and d.get("outcome") in TRANSLATED and d.get("source_sha1") == translib.source_sha1(s.kind, s.items):
                refused.append("%s (%s)" % (s.key, why))
            if hit:
                blocking.append("%s (%s)" % (s.key, ", ".join(hit)))
        total += area_total
        fallback += area_fallback
        lines.append("  %-16s %4d keys, %4d English fallback" % (area, area_total, area_fallback))
    ratio = float(fallback) / total if total else 0.0
    passes = total > 0 and ratio <= BETA_MAX_FALLBACK and not blocking and not unconfirmed
    lines.insert(0, "%s (%s): %d of %d keys fall back to English (%.2f%%; the bar is %.0f%%)" % (
        tag, lang.folder, fallback, total, 100 * ratio, 100 * BETA_MAX_FALLBACK))
    if blocking:
        lines.append("lock or alarm keys falling back (each blocks the beta): %s" % ", ".join(blocking))
    if unconfirmed:
        lines.append("lock or alarm keys translated but not safety-agreed or safety-judged (each blocks the beta): %s" %
                     ", ".join(unconfirmed))
    if refused:
        lines.append("translated decisions merge.py leaves to English: %s" % ", ".join(refused))
    lines.append("beta bar: %s" % ("PASS" if passes else "FAIL"))
    return passes, lines


# ---------------------------------------------------------------------------
# Glossary validation
# ---------------------------------------------------------------------------

def glossary_check(paths, tag, out=None):
    """Print a glossary's problems; 0 only for a glossary that exists and has none."""
    out = out or sys.stdout
    if tag != "en" and not os.path.exists(os.path.join(paths.glossary_dir(), tag + ".tsv")):
        out.write("no l10n/glossary/%s.tsv\n" % tag)
        return 1
    problems = translib.glossary_problems(paths, tag)
    for problem in problems:
        out.write("%s\n" % problem)
    if tag == "en" and not problems:
        orphans = translib.glossary_orphans(paths)
        out.write("information: %d rows match no English string\n" % len(orphans))
        for term in orphans:
            out.write("  %s (%s)\n" % (term.id, term.term))
    unjudged = translib.claude_only_glossary_rows(paths, tag) if tag != "en" and not problems else []
    if unjudged:
        out.write("information: %d rows are claude-only: no council has judged them, and draft openai|google refuses "
                  "until one has\n" % len(unjudged))
    out.write("%s glossary: %s\n" % (tag, "%d problems" % len(problems) if problems else "valid"))
    return 1 if problems else 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("%s is not a positive number" % value)
    return number


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
    p.add_argument("--style", choices=sorted(STYLES), help="default standard (a chunked redraft or top-up: the draft's)")
    p.add_argument("--redraft", action="store_true", help="only the keys whose first attempt failed")
    p.add_argument("--chunk-keys", type=positive_int, help="one prompt per chunk of about this many keys")
    p.add_argument("--top-up", action="store_true", help="with --chunk-keys: only keys the draft lacks or has stale")
    p.add_argument("--no-style-guide", action="store_true", help="allow a prompt without l10n/style/<tag>.md")
    p = sub.add_parser("import-claude", help="validate drafts/claude-*.xml, or convert a JSON reply into one")
    area_args(p)
    p.add_argument("--from", dest="source")
    p.add_argument("--name")
    p.add_argument("--model")
    p.add_argument("--redraft", action="store_true")
    p.add_argument("--from-chunks", action="store_true", help="read the replies of the prompts from --chunk-keys")
    p.add_argument("--partial", action="store_true", help="with --from-chunks: import although chunks are missing")
    p.add_argument("--set", dest="set_id", help="with --from-chunks: an earlier prompt set's id (default: the latest)")
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
    p = sub.add_parser("audit-prompt", help="a blind back-translation prompt of a draft's safety keys (QA record)")
    area_args(p)
    p.add_argument("--name", required=True)
    p.add_argument("--chunk-keys", type=positive_int)
    p.add_argument("--top-up", action="store_true", help="only keys without a compare.tsv row of the current text")
    p = sub.add_parser("audit-compare", help="join the audit's back-translations with the English: audit/compare.tsv")
    area_args(p)
    p.add_argument("--name", required=True)
    p.add_argument("--from-chunks", action="store_true", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--partial", action="store_true")
    p = sub.add_parser("audit-record", help="store an independent reader's verdicts: audit/verdicts.tsv")
    area_args(p)
    p.add_argument("--name", required=True)
    p.add_argument("--from", dest="source", required=True)
    p.add_argument("--model", help="the reader's model id")
    p = sub.add_parser("drafts-status", help="per area, how far the Claude drafts have come (never writes)")
    p.add_argument("--tag", required=True)
    p.add_argument("--name", action="append", help="only drafts/claude-<name>.xml (default: every claude-* draft)")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("usage", help="the token counts the providers reported, summed per model (never writes)")
    p.add_argument("--tag", required=True)
    p.add_argument("--area", help="one area (default: every area, the glossary round included)")
    p.add_argument("--since", help="only runs at or after this UTC time, as runs.jsonl writes it")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("glossary-check", help="validate l10n/glossary/<tag>.tsv against en.tsv, or en.tsv itself")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--tag")
    group.add_argument("--english", action="store_true", help="en.tsv, plus the rows no English string matches")
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    use_java = not args.no_java
    try:
        if getattr(args, "tag", None) is not None:
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
            if args.chunk_keys and (args.role != "draft" or (args.redraft and args.top_up)):
                raise translib.L10nError("--chunk-keys is for a draft or a redraft, and --top-up is not a redraft")
            if args.role == "draft":  # like judge-requests, a judge's prompt shows what the pack holds
                prompt_gate(paths, args.tag, args.area, args.no_style_guide)
                if not args.name or not translib.DRAFT_NAME.match(args.name):
                    raise translib.L10nError("--name is required: lower-case letters, digits and dashes")
                if args.chunk_keys:
                    set_id, written = chunked_prompts(paths, args.tag, args.area, args.name, args.style,
                                                      args.chunk_keys, args.redraft, args.top_up)
                    if not written:
                        print("%s/%s: nothing to draft" % (args.tag, args.area))
                        return 0
                    print("prompt set %s: %d chunks" % (set_id, len(written)))
                    print("\n".join(paths.rel(p) for p in written))
                    return 0
                if args.top_up:
                    raise translib.L10nError("--top-up needs --chunk-keys")
                path = claude_prompt(paths, args.tag, args.area, args.name, args.style or "standard", args.redraft)
            elif args.role == "backtranslate":
                path, count = backtranslation_prompt_file(paths, args.tag, args.area, use_java)
                print("%d keys to back-translate" % count)
            else:
                path, rows = write_judge_requests(paths, args.tag, args.area, use_java)
                print("%d judge requests" % len(rows))
            print(paths.rel(path))
            return 0
        if args.command == "import-claude" and args.from_chunks:
            if args.source:
                raise translib.L10nError("--from and --from-chunks are alternatives")
            return 1 if import_chunks(paths, args.tag, args.area, args.name, args.model, args.partial, args.redraft,
                                      args.set_id) else 0
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
        if args.command == "audit-prompt":
            set_id, written = audit_prompt(paths, args.tag, args.area, args.name, args.chunk_keys, args.top_up)
            if written:
                print("audit set %s: %d parts" % (set_id, len(written)))
                print("\n".join(paths.rel(p) for p in written))
            else:
                print("%s/%s: no safety keys to audit" % (args.tag, args.area))
            return 0
        if args.command == "audit-compare":
            return 1 if audit_compare(paths, args.tag, args.area, args.name, args.model, args.partial) else 0
        if args.command == "audit-record":
            audit_record(paths, args.tag, args.area, args.name, args.source, args.model)
            return 0
        if args.command == "drafts-status":
            status = drafts_status(paths, args.tag, args.name, use_java)
            if args.json:
                print(json.dumps(status, ensure_ascii=False, indent=1))
            else:
                print_drafts_status(status)
            return 1 if status["errors"] else 0
        if args.command == "usage":
            totals = usage_summary(paths, args.tag, args.area, args.since)
            if args.json:
                print(json.dumps(totals, indent=1))
            else:
                print_usage(totals)
            return 0
        if args.command == "glossary-check":
            return glossary_check(paths, "en" if args.english else args.tag)
    except providers.ProviderError as error:
        print("council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    except (translib.L10nError, gen_catalog.CatalogError, fontmetrics.FontError, ValueError, OSError) as error:
        print("council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
