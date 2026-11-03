#!/usr/bin/env python3
"""G4, frozen test literals: every test statement that holds English stays exactly as it was; new
statements may be added.

The existing assertions are the reference for "English did not change", so an extraction PR must
not edit them or switch them to R.string or T lookups (a resource compared with itself proves
nothing). Freezing whole statements, not just literals, means an assertion cannot be weakened
(`|| true`), have its expected text changed while a decoy keeps the old literal elsewhere, or swap
two expectations.

A statement is the run of tokens between `;`, `{` and `}`, compared token for token (spacing and
comments do not matter; a class qualifier such as `TextFit.` in front of a call is ignored, so a
helper can move to a shared class). Statements are compared across all changed files in tests/ and
android-tests/ together, so moving a test helper between files is fine (`--per-file` forbids it).

Freezing statements alone cannot stop an assertion being switched off around its unchanged tokens
(`if (false) { ... }`), replaced while a verbatim copy sits in an uncalled method, or defeated by
editing a helper such as check() that holds no English. So every existing test file must also keep
its code: its tokens at the merge base must all still be there, in order, with nothing changed in
between. Code may only be added, as whole statements or members (balanced brackets, starting after
`;`, `{` or `}` and ending with `;` or `}`), and the added code may not:

  - jump out of the code around it (return, break, continue, yield inside a method; throw anywhere,
    since a new member that throws skips the existing code that called it),
  - assign to, or step, a variable it did not declare itself,
  - declare a member, or a local that hides a field or type, named like anything in the file,
  - declare a type or an initializer block, or import a name the file uses,
  - call exit, halt, Runtime, set*UncaughtExceptionHandler, setAccessible, setOut, setErr,
    setDefault (Locale or TimeZone), setProperty or setSecurityManager, or reach them indirectly:
    reflection (getMethod, invoke, forName, a Class literal, getClass...), method handles, class
    loaders, processes or script engines, whose names are not tokens a token check can see,
  - use a type declared in a file this change adds (src/ or tests): new files are not frozen, so
    an existing test calling into one could run anything, including an early exit.

A new test file may not declare a type named like a src/ class, which would replace it in a test
build that compiles both, nor a top-level type named like a java.lang type (AssertionError, String,
System) or like a top-level type an existing file under tests/ or android-tests/ declares, which
would replace that type for every existing test compiled with it.

Code may be deleted only where a deleted statement is recorded by a removed ledger row. New test
files are not limited. Because of this, a statement cannot move between existing files.

A statement may disappear only when the test of deleted code is deleted: a new ledger row of kind
"removed" whose location is that test file and a line of the statement, holding its English
literals. The deletion must be a deletion: the changed region adds no English-looking literal, and
the removed English does not reappear in a new test statement.

English-looking: a literal with two letters in a row (or any non-ASCII letter) that is not a
lower-case key, path or id such as "app_settings" or "com.jjricks.volt". Java files only.

  frozen_literals.py --base origin/main [--head HEAD|WORKTREE] [--ledger FILE ...] [--per-file] [--report]
"""

import argparse
import collections
import difflib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402

TEST_DIRS = ("tests", "android-tests")
SEPARATORS = (";", "{", "}")

Statement = collections.namedtuple("Statement", "key first_line last_line english strings")


def _canonical(tokens):
    out, i = [], 0
    while i < len(tokens):
        t = tokens[i]
        # Drop class qualifiers (Upper.member and this.member) so a helper can move between classes.
        if t.kind == "ident" and (t.value[:1].isupper() or t.value == "this") and i + 2 < len(tokens) and \
                tokens[i + 1].value == "." and tokens[i + 2].kind == "ident" and \
                (i == 0 or tokens[i - 1].value != "."):
            i += 2
            continue
        out.append((t.kind, t.value) if t.kind in ("string", "char") else (t.kind, t.raw))
        i += 1
    return tuple(out)


def statements(text):
    """Statements holding at least one English-looking string literal."""
    if text is None:
        return []
    tokens = l10nlib.lex_java(text)
    result, current = [], []
    for tok in tokens + [None]:
        if tok is None or (tok.kind == "op" and tok.value in SEPARATORS):
            english = [t.value for t in current if t.kind == "string" and l10nlib.is_english_looking(t.value)]
            if english:
                last = current[-1]
                result.append(Statement(_canonical(current), current[0].line, last.line + last.raw.count("\n"),
                                        tuple(english), tuple(t.value for t in current if t.kind == "string")))
            current = []
        else:
            current.append(tok)
    return result


