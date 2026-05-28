"""add first_login to users

Revision ID: b3f91c2d4e05
Revises: 47ef6902e297
Create Date: 2026-05-27 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b3f91c2d4e05'
down_revision: Union[str, Sequence[str], None] = '47ef6902e297'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('users', sa.Column('first_login', sa.Boolean(), nullable=False, server_default=sa.text('true')))


def downgrade() -> None:
    op.drop_column('users', 'first_login')
