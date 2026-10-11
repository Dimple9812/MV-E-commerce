package com.marketflow.order.domain.saga;

public class IllegalSagaTransitionException extends RuntimeException {
    public IllegalSagaTransitionException(SagaState from, SagaState to) {
        super("Illegal saga transition " + from + " -> " + to);
    }
}