ASSIGN = {"=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>="}
STEP = {"++", "--"}
JUMPS = {"return", "break", "continue", "throw", "yield"}
ESCAPES = {"exit", "halt", "Runtime", "setDefaultUncaughtExceptionHandler", "setUncaughtExceptionHandler",
           "setAccessible", "reflect", "setOut", "setErr", "setDefault", "setProperty", "setSecurityManager",
           # Reflection and other indirect calls: the method name can hide in a string ("ex" + "it").
           "Class", "getClass", "forName", "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods",
           "getConstructor", "getConstructors", "getDeclaredConstructor", "getDeclaredConstructors", "getField",
           "getFields", "getDeclaredField", "getDeclaredFields", "invoke", "invokeExact", "invokeWithArguments",
           "newInstance", "MethodHandles", "MethodHandle", "MethodType", "VarHandle", "LambdaMetafactory", "Proxy",
           "ClassLoader", "loadClass", "defineClass", "ServiceLoader", "Unsafe", "ProcessBuilder", "ProcessHandle",
           "exec", "ScriptEngineManager", "ScriptEngine", "JShell", "ToolProvider", "Thread", "Instrumentation", "Signal"}
TYPE_WORDS = {"class", "interface", "enum", "record"}
# Every top-level type in java.base's java.lang (JDK 25): a type of that name in the tests' package
# replaces the implicitly imported one in every test compiled with it (AssertionError, String, System).
JAVA_LANG = frozenset("""
AbstractMethodError AbstractStringBuilder Appendable ApplicationShutdownHooks ArithmeticException
ArrayIndexOutOfBoundsException ArrayStoreException AssertionError AssertionStatusDirectives AutoCloseable
BaseVirtualThread Boolean BootstrapMethodError Byte CharSequence Character CharacterData CharacterName Class
ClassCastException ClassCircularityError ClassFormatError ClassFrameInfo ClassLoader ClassNotFoundException
ClassValue CloneNotSupportedException Cloneable Comparable Compiler CompoundEnumeration
ConditionalSpecialCasing Deprecated Double Enum EnumConstantNotPresentException Error Exception
ExceptionInInitializerError FdLibm Float FunctionalInterface IO IllegalAccessError IllegalAccessException
IllegalArgumentException IllegalCallerException IllegalMonitorStateException IllegalStateException
IllegalThreadStateException IncompatibleClassChangeError IndexOutOfBoundsException InheritableThreadLocal
InstantiationError InstantiationException Integer InternalError InterruptedException Iterable
LayerInstantiationException LinkageError LiveStackFrame LiveStackFrameInfo Long MatchException Math Module
ModuleLayer NamedPackage NegativeArraySizeException NoClassDefFoundError NoSuchFieldError
NoSuchFieldException NoSuchMethodError NoSuchMethodException NullPointerException Number
NumberFormatException Object OutOfMemoryError Override Package Process ProcessBuilder ProcessEnvironment
ProcessHandle ProcessHandleImpl ProcessImpl PublicMethods Readable Record ReflectiveOperationException
Runnable Runtime RuntimeException RuntimePermission SafeVarargs ScopedValue SecurityException
SecurityManager Short Shutdown StableValue StackFrameInfo StackOverflowError StackStreamFactory
StackTraceElement StackWalker StrictMath String StringBuffer StringBuilder StringCoding StringConcatHelper
StringIndexOutOfBoundsException StringLatin1 StringUTF16 SuppressWarnings System Terminator Thread
ThreadBuilders ThreadDeath ThreadGroup ThreadLocal Throwable TypeNotPresentException UnknownError
UnsatisfiedLinkError UnsupportedClassVersionError UnsupportedOperationException VerifyError VersionProps
VirtualMachineError VirtualThread Void WeakPairMap WrongThreadException
""".split())
PAIRS = {"(": ")", "[": "]", "{": "}"}
NOT_TYPES = {"return", "new", "throw", "case", "else", "yield", "assert", "instanceof"}


def _key(t):
    return (t.kind, t.value if t.kind in ("string", "char") else t.raw)


def _is_op(t, *values):
    return t is not None and t.kind == "op" and t.value in values


