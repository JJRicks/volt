#!/usr/bin/env python3
"""The glossary round: the council judges a language's Claude-only glossary before any string is drafted.

l10n/README.md ("At the debate the glossary comes first") says that l10n/glossary/<tag>.tsv, while
it is Claude's proposal, must be judged by the three providers before OpenAI and Google draft any
strings; council.py's `draft openai|google` refuses while a row's source says `claude-only`. This is
that round, for one language at a time:

  glossary_council.py relabel --tag es [--model claude-opus-5-5]
        label every unlabelled row of a Claude-only proposal `claude-only <model>`, so the draft guard
        sees it (idempotent; never touches a row a council judged)
  glossary_council.py draft openai|google --tag es [--model ID]
        the provider's own term for each English row, drafted without seeing Claude's
  glossary_council.py judge-requests --tag es
        the rows to judge (any disagreement, and every safety row) and the judge's prompt chunks
  glossary_council.py decide --tag es
        apply agreement and the judge's rulings to l10n/glossary/<tag>.tsv; unruled rows stay claude-only

The round covers every English row that is not do-not-translate and whose row in <tag>.tsv is still
claude-only, or missing. A row whose source starts with `council ` was judged and is left alone.

Files, under l10n/work/<tag>/glossary/ (committed, staging only):
  drafts/openai.json, drafts/google.json
        {"format", "tag", "provider", "model", "model_reported", "usage", "rows": {id: {"en_sha1",
        "term"}}, "notes": {id: note}, "problems": [...]}. en_sha1 hashes the English row the term was
        drafted from (id, term, definition, pos, dnt, safety); a row of other English is stale, and
        judge-requests and decide refuse until it is drafted again. A term the provider got wrong
        (an empty form, ";" or "(", a placeholder, U+202F) is left out and named in problems.
  runs.jsonl
        one line per provider call (stage draft: provider, model, model_reported, chunk, rows,
        received, usage with the provider's token counts), per judge-requests and per decide.
  judge-requests.json
        {"format", "tag", "set", "chunks", "chunk_rows", "rows": [...]}: every row of the round with
        its candidates (claude, openai, google, each with its model) and, for the rows to judge, the
        options the judge sees (A, B, C: distinct candidates in a shuffled order, without names).
        set hashes the requests and the prompt text; a later run with other inputs makes a new set.
  prompts/judge.chunk-<i>-of-<n>.md
        the judge's prompt, about CHUNK_ROWS rows each, for one short-lived Claude Code judge agent
        per language (it answers the chunks in order, or one fresh agent per chunk).
  replies/judge.<set>/chunk-<i>-of-<n>.json
        the judge's reply: {"set", "model", "rulings": {id: {"choice": "A" | "B" | "C" | "new",
        "term", "reason"}}}. A pick copies its option's term; "new" is the judge's own term.
  decisions.jsonl
        one line per row of the round as decide left it: agreed, judged or unruled, with the
        candidates and the ruling.

Keys come only from OPENAI_API_KEY and GEMINI_API_KEY (providers.py); nothing here prints one.
"""

import argparse
import collections
import contextlib
import hashlib
import json
import os
import random
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import l10nlib  # noqa: E402
import providers  # noqa: E402
import translib  # noqa: E402

AREA = "glossary"  # the work folder: l10n/work/<tag>/glossary/
VENDORS = ("openai", "google")
DEFAULT_MODELS = {"openai": "gpt-6.1-terra", "google": "gemini-3.8-flash"}  # the owner's choice
DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
CHUNK_ROWS = 80
DRAFT_FORMAT = "volt-l10n-glossary-draft-v1"
REQUESTS_FORMAT = "volt-l10n-glossary-requests-v1"
COUNCIL_SOURCE = "council "
PROPOSAL_HEADER = re.compile(r"claude-only proposal", re.I)
ROUND_NOTE = "# Glossary council round (tools/l10n/glossary_council.py decide):"
# Style-guide sections that restate the Claude-proposed glossary terms. They are withheld from the
# other drafters (independence) and from the judge (so Claude's candidate cannot be recognised).
WITHHELD_SECTIONS = ("safety wording", "scooter vocabulary")
OPTION_LETTERS = "ABC"


def die(message):
    raise translib.L10nError(message)


# ---------------------------------------------------------------------------
# Reading the glossaries
# ---------------------------------------------------------------------------

def en_sha1(fields):
    """The hash of an English row as a drafter saw it: id, term, definition, pos, dnt, safety."""
    return hashlib.sha1("\t".join(fields[:6]).encode("utf-8")).hexdigest()


