import importlib
import pathlib
import sys
from unittest.mock import MagicMock


def _load_research():
    root = pathlib.Path(__file__).resolve().parents[3] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "research" in sys.modules:
            del sys.modules["research"]
        return importlib.import_module("research")
    finally:
        sys.path.pop(0)


def test_research_calls_model_with_instrument_name():
    research = _load_research()
    client = MagicMock()
    client.chat.completions.create.return_value = MagicMock(
        choices=[MagicMock(message=MagicMock(
            content="# Keithley 2400\n\n## Identity\n..."))],
    )
    r = research.Researcher(client=client, model="google/gemma-3-27b-it")
    md = r.draft_profile("Keithley 2400 SMU")
    assert "Keithley 2400" in md
    args = client.chat.completions.create.call_args
    assert args.kwargs["model"] == "google/gemma-3-27b-it"
    messages = args.kwargs["messages"]
    combined = " ".join(m["content"] for m in messages)
    assert "Keithley 2400 SMU" in combined


def test_template_headers_mentioned_in_system_prompt():
    research = _load_research()
    client = MagicMock()
    client.chat.completions.create.return_value = MagicMock(
        choices=[MagicMock(message=MagicMock(content="# X"))]
    )
    r = research.Researcher(client=client, model="google/gemma-3-27b-it")
    r.draft_profile("X")
    system = client.chat.completions.create.call_args.kwargs["messages"][0]["content"]
    for header in ("Identity", "Capabilities", "Pin layout",
                   "Safety limits", "Common operations",
                   "Known gotchas", "Sources"):
        assert header in system
