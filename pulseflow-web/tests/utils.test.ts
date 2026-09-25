import { describe, expect, it } from 'vitest'

import { formatCurrency, formatNumber, formatPercent } from '@/utils/format'
import { getStatusLabel, statusTone } from '@/utils/status'

describe('format helpers', () => {
  it('formats KPI numbers and rates for the console', () => {
    expect(formatNumber(128430)).toBe('128,430')
    expect(formatCurrency(880.5)).toContain('880.50')
    expect(formatPercent(0.126)).toBe('12.6%')
  })
})

describe('status mapping', () => {
  it('labels active campaigns and failed deliveries', () => {
    expect(getStatusLabel('ACTIVE')).toBe('运行中')
    expect(statusTone('FAILED')).toBe('danger')
  })
})
