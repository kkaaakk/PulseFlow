import { describe, expect, it } from 'vitest'

import { formatCurrency, formatNumber, formatPercent } from '@/utils/format'
import { conditionLabel } from '@/utils/dsl'
import { getStatusLabel, statusTone } from '@/utils/status'

describe('format helpers', () => {
  it('formats KPI numbers and rates for the console', () => {
    expect(formatNumber(128430)).toBe('128,430')
    expect(formatCurrency(880.5)).toContain('880.50')
    expect(formatPercent(0.126)).toBe('12.6%')
  })
})

describe('status mapping', () => {
  it('keeps the full AI review state machine visible', () => {
    expect(getStatusLabel('DATA_NOT_READY')).toBe('数据未就绪')
    expect(statusTone('RETRYABLE_FAILED')).toBe('warning')
    expect(statusTone('PERMANENT_FAILED')).toBe('danger')
    expect(statusTone('SKIPPED_INSUFFICIENT_DATA')).toBe('info')
  })
})

describe('DSL mapping', () => {
  it('maps a backend condition into an operator-readable rule', () => {
    expect(conditionLabel({ field: 'activeDays7d', operator: 'GTE', valueType: 'INTEGER', value: 3 })).toBe('近 7 天活跃天数 ≥ 3')
    expect(conditionLabel({ field: 'HIGH_VALUE', operator: 'EQ', valueType: 'BOOLEAN', value: true })).toBe('高价值标签 = true')
  })
})
