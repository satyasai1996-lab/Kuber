from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone

import pytest

from kuber.brokers.zerodha import ZerodhaOAuthConnector
from kuber.instruments import (
    InstrumentCatalog,
    InstrumentCatalogSynchronizer,
    InstrumentSyncError,
    UnusableInstrumentMasterError,
    normalize_zerodha_instruments,
)


T1 = datetime(2026, 8, 26, 9, 15, tzinfo=timezone.utc)
T2 = datetime(2026, 8, 26, 9, 16, tzinfo=timezone.utc)

ZERODHA_ROWS = [
    {
        "instrument_token": 100,
        "tradingsymbol": "RELIANCE",
        "name": "Reliance Industries",
        "expiry": "",
        "strike": 0,
        "tick_size": 0.05,
        "lot_size": 1,
        "instrument_type": "EQ",
        "segment": "NSE",
        "exchange": "NSE",
    },
    {
        "instrument_token": 101,
        "tradingsymbol": "RELIANCE",
        "name": "Reliance Industries",
        "expiry": "",
        "strike": 0,
        "tick_size": 0.05,
        "lot_size": 1,
        "instrument_type": "EQ",
        "segment": "BSE",
        "exchange": "BSE",
    },
    {
        "instrument_token": 102,
        "tradingsymbol": "NIFTY26AUG22000CE",
        "name": "NIFTY",
        "expiry": "2026-08-27",
        "strike": 22_000,
        "tick_size": 0.05,
        "lot_size": 25,
        "instrument_type": "CE",
        "segment": "NFO-OPT",
        "exchange": "NFO",
    },
    {
        "instrument_token": 103,
        "tradingsymbol": "SENSEX26AUG80000CE",
        "name": "SENSEX",
        "expiry": "2026-08-27",
        "strike": 80_000,
        "tick_size": 0.05,
        "lot_size": 20,
        "instrument_type": "CE",
        "segment": "BFO-OPT",
        "exchange": "BFO",
    },
    {
        "instrument_token": 104,
        "tradingsymbol": "CRUDEOIL26SEPFUT",
        "name": "CRUDEOIL",
        "expiry": "2026-09-18",
        "strike": 0,
        "tick_size": 1,
        "lot_size": 100,
        "instrument_type": "FUT",
        "segment": "MCX-FUT",
        "exchange": "MCX",
    },
]


class ControlledKiteClient:
    def __init__(self, rows):
        self.rows = rows
        self.access_token = None
        self.requested_exchanges = []

    def login_url(self):
        return "https://kite.zerodha.com/connect/login?v=3&api_key=test-key"

    def generate_session(self, request_token, api_secret):
        assert request_token == "callback-token"
        assert api_secret == "server-secret"
        return {"access_token": "backend-token", "user_id": "AB1234"}

    def set_access_token(self, access_token):
        self.access_token = access_token

    def instruments(self, exchange=None):
        assert self.access_token == "backend-token"
        self.requested_exchanges.append(exchange)
        return deepcopy([row for row in self.rows if row["exchange"] == exchange])


class StaticConnector:
    def __init__(self, rows):
        self.rows = rows

    def instrument_master(self):
        return deepcopy(self.rows)


def connected_connector(rows=ZERODHA_ROWS):
    client = ControlledKiteClient(rows)
    connector = ZerodhaOAuthConnector(
        "test-key",
        "server-secret",
        client_factory=lambda _: client,
    )
    connector.connect({"request_token": "callback-token"})
    return connector


def sync_good_catalog() -> tuple[InstrumentCatalog, InstrumentCatalogSynchronizer]:
    catalog = InstrumentCatalog()
    synchronizer = InstrumentCatalogSynchronizer(catalog, clock=lambda: T1)
    synchronizer.sync_zerodha(StaticConnector(ZERODHA_ROWS))
    return catalog, synchronizer


def test_sync_requires_connected_provider_and_exposes_unready_status():
    client = ControlledKiteClient(ZERODHA_ROWS)
    connector = ZerodhaOAuthConnector(
        "test-key",
        "server-secret",
        client_factory=lambda _: client,
    )
    catalog = InstrumentCatalog()

    with pytest.raises(InstrumentSyncError, match="load failed"):
        InstrumentCatalogSynchronizer(catalog, clock=lambda: T1).sync_zerodha(connector)

    status = catalog.status()
    assert not status.ready
    assert status.source is None
    assert status.as_of is None
    assert status.version is None
    assert status.item_count == 0
    assert status.last_error == "Zerodha instrument-master load failed (RuntimeError)"


