package com.marketflow.chat.application;

import com.marketflow.chat.persistence.SupportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Refreshes "usually replies within ..." figures. One instance does the work per round (advisory lock). */
@Component
public class ResponseStatsJob {

    private static final Logger log = LoggerFactory.getLogger(ResponseStatsJob.class);

    private final SupportRepository support;

    public ResponseStatsJob(SupportRepository support) {
        this.support = support;
    }

    @Scheduled(initialDelayString = "${marketflow.chat.stats-initial-delay:30s}",
            fixedDelayString = "${marketflow.chat.stats-interval:15m}")
    @Transactional
    public void refresh() {
        int vendors = support.recomputeResponseStats();
        if (vendors > 0) {
            log.debug("Response statistics updated for {} vendors", vendors);
        }
    }
}
