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


# 有当前证据支持的活动方案，含执行配置和操作员授权优惠事实。
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

    # 验证支持证据 ID 都是 UUID 且不重复，校验通过后返回方案自身。
    @model_validator(mode="after")
    def valid_references(self) -> "CampaignProposal":
        for value in self.supporting_evidence_ids:
            UUID(value)
        if len(set(self.supporting_evidence_ids)) != len(self.supporting_evidence_ids):
            raise ValueError("duplicate supporting evidence")
        return self

    # 提取方案名、理由、人群字段和值及优惠描述，供 PII 防线统一检查。
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

    # 把方案执行配置映射为 Java DSL，理由和证据引用留在方案记录中。
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


# 持久化方案记录，绑定调查、操作员、范围版本及可选 Java 草稿。
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


# 读取方案时返回的记录与当前有效证据 ID 集合，供 Java 再次核验。
class ProposalRead(WireModel):
    record: ProposalRecord
    evidence_ids: list[str]
