"""OpenAI and Google Gemini clients for the translation council (plan header: three providers).

Keys come ONLY from the environment: OPENAI_API_KEY and GEMINI_API_KEY, read at the moment a
request is built and never kept. Nothing here logs, prints, stores or echoes a key, a header that
carries one, or a raw HTTP error body: errors are reduced to the status code and the provider's
error message after redaction (redact()), which removes the current key values and anything shaped
like a key or an Authorization header. Gemini's key goes in the x-goog-api-key header, not the URL.

Model ids are chosen at run time from each provider's model list (choose_model), per the patterns
in council_models.json.

Tests replace the transport (set_transport) and never reach the network.
"""

import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

KEY_ENV = {"openai": "OPENAI_API_KEY", "google": "GEMINI_API_KEY"}
OPENAI_BASE = "https://api.openai.com/v1"
GOOGLE_BASE = "https://generativelanguage.googleapis.com/v1beta"
CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "council_models.json")
REDACTED = "[redacted]"
TIMEOUT = 300
RETRIES = 3

_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),
    re.compile(r"AIza[0-9A-Za-z_\-]{20,}"),
    re.compile(r"(?i)(bearer\s+)[^\s\"',;]+"),
    re.compile(r"(?i)(authorization[\"']?\s*[:=]\s*[\"']?)[^\"',;\n]+"),
    re.compile(r"(?i)(x-goog-api-key[\"']?\s*[:=]\s*[\"']?)[^\s\"',;]+"),
    re.compile(r"(?i)((?:api[_-]?key|key|token)[\"']?\s*[:=]\s*[\"']?)[^\s\"'&,;]+"),
]


class ProviderError(Exception):
    """An error whose message is already redacted."""

    def __init__(self, message):
        Exception.__init__(self, redact(message))


def _secrets():
    values = []
    for name in KEY_ENV.values():
        value = os.environ.get(name)
        if value and len(value.strip()) >= 4:
            values.append(value.strip())
    return values


def redact(text):
    """text without any current key value, key-shaped token or credential header value."""
    if text is None:
        return ""
    text = str(text)
    for secret in _secrets():
        text = text.replace(secret, REDACTED)
    for pattern in _PATTERNS:
        if pattern.groups:
            text = pattern.sub(lambda m: m.group(1) + REDACTED, text)
        else:
            text = pattern.sub(REDACTED, text)
    return text


def _key(provider):
    name = KEY_ENV[provider]
    value = os.environ.get(name, "").strip()
    if not value:
        raise ProviderError("%s is not set. Source the keys file in a subshell first (l10n/README.md, "
                            "Running a language)." % name)
    return value


# ---------------------------------------------------------------------------
# Transport
# ---------------------------------------------------------------------------

class UrllibTransport(object):
    """send(method, url, headers, body) -> (status, body bytes). Never raises with request details."""

    def send(self, method, url, headers, body):
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            try:
                data = error.read()
            except Exception:  # noqa: BLE001
                data = b""
            return error.code, data
        except Exception as error:  # noqa: BLE001 - reduce to the class name and a redacted reason
            reason = getattr(error, "reason", None)
            raise ProviderError("network error: %s%s" % (type(error).__name__,
                                                         ": %s" % redact(reason) if reason else "")) from None


_transport = [UrllibTransport()]
_sleep = [time.sleep]


def set_transport(transport, sleep=None):
    """Replace the transport (tests). Returns the previous one."""
    previous = _transport[0]
    _transport[0] = transport
    if sleep is not None:
        _sleep[0] = sleep
    return previous


def _error_message(data):
    try:
        value = json.loads(data.decode("utf-8", "replace"))
        error = value.get("error") if isinstance(value, dict) else None
        if isinstance(error, dict):
            message = error.get("message") or error.get("status") or ""
        else:
            message = error or ""
    except (ValueError, AttributeError):
        message = ""
    message = redact(" ".join(str(message).split()))
    return message[:300] if message else "no error message"


def call(provider, method, url, payload=None):
    """The decoded JSON response. Retries 429 and 5xx. Raises ProviderError (redacted) on failure."""
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    for attempt in range(RETRIES + 1):
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if provider == "openai":
            headers["Authorization"] = "Bearer " + _key(provider)
        elif provider == "google":
            headers["x-goog-api-key"] = _key(provider)
        else:
            raise ProviderError("unknown provider %s" % provider)
        try:
            status, data = _transport[0].send(method, url, headers, body)
        except ProviderError:
            raise
        except Exception as error:  # noqa: BLE001 - a transport must not leak request details
            raise ProviderError("%s request failed: %s" % (provider, type(error).__name__)) from None
        finally:
            headers = None
        if 200 <= status < 300:
            try:
                return json.loads(data.decode("utf-8"))
            except ValueError:
                raise ProviderError("%s returned a response that is not JSON" % provider) from None
        if (status == 429 or status >= 500) and attempt < RETRIES:
            _sleep[0](min(60, 5 * 2 ** attempt))
            continue
        raise ProviderError("%s HTTP %d: %s" % (provider, status, _error_message(data)))


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------

