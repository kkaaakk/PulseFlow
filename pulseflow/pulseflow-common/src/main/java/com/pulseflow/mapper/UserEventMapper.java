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
    String PAID_FACTS = """
            FROM user_event e
            WHERE e.event_type = 'ORDER_PAID'
              AND e.effective_event_time >= #{from} AND #{until} > e.effective_event_time
            """;

    // A stable order belongs to the window containing its first successful fact.
    String FIRST_PAID_IN_WINDOW = """
            (e.paid_order_id IS NULL OR NOT EXISTS (
                SELECT 1 FROM user_event earlier
                WHERE earlier.user_id = e.user_id AND earlier.paid_order_id = e.paid_order_id
                  AND #{from} > earlier.effective_event_time
            ))
            """;

    @Select("<script>SELECT DISTINCT e.user_id " + PAID_FACTS + " AND " + FIRST_PAID_IN_WINDOW + """
            AND e.user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectPaidUsers(@Param("userIds") List<Long> userIds,
                               @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    String CART_MUTATION_ORDER = " ORDER BY effective_event_time DESC, cart_cancelled DESC, id DESC LIMIT 1 ";

    @Select("""
            SELECT id, event_type, effective_event_time, properties FROM user_event
            WHERE user_id = #{userId} AND cart_item_id = #{cartItemId}
            """ + CART_MUTATION_ORDER)
    UserEvent selectLatestCartMutation(@Param("userId") Long userId, @Param("cartItemId") String cartItemId);

    @Select("""
            SELECT CONVERT(cart_item_id USING utf8mb4) FROM user_event
            WHERE event_id = #{eventId} AND user_id = #{userId} AND event_type = 'ADD_CART'
            """)
    String selectAddedCartItem(@Param("eventId") String eventId, @Param("userId") Long userId);

    @Select("SELECT * FROM user_event WHERE event_id = #{eventId}")
    UserEvent selectCanonicalEvent(@Param("eventId") String eventId);

    @Select("""
            SELECT CONVERT(paid_order_id USING utf8mb4) FROM user_event
            WHERE event_id = #{eventId} AND user_id = #{userId}
            """)
    String selectPaidOrderId(@Param("eventId") String eventId, @Param("userId") Long userId);

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