def test_connected_zerodha_sync_preserves_all_market_identities_and_metadata():
    catalog = InstrumentCatalog()
    result = InstrumentCatalogSynchronizer(catalog, clock=lambda: T1).sync_zerodha(
        connected_connector()
    )

    assert result.imported_count == 5
    assert not result.unchanged
    assert result.status.ready
    assert result.status.source == "zerodha"
    assert result.status.as_of == T1
    assert result.status.item_count == 5
    assert result.status.exchanges == ("NSE", "NFO", "BSE", "BFO", "MCX")
    assert result.status.version is not None and len(result.status.version) == 64

    reliance = catalog.search("RELIANCE")
    assert reliance.ready
    assert reliance.source == "zerodha"
    assert reliance.catalog_version == result.status.version
    assert {(item.exchange, item.segment, item.provider_token) for item in reliance.items} == {
        ("NSE", "NSE", "100"),
        ("BSE", "BSE", "101"),
    }
    assert catalog.search("CRUDE", exchanges={"MCX"}).items[0].lot_size == 100


def test_connected_zerodha_fetches_only_supported_exchanges_for_one_snapshot():
    client = ControlledKiteClient(ZERODHA_ROWS)
    connector = ZerodhaOAuthConnector(
        "test-key",
        "server-secret",
        client_factory=lambda _: client,
    )
    connector.connect({"request_token": "callback-token"})

    rows = connector.instrument_master()

    assert client.requested_exchanges == ["NSE", "NFO", "BSE", "BFO", "MCX"]
    assert {row["exchange"] for row in rows} == {"NSE", "NFO", "BSE", "BFO", "MCX"}


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda rows: rows[4].update(tick_size=0), "tick_size"),
        (lambda rows: rows[4].update(segment="NFO-FUT"), "does not belong"),
        (lambda rows: rows[2].update(expiry="not-a-date"), "ISO date"),
    ],
)
def test_invalid_provider_rows_never_displace_the_active_snapshot(mutate, message):
    catalog, _ = sync_good_catalog()
    before = catalog.status()
    bad_rows = deepcopy(ZERODHA_ROWS)
    mutate(bad_rows)

    with pytest.raises(UnusableInstrumentMasterError, match=message):
        InstrumentCatalogSynchronizer(catalog, clock=lambda: T2).sync_zerodha(
            StaticConnector(bad_rows)
        )

    after = catalog.status()
    assert after.ready
    assert (after.version, after.as_of, after.source, after.item_count) == (
        before.version,
        before.as_of,
        before.source,
        before.item_count,
    )
    assert after.last_error is not None


@pytest.mark.parametrize("duplicate_kind", ["token", "tradable_identity"])
def test_duplicate_rows_are_rejected_atomically(duplicate_kind):
    catalog, _ = sync_good_catalog()
    before = catalog.status()
    duplicate_rows = deepcopy(ZERODHA_ROWS)
    if duplicate_kind == "token":
        duplicate_rows[1]["instrument_token"] = duplicate_rows[0]["instrument_token"]
    else:
        duplicate_rows[1]["tradingsymbol"] = "RELIANCE-B"
        duplicate_rows.append(deepcopy(duplicate_rows[1]))
        duplicate_rows[-1]["instrument_token"] = 999

    with pytest.raises(UnusableInstrumentMasterError, match="duplicate"):
        InstrumentCatalogSynchronizer(catalog, clock=lambda: T2).sync_zerodha(
            StaticConnector(duplicate_rows)
        )

    after = catalog.status()
    assert after.version == before.version
    assert after.item_count == before.item_count
    assert catalog.search("RELIANCE").items


def test_incomplete_market_coverage_is_rejected_atomically():
    catalog, _ = sync_good_catalog()
    before = catalog.status()
    without_mcx = [row for row in ZERODHA_ROWS if row["exchange"] != "MCX"]

    with pytest.raises(UnusableInstrumentMasterError, match="missing required exchanges: MCX"):
        InstrumentCatalogSynchronizer(catalog, clock=lambda: T2).sync_zerodha(
            StaticConnector(without_mcx)
        )

    after = catalog.status()
    assert (after.version, after.as_of, after.item_count) == (
        before.version,
        before.as_of,
        before.item_count,
    )


def test_same_snapshot_in_different_order_is_unchanged_and_keeps_original_as_of():
    catalog, _ = sync_good_catalog()
    before = catalog.status()

    result = InstrumentCatalogSynchronizer(catalog, clock=lambda: T2).sync_zerodha(
        StaticConnector(list(reversed(ZERODHA_ROWS)))
    )

    assert result.unchanged
    assert result.status.version == before.version
    assert result.status.as_of == T1
    assert result.status.last_error is None


def test_concurrent_catalog_publication_wins_over_an_older_sync():
    catalog, _ = sync_good_catalog()
    updated_rows = deepcopy(ZERODHA_ROWS)
    updated_rows[0]["tick_size"] = 0.1

    class RacingConnector:
        def instrument_master(self):
            catalog.replace(
                normalize_zerodha_instruments(updated_rows),
                source="zerodha",
                as_of=T2,
            )
            return deepcopy(ZERODHA_ROWS)

    with pytest.raises(InstrumentSyncError, match="changed before"):
        InstrumentCatalogSynchronizer(catalog, clock=lambda: T1).sync_zerodha(
            RacingConnector()
        )

    status = catalog.status()
    assert status.as_of == T2
    assert catalog.search("RELIANCE", exchanges={"NSE"}).items[0].tick_size == 0.1
