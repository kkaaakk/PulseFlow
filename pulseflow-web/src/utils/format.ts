/**
 * 以 zh-CN 分组并按 fractionDigits 固定小数位；null、undefined 和 NaN 显示缺失占位符。
 */
export const formatNumber = (value: number | null | undefined, fractionDigits = 0) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: fractionDigits,
    minimumFractionDigits: fractionDigits,
  }).format(value)
}

/**
 * 以人民币货币样式显示至多两位小数；缺失值和 NaN 使用占位符。
 */
export const formatCurrency = (value: number | null | undefined) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * 将零至一比例乘以一百并保留 digits 位小数，默认一位；缺失值和 NaN 使用占位符。
 */
export const formatPercent = (value: number | null | undefined, digits = 1) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${(value * 100).toFixed(digits)}%`
}

/**
 * 按浏览器本地时区显示月日和时分；空值使用占位符，非法日期保留原始文本。
 */
export const formatDateTime = (value?: string | null) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

/**
 * 按浏览器本地时区显示年月日；空值使用占位符，非法日期保留原始文本。
 */
export const formatDate = (value?: string | null) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}
