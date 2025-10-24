package com.patriotlogger.logger.test;

import com.patriotlogger.logger.data.CalibrationSample;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class MedianRssiHandler extends AbstractRssiHandler {

    private final int window;
    private final Deque<Integer> buf = new ArrayDeque<>();

    public MedianRssiHandler(int window) {
        this.window = Math.max(1, window);
    }

    @Override
    protected void onInit() {
        buf.clear();
    }

    @Override
    protected float smooth(CalibrationSample s) {
        if (buf.size() == window) buf.removeFirst();
        buf.addLast(s.rssi);
        List<Integer> tmp = new ArrayList<>(buf);
        Collections.sort(tmp);
        int mid = tmp.size() / 2;
        if ((tmp.size() & 1) == 1) return tmp.get(mid);
        return (tmp.get(mid - 1) + tmp.get(mid)) / 2.0f;
    }

    @Override
    public String getName() { return "MedianN(N=" + window + ")"; }
}