def _anonymous_body(tokens, p):
    """True when the { at p opens an anonymous class body: new Name(...) {."""
    if p == 0 or not _is_op(tokens[p - 1], ")"):
        return False
    depth, j = 0, p - 1
    while j >= 0:
        if _is_op(tokens[j], ")"):
            depth += 1
        elif _is_op(tokens[j], "("):
            depth -= 1
            if depth == 0:
                break
        j -= 1
    j -= 1
    while j >= 0 and (tokens[j].kind == "ident" or _is_op(tokens[j], ".", "<", ">", ",")):
        if tokens[j].kind == "ident" and tokens[j].value == "new":
            return True
        j -= 1
    return False


def contexts(tokens):
    """For each token index p, what code inserted before it sits in: None (file level), "type" (a
    class body, where members go) or "code" (a method body or other block)."""
    result, stack, head_start = [], [], 0
    for p, t in enumerate(tokens):
        result.append(stack[-1] if stack else None)
        if _is_op(t, "{"):
            head = tokens[head_start:p]
            # record is contextual: void check(Result record) { opens a method body, not a type.
            declares_type = bool(ledger_check.header_type_keywords(head))
            stack.append("type" if declares_type or _anonymous_body(tokens, p) else "code")
        elif _is_op(t, "}") and stack:
            stack.pop()
        if _is_op(t, ";", "{", "}"):
            head_start = p + 1
    result.append(stack[-1] if stack else None)
    return result


def _declared(run, k):
    """True when run[k] is a name being declared: Type name followed by =, ;, ,, :, ) or (."""
    t = run[k]
    if t.kind != "ident" or k == 0 or k + 1 >= len(run):
        return False
    prev, nxt = run[k - 1], run[k + 1]
    typed = (prev.kind == "ident" and prev.value not in NOT_TYPES) or _is_op(prev, ">", "]")
    return typed and _is_op(nxt, "=", ";", ",", ":", ")", "(")


def _lvalue_root(run, end):
    """The first token of the assignable expression ending at run[end] (name, a.b, a[i])."""
    j = end
    while j >= 0:
        if _is_op(run[j], "]"):
            depth = 0
            while j >= 0:
                depth += 1 if _is_op(run[j], "]") else -1 if _is_op(run[j], "[") else 0
                if depth == 0:
                    break
                j -= 1
            j -= 1
            continue
        if run[j].kind == "ident" and j >= 2 and _is_op(run[j - 1], "."):
            j -= 2
            continue
        return run[j] if j >= 0 else None
    return None


def shape_problem(run, before_token):
    """Why a run of tokens is not whole statements or members, or None."""
    if not (before_token is None or _is_op(before_token, ";", "{", "}")) or not _is_op(run[-1], ";", "}"):
        return "it does not start and end at a statement boundary"
    depth, stack = 0, []
    for t in run:
        if _is_op(t, "(", "[", "{"):
            stack.append(PAIRS[t.value])
        elif _is_op(t, ")", "]", "}"):
            if not stack or stack.pop() != t.value:
                return "its brackets do not balance (it wraps or cuts existing code)"
    if stack:
        return "its brackets do not balance (it wraps or cuts existing code)"
    if run[0].kind == "ident" and run[0].value in ("else", "catch", "finally"):
        return "it attaches %s to existing code" % run[0].value
    return None


def declared_types(text):
    """Names of the classes, interfaces, enums and records a Java source declares."""
    if text is None:
        return set()
    tokens = l10nlib.lex_java(text)
    return set(tokens[k + 1].value for k in range(len(tokens) - 1)
               if tokens[k].kind == "ident" and tokens[k].value in TYPE_WORDS and tokens[k + 1].kind == "ident" and
               not (k > 0 and _is_op(tokens[k - 1], ".")))


def top_level_types(text):
    """Names of the types a Java source declares outside any braces: the ones its package sees."""
    if text is None:
        return set()
    tokens = l10nlib.lex_java(text)
    names, depth = set(), 0
    for k, t in enumerate(tokens):
        if _is_op(t, "{"):
            depth += 1
        elif _is_op(t, "}"):
            depth -= 1
        elif depth == 0 and k + 1 < len(tokens) and t.kind == "ident" and t.value in TYPE_WORDS and \
                tokens[k + 1].kind == "ident" and not (k > 0 and _is_op(tokens[k - 1], ".")):
            names.add(tokens[k + 1].value)
    return names


