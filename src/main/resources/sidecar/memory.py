"""Profile store — thin wrapper over supabase-py for the memory layer."""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any


@dataclass
class Profile:
    id: str | None
    kind: str
    slug: str
    title: str
    content: str
    status: str = "draft"
    version: int = 1

    def to_row(self) -> dict[str, Any]:
        row = asdict(self)
        if row["id"] is None:
            row.pop("id")
        return row


class ProfileStore:
    """Read/write profiles via Supabase."""

    def __init__(self, supabase_client):
        self.sb = supabase_client

    def recall(self, slug: str) -> Profile | None:
        res = (
            self.sb.table("profiles")
            .select("*")
            .eq("slug", slug)
            .eq("status", "published")
            .limit(1)
            .single()
            .execute()
        )
        data = res.data
        if not data:
            return None
        return self._row_to_profile(data)

    def search(self, query: str, limit: int = 5) -> list[Profile]:
        res = (
            self.sb.table("profiles")
            .select("*")
            .eq("status", "published")
            .or_(f"title.ilike.%{query}%,slug.ilike.%{query}%,content.ilike.%{query}%")
            .limit(limit)
            .execute()
        )
        rows = res.data or []
        return [self._row_to_profile(r) for r in rows]

    def remember(self, profile: Profile) -> str:
        # Upsert so re-researching the same instrument updates in place
        # instead of tripping the unique (kind, slug, version) index.
        res = (
            self.sb.table("profiles")
            .upsert(profile.to_row(), on_conflict="kind,slug,version")
            .execute()
        )
        rows = res.data or []
        if not rows:
            raise RuntimeError("upsert returned no rows (RLS rejected?)")
        return rows[0]["id"]

    def publish(self, profile_id: str) -> None:
        self.sb.table("profiles").update({"status": "published"}).eq("id", profile_id).execute()

    @staticmethod
    def _row_to_profile(row: dict[str, Any]) -> Profile:
        return Profile(
            id=row.get("id"),
            kind=row.get("kind", "device"),
            slug=row["slug"],
            title=row["title"],
            content=row["content"],
            status=row.get("status", "draft"),
            version=row.get("version", 1),
        )
