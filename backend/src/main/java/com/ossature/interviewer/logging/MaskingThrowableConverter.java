package com.ossature.interviewer.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback conversion word {@code %mex}: the stack trace (message lines included), with {@link Masking} applied. */
public class MaskingThrowableConverter extends ThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return Masking.mask(super.convert(event));
    }
}
