"""Internal PII evidence: code-point spans, provider labels, and policy types.

Never store matched text here or include these objects in public errors/logs.
"""

from dataclasses import dataclass, field, replace

AZURE_TYPES = {
    "Person": "NAME",
    "PhoneNumber": "PHONE",
    "Email": "EMAIL",
    "Address": "POSTAL_ADDRESS",
    "CNResidentId": "GOVERNMENT_ID_CN",
    "CNResidentIdentityCardNumber": "GOVERNMENT_ID_CN",
    "CreditCardNumber": "BANK_CARD",
}
CORE_TYPES = frozenset({"NAME", "PHONE", "EMAIL", "GOVERNMENT_ID_CN", "BANK_CARD"})
VALIDATED_TYPES = frozenset({"PHONE", "GOVERNMENT_ID_CN", "BANK_CARD"})


@dataclass(frozen=True, repr=False)
class PiiEntity:
    start: int
    end: int
    raw_label: str
    normalized_family: str
    score: float
    sources: tuple[str, ...]
    subcategory: str | None = None
    # Preserve each source's original label/span/confidence through deduplication.
    evidence: tuple["PiiEntity", ...] = field(default=(), repr=False)


def overlaps(left: PiiEntity, right: PiiEntity) -> bool:
    return left.start < right.end and right.start < left.end


def blocking_entities(azure: list[PiiEntity], local: list[PiiEntity]) -> list[PiiEntity]:
    """Structured Azure claims require local validation; semantic claims do not."""
    accepted = {
        (e.start, e.end, e.normalized_family): e
        for e in azure
        if e.normalized_family in CORE_TYPES - VALIDATED_TYPES
    }
    for entity in local:
        if entity.normalized_family not in VALIDATED_TYPES:
            continue
        corroborating = [
            e
            for e in azure
            if e.normalized_family == entity.normalized_family and overlaps(e, entity)
        ]
        sources = tuple(
            sorted(set(entity.sources).union(source for e in corroborating for source in e.sources))
        )
        accepted[(entity.start, entity.end, entity.normalized_family)] = replace(
            entity, sources=sources, evidence=tuple(corroborating)
        )
    return sorted(accepted.values(), key=lambda e: (e.start, e.end, e.normalized_family))
