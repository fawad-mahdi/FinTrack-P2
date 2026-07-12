# FinTracker PK — AI Intelligence Roadmap (v2)

Brainstorm of AI-driven features to differentiate FinTracker PK from competing finance trackers.
The guiding principle: **compound the existing assets** — the parsing pipeline with confidence
scoring, the pending-review queue, merchant category learning, and the declarative Pakistani
bank registry — rather than bolting on a generic chatbot.

---

## Platform Constraint (read first)

Chaquopy on Python 3.9 means **no torch / transformers / onnx on-device**. Every "AI" feature
is therefore a choice between:

- **Statistical / heuristic on-device** — private, free, works offline
- **LLM via API** (Claude, etc.) — powerful, but financial data leaves the device; requires
  redaction + explicit opt-in

A hybrid stance — *"rules first, LLM only on failure, always redacted"* — can itself be a
selling point vs. competitors who ship everything to their servers.

---

## Tier 1 — Compounds the Existing Moat (highest leverage)

### 1. LLM Fallback Parser
When `generic_parser` returns `medium` / `low` / `failed` confidence, send the **redacted**
email body to an LLM to extract amount / direction / merchant / date.

- Shrinks the pending-review queue toward zero
- Every LLM result the user confirms becomes training signal for the heuristics
- Competitors (SMS-parsing apps like Axio) mostly fail silently on weird formats

### 2. Self-Extending Bank Support
The killer feature given the current architecture. `bank_registry.py` is declarative — when an
email from an **unregistered** bank arrives:

1. LLM proposes the registry entry + extraction patterns
2. Auto-test the proposal against the user's actual emails
3. Ship the config without writing code

Users of bank #37 get support automatically. No competitor in the PK market self-heals like this.

### 3. Smart Categorization with Cold-Start
`merchant_categories` currently learns only from user corrections. Add LLM categorization for
**first-seen** merchants ("KHAADI ISB" → Clothing) so day-one users don't face a wall of
"Uncategorized."

- Cache aggressively — each merchant costs one API call *ever*
- Optionally share an anonymized merchant→category map across the user base

---

## Tier 2 — Insight Features (differentiators users can see)

### 4. Subscription & Recurring-Payment Radar
Pure on-device statistics: same normalized merchant + similar amount + regular cadence → flag it.

- "Netflix went from 1,100 to 1,500 PKR"
- "You were charged twice by Careem today" — duplicate-charge detection alone is a
  screenshot-worthy feature

### 5. Safe-to-Spend / Cash-Flow Forecast
Detect salary credits, project known recurring debits, show *"you have X truly free until
the 1st."* Copilot Money's most-loved feature; nobody does it for Pakistani banks.

### 6. Monthly Narrative
LLM-written "Your June in review" — five sentences that actually notice things
(*"dining doubled, but it was mostly that one trip to Lahore"*). Cheap to build once data is
clean; feels magical vs. bar charts.

---

## Tier 3 — Pakistan-Specific Moat (global apps can't follow)

### 7. Local Transaction Intelligence
- Classify Raast / IBFT transfers vs. purchases
- Understand mixed Urdu/English alert text
- Recognize local merchant naming chaos (PSO, petrol stations, EasyPaisa/JazzCash wallet
  loads vs. spends)

### 8. Committee (ROSCA), Zakat & Remittance Awareness
- Track committee contributions as savings, not spending
- Auto-compute zakat-eligible balances
- Tag inbound remittances

Deeply local, deeply sticky.

---

## Tier 4 — Interaction Layer

### 9. Natural-Language Queries
"Kitna kharcha hua food pe June mein?" → text-to-SQL over SQLite.

Nice demo, but ranked below the parsing features — it's table stakes now and only as good as
the underlying data quality, which Tier 1 fixes.

---

## Recommended Sequencing

**#1 → #3 → #4 first.**

- They share infrastructure (one LLM integration + one redaction layer)
- They directly attack the biggest quality lever: parse coverage and category accuracy
- #4 provides a visible headline feature

**#2** is the strategic moat play once #1's plumbing exists.

---

## Open Decisions

1. **Cloud stance** — is sending (redacted) email text to a cloud LLM acceptable with opt-in,
   or is "100% on-device" part of the product identity?
2. **Ambition level** — personal tool / small user base, or a real launch against PK budgeting
   apps? This determines whether #2 (self-extending bank support) is worth the investment.
