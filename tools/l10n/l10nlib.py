"""Shared helpers for the localization proof tools: a Java lexer, git access and TSV files.

Standard library only. Every tool in tools/l10n imports this module from its own folder.
"""

import collections
import fcntl
import json
import os
import re
import stat
import secrets
import subprocess
import unicodedata

# ---------------------------------------------------------------------------
# Java lexing
# ---------------------------------------------------------------------------

Token = collections.namedtuple("Token", "kind value raw line pos")
"""kind is ident, string, char, number or op. value is the decoded text for string and char."""

_HEX = set("0123456789abcdefABCDEF")
_OPS3 = (">>>=", "<<=", ">>=", ">>>", "...")
_OPS2 = ("++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "==", "!=", "<=", ">=",
         "&&", "||", "->", "::", "<<", ">>")
_SIMPLE_ESCAPES = {"b": "\b", "t": "\t", "n": "\n", "f": "\f", "r": "\r", "s": " ",
                   '"': '"', "'": "'", "\\": "\\"}


class LexError(ValueError):
    pass


def translate_unicode_escapes(text):
    """javac's first step: \\uXXXX escapes (with any number of u's) become characters.

    A backslash starts an escape only when an even number of backslashes precede it.
    """
    if "\\u" not in text:
        return text
    out = []
    i, n, run = 0, len(text), 0
    while i < n:
        c = text[i]
        if c == "\\" and run % 2 == 0 and i + 1 < n and text[i + 1] == "u":
            j = i + 1
            while j < n and text[j] == "u":
                j += 1
            digits = text[j:j + 4]
            if len(digits) == 4 and all(d in _HEX for d in digits):
                out.append(chr(int(digits, 16)))
                i = j + 4
                run = 0
                continue
        run = run + 1 if c == "\\" else 0
        out.append(c)
        i += 1
    return "".join(out)


def join_surrogates(value):
    """Combines UTF-16 surrogate pairs left by \\uD83D\\uDD0B escapes into one character."""
    if not any(0xD800 <= ord(c) <= 0xDFFF for c in value):
        return value
    return value.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")


def decode_java_escapes(body):
    """The value of a string or char literal body (the text between the quotes)."""
    if "\\" not in body:
        return join_surrogates(body)
    out = []
    i, n = 0, len(body)
    while i < n:
        c = body[i]
        if c != "\\" or i + 1 >= n:
            out.append(c)
            i += 1
            continue
        e = body[i + 1]
        if e in _SIMPLE_ESCAPES:
            out.append(_SIMPLE_ESCAPES[e])
            i += 2
        elif e in "01234567":
            j = i + 1
            limit = 3 if e in "0123" else 2
            while j < n and j - (i + 1) < limit and body[j] in "01234567":
                j += 1
            out.append(chr(int(body[i + 1:j], 8)))
            i = j
        elif e == "\n":  # text-block line continuation
            i += 2
        else:
            out.append(c + e)
            i += 2
    return join_surrogates("".join(out))


def _text_block_value(raw_body):
    lines = raw_body.split("\n")
    if lines and lines[0].strip() == "":
        lines = lines[1:]
    content = [l for l in lines if l.strip()]
    indent = min((len(l) - len(l.lstrip(" \t")) for l in content), default=0)
    last = lines[-1] if lines else ""
    if last.strip() == "":
        indent = min(indent, len(last))
    stripped = [l[indent:].rstrip(" \t") for l in lines]
    return decode_java_escapes("\n".join(stripped))