def insertion_problem(run, ctx, base_names, guarded_names, before_token, new_types=frozenset()):
    """Why an inserted run of tokens is not plain new test code, or None."""
    problem = shape_problem(run, before_token)
    if problem:
        return problem
    names = set(run[k].value for k in range(len(run)) if _declared(run, k))
    for k, t in enumerate(run):
        prev = run[k - 1] if k else None
        if t.kind == "ident" and t.value in ESCAPES:
            return "it calls %s" % t.value
        if t.kind == "ident" and t.value == "class" and _is_op(prev, "."):
            return "it uses a Class literal (reflection)"
        if t.kind == "ident" and t.value in TYPE_WORDS and not _is_op(prev, "."):
            return "it declares a type (%s)" % t.value
        if t.kind == "ident" and t.value == "throw":
            return "it throws (a new member that throws skips the existing code that called it)"
        if ctx == "code" and t.kind == "ident" and t.value in JUMPS:
            return "it jumps out of the existing code (%s)" % t.value
        if t.kind == "ident" and t.value in new_types and not _is_op(prev, "."):
            return "it uses %s, which a file new in this change declares; an existing test may not depend on " \
                "code that is not frozen" % t.value
        if _is_op(t, *ASSIGN) or _is_op(t, *STEP):
            if _is_op(t, *ASSIGN) and _declared(run, k - 1):
                continue
            if _is_op(t, *STEP) and (prev is None or not (prev.kind == "ident" or _is_op(prev, "]"))):
                root = run[k + 1] if k + 1 < len(run) else None
            else:
                root = _lvalue_root(run, k - 1)
            if root is None or root.kind != "ident" or root.value not in names:
                return "it changes %s, which it did not declare" % (root.raw if root is not None else "a value")
    if ctx is None:
        if run[0].value != "import":
            return "only imports may be added outside a class"
        imported = [t.value for t in run if t.kind == "ident"]
        if imported and imported[-1] in base_names:
            return "it imports %s, a name the file already uses" % imported[-1]
        return None
    depth = 0
    for k, t in enumerate(run):
        prev = run[k - 1] if k else None
        if _is_op(t, "{") and depth == 0 and ctx == "type" and (prev is None or _is_op(prev, ";", "}") or
                                                                 (prev.kind == "ident" and prev.value == "static")):
            return "it adds an initializer block"
        if depth == 0 and _declared(run, k):
            hidden = base_names if ctx == "type" else guarded_names
            if t.value in hidden:
                return "it declares %s, a name the file already uses" % t.value
        if _is_op(t, "(", "[", "{"):
            depth += 1
        elif _is_op(t, ")", "]", "}"):
            depth -= 1
    return None


def code_problems(path, before, after, removable_lines, new_types=frozenset()):
    """Problems with how an existing test file's code changed (see the module docstring).

    removable_lines: base (first, last) line spans of statements a removed ledger row covers.
    """
    if before is None or after is None:
        return []
    base, head = l10nlib.lex_java(before), l10nlib.lex_java(after)
    ctx = contexts(base)
    base_names = set(t.value for t in base if t.kind == "ident")
    fields = set(base[k].value for k in range(len(base)) if ctx[k] == "type" and _declared(base, k))
    guarded = fields | set(n for n in base_names if n[:1].isupper())
    problems = []
    a, b = [_key(t) for t in base], [_key(t) for t in head]
    for tag, i1, i2, j1, j2 in _token_opcodes(before, after, base, head, a, b):
        if tag == "equal":
            continue
        line = base[i1].line if i1 < len(base) else base[-1].line if base else 1
        if tag == "replace":
            problems.append("%s:%d: existing test code changed (%s became %s); existing tests only gain new statements" % (
                path, line, _excerpt(base[i1:i2]), _excerpt(head[j1:j2])))
        elif tag == "delete":
            first, last = base[i1].line, base[i2 - 1].line
            if not any(f <= last and first <= l for f, l in removable_lines) or \
                    shape_problem(base[i1:i2], base[i1 - 1] if i1 else None):
                problems.append("%s:%d: existing test code deleted (%s); only a test of deleted code may go, with a "
                                "removed ledger row" % (path, line, _excerpt(base[i1:i2])))
        else:
            reasons = []
            for p, j in _slides(a, b, i1, j1, j2 - j1):
                run_tokens, before_token = head[j:j + j2 - j1], base[p - 1] if p else None
                reason = insertion_problem(run_tokens, ctx[p], base_names, guarded, before_token, new_types)
                if reason is None:
                    break
                # Prefer the reason from a placement that is whole statements: it names what is wrong.
                reasons.append((shape_problem(run_tokens, before_token) is not None, reason))
            else:
                problems.append("%s:%d: code added to an existing test is not plain new test code: %s (%s)" % (
                    path, head[j1].line, sorted(reasons, key=lambda r: r[0])[0][1], _excerpt(head[j1:j2])))
    return problems