def list_models(provider):
    """Model ids the key can use for text generation."""
    if provider == "openai":
        value = call("openai", "GET", OPENAI_BASE + "/models")
        return sorted(m["id"] for m in value.get("data", []) if isinstance(m, dict) and m.get("id"))
    if provider == "google":
        ids, token = [], None
        for _ in range(50):
            query = {"pageSize": "1000"}
            if token:
                query["pageToken"] = token
            value = call("google", "GET", GOOGLE_BASE + "/models?" + urllib.parse.urlencode(query))
            for m in value.get("models", []):
                methods = m.get("supportedGenerationMethods") or []
                name = m.get("name", "")
                if "generateContent" in methods and name.startswith("models/"):
                    ids.append(name[len("models/"):])
            token = value.get("nextPageToken")
            if not token:
                break
        return sorted(ids)
    raise ProviderError("unknown provider %s" % provider)


def load_config(path=None):
    with open(path or CONFIG, "rb") as handle:
        return json.loads(handle.read().decode("utf-8"))


def _natural(value):
    return [(0, int(part), "") if part.isdigit() else (1, 0, part) for part in re.split(r"(\d+)", value)]


def choose_model(provider, role, available, config=None, prefer=None, avoid=()):
    """The model id to use, or ProviderError. prefer: patterns that replace the config's for this call."""
    config = config or load_config()
    section = config.get(provider, {})
    patterns = list(prefer) if prefer else list(section.get(role, []))
    excludes = [re.compile(p) for p in section.get("exclude", [])]
    usable = [m for m in available if m not in avoid and not any(x.search(m) for x in excludes)]
    for pattern in patterns:
        matcher = re.compile(pattern)
        matches = [m for m in usable if matcher.search(m)]
        if matches:
            return max(matches, key=_natural)
    raise ProviderError("no %s model matches the %s preference %s (of %d listed)" % (
        provider, role, patterns, len(available)))


# ---------------------------------------------------------------------------
# Generation
# ---------------------------------------------------------------------------

def generate(provider, model, instructions, prompt, config=None):
    """(text, reported model id) for one JSON-mode request."""
    config = config or load_config()
    if provider == "openai":
        if config.get("openai", {}).get("api", "responses") == "chat":
            value = call("openai", "POST", OPENAI_BASE + "/chat/completions", {
                "model": model, "response_format": {"type": "json_object"},
                "messages": [{"role": "system", "content": instructions}, {"role": "user", "content": prompt}]})
            choices = value.get("choices") or []
            text = choices[0].get("message", {}).get("content") if choices else None
        else:
            value = call("openai", "POST", OPENAI_BASE + "/responses", {
                "model": model, "instructions": instructions, "input": prompt,
                "text": {"format": {"type": "json_object"}}, "store": False})
            text = value.get("output_text")
            if not text:
                parts = []
                for item in value.get("output") or []:
                    for content in item.get("content") or [] if isinstance(item, dict) else []:
                        if isinstance(content, dict) and content.get("type") == "output_text":
                            parts.append(content.get("text", ""))
                text = "".join(parts)
        if not text:
            raise ProviderError("openai returned no text (status %s)" % value.get("status", "?"))
        return text, value.get("model") or model
    if provider == "google":
        value = call("google", "POST", "%s/models/%s:generateContent" % (GOOGLE_BASE, urllib.parse.quote(model)), {
            "systemInstruction": {"parts": [{"text": instructions}]},
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2}})
        candidates = value.get("candidates") or []
        parts = (candidates[0].get("content") or {}).get("parts") or [] if candidates else []
        text = "".join(p.get("text", "") for p in parts if isinstance(p, dict) and not p.get("thought"))
        if not text:
            reason = candidates[0].get("finishReason") if candidates else (value.get("promptFeedback") or {}).get(
                "blockReason")
            raise ProviderError("google returned no text (%s)" % (reason or "no candidates"))
        return text, value.get("modelVersion") or model
    raise ProviderError("unknown provider %s" % provider)


def parse_json_reply(text):
    """The JSON object in a model's reply, tolerating a ```json fence."""
    stripped = text.strip()
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", stripped, re.S)
    if fence:
        stripped = fence.group(1)
    try:
        value = json.loads(stripped)
    except ValueError:
        start, end = stripped.find("{"), stripped.rfind("}")
        try:
            value = json.loads(stripped[start:end + 1]) if start >= 0 < end else None
        except ValueError:
            value = None
    if not isinstance(value, dict):
        raise ProviderError("the reply is not a JSON object")
    return value