def lex_java(source):
    """Tokens of a Java source file. Comments and whitespace are dropped."""
    text = translate_unicode_escapes(source)
    tokens = []
    i, n, line = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if c in " \t\r\f":
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            end = n if j < 0 else j + 2
            line += text.count("\n", i, end)
            i = end
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            while j > 0 and _escaped(text, j):
                j = text.find('"""', j + 1)
            end = n if j < 0 else j + 3
            raw = text[i:end]
            tokens.append(Token("string", _text_block_value(text[i + 3:end - 3]), raw, line, i))
            line += raw.count("\n")
            i = end
            continue
        if c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            if j >= n or text[j] != c:
                raise LexError("line %d: unterminated %s literal" % (line, "string" if c == '"' else "char"))
            raw = text[i:j + 1]
            kind = "string" if c == '"' else "char"
            tokens.append(Token(kind, decode_java_escapes(text[i + 1:j]), raw, line, i))
            i = j + 1
            continue
        if c.isalpha() or c in "_$" or (ord(c) > 127 and c.isidentifier()):
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] in "_$" or (ord(text[j]) > 127 and ("a" + text[j]).isidentifier())):
                j += 1
            tokens.append(Token("ident", text[i:j], text[i:j], line, i))
            i = j
            continue
        if c.isdigit() or (c == "." and i + 1 < n and text[i + 1].isdigit()):
            j = i + 1
            hexadecimal = text[i:i + 2].lower() == "0x"
            while j < n:
                d = text[j]
                if d.isalnum() or d in "._":
                    j += 1
                elif d in "+-" and (text[j - 1] in "pP" or (text[j - 1] in "eE" and not hexadecimal)):
                    j += 1
                else:
                    break
            tokens.append(Token("number", text[i:j], text[i:j], line, i))
            i = j
            continue
        for op in _OPS3 + _OPS2:
            if text.startswith(op, i):
                break
        else:
            op = c
        tokens.append(Token("op", op, op, line, i))
        i += len(op)
    return tokens


def _escaped(text, index):
    k, count = index - 1, 0
    while k >= 0 and text[k] == "\\":
        count += 1
        k -= 1
    return count % 2 == 1


def string_literals(source):
    """(line, value) for every string literal, in source order."""
    return [(t.line, t.value) for t in lex_java(source) if t.kind == "string"]


# ---------------------------------------------------------------------------
# English-looking text
# ---------------------------------------------------------------------------

FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%]")
_IDENTIFIER_LIKE = re.compile(r"^[a-z0-9_.:/#@\-]*$")
_WORDS = re.compile(r"[^\W\d_]{1,}(?:['’][^\W\d_]+)*", re.UNICODE)


def without_format_specs(value):
    return FORMAT_SPEC.sub(" ", value)


def has_letters(value):
    return any(ch.isalpha() for ch in without_format_specs(value))


def _is_symbol_chunk(chunk):
    """True for a whitespace-separated chunk holding a non-ASCII symbol ("✓", "☆", "⚠\ufe0e"). ASCII
    symbols do not count: "x = " and "a + " are code and log shapes, not a word and a mark."""
    return any(ord(ch) > 0x7f and unicodedata.category(ch).startswith("S") for ch in chunk)


def is_multiword(value):
    """Two or more words of letters separated by whitespace ("Turn off"), or a word and a symbol set
    apart by whitespace ("Unrestricted ✓", "☆ Star"): a shown label with a mark, not a key."""
    words = symbols = 0
    for chunk in re.split(r"\s+", without_format_specs(value).strip()):
        if _WORDS.search(chunk):
            words += 1
        elif _is_symbol_chunk(chunk):
            symbols += 1
    return words >= 2 or (words == 1 and symbols >= 1)


def is_english_looking(value):
    """Text a person reads: a run of two letters that is not a lower-case key, path or id."""
    if not re.search(r"[A-Za-z]{2}|[^\x00-\x7f]", value):
        return False
    if not any(ch.isalpha() for ch in value):
        return False
    return not _IDENTIFIER_LIKE.match(value)


_KEY_OR_ID = re.compile(r"^[a-z][a-z0-9]*(?:[_.][a-z0-9]+)*$")


