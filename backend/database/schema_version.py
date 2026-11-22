"""
KHATAWAS Backend - Schema Version Definitions
"""

from typing import NamedTuple
from datetime import datetime

class SchemaVersion(NamedTuple):
    version: int
    description: str
    applied_at: datetime

