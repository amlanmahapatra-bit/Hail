from app.store import engine_url


def test_postgres_urls_use_psycopg3():
    assert engine_url("postgres://u:p@h:6543/db") == "postgresql+psycopg://u:p@h:6543/db"
    assert engine_url("postgresql://u:p@h/db") == "postgresql+psycopg://u:p@h/db"


def test_other_urls_unchanged():
    assert engine_url("sqlite:///./hail.db") == "sqlite:///./hail.db"
    assert engine_url("postgresql+psycopg://h/db") == "postgresql+psycopg://h/db"