def english_rows(paths):
    """[fields] of en.tsv rows that need a term (not do-not-translate), in file order."""
    problems = translib.english_glossary_problems(paths)
    if problems:
        die("l10n/glossary/en.tsv is invalid (council.py glossary-check --english):\n  " + "\n  ".join(problems))
    rows, _ = translib._glossary_rows(os.path.join(paths.glossary_dir(), "en.tsv"), "en.tsv")
    return [fields for _, fields in rows if fields[4] != "yes"]


def glossary_path(paths, tag):
    return os.path.join(paths.glossary_dir(), tag + ".tsv")


def read_language_glossary(paths, tag):
    """(lines, {id: (line index, fields)}, is a Claude-only proposal). Refuses a file glossary-check refuses."""
    path = glossary_path(paths, tag)
    if not os.path.exists(path):
        die("l10n/glossary/%s.tsv does not exist: the round judges Claude's proposal, so write it first "
            "(l10n/README.md, The glossary before the keys)" % tag)
    problems = translib.glossary_problems(paths, tag)
    if problems:
        die("l10n/glossary/%s.tsv is invalid (council.py glossary-check --tag %s):\n  %s" % (
            tag, tag, "\n  ".join(problems)))
    lines = l10nlib.read_text(path).split("\n")
    rows, header_seen, proposal = collections.OrderedDict(), False, False
    for index, line in enumerate(lines):
        bare = line[:-1] if line.endswith("\r") else line
        if bare.startswith("#"):
            if PROPOSAL_HEADER.search(bare) and not bare.startswith(ROUND_NOTE):
                proposal = True
            continue
        if not bare.strip():
            continue
        if not header_seen:
            header_seen = True
            continue
        fields = bare.split("\t")
        rows[fields[0]] = (index, fields)
    return lines, rows, proposal


def row_state(fields):
    source = fields[6]
    if source.startswith(translib.CLAUDE_ONLY_SOURCE):
        return "claude-only"
    if source.startswith(COUNCIL_SOURCE):
        return "council"
    return "unlabelled"


def claude_label(source):
    """(model id, the rest of the source) of a `claude-only <model>; <rest>` source."""
    body = source[len(translib.CLAUDE_ONLY_SOURCE):].strip()
    model, _, rest = body.partition(";")
    model = model.strip()
    return (model or None), rest.strip()


def set_field(line, column, value):
    carriage = line.endswith("\r")
    fields = (line[:-1] if carriage else line).split("\t")
    fields[column] = value
    return "\t".join(fields) + ("\r" if carriage else "")


def write_glossary_checked(paths, tag, text):
    """Write <tag>.tsv, keep it only if glossary-check passes; refuse (old file restored) otherwise."""
    path = glossary_path(paths, tag)
    before = l10nlib.read_text(path) if os.path.exists(path) else None
    l10nlib.write_text(path, text)
    problems = translib.glossary_problems(paths, tag)
    if problems:
        if before is None:
            os.remove(path)
        else:
            l10nlib.write_text(path, before)
        die("the new l10n/glossary/%s.tsv would fail glossary-check; nothing was written:\n  %s" % (
            tag, "\n  ".join(problems)))


# ---------------------------------------------------------------------------
# relabel
# ---------------------------------------------------------------------------

def relabel(paths, tag, model=DEFAULT_CLAUDE_MODEL, out=None):
    """Label the unlabelled rows of a Claude-only proposal; returns how many rows changed."""
    out = out or sys.stdout
    translib.check_tag_area(tag, None)
    if not model.startswith("claude"):
        die("%s is not a Claude model id: relabel records the Claude model that drafted the proposal" % model)
    lines, rows, proposal = read_language_glossary(paths, tag)
    if not proposal:
        die("l10n/glossary/%s.tsv does not say it is a Claude-only proposal in its header; relabel only "
            "labels such a file (a row's label says who made it, so it is never guessed)" % tag)
    changed = 0
    for term_id, (index, fields) in rows.items():
        if row_state(fields) != "unlabelled":
            continue
        lines[index] = set_field(lines[index], 6, "%s %s; %s" % (translib.CLAUDE_ONLY_SOURCE, model, fields[6]))
        changed += 1
    if changed:
        write_glossary_checked(paths, tag, "\n".join(lines))
    states = collections.Counter(row_state(f) for _, f in read_language_glossary(paths, tag)[1].values())
    out.write("l10n/glossary/%s.tsv: %d rows labelled claude-only %s; now %d claude-only, %d judged by a "
              "council\n" % (tag, changed, model, states["claude-only"], states["council"]))
    return changed


# ---------------------------------------------------------------------------
# The rows of the round
# ---------------------------------------------------------------------------

