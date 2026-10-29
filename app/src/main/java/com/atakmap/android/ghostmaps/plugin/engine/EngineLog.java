package com.atakmap.android.ghostmaps.plugin.engine;

/** Logging seam so the engine stays plain Java; the plugin routes it to ATAK's log. */
public interface EngineLog {

    void i(String msg);

    void w(String msg, Throwable t);

    EngineLog NONE = new EngineLog() {
        @Override
        public void i(String msg) {
        }

        @Override
        public void w(String msg, Throwable t) {
        }
    };
}
