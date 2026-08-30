export const statusLabel: Record<string, string> = {
  ACTIVE: '运行中',
  RUNNING: '运行中',
  DRAFT: '草稿',
  PAUSED: '已暂停',
  CLOSED: '已结束',
  PENDING: '待处理',
  PROCESSING: '处理中',
  SENT: '已发送',
  WAIT_RETRY: '等待重试',
  CANCELLED: '已取消',
  FAILED: '失败',
  PUBLISHED: '已发布',
  SUCCESS: '成功',
  DATA_NOT_READY: '数据未就绪',
  RETRYABLE_FAILED: '可重试失败',
  PERMANENT_FAILED: '永久失败',
  SKIPPED_INSUFFICIENT_DATA: '数据不足已跳过',
  VALIDATED: '已通过校验',
  NEEDS_CONFIRMATION: '待补充确认',
  INVALID: '校验未通过',
  CONFIRMED: '已确认',
  EXPIRED: '已过期',
}

export const statusTone = (status?: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' => {
  switch (status) {
    case 'ACTIVE':
    case 'RUNNING':
    case 'SENT':
    case 'PUBLISHED':
    case 'SUCCESS':
    case 'VALIDATED':
    case 'CONFIRMED':
      return 'success'
    case 'PROCESSING':
    case 'PENDING':
    case 'WAIT_RETRY':
    case 'DATA_NOT_READY':
    case 'RETRYABLE_FAILED':
    case 'NEEDS_CONFIRMATION':
      return 'warning'
    case 'FAILED':
    case 'PERMANENT_FAILED':
    case 'INVALID':
      return 'danger'
    case 'SKIPPED_INSUFFICIENT_DATA':
    case 'PAUSED':
      return 'info'
    default:
      return 'neutral'
  }
}

export const getStatusLabel = (status?: string) => (status ? statusLabel[status] ?? status : '—')
