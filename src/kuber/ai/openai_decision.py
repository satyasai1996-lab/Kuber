"""Backend-only ChatGPT market adviser using the OpenAI Responses API."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime
from enum import Enum
import json
from typing import Any, Callable, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from kuber.models import AnalysisResult, Bias


class ResponseTransport(Protocol):
    def __call__(self, request: Request) -> bytes: ...


def _urlopen(request: Request) -> bytes:
    try:
        with urlopen(request, timeout=30) as response:  # noqa: S310 - fixed OpenAI HTTPS endpoint
            return response.read()
    except HTTPError as error:
        raise RuntimeError(f"OpenAI Responses API returned HTTP {error.code}") from error
    except URLError as error:
        raise RuntimeError("Kuber could not reach the OpenAI Responses API") from error


@dataclass(frozen=True)
class OpenAIDecisionOpinion:
    provider: str
    model: str
    bias: Bias
    confidence: int
    thesis: str
    risk_flags: tuple[str, ...]
    requires_human_review: bool


def _json(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(_json(key)): _json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json(item) for item in value]
    return value


class OpenAIDecisionService:
    """Creates a structured advisory opinion from Kuber's validated snapshot.

    This service cannot submit orders. The seven documented agents and Kuber's
    risk engine remain responsible for analysis and the final execution veto.
    """

    endpoint = "https://api.openai.com/v1/responses"

    def __init__(self, api_key: str | None, model: str = "gpt-5", transport: ResponseTransport = _urlopen) -> None:
        self.api_key = api_key
        self.model = model
        self.transport = transport

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def assess(self, analysis: AnalysisResult) -> OpenAIDecisionOpinion:
        if not self.api_key:
            raise RuntimeError("OpenAI decision service is not configured; set OPENAI_API_KEY on the backend")
        body = {
            "model": self.model,
            "store": False,
            "instructions": (
                "You are Kuber's non-executing market adviser. Use only the supplied, validated market snapshot. "
                "Do not invent live prices, do not request credentials, and do not recommend automatic order placement. "
                "Return a cautious opinion that explicitly identifies uncertainty."
            ),
            "input": json.dumps(self._market_context(analysis), separators=(",", ":")),
            "text": {"format": {"type": "json_schema", "name": "kuber_market_opinion", "strict": True, "schema": {
                "type": "object", "additionalProperties": False,
                "properties": {
                    "bias": {"type": "string", "enum": [item.value for item in Bias]},
                    "confidence": {"type": "integer"},
                    "thesis": {"type": "string"},
                    "risk_flags": {"type": "array", "items": {"type": "string"}},
                    "requires_human_review": {"type": "boolean"},
                },
                "required": ["bias", "confidence", "thesis", "risk_flags", "requires_human_review"],
            }}},
        }
        request = Request(
            self.endpoint,
            data=json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            payload = json.loads(self.transport(request).decode())
            opinion = json.loads(self._output_text(payload))
            confidence = int(opinion["confidence"])
            if not 0 <= confidence <= 100:
                raise ValueError("confidence must be between 0 and 100")
            return OpenAIDecisionOpinion(
                provider="openai",
                model=self.model,
                bias=Bias(opinion["bias"]),
                confidence=confidence,
                thesis=str(opinion["thesis"]),
                risk_flags=tuple(str(flag) for flag in opinion["risk_flags"]),
                requires_human_review=bool(opinion["requires_human_review"]),
            )
        except (KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError("OpenAI returned an invalid structured decision response") from error

    @staticmethod
    def _output_text(payload: dict[str, Any]) -> str:
        for item in payload.get("output", []):
            for content in item.get("content", []):
                if content.get("type") == "output_text" and isinstance(content.get("text"), str):
                    return content["text"]
        raise ValueError("missing output text")

    @staticmethod
    def _market_context(analysis: AnalysisResult) -> dict[str, Any]:
        snapshot = analysis.intelligence.snapshot
        return _json({
            "quote": asdict(analysis.intelligence.quote),
            "gex_snapshot": {
                "snapshot_id": snapshot.snapshot_id, "spot": snapshot.spot, "total_gex": snapshot.total_gex,
                "gamma_flip": snapshot.gamma_flip, "regime": snapshot.regime, "gamma_walls": snapshot.gamma_walls,
                "timestamp": snapshot.timestamp, "source": snapshot.source,
            },
            "seven_agent_results": [asdict(agent) for agent in analysis.agents],
            "scorecard": asdict(analysis.scorecard),
            "debate": asdict(analysis.debate),
            "risk_decision": asdict(analysis.risk),
            "trade_plans": [asdict(plan) for plan in analysis.trade_plans],
            "final_bias": analysis.final_bias,
        })
