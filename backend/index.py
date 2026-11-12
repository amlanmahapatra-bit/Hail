"""Entry point for Vercel's Python runtime, which serves the module-level `app`."""

from app.main import app_factory

app = app_factory()
