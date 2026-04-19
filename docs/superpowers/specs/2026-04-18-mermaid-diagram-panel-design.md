# Mermaid Diagram Panel — Design

## Goal

Render a high-level Mermaid circuit diagram inside the Scopecreep tool window
after schematic analysis completes. Users see a simplified description and a
rendered diagram without opening the editor.

## User Flow

1. User adds `.SchDoc` files + context, clicks **Generate** (existing flow).
2. Orchestrator produces a detailed schematic analysis (teammate's work, not
   part of this spec — assumed to return a `String`).
3. A second OpenAI call takes that analysis and produces Mermaid diagram code.
4. The tool window panel updates:
   - **Summary** (simplified description) — top, plain text.
   - **Diagram** — below, rendered via JCEF (embedded Chromium) using
     Mermaid.js.
5. A **View Full Analysis** button opens the raw detailed analysis as a
   scratch Markdown file, on demand only.

## Architecture

```
┌────────────────────────────────────────────┐
│ ScopecreepPanel (Swing)                    │
│                                            │
│  [existing: files, context, Generate]      │
│  ────────────────────────────────────────  │
│  Summary      ← JBTextArea (read-only)     │
│  Diagram      ← JBCefBrowser (JCEF)        │
│  [View Full Analysis]  ← JButton (hidden   │
│                          until generated)  │
└────────────────────────────────────────────┘
             │
             ▼
   MermaidGenerator (new)
   - in:  detailed analysis String
   - out: Pair<summaryText, mermaidCode>
             │
             ▼
   OpenAiClient.generate(prompt)
```

## Components

### 1. `MermaidGenerator` (new, `service/`)
- Single class, single method: `generate(analysis: String): Result`
- `Result` is a sealed class: `Ok(summary: String, mermaid: String)` or
  `Err(message: String)`.
- Calls `OpenAiClient` once with a prompt instructing it to return JSON with
  two fields: `summary` and `mermaid`. Parses the response.
- Runs off the EDT (pooled thread), results marshalled back via
  `SwingUtilities.invokeLater`.

### 2. `MermaidView` (new, `ui/`)
- Wraps a `JBCefBrowser`.
- Public method: `render(mermaidCode: String)`.
- Loads a static HTML shell that imports Mermaid.js from CDN
  (`https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js`) and exposes
  a JS function for setting diagram source. Kotlin side calls it via
  `cefBrowser.executeJavaScript(...)`.
- Has a "not yet generated" placeholder state.

### 3. `ScopecreepPanel` (existing, modified)
- Add fields: `summaryArea: JBTextArea`, `mermaidView: MermaidView`,
  `viewAnalysisButton: JButton`, `currentAnalysis: String?`.
- Layout: existing top section (files + context + Generate) stays; new
  vertical stack below — summary area, then mermaid view, then the "View
  Full Analysis" button.
- On Generate success → hand the analysis to `MermaidGenerator` →
  populate summary + render diagram → show the button.

### 4. `OpenAiClient` dependency
- Assumed to exist (teammate's branch). This spec does not define it.
- If the API differs, `MermaidGenerator` is the only adapter point.

## Data Flow

```
Generate click
  → existing orchestrator → analysis: String
    → MermaidGenerator.generate(analysis)
      → OpenAiClient → JSON { summary, mermaid }
        → panel.onMermaidReady(summary, mermaid)
          → summaryArea.text = summary
          → mermaidView.render(mermaid)
          → viewAnalysisButton.isVisible = true
          → currentAnalysis = analysis
```

"View Full Analysis" click → open scratch Markdown file with
`currentAnalysis` contents (IntelliJ `ScratchRootType`).

## Error Handling

- OpenAI error → status label shows `error: <msg>`. Panel keeps previous
  contents (if any).
- Mermaid parse failure (bad code from the model) → JCEF shows the raw error
  from Mermaid.js. No retry.
- JCEF unavailable (shouldn't happen on 2025.2+) → diagram area shows a
  plain-text fallback: the raw Mermaid code in a read-only text area.

## Testing

- `MermaidGeneratorTest` — unit test with a stubbed OpenAI client: verifies
  JSON parsing (happy path + malformed response).
- Panel smoke test via `BasePlatformTestCase` — instantiate the panel,
  verify components render.
- JCEF rendering cannot be unit tested — manual smoke via `./gradlew runIde`.

## Out of Scope

- Editing Mermaid code inside the panel.
- Exporting the diagram to file.
- Multiple diagrams per session.
- Offline Mermaid (CDN is fine for MVP).
