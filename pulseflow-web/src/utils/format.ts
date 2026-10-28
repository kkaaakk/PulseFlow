/** 按中文分组和指定小数位格式化数字，缺失或 NaN 返回占位符。 */
export const formatNumber = (value: number | null | undefined, fractionDigits = 0) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: fractionDigits,
    minimumFractionDigits: fractionDigits,
  }).format(value)
}

/** 按人民币及两位小数格式化金额，缺失或 NaN 返回占位符。 */
export const formatCurrency = (value: number | null | undefined) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(value)
}

/** 将零至一口径的比率转换为指定精度的百分比文本。 */
export const formatPercent = (value: number | null | undefined, digits = 1) => {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${(value * 100).toFixed(digits)}%`
}

/** 解析并以中文月日时分展示时间，无效输入保留原文，缺失显示占位符。 */
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

/** 按中文年月日显示日期，无效输入保留原文。 */
export const formatDate = (value?: string | null) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}
