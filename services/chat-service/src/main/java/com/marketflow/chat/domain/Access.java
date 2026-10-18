package com.marketflow.chat.domain;

/**
 * What the actor may do in one conversation.
 *
 * @param side     the actor's side, or null for an administrator observing it
 * @param canWrite send, edit, delete, mark read, type
 */
public record Access(Side side, boolean canWrite) {

    public static final Access ADMIN_READ_ONLY = new Access(null, false);

    public boolean participant() {
        return side != null;
    }
}
