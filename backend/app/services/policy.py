"""Policy engine stub."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class PolicyResult:
    decision: str
    reason: str


class PolicyEngine:
    """Simple policy evaluator for MVP."""

    def is_allowed(self, action_type: str, *, untrusted_context: bool = False) -> PolicyResult:
        if untrusted_context:
            return PolicyResult(decision="denied", reason="untrusted_context")

        sensitive = {
            "email.send",
            "calendar.create",
            "calendar.update",
            "calendar.delete",
            "settings.update",
        }
        if action_type in sensitive:
            return PolicyResult(decision="requires_approval", reason="sensitive_action")

        return PolicyResult(decision="allowed", reason="safe_action")
