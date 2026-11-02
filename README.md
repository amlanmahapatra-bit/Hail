# Hail

One request, every cab, best price. An AI agent that compares Uber, Ola and Rapido on your
phone, recommends the best ride, and books it after one tap.

Status: Week 1. The backend agent loop and the confirmation gate work end to end with a fake
phone. The Android client is next.

## Backend: setup

Needs [uv](https://docs.astral.sh/uv/) (`curl -LsSf https://astral.sh/uv/install.sh | sh`).

```bash
cd backend
uv sync                      # creates .venv and installs everything
cp .env.example .env         # then put your OpenRouter key in OPENROUTER_API_KEY
```

## Run the checks

```bash
cd backend
uv run ruff check . && uv run ruff format --check .
uv run pytest -q             # expect: "33 passed"
```

## Acceptance test: fake phone

```bash
cd backend
uv run python ../tools/fake_phone.py                    # offline rule-based agent, no key needed
uv run python ../tools/fake_phone.py --llm openrouter   # real Gemini via OpenRouter
```
Expected final lines:
```
FINAL: Booked: Rapido bike. You saved ₹15.
PASS: 3 fare checks -> recommendation -> forged confirmation refused -> BOOK
```
The fares are canned test data, not real prices.

## Run the server

```bash
cd backend
uv run uvicorn app.main:app_factory --factory --reload --port 8000
# API docs: http://127.0.0.1:8000/docs
uv run python ../tools/fake_phone.py --base-url http://127.0.0.1:8000
```

To reach it from a phone, use a Cloudflare quick tunnel:
`cloudflared tunnel --url http://localhost:8000` (it prints an `https://….trycloudflare.com` URL).

## Docs
- `docs/protocol.md`: every message type, with examples
- `docs/decisions.md`: why things are the way they are
- `CLAUDE.md`: engineering brief and non-negotiable rules
