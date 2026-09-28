"""Evidence-backed proposals can create Java drafts but cannot execute campaigns."""

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import Field, model_validator

from pulseflow_agent.domain.contracts import (
    AudienceGroup,
    CampaignDsl,
    CampaignSchedule,
    FrequencyCap,
    PromotionFact,
    WireModel,
)


class CampaignProposal(WireModel):
    campaign_name: str = Field(min_length=1, max_length=128)
    objective: Literal["CONVERSION", "RETENTION", "ACTIVATION", "BRANDING"]
    rationale: str = Field(min_length=1, max_length=2000)
    target_audience: AudienceGroup
    channel: Literal["IN_APP", "PUSH", "EMAIL"]
    schedule: CampaignSchedule
    frequency_cap: FrequencyCap
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)
    supporting_evidence_ids: list[str] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def valid_references(self) -> "CampaignProposal":
        for value in self.supporting_evidence_ids:
            UUID(value)
        if len(set(self.supporting_evidence_ids)) != len(self.supporting_evidence_ids):
            raise ValueError("duplicate supporting evidence")
        return self

    def free_text(self) -> list[str]:
        values = [self.campaign_name, self.rationale]
        for condition in self.target_audience.conditions:
            values.append(condition.field)
            if isinstance(condition.value, str):
                values.append(condition.value)
        for fact in self.promotion_facts:
            if fact.description:
                values.append(fact.description)
        return values

    def to_dsl(self) -> CampaignDsl:
        return CampaignDsl(
            campaign_name=self.campaign_name,
            objective=self.objective,
            audience=self.target_audience,
            channel=self.channel,
            schedule=self.schedule,
            frequency_cap=self.frequency_cap,
            promotion_facts=self.promotion_facts,
        )


class ProposalRecord(WireModel):
    id: str
    investigation_id: str
    owner_id: int | None = Field(default=None, gt=0)
    proposal: CampaignProposal
    status: Literal["GENERATED", "DRAFT_CREATED", "SUPERSEDED", "CANCELLED"]
    draft_id: int | None = None
    scope_version: int
    created_at: datetime
    updated_at: datetime


class ProposalRead(WireModel):
    record: ProposalRecord
    evidence_ids: list[str]
