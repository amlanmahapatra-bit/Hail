from app.store import engine_url


def test_postgres_urls_use_psycopg3():
    assert engine_url("postgres://u:p@h:6543/db") == "postgresql+psycopg://u:p@h:6543/db"
    assert engine_url("postgresql://u:p@h/db") == "postgresql+psycopg://u:p@h/db"


def test_other_urls_unchanged():
    assert engine_url("sqlite:///./hail.db") == "sqlite:///./hail.db"
    assert engine_url("postgresql+psycopg://h/db") == "postgresql+psycopg://h/db"


def test_supabase_integration_url_keeps_only_sslmode():
    url = "postgres://u:p@aws-0-ap-south-1.pooler.supabase.com:6543/postgres?sslmode=require&supa=base-pooler.x"
    assert engine_url(url) == (
        "postgresql+psycopg://u:p@aws-0-ap-south-1.pooler.supabase.com:6543/postgres?sslmode=require"
    )


def test_postgres_url_env_is_read(monkeypatch):
    from app.config import Settings

    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("POSTGRES_URL", "postgres://h/db")
    assert Settings(_env_file=None).database_url == "postgres://h/db"
