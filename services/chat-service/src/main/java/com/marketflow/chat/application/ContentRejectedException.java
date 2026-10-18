package com.marketflow.chat.application;

import com.marketflow.platform.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Set;

/**
 * A message was refused by the contact-information policy. Thrown out of transactions declared with
 * {@code noRollbackFor = ContentRejectedException.class}, so the recorded violation commits while nothing else
 * was written.
 */
public class ContentRejectedException extends ApiException {

    private final Set<?> violations;

    public ContentRejectedException(Set<?> violations) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "contact_info_not_allowed",
                "For your safety, keep contact details and payments on MarketFlow. Remove phone numbers, e-mail "
                        + "addresses and payment links, then send again.");
        this.violations = violations;
    }

    public Set<?> violations() {
        return violations;
    }
}
