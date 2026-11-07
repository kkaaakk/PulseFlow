package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
@Repository
public interface UserEventMapper extends BaseMapper<UserEvent> {
    // A stable order belongs to the window containing its first successful fact.
    // The anti-lookup uses (user_id, paid_order_id, effective_event_time).
    String PAID_WINDOW = """
            FROM user_event e
            WHERE e.event_type = 'ORDER_PAID'
              AND e.effective_event_time >= #{from} AND #{until} > e.effective_event_time
              AND (e.paid_order_id IS NULL OR NOT EXISTS (
                  SELECT 1 FROM user_event earlier
                  WHERE earlier.user_id = e.user_id AND earlier.paid_order_id = e.paid_order_id
                    AND #{from} > earlier.effective_event_time
              ))
            """;

    @Select("<script>SELECT DISTINCT e.user_id " + PAID_WINDOW + """
            AND e.user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectPaidUsers(@Param("userIds") List<Long> userIds,
                               @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM user_event added
                WHERE added.event_id = #{eventId} AND added.user_id = #{userId}
                  AND added.event_type = 'ADD_CART' AND added.cart_item_id = #{cartItemId}
                  AND NOT EXISTS (
                      SELECT 1 FROM user_event newer
                      WHERE newer.user_id = added.user_id AND newer.cart_item_id = added.cart_item_id
                        AND newer.id > added.id
                  )
            )
            """)
    boolean isCurrentCartAddition(@Param("eventId") String eventId, @Param("userId") Long userId,
                                  @Param("cartItemId") String cartItemId);

    @Select("SELECT * FROM user_event WHERE event_id = #{eventId} FOR UPDATE")
    UserEvent selectByEventIdForUpdate(@Param("eventId") String eventId);

    @Update("UPDATE user_event SET marketing_click_status = #{status}, marketing_click_reason = #{reason} "
            + "WHERE event_id = #{eventId}")
    int updateMarketingClick(@Param("eventId") String eventId, @Param("status") String status,
                             @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM user_event WHERE user_id = #{userId} AND event_type = 'CLICK' "
            + "AND marketing_click_status = 'PENDING' "
            + "AND effective_event_time >= #{from} AND effective_event_time < #{to}")
    long countPendingMarketingClicks(@Param("userId") Long userId,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to);
}
