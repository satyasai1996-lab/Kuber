from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from kuber.api.app import KuberServices, create_app
from kuber.instruments import InstrumentCatalog, normalize_zerodha_instruments


ROWS = [
    {"instrument_token": 256265, "exchange_token": 1001, "tradingsymbol": "NIFTY 50", "name": "NIFTY 50", "last_price": 0, "expiry": "", "strike": 0, "tick_size": 0.05, "lot_size": 1, "instrument_type": "EQ", "segment": "INDICES", "exchange": "NSE"},
    {"instrument_token": 101, "exchange_token": 2001, "tradingsymbol": "NIFTY26AUG22000CE", "name": "NIFTY", "last_price": 0, "expiry": "2026-08-27", "strike": 22000, "tick_size": 0.05, "lot_size": 25, "instrument_type": "CE", "segment": "NFO-OPT", "exchange": "NFO"},
    {"instrument_token": 202, "exchange_token": 3001, "tradingsymbol": "SENSEX26AUG80000CE", "name": "SENSEX", "last_price": 0, "expiry": "2026-08-27", "strike": 80000, "tick_size": 0.05, "lot_size": 20, "instrument_type": "CE", "segment": "BFO-OPT", "exchange": "BFO"},
    {"instrument_token": 303, "exchange_token": 4001, "tradingsymbol": "CRUDEOIL26SEPFUT", "name": "CRUDEOIL", "last_price": 0, "expiry": "2026-09-18", "strike": 0, "tick_size": 1, "lot_size": 100, "instrument_type": "FUT", "segment": "MCX-FUT", "exchange": "MCX"},
]


def catalogue() -> InstrumentCatalog:
    result = InstrumentCatalog()
    result.replace(normalize_zerodha_instruments(ROWS), as_of=datetime(2026, 8, 26, tzinfo=timezone.utc))
    return result


def test_search_preserves_exchange_and_derivative_identity():
    result = catalogue().search("SENSEX", exchanges={"BFO"})
    assert len(result.items) == 1
    assert result.items[0].instrument_id == "zerodha:202"
    assert result.items[0].segment == "BFO-OPT"
    assert result.items[0].option_type == "CE"


def test_search_filters_mcx_and_never_invents_a_price():
    result = catalogue().search("CRUDE", exchanges={"MCX"}, instrument_types={"FUT"})
    assert len(result.items) == 1
    assert result.items[0].tradingsymbol == "CRUDEOIL26SEPFUT"
    assert not hasattr(result.items[0], "last_price")


def test_catalogue_rejects_duplicates_and_invalid_limits():
    item = normalize_zerodha_instruments(ROWS[:1])[0]
    with pytest.raises(ValueError, match="duplicate"):
        InstrumentCatalog().replace((item, item))
    with pytest.raises(ValueError, match="limit"):
        catalogue().search("", limit=0)


def test_versioned_api_searches_nse_bfo_and_mcx():
    services = KuberServices()
    services.instruments = catalogue()
    client = TestClient(create_app(services=services))

    response = client.get("/api/v1/instruments/search", params={"q": "SENSEX", "exchanges": "BFO"})
    assert response.status_code == 200
    body = response.json()
    assert len(body["catalog_version"]) == 64
    assert body["items"][0]["instrument_id"] == "zerodha:202"

    mcx = client.get("/api/v1/instruments/search", params={"q": "CRUDE", "exchanges": "MCX", "types": "FUT"})
    assert mcx.status_code == 200
    assert mcx.json()["items"][0]["exchange"] == "MCX"