def round_ids(paths, tag):
    """(English rows by id, the language's rows, ids in the round). Refuses a proposal still unlabelled."""
    english = collections.OrderedDict((f[0], f) for f in english_rows(paths))
    _, rows, proposal = read_language_glossary(paths, tag)
    unlabelled = [i for i, (_, f) in rows.items() if row_state(f) == "unlabelled"]
    if proposal and unlabelled:
        die("l10n/glossary/%s.tsv is a Claude-only proposal with %d unlabelled rows (%s): run "
            "glossary_council.py relabel --tag %s first" % (tag, len(unlabelled), ", ".join(unlabelled[:5]), tag))
    ids = [i for i in english if i not in rows or row_state(rows[i][1]) == "claude-only"]
    return english, rows, ids


def draft_path(paths, tag, provider):
    return paths.work(tag, AREA, "drafts", provider + ".json")


def load_vendor_draft(paths, tag, provider):
    path = draft_path(paths, tag, provider)
    if not os.path.exists(path):
        return None
    try:
        value = json.loads(l10nlib.read_text(path))
    except ValueError as error:
        die("%s: not JSON: %s" % (paths.rel(path), error))
    if not isinstance(value, dict) or value.get("format") != DRAFT_FORMAT or not isinstance(value.get("rows"), dict):
        die("%s is not a %s file" % (paths.rel(path), DRAFT_FORMAT))
    return value


def forms_key(term):
    """The agreement form of a term: each form normalized as council.py normalizes drafts, in order."""
    return tuple(translib.normalize(f) for f in term.split("|") if f.strip())


def candidates_of(paths, tag, require_drafts=True):
    """[(id, English fields, [{"provider", "model", "term"}])] for the round, or a refusal on stale drafts."""
    english, rows, ids = round_ids(paths, tag)
    drafts = {}
    for provider in VENDORS:
        draft = load_vendor_draft(paths, tag, provider)
        if draft is None and require_drafts:
            die("no %s glossary draft: run glossary_council.py draft %s --tag %s first" % (provider, provider, tag))
        drafts[provider] = draft
    stale = []
    out = []
    for term_id in ids:
        fields = english[term_id]
        current = en_sha1(fields)
        candidates = []
        if term_id in rows:
            model, _ = claude_label(rows[term_id][1][6])
            candidates.append(collections.OrderedDict([
                ("provider", "claude"), ("model", model), ("term", rows[term_id][1][1])]))
        for provider in VENDORS:
            draft = drafts[provider]
            if draft is None:
                continue
            row = draft["rows"].get(term_id)
            if not isinstance(row, dict) or row.get("en_sha1") != current:
                stale.append("%s (%s)" % (term_id, provider))
                continue
            if row.get("term"):
                candidates.append(collections.OrderedDict([
                    ("provider", provider), ("model", draft.get("model")), ("term", row["term"])]))
        out.append((term_id, fields, candidates))
    if stale:
        die("these rows were drafted from other English, or not at all (en.tsv changed since): %s. Run "
            "glossary_council.py draft for that provider again" % ", ".join(stale))
    return out


# ---------------------------------------------------------------------------
# draft openai|google
# ---------------------------------------------------------------------------

def withheld_style_guide(text):
    """The style guide without the sections that restate the Claude-proposed glossary terms."""
    out, skipping = [], False
    for line in text.split("\n"):
        if line.startswith("## "):
            heading = re.sub(r"^\d+\.\s*", "", line[3:].strip()).lower()
            skipping = heading.startswith(WITHHELD_SECTIONS)
            if skipping:
                out.append(line)
                out.append("")
                out.append("(Withheld from this round: it restates the proposed glossary terms the council "
                           "is judging.)")
                out.append("")
                continue
        if not skipping:
            out.append(line)
    return "\n".join(out).strip()


def language_settings(paths, tag):
    path = os.path.join(paths.lang_dir(), tag + ".tsv")
    if not os.path.exists(path):
        die("l10n/lang/%s.tsv does not exist; add it first (l10n/README.md, Running a language)" % tag)
    _, rows = l10nlib.read_tsv(l10nlib.read_text(path))
    return collections.OrderedDict((f[0], f[1]) for _, f in rows if len(f) >= 2)


def style_guide(paths, tag):
    path = paths.style(tag)
    if not os.path.exists(path):
        die("l10n/style/%s.md does not exist; the glossary round needs the style guide" % tag)
    return withheld_style_guide(l10nlib.read_text(path))


