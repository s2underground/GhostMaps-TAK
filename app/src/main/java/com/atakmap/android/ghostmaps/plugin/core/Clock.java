package com.atakmap.android.ghostmaps.plugin.core;

/** Time source, injectable so time-dependent rules can be tested. */
public interface Clock {

    long now();

    Clock SYSTEM = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };
}
