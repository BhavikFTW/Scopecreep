import importlib
import pathlib
import sys
from unittest.mock import MagicMock


def _load_memory():
    root = pathlib.Path(__file__).resolve().parents[3] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "memory" in sys.modules:
            del sys.modules["memory"]
        return importlib.import_module("memory")
    finally:
        sys.path.pop(0)


def _mock_supabase(return_value):
    sb = MagicMock()
    chain = sb.table.return_value
    chain.select.return_value = chain
    chain.insert.return_value = chain
    chain.update.return_value = chain
    chain.eq.return_value = chain
    chain.ilike.return_value = chain
    chain.or_.return_value = chain
    chain.limit.return_value = chain
    chain.single.return_value = chain
    chain.execute.return_value = MagicMock(data=return_value)
    return sb


def test_recall_returns_profile():
    memory = _load_memory()
    sb = _mock_supabase({
        "id": "abc",
        "kind": "device",
        "slug": "analog-discovery-rev-c",
        "title": "AD",
        "content": "# AD",
        "status": "published",
        "version": 1,
    })
    store = memory.ProfileStore(sb)
    profile = store.recall("analog-discovery-rev-c")
    assert profile is not None
    assert profile.slug == "analog-discovery-rev-c"
    assert profile.status == "published"


def test_recall_returns_none_when_not_found():
    memory = _load_memory()
    sb = _mock_supabase(None)
    store = memory.ProfileStore(sb)
    assert store.recall("nope") is None


def test_search_returns_list():
    memory = _load_memory()
    sb = _mock_supabase([
        {"id": "1", "kind": "device", "slug": "a", "title": "A", "content": "x",
         "status": "published", "version": 1},
        {"id": "2", "kind": "device", "slug": "b", "title": "B", "content": "y",
         "status": "published", "version": 1},
    ])
    store = memory.ProfileStore(sb)
    results = store.search("a", limit=5)
    assert len(results) == 2
    assert results[0].slug == "a"


def test_remember_inserts_draft():
    memory = _load_memory()
    sb = _mock_supabase([{"id": "new-id"}])
    store = memory.ProfileStore(sb)
    new_id = store.remember(memory.Profile(
        id=None, kind="device", slug="x", title="X",
        content="# X", status="draft", version=1,
    ))
    assert new_id == "new-id"
    sb.table.assert_called_with("profiles")


def test_publish_flips_status():
    memory = _load_memory()
    sb = _mock_supabase([{"id": "abc", "status": "published"}])
    store = memory.ProfileStore(sb)
    store.publish("abc")
    sb.table.assert_called_with("profiles")