def _excerpt(tokens, limit=12):
    text = " ".join(t.raw for t in tokens[:limit])
    return text + (" ..." if len(tokens) > limit else "")


def _slides(a, b, p, j, length):
    """(base index, head index) of every equivalent placement of an insertion of length tokens."""
    while p > 0 and j > 0 and b[j - 1] == b[j + length - 1] and a[p - 1] == b[j - 1]:
        p, j = p - 1, j - 1
    result = [(p, j)]
    while j + length < len(b) and p < len(a) and b[j] == b[j + length] and a[p] == b[j]:
        p, j = p + 1, j + 1
        result.append((p, j))
    return result


def _token_opcodes(before, after, base, head, a, b):
    """Token opcodes over whole files: lines are aligned first, then tokens inside each changed region."""
    lines_a, lines_b = before.split("\n"), after.split("\n")
    start_a = [0] * (len(lines_a) + 2)
    start_b = [0] * (len(lines_b) + 2)
    for starts, tokens in ((start_a, base), (start_b, head)):
        k = 0
        for line in range(1, len(starts)):
            while k < len(tokens) and tokens[k].line < line:
                k += 1
            starts[line] = k
    result = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, lines_a, lines_b, autojunk=False).get_opcodes():
        ta1, ta2, tb1, tb2 = start_a[i1 + 1], start_a[i2 + 1], start_b[j1 + 1], start_b[j2 + 1]
        if tag == "equal":
            result.append(("equal", ta1, ta2, tb1, tb2))
            continue
        for sub in difflib.SequenceMatcher(None, a[ta1:ta2], b[tb1:tb2], autojunk=False).get_opcodes():
            result.append((sub[0], ta1 + sub[1], ta1 + sub[2], tb1 + sub[3], tb1 + sub[4]))
    return result


def english_literals(text):
    if text is None:
        return []
    return [(line, value) for line, value in l10nlib.string_literals(text) if l10nlib.is_english_looking(value)]


def _changed_regions(before, after):
    """[(base first line, base last line, head first line, head last line)] of the changed lines."""
    a = before.split("\n") if before is not None else []
    b = after.split("\n") if after is not None else []
    return [(i1 + 1, i2, j1 + 1, j2) for tag, i1, i2, j1, j2 in
            difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if tag != "equal"]


