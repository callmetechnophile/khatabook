"""
KHATAWAS Backend - SurrealDB Migration Runner
Executes versioned .surql migrations in sequential order with status tracking and rollback limitations.
"""

import os
import re
from pathlib import Path
from typing import List, Tuple
from datetime import datetime, timezone
from surrealdb import Surreal

MIGRATIONS_DIR = Path(__file__).parent / "migrations"

class MigrationError(Exception):
    """Raised when a database migration fails."""
    pass

class MigrationRunner:
    def __init__(self, db: Surreal, migrations_dir: Path = MIGRATIONS_DIR):
        self.db = db
        self.migrations_dir = migrations_dir

    def _ensure_schema_version_table(self):
        """Bootstrap the schema_version table if not yet present."""
        bootstrap_query = (
            "DEFINE TABLE IF NOT EXISTS schema_version SCHEMAFULL; "
            "DEFINE FIELD IF NOT EXISTS version ON TABLE schema_version TYPE int; "
            "DEFINE FIELD IF NOT EXISTS description ON TABLE schema_version TYPE string; "
            "DEFINE FIELD IF NOT EXISTS applied_at ON TABLE schema_version TYPE datetime DEFAULT time::now(); "
            "DEFINE INDEX IF NOT EXISTS idx_schema_version ON TABLE schema_version COLUMNS version UNIQUE;"
        )
        self.db.query(bootstrap_query)

    def get_applied_versions(self) -> List[int]:
        """Fetch list of migration versions already applied to the database."""
        self._ensure_schema_version_table()
        res = self.db.query("SELECT version FROM schema_version ORDER BY version ASC;")
        applied = []
        if res and isinstance(res, list):
            for row in res:
                # Handle varying response shapes in SurrealDB python SDK
                if isinstance(row, dict) and "result" in row:
                    results = row["result"]
                    if isinstance(results, list):
                        for item in results:
                            if isinstance(item, dict) and "version" in item:
                                applied.append(int(item["version"]))
                elif isinstance(row, dict) and "version" in row:
                    applied.append(int(row["version"]))
        return sorted(list(set(applied)))

    def get_available_migrations(self) -> List[Tuple[int, str, Path]]:
        """Discover and sort available .surql migration files."""
        if not self.migrations_dir.exists():
            return []
        
        migrations = []
        for file in self.migrations_dir.glob("*.surql"):
            match = re.match(r"^(\d+)_([a-zA-Z0-9_\-]+)\.surql$", file.name)
            if match:
                version = int(match.group(1))
                description = match.group(2).replace("_", " ")
                migrations.append((version, description, file))
        
        return sorted(migrations, key=lambda x: x[0])

    def apply_pending_migrations(self) -> List[int]:
        """Execute all unapplied migrations in strictly ascending numerical order."""
        applied_versions = set(self.get_applied_versions())
        available = self.get_available_migrations()
        newly_applied = []

        for version, description, path in available:
            if version in applied_versions:
                continue

            content = path.read_text(encoding="utf-8")
            # Strip comments and execute
            clean_statements = []
            for line in content.splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("--") or stripped.startswith("//"):
                    continue
                clean_statements.append(stripped)

            clean_sql = " ".join(clean_statements)
            if not clean_sql:
                continue

            try:
                # Execute migration statements
                self.db.query(clean_sql)
                
                # Record successful migration in schema_version table
                record_version_sql = (
                    f"CREATE schema_version CONTENT {{ "
                    f"version: {version}, "
                    f"description: '{description}', "
                    f"applied_at: time::now() "
                    f"}};"
                )
                self.db.query(record_version_sql)
                newly_applied.append(version)
            except Exception as e:
                raise MigrationError(f"Migration {version:03d} ({description}) failed: {e}") from e

        return newly_applied

    def reset_development_database(self, confirm: bool = False, environment: str = "development") -> bool:
        """
        Development-only database reset procedure.
        Strictly prohibited in production.
        """
        if environment.lower() == "production" or not confirm:
            raise PermissionError("Database reset is strictly prohibited in production and requires explicit confirmation.")

        # Drop tables in development
        tables = [
            "conflict_record", "audit_event", "chat_message", "conversation",
            "sync_receipt", "device_sync_state", "event_chain_head", "ledger_event",
            "device_registry", "customer_relationship", "customer_alias",
            "customer", "shop_member", "shop", "app_user", "schema_version"
        ]
        for tbl in tables:
            try:
                self.db.query(f"REMOVE TABLE IF EXISTS {tbl};")
            except Exception:
                pass
        return True

