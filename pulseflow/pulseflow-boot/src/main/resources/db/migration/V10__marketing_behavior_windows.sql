-- Keep historical events and snapshots. The generated keys are lookups over canonical JSON.
ALTER TABLE user_event
    ADD COLUMN paid_order_id VARBINARY(256) GENERATED ALWAYS AS (
        CASE WHEN event_type = 'ORDER_PAID'
               AND JSON_TYPE(JSON_EXTRACT(properties, '$.orderId')) IN ('STRING', 'INTEGER')
               AND OCTET_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(properties, '$.orderId')))) BETWEEN 1 AND 256
             THEN TRIM(JSON_UNQUOTE(JSON_EXTRACT(properties, '$.orderId'))) END
    ) VIRTUAL,
    ADD COLUMN cart_item_id VARBINARY(256) GENERATED ALWAYS AS (
        CASE WHEN event_type IN ('ADD_CART', 'REMOVE_CART', 'ORDER_PAID')
               AND JSON_TYPE(JSON_EXTRACT(properties, '$.cartItemId')) IN ('STRING', 'INTEGER')
               AND OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(properties, '$.cartItemId'))) BETWEEN 1 AND 256
             THEN JSON_UNQUOTE(JSON_EXTRACT(properties, '$.cartItemId')) END
    ) VIRTUAL;

ALTER TABLE user_event
    ADD KEY idx_user_paid_window (user_id, event_type, effective_event_time, paid_order_id),
    ADD KEY idx_user_paid_order (user_id, paid_order_id, effective_event_time),
    ADD KEY idx_user_cart_mutation (user_id, cart_item_id, id);

-- A set-based amount rollup can exceed the precision of one hourly bucket.
ALTER TABLE user_behavior_summary
    MODIFY COLUMN metric_value DECIMAL(20,2) DEFAULT 0.00,
    ADD KEY idx_metric_calculated_at (metric_type, calculated_at);
