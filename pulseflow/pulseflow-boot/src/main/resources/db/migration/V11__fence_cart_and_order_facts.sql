-- V10 stays immutable for databases that have already rehearsed the upgrade.
ALTER TABLE user_event
    ADD COLUMN cart_cancelled TINYINT GENERATED ALWAYS AS (
        IF(event_type IN ('REMOVE_CART', 'ORDER_PAID'), 1, 0)
    ) VIRTUAL,
    DROP KEY idx_user_cart_mutation,
    ADD KEY idx_user_cart_mutation (user_id, cart_item_id, effective_event_time, cart_cancelled, id);

-- Legacy attribution rows keep their original target-event key.
ALTER TABLE attribution_task
    ADD COLUMN paid_order_id VARBINARY(256),
    ADD UNIQUE KEY uk_attribution_paid_order (user_id, paid_order_id);
