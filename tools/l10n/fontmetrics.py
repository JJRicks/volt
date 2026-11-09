"""Glyph coverage and text widths for the translation checks (plan 4.7 and 7.2 step 3).

Coverage reads the font's cmap table directly. Widths come from java.awt when a Java runtime is
available (TextWidth.java beside this file, headless, kerning and ligatures on, as Android shapes
text); otherwise from a documented approximation: the sum of the glyphs' advance widths from the
hmtx table, with no kerning. Saira's kerning narrows typical Latin text by about 1-2%, so the
approximation errs slightly wide (safe for a budget check). Every result records which method made it.

Widths are in dp: a budget volt:max="96dp@14sp" is measured at 14 px with a 1 px = 1 dp scale, which
is what 14sp means at font scale 1.0. Letter-spacing (some captions use 0.08em) is not applied; the
budgets in the catalog are written for the plain text width.
"""

import os
import shutil
import struct
import subprocess

JAVA_SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "TextWidth.java")
METHOD_JAVA = "java.awt"
METHOD_APPROX = "hmtx-approx"


class FontError(ValueError):
    pass


class Font(object):
    """The tables of one TrueType or OpenType font that the checks need."""

    def __init__(self, path):
        self.path = path
        with open(path, "rb") as handle:
            data = handle.read()
        tables = self._tables(data)
        for name in ("head", "hhea", "hmtx", "cmap"):
            if name not in tables:
                raise FontError("%s: no %s table" % (path, name))
        head_at = tables["head"][0]
        self.units_per_em = struct.unpack(">H", data[head_at + 18:head_at + 20])[0]
        hhea_at = tables["hhea"][0]
        metrics = struct.unpack(">H", data[hhea_at + 34:hhea_at + 36])[0]
        hmtx_at = tables["hmtx"][0]
        self.advances = [struct.unpack(">H", data[hmtx_at + 4 * i:hmtx_at + 4 * i + 2])[0] for i in range(metrics)]
        self.cmap = self._cmap(data, tables["cmap"][0])

    @staticmethod
    def _tables(data):
        if data[:4] not in (b"\x00\x01\x00\x00", b"OTTO", b"true"):
            raise FontError("not a TrueType or OpenType font")
        count = struct.unpack(">H", data[4:6])[0]
        tables = {}
        for i in range(count):
            tag, _checksum, offset, length = struct.unpack(">4sIII", data[12 + 16 * i:28 + 16 * i])
            tables[tag.decode("latin-1")] = (offset, length)
        return tables

    @staticmethod
    def _cmap(data, at):
        count = struct.unpack(">H", data[at + 2:at + 4])[0]
        subtables = []
        for i in range(count):
            platform, encoding, offset = struct.unpack(">HHI", data[at + 4 + 8 * i:at + 12 + 8 * i])
            fmt = struct.unpack(">H", data[at + offset:at + offset + 2])[0]
            subtables.append((platform, encoding, fmt, at + offset))
        # Prefer the full-Unicode table, then the BMP table.
        ranked = sorted(subtables, key=lambda s: (s[2] != 12, s[2] != 4, s[0] != 3))
        for _platform, _encoding, fmt, offset in ranked:
            if fmt == 12:
                return Font._format12(data, offset)
            if fmt == 4:
                return Font._format4(data, offset)
        raise FontError("no cmap subtable in format 4 or 12")

    @staticmethod
    def _format4(data, at):
        segments = struct.unpack(">H", data[at + 6:at + 8])[0] // 2
        ends_at = at + 14
        starts_at = ends_at + 2 * segments + 2
        deltas_at = starts_at + 2 * segments
        ranges_at = deltas_at + 2 * segments
        cmap = {}
        for s in range(segments):
            end = struct.unpack(">H", data[ends_at + 2 * s:ends_at + 2 * s + 2])[0]
            start = struct.unpack(">H", data[starts_at + 2 * s:starts_at + 2 * s + 2])[0]
            delta = struct.unpack(">h", data[deltas_at + 2 * s:deltas_at + 2 * s + 2])[0]
            range_offset = struct.unpack(">H", data[ranges_at + 2 * s:ranges_at + 2 * s + 2])[0]
            for code in range(start, end + 1):
                if code == 0xFFFF:
                    continue
                if range_offset == 0:
                    glyph = (code + delta) & 0xFFFF
                else:
                    where = ranges_at + 2 * s + range_offset + 2 * (code - start)
                    glyph = struct.unpack(">H", data[where:where + 2])[0]
                    if glyph:
                        glyph = (glyph + delta) & 0xFFFF
                if glyph:
                    cmap[code] = glyph
        return cmap

    @staticmethod
    def _format12(data, at):
        groups = struct.unpack(">I", data[at + 12:at + 16])[0]
        cmap = {}
        for g in range(groups):
            start, end, glyph = struct.unpack(">III", data[at + 16 + 12 * g:at + 28 + 12 * g])
            for code in range(start, end + 1):
                cmap[code] = glyph + code - start
        return cmap

    def covers(self, ch):
        return ord(ch) in self.cmap

    def missing(self, text):
        """Characters of text the font has no glyph for (line breaks and format controls excepted)."""
        out = []
        for ch in text:
            if ord(ch) < 0x20 or ch == "\x7f" or ch in "\u200b\u200c\u200d\u200e\u200f\u2060\ufeff":
                continue
            if not self.covers(ch) and ch not in out:
                out.append(ch)
        return out

    def approx_width(self, text, size):
        """Sum of advance widths at size px, without kerning; the widest line of a multi-line text."""
        widest = 0.0
        for line in text.split("\n"):
            units = 0
            for ch in line:
                glyph = self.cmap.get(ord(ch), 0)
                units += self.advances[min(glyph, len(self.advances) - 1)]
            widest = max(widest, units * size / float(self.units_per_em))
        return widest


