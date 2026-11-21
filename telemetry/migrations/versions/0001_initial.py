"""Create the install tracking table.

Revision ID: 0001
Revises:
Create Date: 2026-11-21
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "installs",
        sa.Column("install_id", sa.String(length=36), primary_key=True),
        sa.Column("first_seen", sa.DateTime(), nullable=False),
        sa.Column("last_seen", sa.DateTime(), nullable=False),
        sa.Column("app_version", sa.String(length=32), nullable=False),
        sa.Column("platform", sa.String(length=64), nullable=False),
        sa.Column("deployment", sa.String(length=32), nullable=False),
        sa.Column("schema_version", sa.Integer(), nullable=False, server_default="1"),
    )
    op.create_index("ix_installs_last_seen", "installs", ["last_seen"])
    op.create_index("ix_installs_app_version", "installs", ["app_version"])
    op.create_index("ix_installs_platform", "installs", ["platform"])


def downgrade() -> None:
    op.drop_index("ix_installs_platform", table_name="installs")
    op.drop_index("ix_installs_app_version", table_name="installs")
    op.drop_index("ix_installs_last_seen", table_name="installs")
    op.drop_table("installs")
