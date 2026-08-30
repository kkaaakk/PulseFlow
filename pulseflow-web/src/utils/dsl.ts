import type { AudienceCondition, CampaignDsl } from '@/types/api'

export const fieldOptions = [
  { value: 'activeDays7d', label: '近 7 天活跃天数', valueType: 'INTEGER' },
  { value: 'viewCount7d', label: '近 7 天浏览次数', valueType: 'INTEGER' },
  { value: 'searchCount1h', label: '近 1 小时搜索次数', valueType: 'INTEGER' },
  { value: 'spend30d', label: '近 30 天消费金额', valueType: 'DECIMAL' },
  { value: 'orderCount30d', label: '近 30 天订单数', valueType: 'INTEGER' },
  { value: 'daysSinceLastPurchase', label: '距上次购买天数', valueType: 'INTEGER' },
  { value: 'HIGH_VALUE', label: '高价值标签', valueType: 'BOOLEAN' },
  { value: 'PRICE_SENSITIVE', label: '价格敏感标签', valueType: 'BOOLEAN' },
  { value: 'CHURN_RISK', label: '流失风险标签', valueType: 'BOOLEAN' },
]

export const operatorOptions = [
  { value: 'GTE', label: '≥' },
  { value: 'GT', label: '>' },
  { value: 'EQ', label: '=' },
  { value: 'LTE', label: '≤' },
  { value: 'LT', label: '<' },
  { value: 'NE', label: '≠' },
]

export const cloneDsl = (dsl: CampaignDsl): CampaignDsl => structuredClone(dsl)

export const conditionLabel = (condition: AudienceCondition) => {
  const field = fieldOptions.find((option) => option.value === condition.field)
  const operator = operatorOptions.find((option) => option.value === condition.operator)
  return `${field?.label ?? condition.field} ${operator?.label ?? condition.operator} ${condition.value}`
}

export const dslSummary = (dsl: CampaignDsl) => ({
  conditions: dsl.audience?.conditions?.map(conditionLabel) ?? [],
  channel: dsl.channel,
  sendAt: dsl.schedule?.sendAt,
  frequency: `${dsl.frequencyCap?.windowHours ?? 24} 小时最多 ${dsl.frequencyCap?.maxTimes ?? 1} 次`,
})

export const ensureCondition = (condition: AudienceCondition): AudienceCondition => ({
  field: condition.field || 'activeDays7d',
  operator: condition.operator || 'GTE',
  valueType: condition.valueType || 'INTEGER',
  value: condition.value ?? 0,
})