def guide_overlap(paths, tag, guide, ids):
    """{id: [forms]} of Claude's proposed forms that the withheld style guide still names as words.

    The guide is Claude's too, and its other sections give examples in the language, so withholding
    the two term sections cannot make it blind; this records how far it is not."""
    _, rows, _ = read_language_glossary(paths, tag)
    out = collections.OrderedDict()
    for term_id in ids:
        if term_id not in rows:
            continue
        forms = [f.strip() for f in rows[term_id][1][1].split("|") if f.strip()]
        found = [f for f in forms if re.search(r"(?<!\w)%s(?!\w)" % re.escape(f), guide, re.I)]
        if found:
            out[term_id] = found
    return out


def draft_instructions(settings, guide):
    lang = "%s (%s)" % (settings.get("endonym", settings.get("tag")), settings.get("tag"))
    return "\n".join([
        "You are one of three independent drafters of the glossary for Volt, an independent Android app for NAVEE "
        "electric scooters, translating it from English into %s. The others work separately; a judge compares the "
        "glossaries. The glossary fixes the word every translator of the app's strings must then use." % lang,
        "For each English row give your term in %s:" % lang,
        "- The main form first, then the inflected forms the strings will need (plural, cases, the forms a verb "
        "takes on a button and in a sentence), separated by \" | \" (for example \"patinete | patinetes\"). List the "
        "nominal forms too where the language makes nouns from verbs (\"bloquear | bloqueo\").",
        "- One term per row: no \";\" and no \"(\" in a term, no placeholders, and no U+202F (write U+00A0 where a "
        "no-break space is needed).",
        "- Keep names and do-not-translate words as written (Volt, NAVEE, unit symbols, drive-mode names).",
        "- Rows with a safety value name a safety meaning: locked and unlocked stay distinct, on/off and "
        "never/always are never inverted, \"unverified\" or \"unknown\" never reads as confirmed, and \"not "
        "affiliated with NAVEE\" is never softened.",
        "- Follow the language settings and the style guide below.",
        "Answer with one JSON object only: {\"terms\": {\"<id>\": \"<forms>\"}, \"notes\": {\"<id>\": \"<optional "
        "short note for the judge>\"}}. Include every id you were given.",
        "Language settings:\n" + json.dumps(settings, ensure_ascii=False, indent=1),
        "Style guide for %s:\n%s" % (settings.get("tag"), guide),
    ])


def draft_prompt(rows):
    body = [collections.OrderedDict([("id", f[0]), ("term", f[1]), ("definition", f[2]), ("pos", f[3]),
                                     ("dnt", f[4]), ("safety", f[5])]) for f in rows]
    return "Give your term for each of these %d English glossary rows.\n\n%s" % (
        len(body), json.dumps(body, ensure_ascii=False, indent=1))


@contextlib.contextmanager
def recorded_responses():
    """Every decoded provider response while the block runs (for the token usage generate() drops)."""
    seen = []
    original = providers.call

    def recording(provider, method, url, payload=None):
        value = original(provider, method, url, payload)
        seen.append(value)
        return value

    providers.call = recording
    try:
        yield seen
    finally:
        providers.call = original


def usage_of(response):
    """The token counts a provider reported (numbers only), or None."""
    if not isinstance(response, dict):
        return None
    usage = response.get("usage") if isinstance(response.get("usage"), dict) else response.get("usageMetadata")
    if not isinstance(usage, dict):
        return None
    out = collections.OrderedDict()
    for name, value in sorted(usage.items()):
        if isinstance(value, int) and not isinstance(value, bool):
            out[name] = value
        elif isinstance(value, dict):
            for inner, number in sorted(value.items()):
                if isinstance(number, int) and not isinstance(number, bool):
                    out["%s.%s" % (name, inner)] = number
    return out or None


def add_usage(total, usage):
    for name, value in (usage or {}).items():
        total[name] = total.get(name, 0) + value
    return total


