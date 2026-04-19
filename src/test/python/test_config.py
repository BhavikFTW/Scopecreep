import importlib
import pathlib
import sys


def _load_module():
    root = pathlib.Path(__file__).resolve().parents[3] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "config" in sys.modules:
            del sys.modules["config"]
        return importlib.import_module("config")
    finally:
        sys.path.pop(0)


def test_defaults_when_no_env(monkeypatch):
    for key in (
        "SCOPECREEP_SUPABASE_URL",
        "SCOPECREEP_SUPABASE_ANON_KEY",
        "SCOPECREEP_NEBIUS_API_KEY",
        "SCOPECREEP_NEBIUS_BASE_URL",
        "SCOPECREEP_NEBIUS_RESEARCH_MODEL",
    ):
        monkeypatch.delenv(key, raising=False)
    config = _load_module().load()
    assert config.supabase_url is None
    assert config.supabase_anon_key is None
    assert config.nebius_api_key is None
    assert config.nebius_base_url == "https://api.tokenfactory.nebius.com/v1/"
    assert config.nebius_research_model == "google/gemma-3-27b-it"


def test_reads_env(monkeypatch):
    monkeypatch.setenv("SCOPECREEP_SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SCOPECREEP_NEBIUS_RESEARCH_MODEL", "meta-llama/Llama-3.3-70B-Instruct")
    config = _load_module().load()
    assert config.supabase_url == "https://example.supabase.co"
    assert config.nebius_research_model == "meta-llama/Llama-3.3-70B-Instruct"
