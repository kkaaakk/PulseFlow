# Redis 校验

## 中文说明

Redis 校验器使用源码中的真实 Key，检查事件 processed flag、TTL 和采样用户实时画像。
它不会复制 Lua 或业务算法；缺 Key、TTL 过期或无法连接都会在报告中标记为失败或
`NOT_RUN`，不会当作成功。

`../validate_run.py` 使用 `redis-cli` 和 `RealtimeProfileUpdateService`、`ProfileService`
中的真实 Key：

- 检查 `event:processed:{eventId}` 是否存在以及 TTL 是否为正数；
- 检查采样用户的 `user:rt:{userId}` 是否存在。

Validator 不复制 Lua 逻辑，也不通过第二套实现推导业务结果。缺少 flag、TTL 过期或
实时画像缺失都会报告为失败。默认目标是隔离 Compose Redis：`127.0.0.1:16379`。