def vendor_draft(paths, provider, tag, model=None, out=None):
    """Draft the round's rows with one provider; writes drafts/<provider>.json."""
    out = out or sys.stdout
    translib.check_tag_area(tag, None)
    if provider not in VENDORS:
        die("unknown provider %s" % provider)
    english, _, ids = round_ids(paths, tag)
    if not ids:
        out.write("%s: every glossary row was judged by a council; nothing to draft\n" % tag)
        return None
    settings = language_settings(paths, tag)
    guide = style_guide(paths, tag)
    system = draft_instructions(settings, guide)
    overlap = guide_overlap(paths, tag, guide, ids)
    model = council.pick_model(provider, "draft", model or DEFAULT_MODELS[provider])
    rows = [english[i] for i in ids]
    drafted, notes, problems, reported, usage = collections.OrderedDict(), {}, [], None, {}
    for index in range(0, len(rows), CHUNK_ROWS):
        chunk = rows[index:index + CHUNK_ROWS]
        with recorded_responses() as responses:
            text, reported = providers.generate(provider, model, system, draft_prompt(chunk))
        chunk_usage = usage_of(responses[-1]) if responses else None
        add_usage(usage, chunk_usage)
        value = providers.parse_json_reply(text)
        terms = value.get("terms") if isinstance(value.get("terms"), dict) else {}
        chunk_notes = value.get("notes") if isinstance(value.get("notes"), dict) else {}
        received = 0
        for fields in chunk:
            term_id = fields[0]
            term = terms.get(term_id)
            if not isinstance(term, str) or not term.strip():
                problems.append("%s: missing from the reply" % term_id)
                continue
            term = " | ".join(f.strip() for f in term.split("|"))
            bad = translib._form_problems(provider, term_id, term)
            if bad:
                problems.extend(bad)
                continue
            drafted[term_id] = collections.OrderedDict([("en_sha1", en_sha1(fields)), ("term", term)])
            if isinstance(chunk_notes.get(term_id), str) and chunk_notes[term_id].strip():
                notes[term_id] = chunk_notes[term_id].strip()
            received += 1
        for term_id in terms:
            if term_id not in [f[0] for f in chunk]:
                problems.append("%s: not asked for; ignored" % term_id)
        council.log_run(paths, tag, AREA, collections.OrderedDict([
            ("stage", "glossary-draft"), ("provider", provider), ("model", model), ("model_reported", reported),
            ("chunk", index // CHUNK_ROWS), ("rows", len(chunk)), ("received", received), ("usage", chunk_usage)]))
    # Rows the provider left out stay listed, without a term, so the draft is current for them.
    for fields in rows:
        if fields[0] not in drafted:
            drafted[fields[0]] = collections.OrderedDict([("en_sha1", en_sha1(fields)), ("term", None)])
    ordered = collections.OrderedDict((f[0], drafted[f[0]]) for f in rows)
    path = draft_path(paths, tag, provider)
    translib.write_json(path, collections.OrderedDict([
        ("format", DRAFT_FORMAT), ("tag", tag), ("provider", provider), ("model", model),
        ("model_reported", reported), ("usage", usage or None), ("style_guide_overlap", overlap), ("rows", ordered),
        ("notes", collections.OrderedDict(sorted(notes.items()))), ("problems", problems)]))
    got = sum(1 for r in ordered.values() if r["term"])
    out.write("%s: %d of %d rows drafted by %s%s\n" % (paths.rel(path), got, len(rows), model,
                                                       " (reported %s)" % reported if reported != model else ""))
    if overlap:
        out.write("  note: the style guide it saw still names %d of Claude's proposed terms (%s); see "
                  "style_guide_overlap\n" % (len(overlap), ", ".join(sorted(overlap)[:5])))
    for problem in problems:
        out.write("  %s\n" % problem)
    return path


# ---------------------------------------------------------------------------
# judge-requests
# ---------------------------------------------------------------------------

def requests_path(paths, tag):
    return paths.work(tag, AREA, "judge-requests.json")


def options_of(term_id, sha, candidates):
    """Distinct candidates in a shuffled order that depends only on the row and its candidates."""
    groups = collections.OrderedDict()
    for c in candidates:
        groups.setdefault(forms_key(c["term"]), []).append(c)
    distinct = list(groups.values())
    seed = hashlib.sha1(json.dumps([term_id, sha, sorted(groups)], ensure_ascii=False).encode("utf-8")).hexdigest()
    random.Random(seed).shuffle(distinct)
    return [collections.OrderedDict([("option", OPTION_LETTERS[i]), ("term", group[0]["term"]),
                                     ("drafters", len(group)), ("providers", [c["provider"] for c in group])])
            for i, group in enumerate(distinct)]


def build_requests(paths, tag):
    rows = []
    for term_id, fields, candidates in candidates_of(paths, tag):
        sha = en_sha1(fields)
        agree = len(candidates) == 3 and len(set(forms_key(c["term"]) for c in candidates)) == 1
        safety = fields[5] != "-"
        if agree and not safety:
            status, reason = "agreed", None
        else:
            status, reason = "judge", ("safety" if safety else "disagreement")
        row = collections.OrderedDict([
            ("id", term_id), ("en_sha1", sha), ("english", fields[1]), ("definition", fields[2]), ("pos", fields[3]),
            ("safety", fields[5]), ("status", status), ("reason", reason), ("candidates", candidates)])
        if status == "judge":
            row["options"] = options_of(term_id, sha, candidates)
        rows.append(row)
    return rows


def judge_instructions(tag, settings, guide):
    return "\n".join([
        "You are the judge of Volt's glossary council for %s (%s), in a session separate from every drafter. Volt is "
        "an independent Android app for NAVEE electric scooters. Three drafters each proposed a term for the English "
        "rows below; the options are the distinct proposals, without names and in no particular order, and "
        "\"drafters\" says how many proposed each." % (settings.get("endonym", tag), tag),
        "For each row, pick the best option or write a better term, with a one-line reason. The term you choose is "
        "what every translator of the app's strings must then use, so prefer the clear, common word over the "
        "clever one.",
        "- A term is the main form first, then the inflected and nominal forms the strings need, separated by "
        "\" | \". No \";\" or \"(\", no placeholders, no U+202F.",
        "- Rows with reason \"safety\" are listed even when every drafter agreed. Check that locked and unlocked "
        "stay distinct, on/off and never/always are not inverted, \"unverified\" or \"unknown\" never reads as "
        "confirmed, and \"not affiliated with NAVEE\" is not softened.",
        "- A row you leave out keeps no council term: it stays Claude's proposal and the string drafts wait.",
        "Language settings:\n" + json.dumps(settings, ensure_ascii=False, indent=1),
        "Style guide for %s:\n%s" % (tag, guide),
    ])


def judge_view(row):
    view = collections.OrderedDict((f, row[f]) for f in ("id", "english", "definition", "pos", "safety", "reason"))
    view["options"] = [collections.OrderedDict([("option", o["option"]), ("term", o["term"]),
                                                ("drafters", o["drafters"])]) for o in row["options"]]
    return view


def reply_dir(paths, tag, set_id):
    return paths.work(tag, AREA, "replies", "judge.%s" % set_id)


def reply_path(paths, tag, set_id, index, count):
    return os.path.join(reply_dir(paths, tag, set_id), "chunk-%d-of-%d.json" % (index, count))


def write_judge_requests(paths, tag, out=None):
    out = out or sys.stdout
    translib.check_tag_area(tag, None)
    rows = build_requests(paths, tag)
    settings = language_settings(paths, tag)
    system = judge_instructions(tag, settings, style_guide(paths, tag))
    judged = [r for r in rows if r["status"] == "judge"]
    chunked = [judged[i:i + CHUNK_ROWS] for i in range(0, len(judged), CHUNK_ROWS)]
    set_id = hashlib.sha1(json.dumps([tag, rows, system], ensure_ascii=False, sort_keys=True)
                          .encode("utf-8")).hexdigest()[:12]
    translib.write_json(requests_path(paths, tag), collections.OrderedDict([
        ("format", REQUESTS_FORMAT), ("tag", tag), ("set", set_id), ("chunks", len(chunked)),
        ("chunk_rows", [[r["id"] for r in c] for c in chunked]), ("rows", rows)]))
    prompts = paths.work(tag, AREA, "prompts")
    if os.path.isdir(prompts):
        for name in os.listdir(prompts):
            if re.match(r"^judge\.chunk-\d+-of-\d+\.md$", name):
                os.remove(os.path.join(prompts, name))
    written = []
    for index, chunk in enumerate(chunked, 1):
        reply = paths.rel(reply_path(paths, tag, set_id, index, len(chunked)))
        text = "\n\n".join([
            "# Volt glossary judge: %s, chunk %d of %d (set %s)" % (tag, index, len(chunked), set_id),
            system,
            "## Your reply",
            "Write one JSON object to `%s`, and nothing else in that file:\n\n"
            "    {\"set\": \"%s\", \"model\": \"<your exact model id>\",\n"
            "     \"rulings\": {\"<id>\": {\"choice\": \"A\" | \"B\" | \"C\" | \"new\", \"term\": \"<the term>\",\n"
            "                            \"reason\": \"<one line>\"}}}\n\n"
            "For a pick, copy that option's term exactly into term. For \"new\", term is your own. Read nothing else "
            "in the repository: this prompt is everything the ruling needs." % (reply, set_id),
            "## Rows\n\n" + json.dumps([judge_view(r) for r in chunk], ensure_ascii=False, indent=1),
        ])
        path = os.path.join(prompts, "judge.chunk-%d-of-%d.md" % (index, len(chunked)))
        l10nlib.write_text(path, text + "\n")
        written.append(path)
    council.log_run(paths, tag, AREA, collections.OrderedDict([
        ("stage", "glossary-judge-requests"), ("set", set_id), ("rows", len(rows)), ("judge", len(judged)),
        ("chunks", len(chunked))]))
    counts = collections.Counter(r["reason"] or "agreed" for r in rows)
    out.write("%s: %d rows in the round: %d agreed, %d disagreement, %d safety; set %s\n" % (
        paths.rel(requests_path(paths, tag)), len(rows), counts["agreed"], counts["disagreement"], counts["safety"],
        set_id))
    for row in judged:
        out.write("  %s (%s): %s\n" % (row["id"], row["reason"], " / ".join(o["term"] for o in row["options"])))
    for path in written:
        out.write("  prompt: %s\n" % paths.rel(path))
    return written


# ---------------------------------------------------------------------------
# decide
# ---------------------------------------------------------------------------

def load_requests(paths, tag):
    path = requests_path(paths, tag)
    if not os.path.exists(path):
        die("no %s: run glossary_council.py judge-requests --tag %s first" % (paths.rel(path), tag))
    value = json.loads(l10nlib.read_text(path))
    if not isinstance(value, dict) or value.get("format") != REQUESTS_FORMAT:
        die("%s is not a %s file" % (paths.rel(path), REQUESTS_FORMAT))
    return value


def read_rulings(paths, tag, requests):
    """({id: ruling with "model"}, problems). Refusals are problems; a missing chunk is only a note."""
    set_id = requests["set"]
    by_id = dict((r["id"], r) for r in requests["rows"])
    rulings, problems, notes = {}, [], []
    count = requests["chunks"]
    for index in range(1, count + 1):
        path = reply_path(paths, tag, set_id, index, count)
        where = paths.rel(path)
        if not os.path.exists(path):
            notes.append("%s: no reply yet; its rows stay claude-only" % where)
            continue
        try:
            value = json.loads(l10nlib.read_text(path))
        except ValueError as error:
            problems.append("%s: not JSON: %s" % (where, error))
            continue
        if not isinstance(value, dict) or not isinstance(value.get("rulings"), dict):
            problems.append("%s: needs {\"set\", \"model\", \"rulings\"}" % where)
            continue
        if value.get("set") != set_id:
            problems.append("%s: set %r is not the current set %s" % (where, value.get("set"), set_id))
            continue
        model = value.get("model")
        if not isinstance(model, str) or not model.startswith("claude"):
            problems.append("%s: model %r is not a Claude model id" % (where, model))
            continue
        for term_id, ruling in value["rulings"].items():
            row = by_id.get(term_id)
            if row is None or row["status"] != "judge":
                problems.append("%s: %s is not a row this set asked the judge about" % (where, term_id))
                continue
            if not isinstance(ruling, dict) or not isinstance(ruling.get("term"), str) or \
                    not isinstance(ruling.get("reason"), str) or not ruling["reason"].strip():
                problems.append("%s: %s needs choice, term and reason" % (where, term_id))
                continue
            choice = ruling.get("choice")
            term = " | ".join(f.strip() for f in ruling["term"].split("|"))
            options = dict((o["option"], o) for o in row["options"])
            if choice in options:
                if forms_key(term) != forms_key(options[choice]["term"]):
                    problems.append("%s: %s picks %s but its term is not that option's" % (where, term_id, choice))
                    continue
                term = options[choice]["term"]
            elif choice != "new":
                problems.append("%s: %s: choice %r is not one of %s or new" % (
                    where, term_id, choice, ", ".join(sorted(options))))
                continue
            bad = translib._form_problems(where, term_id, term)
            if bad:
                problems.extend(bad)
                continue
            if term_id in rulings:
                problems.append("%s: %s is ruled twice" % (where, term_id))
                continue
            rulings[term_id] = collections.OrderedDict([
                ("choice", choice), ("term", term), ("reason", ruling["reason"].strip()), ("model", model),
                ("picked", options[choice]["providers"] if choice in options else [])])
    return rulings, problems, notes


def council_source(candidates, judge, rest):
    models = dict((c["provider"], c["model"] or "-") for c in candidates)
    parts = ["council"] + ["%s:%s" % (p, models.get(p, "-")) for p in ("claude",) + VENDORS]
    parts.append("judge:%s" % judge if judge else "agreed")
    return " ".join(parts) + ("; " + rest if rest else "")


def decide(paths, tag, out=None):
    out = out or sys.stdout
    translib.check_tag_area(tag, None)
    requests = load_requests(paths, tag)
    stored = collections.OrderedDict((r["id"], r) for r in requests["rows"])
    current = collections.OrderedDict((r["id"], r) for r in build_requests(paths, tag))
    changed = []
    for term_id, row in current.items():
        old = stored.get(term_id)
        if old is None:
            changed.append("%s (not in the requests)" % term_id)
        elif old["en_sha1"] != row["en_sha1"]:
            changed.append("%s (the English changed)" % term_id)
        elif json.dumps(old["candidates"], sort_keys=True) != json.dumps(row["candidates"], sort_keys=True):
            changed.append("%s (a candidate changed)" % term_id)
    if changed:
        die("the round changed since judge-requests (set %s): %s. Run judge-requests again (and the judge on its "
            "new prompts); nothing was written" % (requests["set"], ", ".join(changed)))
    rulings, problems, notes = read_rulings(paths, tag, requests)
    if problems:
        die("the judge's replies have problems; nothing was written:\n  " + "\n  ".join(problems))
    english, _, _ = round_ids(paths, tag)
    lines, rows, _ = read_language_glossary(paths, tag)
    records, counts = [], collections.Counter()
    appended = []
    for term_id, row in current.items():
        ruling = rulings.get(term_id)
        if row["status"] == "agreed":
            claude = next(c["term"] for c in row["candidates"] if c["provider"] == "claude")
            outcome, term, judge = "agreed", claude, None
        elif ruling:
            outcome, term, judge = "judged", ruling["term"], ruling["model"]
        else:
            outcome, term, judge = "unruled", None, None
        counts[outcome] += 1
        record = collections.OrderedDict([
            ("id", term_id), ("en_sha1", row["en_sha1"]), ("outcome", outcome), ("term", term),
            ("reason", row["reason"]), ("candidates", row["candidates"]), ("ruling", ruling)])
        if term is not None:
            if term_id in rows:
                index, fields = rows[term_id]
                _, rest = claude_label(fields[6])
                source = council_source(row["candidates"], judge, rest)
                lines[index] = set_field(set_field(lines[index], 1, term), 6, source)
            else:
                fields = english[term_id]
                source = council_source(row["candidates"], judge, fields[6])
                appended.append("\t".join([term_id, term, fields[2], fields[3], fields[4], fields[5], source]))
            record["source"] = source
        records.append(record)
    lines = [l for l in lines if not l.startswith(ROUND_NOTE)]
    while lines and lines[-1] == "":
        lines.pop()
    body = lines + appended
    states = collections.Counter(row_state(l.split("\t")) for l in body
                                 if l and not l.startswith("#") and len(l.split("\t")) == 7)
    note = "%s %d rows judged by a council, %d still claude-only." % (
        ROUND_NOTE, states["council"], states["claude-only"])
    text = "\n".join([note] + body) + "\n"
    write_glossary_checked(paths, tag, text)
    translib.write_jsonl(paths.work(tag, AREA, "decisions.jsonl"), records)
    council.log_run(paths, tag, AREA, collections.OrderedDict([
        ("stage", "glossary-decide"), ("set", requests["set"]), ("agreed", counts["agreed"]),
        ("judged", counts["judged"]), ("unruled", counts["unruled"]),
        ("judges", sorted(set(r["model"] for r in rulings.values())))]))
    unjudged = translib.claude_only_glossary_rows(paths, tag)
    out.write("l10n/glossary/%s.tsv: %d agreed, %d judged, %d unruled; %d rows claude-only%s\n" % (
        tag, counts["agreed"], counts["judged"], counts["unruled"], len(unjudged),
        " (draft openai|google still refuses)" if unjudged else " (the string drafts may start)"))
    for line in notes:
        out.write("  %s\n" % line)
    return counts


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", help="repository root (default: this checkout)")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("relabel", help="label a Claude-only proposal's rows claude-only <model>")
    p.add_argument("--tag", required=True)
    p.add_argument("--model", default=DEFAULT_CLAUDE_MODEL, help="the Claude model that drafted it (default %s)" %
                   DEFAULT_CLAUDE_MODEL)
    p = sub.add_parser("draft", help="a provider's own glossary terms, drafted without Claude's")
    p.add_argument("provider", choices=VENDORS)
    p.add_argument("--tag", required=True)
    p.add_argument("--model", help="an exact model id (default: openai %s, google %s)" % (
        DEFAULT_MODELS["openai"], DEFAULT_MODELS["google"]))
    p = sub.add_parser("judge-requests", help="the rows to judge and the judge's prompt chunks")
    p.add_argument("--tag", required=True)
    p = sub.add_parser("decide", help="apply agreement and rulings to l10n/glossary/<tag>.tsv")
    p.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    try:
        if args.command == "relabel":
            relabel(paths, args.tag, args.model)
        elif args.command == "draft":
            vendor_draft(paths, args.provider, args.tag, args.model)
        elif args.command == "judge-requests":
            write_judge_requests(paths, args.tag)
        elif args.command == "decide":
            decide(paths, args.tag)
    except providers.ProviderError as error:
        print("glossary_council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    except (translib.L10nError, ValueError, OSError) as error:
        print("glossary_council.py: %s" % providers.redact(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
