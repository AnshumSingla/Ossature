package com.ossature.interviewer.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback conversion word {@code %mmsg}: the formatted log message, with {@link Masking} applied. */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return Masking.mask(super.convert(event));
    }
}
