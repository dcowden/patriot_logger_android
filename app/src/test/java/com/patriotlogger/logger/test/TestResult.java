package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.TagStatus.TagStatusState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stores first-seen timestamps for transitions; use "X" when missing. */
public class TestResult {
    private final Map<TagStatusState, Long> firstSeen = new LinkedHashMap<>();

    public void record(TagStatusState state, long ts) {
        firstSeen.putIfAbsent(state, ts);
    }

    public String getOrX(TagStatusState s) {
        Long v = firstSeen.get(s);
        return (v == null) ? "X" : Long.toString(v);
    }
}