def java_command():
    """The java launcher to use: JAVA_HOME's, else the one on PATH, else None."""
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = os.path.join(home, "bin", "java")
        if os.path.exists(candidate):
            return candidate
    return shutil.which("java")


class Measurer(object):
    """Measures many texts at once. method is METHOD_JAVA or METHOD_APPROX after the first call."""

    def __init__(self, font_path, use_java=True):
        self.font = Font(font_path)
        self.font_path = font_path
        self.use_java = use_java
        self.method = None
        self.java_problem = None

    def measure(self, requests):
        """[(size_px, text)] -> [width_px]. Multi-line texts give their widest line."""
        lines, owners = [], []
        for index, (size, text) in enumerate(requests):
            for line in text.split("\n"):
                lines.append((size, line))
                owners.append(index)
        widths = None
        if self.use_java and lines:
            widths = self._java(lines)
        if widths is None:
            self.method = METHOD_APPROX
            widths = [self.font.approx_width(text, size) for size, text in lines]
        else:
            self.method = METHOD_JAVA
        result = [0.0] * len(requests)
        for owner, width in zip(owners, widths):
            result[owner] = max(result[owner], width)
        if not requests and self.method is None:
            self.method = METHOD_JAVA if self.use_java and java_command() else METHOD_APPROX
        return result

    def _java(self, lines):
        java = java_command()
        if not java:
            self.java_problem = "no Java runtime (set JAVA_HOME)"
            return None
        payload = "".join("%s\t%s\n" % (repr(float(size)), text.encode("utf-8").hex()) for size, text in lines)
        try:
            result = subprocess.run([java, "-Djava.awt.headless=true", JAVA_SOURCE, self.font_path],
                                    input=payload.encode("utf-8"), stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=120)
        except (OSError, subprocess.SubprocessError) as error:
            self.java_problem = "java failed to start: %s" % type(error).__name__
            return None
        output = result.stdout.decode("utf-8", "replace").split()
        if result.returncode != 0 or len(output) != len(lines) or "error" in output:
            self.java_problem = "java.awt measurement failed (exit %d)" % result.returncode
            return None
        try:
            return [float(v) for v in output]
        except ValueError:
            self.java_problem = "java.awt printed something other than widths"
            return None


# A text with more hard line breaks than its budget allows fits at no width.
NO_FIT = 9999.0


def _paragraph_words(text):
    """The words of each hard line: split at plain spaces only (a no-break space joins its words)."""
    return [[w for w in line.split(" ") if w] for line in text.split("\n")]


def _greedy_lines(words, span, limit):
    """Lines a greedy whole-word wrap takes at limit, or None when a single word is wider than limit.

    span(i, j) is the measured width of words[i:j] joined by spaces. Like Android's simple break
    strategy, each line takes words while they fit and never breaks inside a word.
    """
    lines, start = 0, 0
    while start < len(words):
        if span(start, start + 1) > limit + 0.01:
            return None
        end = start + 1
        while end < len(words) and span(start, end + 1) <= limit + 0.01:
            end += 1
        lines, start = lines + 1, end
    return max(lines, 1)


def wrapped_width(paragraphs, span, lines):
    """The narrowest width at which every hard line wraps into at most lines lines in all.

    paragraphs is _paragraph_words(text); span(p, i, j) the width of paragraph p's words[i:j].
    """
    if len(paragraphs) > lines:
        return NO_FIT
    candidates = sorted(set(span(p, i, j) for p, words in enumerate(paragraphs)
                            for i in range(len(words)) for j in range(i + 1, len(words) + 1)))
    for limit in candidates:
        total = 0
        for p, words in enumerate(paragraphs):
            used = _greedy_lines(words, lambda i, j, p=p: span(p, i, j), limit)
            if used is None:
                total = lines + 1
                break
            total += used
        if total <= lines:
            return limit
    return candidates[-1] if candidates else 0.0


def measure_lines(measurer, requests):
    """[(size_px, text, lines)] -> [width_px]: for one line the plain width (Measurer.measure), for more
    the narrowest width that holds the text in that many lines of whole words (wrapped_width), which is
    what a budget of that many lines must hold. Every line is measured as a whole, with its kerning."""
    plain, spans, plans = [], [], []
    for size, text, lines in requests:
        if lines <= 1:
            plans.append(("plain", len(plain)))
            plain.append((size, text))
            continue
        paragraphs = _paragraph_words(text)
        index = {}
        for p, words in enumerate(paragraphs):
            for i in range(len(words)):
                for j in range(i + 1, len(words) + 1):
                    index[(p, i, j)] = len(spans)
                    spans.append((size, " ".join(words[i:j])))
        plans.append(("wrap", (paragraphs, index, lines)))
    widths = measurer.measure(plain + spans)
    plain_widths, span_widths = widths[:len(plain)], widths[len(plain):]
    result = []
    for kind, plan in plans:
        if kind == "plain":
            result.append(plain_widths[plan])
        else:
            paragraphs, index, lines = plan
            result.append(wrapped_width(paragraphs, lambda p, i, j: span_widths[index[(p, i, j)]], lines))
    return result