def run(base, head, explicit_ledgers=None, per_file=False, cwd=None):
    """(problems, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, list(TEST_DIRS), cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    problems = []
    old, new = collections.Counter(), collections.Counter()
    old_where = collections.defaultdict(list)
    head_statements = {}
    for path in files:
        try:
            for s in statements(before[path]):
                key = (path, s.key) if per_file else s.key
                old[key] += 1
                old_where[key].append((path, s))
            head_statements[path] = statements(after[path])
        except l10nlib.LexError as error:
            problems.append("%s: %s" % (path, error))
            head_statements[path] = []
        for s in head_statements[path]:
            new[(path, s.key) if per_file else s.key] += 1
    added_english = collections.Counter()
    for (key, count) in (new - old).items():
        statement_key = key[1] if per_file else key
        for kind, value in statement_key:
            if kind == "string" and l10nlib.is_english_looking(value):
                added_english[value] += count

    rows, row_problems = ledger_check.range_rows(merge, head, explicit_ledgers, cwd)
    problems.extend(row_problems)
    removable = collections.defaultdict(list)
    removals = [r for r in rows if r.kind == "removed" and r.path.startswith(tuple(d + "/" for d in TEST_DIRS))]
    used_rows = set()
    removed = old - new
    recorded = 0
    for key, count in sorted(removed.items(), key=lambda kv: str(kv[0])):
        occurrences = old_where[key]
        matched = []
        for path, statement in occurrences:
            if len(matched) == count:
                break
            for r in removals:
                if id(r) not in used_rows and r.path == path and \
                        statement.first_line <= r.base_line <= statement.last_line and \
                        not (r.literals - collections.Counter(statement.strings)):
                    used_rows.add(id(r))
                    matched.append((path, statement, r))
                    break
        unmatched = [o for o in occurrences if not any(o[1] is m[1] for m in matched)][:count - len(matched)]
        for path, statement in unmatched:
            problems.append("%s:%d: a test statement with %s was changed or removed; test assertions are frozen "
                            "(a deleted test of deleted code needs a removed ledger row)" % (
                                path, statement.first_line, ", ".join(l10nlib.json_string(v) for v in statement.english)))
        for path, statement, row in matched:
            removable[path].append((statement.first_line, statement.last_line))
            where = "%s:%d" % (row.source, row.line)
            region_english = []
            for b1, b2, h1, h2 in _changed_regions(before[path], after[path]):
                if b1 <= statement.last_line and statement.first_line <= b2 and after[path] is not None:
                    region_english.extend(v for line, v in english_literals(after[path]) if h1 <= line <= h2)
            back = [v for v in statement.english if added_english[v] > 0]
            if region_english:
                problems.append("%s: removed is for a deleted test, but the change at %s:%d puts %s in its place" % (
                    where, path, statement.first_line, l10nlib.json_string(region_english[0])))
            elif back:
                problems.append("%s: removed is for a deleted test, but %s comes back in a new test statement; "
                                "a changed assertion is not a removal" % (where, l10nlib.json_string(back[0])))
            else:
                recorded += 1
    for r in removals:
        if id(r) not in used_rows:
            problems.append("%s:%d: no frozen test statement on line %d of %s was removed; the row covers nothing" % (
                r.source, r.line, r.base_line, r.path))
    new_types = set()
    others = [p for p in l10nlib.changed_files(merge, head, ["src"] + list(TEST_DIRS), cwd) if p.endswith(".java")]
    other_before = l10nlib.read_files(merge, [p for p in others if p not in before], cwd)
    added_files = [p for p in others if before.get(p, other_before.get(p)) is None]
    added_text = l10nlib.read_files(head, added_files, cwd)
    src_classes = set(os.path.basename(p)[:-len(".java")] for p in l10nlib.list_files(head, ["src"], cwd)
                      if p.endswith(".java"))
    test_types = None  # name -> a test file at the merge base declaring it at top level, read on demand
    for path in added_files:
        try:
            declared = declared_types(added_text[path])
        except l10nlib.LexError:
            continue  # reported above for a test file; a src file is G1's
        declared.add(os.path.basename(path)[:-len(".java")])
        new_types |= declared
        if path.startswith(tuple(d + "/" for d in TEST_DIRS)):
            for name in sorted(declared & src_classes):
                problems.append("%s: declares %s, the name of a src/ class; a test build compiling both would use "
                                "the test's copy" % (path, name))
            try:
                top = top_level_types(added_text[path]) | {os.path.basename(path)[:-len(".java")]}
            except l10nlib.LexError:
                continue
            for name in sorted(top & JAVA_LANG):
                problems.append("%s: declares %s, the name of a java.lang type; every test compiled with it would "
                                "use this copy instead of java.lang.%s" % (path, name, name))
            if test_types is None:
                test_types = {}
                existing = [p for p in l10nlib.list_files(merge, list(TEST_DIRS), cwd) if p.endswith(".java")]
                for old_path, text in sorted(l10nlib.read_files(merge, existing, cwd).items()):
                    try:
                        names = top_level_types(text) | {os.path.basename(old_path)[:-len(".java")]}
                    except l10nlib.LexError:
                        continue
                    for name in names:
                        test_types.setdefault(name, old_path)
            for name in sorted((top & set(test_types)) - JAVA_LANG):
                problems.append("%s: declares %s, a type %s already declares; a test build compiling both would use "
                                "one copy for every test" % (path, name, test_types[name]))
    for path in files:
        try:
            problems.extend(code_problems(path, before[path], after[path], removable[path],
                                          new_types - set(declared_types(before[path]))))
        except l10nlib.LexError:
            pass  # reported above
    added = sum((new - old).values())
    summary = "%d changed test file(s); %d English statement(s) kept, %d added, %d removed (%d recorded in the ledger)" % (
        len(files), sum((old & new).values()), added, sum(removed.values()), recorded)
    return problems, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: rows added since the merge base)")
    parser.add_argument("--per-file", action="store_true", help="also forbid moving a statement between files")
    parser.add_argument("--report", action="store_true",
                        help="list changes but never fail (an owner-approved English change, plan part D)")
    args = parser.parse_args(argv)
    problems, summary = run(args.base, l10nlib.resolve(args.head), args.ledger, args.per_file)
    for problem in problems:
        print("frozen_literals: %s%s" % ("report: " if args.report else "", problem), file=sys.stderr)
    status = "FAILED" if problems and not args.report else "PASS" if not problems else "REPORT"
    print("frozen_literals: %s: %s" % (status, summary))
    return 1 if problems and not args.report else 0


if __name__ == "__main__":
    sys.exit(main())
