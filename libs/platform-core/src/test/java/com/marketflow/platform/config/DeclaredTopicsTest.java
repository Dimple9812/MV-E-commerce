package com.marketflow.platform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeclaredTopicsTest {

    private static Map<String, NewTopic> byName(List<NewTopic> topics) {
        return topics.stream().collect(Collectors.toMap(NewTopic::name, t -> t));
    }

    @Test
    void consumedTopicsAreDeclaredWithFullPartitionCountSoEarlyConsumersSeeAllPartitions() {
        var topics = byName(PlatformAutoConfiguration.declaredTopics(
                new String[] {"identity.user.events", "platform.audit.events"}, new String[] {"vendor.events"},
                new String[0], 3, (short) 1));

        assertThat(topics).containsOnlyKeys("identity.user.events", "platform.audit.events", "vendor.events",
                "vendor.events.dlt");
        assertThat(topics.get("vendor.events").numPartitions()).isEqualTo(3);
        assertThat(topics.get("vendor.events.dlt").numPartitions()).isEqualTo(1);
        assertThat(topics.get("vendor.events.dlt").configs()).containsKey("retention.ms");
    }

    @Test
    void retryTopicConsumersGetTheSourceTopicButNoPlatformDlt() {
        var topics = byName(PlatformAutoConfiguration.declaredTopics(new String[] {"platform.audit.events"},
                new String[] {""}, new String[] {"order.events", " inventory.events "}, 3, (short) 1));

        assertThat(topics).containsOnlyKeys("platform.audit.events", "order.events", "inventory.events");
    }

    @Test
    void aTopicListedTwiceIsDeclaredOnce() {
        var topics = PlatformAutoConfiguration.declaredTopics(new String[] {"order.events"},
                new String[] {"order.events"}, new String[0], 6, (short) 3);

        assertThat(topics).extracting(NewTopic::name).containsExactly("order.events", "order.events.dlt");
        assertThat(topics.get(0).numPartitions()).isEqualTo(6);
        assertThat(topics.get(0).replicationFactor()).isEqualTo((short) 3);
    }
}
