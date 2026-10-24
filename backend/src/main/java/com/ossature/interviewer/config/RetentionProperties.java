package com.ossature.interviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Data retention (H21), {@code ossature.retention.*}.
 *
 * @param sessionDays interview sessions - the transcript, diagram, summaries and debrief - are deleted this
 *                    many days after they started; 0 turns the automatic purge off. At least
 *                    {@value #MIN_DAYS}, which is also a floor enforced inside the database (V11), so a
 *                    mistaken setting cannot delete recent interviews.
 */
@ConfigurationProperties(prefix = "ossature.retention")
public record RetentionProperties(@DefaultValue("365") int sessionDays) {

    public static final int MIN_DAYS = 30;

    public RetentionProperties {
        if (sessionDays != 0 && sessionDays < MIN_DAYS) {
            throw new IllegalArgumentException("ossature.retention.session-days must be 0 (off) or at least " + MIN_DAYS);
        }
    }
}
