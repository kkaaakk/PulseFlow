package com.pulseflow.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证三种触发类型的 dedup_key 格式。
 *
 * <p>dedup_key 是 delivery_task.uk_dedup 的防重基础，格式错误会导致：
 * <ul>
 *   <li>不同活动/用户撞键 → 误判重复，漏发触达；</li>
 *   <li>同一逻辑重复键失效 → 重复发送。</li>
 * </ul>
 * 格式由设计文档 §附录 dedup_key 生成规则固定。</p>
 */
class DedupKeyUtilTest {

    @Test
    @DisplayName("EVENT 触发: {campaignId}:{userId}:{eventId}")
    void forEvent_format() {
        assertEquals("5:1024:evt_001",
                DedupKeyUtil.forEvent(5L, 1024L, "evt_001"));
    }

    @Test
    @DisplayName("DELAYED 触发: {campaignId}:{userId}:{cartItemId}:{addCartEventId}")
    void forDelayed_format() {
        assertEquals("5:1024:ci_501:evt_100",
                DedupKeyUtil.forDelayed(5L, 1024L, "ci_501", "evt_100"));
    }

    @Test
    @DisplayName("SCHEDULED 触发: {campaignExecutionId}:{userId}")
    void forScheduled_format() {
        assertEquals("42:1024",
                DedupKeyUtil.forScheduled(42L, 1024L));
    }

    @Test
    @DisplayName("同一参数多次调用结果一致（ZADD 去重依赖此性质）")
    void forDelayed_deterministic() {
        String k1 = DedupKeyUtil.forDelayed(5L, 1024L, "ci_501", "evt_100");
        String k2 = DedupKeyUtil.forDelayed(5L, 1024L, "ci_501", "evt_100");
        assertEquals(k1, k2, "same params must produce same key for ZADD dedup");
    }

    @Test
    @DisplayName("不同 cartItemId 产生不同 key（避免不同购物车项撞键）")
    void forDelayed_distinctCartItem() {
        String k1 = DedupKeyUtil.forDelayed(5L, 1024L, "ci_501", "evt_100");
        String k2 = DedupKeyUtil.forDelayed(5L, 1024L, "ci_502", "evt_100");
        assert !k1.equals(k2) : "different cartItemId must yield different key";
    }

    @Test
    @DisplayName("工具类不可实例化")
    void utilityClass_notInstantiable() {
        assertThrows(Exception.class, () -> {
            // 反射绕过 private 构造器应抛 UnsupportedOperationException
            var ctor = DedupKeyUtil.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }
}
