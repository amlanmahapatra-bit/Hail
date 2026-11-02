"""LLM clients.

OpenRouterClient: Google Gemini models through OpenRouter (OpenAI-compatible chat
completions with function calling), pinned to Google's own infrastructure.
FakeLLM: deterministic rule-based stand-in used by tests, CI and offline demos.

Both return the assistant message dict exactly as it should be appended to history.
For Gemini this matters: the message can carry `reasoning_details` (thought signatures)
that must be sent back unmodified on the next call.
"""

import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

log = logging.getLogger("hail.llm")


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]
    raw_arguments: str = ""
    parse_error: str | None = None


@dataclass
class LLMTurn:
    message: dict[str, Any]
    tool_calls: list[ToolCall] = field(default_factory=list)

    @property
    def text(self) -> str:
        return self.message.get("content") or ""


class LLMError(Exception):
    def __init__(self, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class LLMClient(Protocol):
    def complete(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]]) -> LLMTurn: ...


def parse_tool_calls(message: dict[str, Any]) -> list[ToolCall]:
    calls = []
    for tc in message.get("tool_calls") or []:
        fn = tc.get("function") or {}
        raw = fn.get("arguments") or "{}"
        try:
            args = json.loads(raw) if isinstance(raw, str) else dict(raw)
            err = None if isinstance(args, dict) else "arguments must be an object"
        except json.JSONDecodeError as e:
            args, err = {}, f"invalid JSON arguments: {e.msg}"
        calls.append(
            ToolCall(
                id=tc.get("id", ""),
                name=fn.get("name", ""),
                arguments=args or {},
                raw_arguments=raw if isinstance(raw, str) else json.dumps(raw),
                parse_error=err,
            )
        )
    return calls


class OpenRouterClient:
    PROVIDER = {
        "only": ["google-vertex", "google-ai-studio"],
        "allow_fallbacks": False,
        "data_collection": "deny",
    }

    def __init__(self, api_key: str, model: str, base_url: str, timeout: float = 30.0):
        self._model = model
        self._http = httpx.Client(
            base_url=base_url,
            timeout=timeout,
            headers={"Authorization": f"Bearer {api_key}", "X-Title": "Hail"},
        )

    def complete(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]]) -> LLMTurn:
        body = {
            "model": self._model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "provider": self.PROVIDER,
        }
        for attempt in range(4):
            try:
                r = self._http.post("/chat/completions", json=body)
            except httpx.TransportError as e:
                if attempt == 3:
                    raise LLMError("llm_unreachable", str(e), retryable=True) from e
                time.sleep(2**attempt)
                continue
            if r.status_code == 402:
                raise LLMError("llm_out_of_credits", "OpenRouter credits exhausted")
            if r.status_code == 429 or r.status_code >= 500:
                if attempt == 3:
                    raise LLMError("llm_unavailable", f"HTTP {r.status_code}", retryable=True)
                time.sleep(2**attempt)
                continue
            if r.status_code >= 400:
                # Body may echo the request; log status only.
                log.warning("llm request rejected: HTTP %s", r.status_code)
                raise LLMError("llm_rejected", f"HTTP {r.status_code}")
            data = r.json()
            if "error" in data:
                raise LLMError("llm_error", str(data["error"].get("code", "unknown")), True)
            msg = data["choices"][0]["message"]
            msg = {k: v for k, v in msg.items() if v is not None or k == "content"}
            msg["role"] = "assistant"
            return LLMTurn(message=msg, tool_calls=parse_tool_calls(msg))
        raise LLMError("llm_unavailable", "retries exhausted", retryable=True)


# ---------------------------------------------------------------------------
# FakeLLM: follows the happy path deterministically so the loop can be tested offline.
# ---------------------------------------------------------------------------

_PREF_WORDS = {
    "cheapest": "cheapest",
    "cheap": "cheapest",
    "fastest": "fastest",
    "quickest": "fastest",
    "fast": "fastest",
    "quick": "fastest",
}
_RIDE_WORDS = {"auto": "auto", "rickshaw": "auto", "bike": "bike", "cab": "cab", "car": "cab"}


