package com.ossature.interviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossature.interviewer.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerPolicyTest {

    private static OwnerPolicy policy(String... owners) {
        var auth = new AppProperties.Auth("s".repeat(40), Duration.ofHours(1), List.of(owners));
        return new OwnerPolicy(new AppProperties(null, null, null, auth, null, null));
    }

    @Test
    void matchesRegardlessOfCaseOrPadding() {
        OwnerPolicy p = policy("Owner@X.com");
        assertThat(p.isOwner("owner@x.com")).isTrue();
        assertThat(p.isOwner("  OWNER@X.COM ")).isTrue();
    }

    @Test
    void otherAddressesAreNotOwners() {
        OwnerPolicy p = policy("owner@x.com");
        assertThat(p.isOwner("owner@x.com.evil.io")).isFalse();
        assertThat(p.isOwner("xowner@x.com")).isFalse();
        assertThat(p.isOwner(null)).isFalse();
    }

    @Test
    void nobodyIsOwnerWhenNoneConfigured() {
        assertThat(policy().isOwner("owner@x.com")).isFalse();
    }
}
