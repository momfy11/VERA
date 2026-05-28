"""add expires_at to sessions; fix first_login for existing users

Revision ID: c4a1d8e2f903
Revises: b3f91c2d4e05
Create Date: 2026-05-28 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c4a1d8e2f903'
down_revision: Union[str, Sequence[str], None] = 'b3f91c2d4e05'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Add expires_at — nullable so native app sessions can opt out of expiry
    op.add_column('sessions', sa.Column('expires_at', sa.DateTime(), nullable=True))

    # Give all existing open sessions a 30-day expiry from now
    op.execute(
        "UPDATE sessions SET expires_at = NOW() + INTERVAL '30 days' "
        "WHERE ended_at IS NULL AND expires_at IS NULL"
    )

    # Backfill: existing users who already have sessions are not first-time users.
    # The b3f91c2d4e05 migration set first_login=TRUE for everyone via DEFAULT.
    op.execute(
        "UPDATE users SET first_login = FALSE "
        "WHERE first_login = TRUE "
        "AND id IN (SELECT DISTINCT user_id FROM sessions)"
    )


def downgrade() -> None:
    op.drop_column('sessions', 'expires_at')