def parse_request(text: str) -> dict[str, Any] | None:
    t = text.lower().strip()
    m = re.search(r"(?:from\s+)?(.+?)\s+to\s+(.+)", t)
    if m:
        pickup, rest = m.group(1).strip(), m.group(2)
    elif t.startswith("to "):  # "to Andheri station": pickup is where the user is
        pickup, rest = "current location", t[3:]
    else:
        return None
    drop = re.split(r"[,.;!?]", rest)[0].strip()
    words = set(re.findall(r"[a-z]+", rest))
    drop = re.sub(r"\b(cheapest|cheap|fastest|quickest|by|in an?|an?|please)\b.*$", "", drop)
    pref = next((_PREF_WORDS[w] for w in words if w in _PREF_WORDS), "balanced")
    ride = next((_RIDE_WORDS[w] for w in words if w in _RIDE_WORDS), None)
    return {
        "pickup": "current location" if pickup in {"here", "my location"} else pickup,
        "drop": drop.strip(),
        "preference": pref,
        "ride_type": ride,
        "luggage": bool(words & {"luggage", "bags", "suitcase", "suitcases"}),
        "late_night": "late night" in t,
    }


class FakeLLM:
    def __init__(self) -> None:
        self._n = 0

    def _call(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        self._n += 1
        return {
            "id": f"fake_{self._n}",
            "type": "function",
            "function": {"name": name, "arguments": json.dumps(args)},
        }

    def _turn(self, content: str | None, calls: list[dict[str, Any]]) -> LLMTurn:
        msg: dict[str, Any] = {"role": "assistant", "content": content}
        if calls:
            msg["tool_calls"] = calls
        return LLMTurn(message=msg, tool_calls=parse_tool_calls(msg))

    def complete(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]]) -> LLMTurn:
        last_user = max(i for i, m in enumerate(messages) if m["role"] == "user")
        req = parse_request(messages[last_user]["content"])
        if req is None:
            return self._turn("Where should I pick you up, and where are you going?", [])
        names: dict[str, str] = {}
        results: dict[str, Any] = {}
        for m in messages[last_user + 1 :]:
            for tc in m.get("tool_calls") or []:
                names[tc["id"]] = tc["function"]["name"]
            if m["role"] == "tool":
                results.setdefault(names[m["tool_call_id"]], []).append(json.loads(m["content"]))
        tool_names = {t["function"]["name"] for t in tools}

        if "geocode_place" not in results:
            return self._turn(
                None,
                [
                    self._call("geocode_place", {"query": req["pickup"]}),
                    self._call("geocode_place", {"query": req["drop"]}),
                ],
            )
        geos = results["geocode_place"]
        if any("error" in g for g in geos):
            return self._turn("I couldn't find one of those places. Could you rephrase?", [])
        if "request_fare_check" not in results:
            if "request_fare_check" not in tool_names:
                return self._turn("I can't check live fares from this device yet.", [])
            args: dict[str, Any] = {
                "pickup_id": geos[0]["place_id"],
                "drop_id": geos[1]["place_id"],
            }
            if req["ride_type"]:
                args["ride_types"] = [req["ride_type"]]
            return self._turn(None, [self._call("request_fare_check", args)])
        if "rank_options" not in results:
            return self._turn(
                None,
                [
                    self._call(
                        "rank_options",
                        {
                            k: req[k]
                            for k in ("preference", "ride_type", "luggage", "late_night")
                            if req[k] is not None
                        },
                    )
                ],
            )
        ranked = results["rank_options"][-1].get("ranked") or []
        if not ranked:
            return self._turn("None of the apps had a ride that fits. Want me to try again?", [])
        best = ranked[0]["option_id"]
        if "present_options" not in results:
            return self._turn(
                None,
                [
                    self._call(
                        "present_options",
                        {
                            "recommended_option_id": best,
                            "reason": f"Best match for {req['preference']}.",
                        },
                    )
                ],
            )
        return self._turn("Pick an option above, or tell me what to change.", [])
