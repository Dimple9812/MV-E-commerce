package com.marketflow.chat.domain;

/** Which side of a conversation a person is on. Admins are never a side: they only observe. */
public enum Side {
    BUYER, VENDOR;

    public Side other() {
        return this == BUYER ? VENDOR : BUYER;
    }
}
