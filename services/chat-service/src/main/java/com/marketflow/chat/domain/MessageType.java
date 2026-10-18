package com.marketflow.chat.domain;

public enum MessageType {
    TEXT, IMAGE, PRODUCT_CARD, ORDER_CARD, SYSTEM;

    /** Types a person may send; SYSTEM messages are only written by the order-status consumer. */
    public boolean sendableByPeople() {
        return this != SYSTEM;
    }
}