def is_identifier_like(value):
    """A lower-case key or id of two characters or more, words joined by _ or ., for example
    "app_language", "com.jjricks.volt" or "msg". Narrower than what is_english_looking skips: a unit
    such as "km/h" or a separator such as "x" is not one. A person can still read a key, so callers
    show the code that holds it."""
    return len(value) >= 2 and bool(_KEY_OR_ID.match(value))


# ---------------------------------------------------------------------------
# git
# ---------------------------------------------------------------------------

WORKTREE = "WORKTREE"


def git(args, cwd=None, check=True):
    result = subprocess.run(["git"] + list(args), cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise SystemExit("git %s failed: %s" % (" ".join(args), result.stderr.decode("utf-8", "replace").strip()))
    return result.stdout.decode("utf-8", "surrogateescape")


def repo_root(cwd=None):
    return git(["rev-parse", "--show-toplevel"], cwd=cwd).strip()


def resolve(rev, cwd=None):
    if rev == WORKTREE:
        return WORKTREE
    return git(["rev-parse", "--verify", rev + "^{commit}"], cwd=cwd).strip()


def merge_base(base, head, cwd=None):
    """The commit to compare against: where head left base (head itself may be WORKTREE)."""
    head_rev = "HEAD" if head == WORKTREE else head
    out = git(["merge-base", base, head_rev], cwd=cwd, check=False).strip()
    if not out:
        raise SystemExit("No merge base between %s and %s" % (base, head_rev))
    return out


def changed_files(base, head, pathspecs, cwd=None):
    """Paths that differ between base and head (renames count as delete plus add)."""
    if head == WORKTREE:
        out = git(["diff", "--name-only", "--no-renames", "-z", base, "--"] + list(pathspecs), cwd=cwd)
        untracked = git(["ls-files", "-z", "--others", "--exclude-standard", "--"] + list(pathspecs), cwd=cwd)
        names = out.split("\0") + untracked.split("\0")
    else:
        out = git(["diff", "--name-only", "--no-renames", "-z", base, head, "--"] + list(pathspecs), cwd=cwd)
        names = out.split("\0")
    return sorted(set(n for n in names if n))


def read_files(rev, paths, cwd=None):
    """{path: text or None}. rev may be WORKTREE to read the files on disk."""
    result = {}
    if not paths:
        return result
    if rev == WORKTREE:
        root = repo_root(cwd)
        for path in paths:
            full = os.path.join(root, path)
            if os.path.isfile(full):
                with open(full, "rb") as handle:
                    result[path] = handle.read().decode("utf-8", "surrogateescape")
            else:
                result[path] = None
        return result
    request = "".join("%s:%s\n" % (rev, p) for p in paths).encode("utf-8")
    proc = subprocess.run(["git", "cat-file", "--batch"], cwd=cwd, input=request,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("git cat-file failed: " + proc.stderr.decode("utf-8", "replace"))
    data, at = proc.stdout, 0
    for path in paths:
        end = data.index(b"\n", at)
        header = data[at:end].decode("utf-8", "replace")
        at = end + 1
        if header.endswith(" missing"):
            result[path] = None
            continue
        size = int(header.rsplit(" ", 1)[1])
        result[path] = data[at:at + size].decode("utf-8", "surrogateescape")
        at += size + 1
    return result


def list_files(rev, pathspecs, cwd=None):
    if rev == WORKTREE:
        out = git(["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--"] + list(pathspecs), cwd=cwd)
        root = repo_root(cwd)
        return sorted(p for p in set(out.split("\0")) if p and os.path.isfile(os.path.join(root, p)))
    out = git(["ls-tree", "-r", "-z", "--name-only", rev, "--"] + list(pathspecs), cwd=cwd)
    return sorted(p for p in out.split("\0") if p)


def first_parent_commits(base, head, cwd=None):
    """Commits on head's first-parent line after base, oldest first."""
    out = git(["rev-list", "--reverse", "--first-parent", "%s..%s" % (base, head)], cwd=cwd)
    return [line for line in out.split("\n") if line]


def short(rev):
    return rev if rev == WORKTREE else rev[:12]


# ---------------------------------------------------------------------------
# TSV and text escapes
# ---------------------------------------------------------------------------

def escape_field(value):
    """Backslash escapes for a TSV field: backslash, tab, newline, carriage return, other controls."""
    out = []
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ord(ch) < 0x20 or ord(ch) == 0x7f:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)


def unescape_field(value):
    if "\\" not in value:
        return value
    out = []
    i, n = 0, len(value)
    while i < n:
        c = value[i]
        if c == "\\" and i + 1 < n:
            e = value[i + 1]
            if e == "t":
                out.append("\t")
            elif e == "n":
                out.append("\n")
            elif e == "r":
                out.append("\r")
            elif e == "\\":
                out.append("\\")
            elif e == "u" and len(value[i + 2:i + 6]) == 4 and all(h in _HEX for h in value[i + 2:i + 6]):
                out.append(chr(int(value[i + 2:i + 6], 16)))
                i += 6
                continue
            else:
                raise ValueError("unknown escape \\%s in %r" % (e, value))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def read_tsv(text, required_header=None):
    """Rows of a TSV with a header line. Blank lines and lines starting with # are skipped.

    Returns (header, rows) where each row is (line_number, [fields]).
    """
    header, rows = None, []
    for number, line in enumerate(text.split("\n"), 1):
        if line.endswith("\r"):
            line = line[:-1]
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if header is None:
            header = fields
            if required_header is not None and header != list(required_header):
                raise ValueError("line %d: expected header %s, found %s" % (
                    number, "\\t".join(required_header), "\\t".join(header)))
            continue
        if len(fields) != len(header):
            raise ValueError("line %d: expected %d fields, found %d" % (number, len(header), len(fields)))
        rows.append((number, fields))
    if header is None and required_header is not None:
        raise ValueError("missing header " + "\\t".join(required_header))
    return header or [], rows


def json_string(value):
    return json.dumps(value, ensure_ascii=False)


def read_text(path):
    with open(path, "rb") as handle:
        return handle.read().decode("utf-8")


def write_text(path, text):
    """Replace the file at path with text, atomically: the bytes go to a temporary file in the same directory,
    which then takes the file's place with os.replace, so a reader (or a second process) sees the whole old
    file or the whole new one, never a truncated or half-written one. A symlink keeps pointing where it did
    (its target is replaced), and an existing file keeps its permission bits."""
    path = os.path.realpath(path) if os.path.islink(path) else path
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    data = text.encode("utf-8")  # before opening: text that cannot be encoded leaves the file as it was
    try:
        mode = stat.S_IMODE(os.stat(path).st_mode)
    except FileNotFoundError:
        mode = None  # a new file: the OS applies the umask, as open() would; os.umask is process-wide, never set it
    while True:
        temporary = os.path.join(directory or ".", ".%s.%s.tmp" % (os.path.basename(path), secrets.token_hex(6)))
        try:
            handle = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o666)
            break
        except FileExistsError:
            continue
    try:
        with os.fdopen(handle, "wb") as out:
            if mode is not None:
                os.fchmod(out.fileno(), mode)
            out.write(data)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def append_line(path, line):
    """Append one line (a newline is added) to the file at path as a single write, holding an exclusive
    fcntl.flock on the file meanwhile, so several processes logging to one file never lose or interleave a
    row. A file whose last line has no newline gets one first, so the new line never joins it."""
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    data = (line + "\n").encode("utf-8")
    descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o666)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        try:
            size = os.fstat(descriptor).st_size
            if size:
                with open(path, "rb") as reader:
                    reader.seek(size - 1)
                    if reader.read(1) != b"\n":
                        data = b"\n" + data
            written = 0
            while written < len(data):
                written += os.write(descriptor, data[written:])
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
    finally:
        os.close(descriptor)
