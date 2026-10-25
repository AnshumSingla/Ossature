package com.ossature.interviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cost controls for the paid model calls (C6), {@code ossature.budget.*}. Each candidate utterance and each diagram
 * review is one "turn". A value of 0 turns that limit off.
 *
 * @param userDailyTurns   turns one person may use per rolling 24 hours (a long interview is a few hundred)
 * @param globalHourlyTurns turns the whole service may use per hour - a circuit breaker that bounds the bill if
 *                          something (a bug, a bot farm of real accounts) multiplies usage
 */
@ConfigurationProperties(prefix = "ossature.budget")
public record BudgetProperties(@DefaultValue("1500") int userDailyTurns, @DefaultValue("5000") int globalHourlyTurns) {}
