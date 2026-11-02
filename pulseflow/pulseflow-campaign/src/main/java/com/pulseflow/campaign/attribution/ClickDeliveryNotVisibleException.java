package com.pulseflow.campaign.attribution;

/** A bounded business visibility retry, distinct from infrastructure exceptions. */
public class ClickDeliveryNotVisibleException extends RuntimeException {
    public ClickDeliveryNotVisibleException() {
        super("DELIVERY_NOT_VISIBLE");
    }
}
