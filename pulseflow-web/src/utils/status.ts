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
  VALIDATED: '已通过校验',
  NEEDS_CONFIRMATION: '待补充确认',
  INVALID: '校验未通过',
  CONFIRMED: '已确认',
  EXPIRED: '已过期',
}

/**
 * 将业务状态映射为标签语义色，未知或空状态使用 neutral。
 */
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
    case 'NEEDS_CONFIRMATION':
      return 'warning'
    case 'FAILED':
    case 'INVALID':
      return 'danger'
    case 'PAUSED':
      return 'info'
    default:
      return 'neutral'
  }
}

/**
 * 查找状态的中文名称，未知状态保留原值，空状态显示缺失占位符。
 */
export const getStatusLabel = (status?: string) => (status ? statusLabel[status] ?? status : '—')
