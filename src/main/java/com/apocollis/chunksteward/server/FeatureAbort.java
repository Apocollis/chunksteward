package com.apocollis.chunksteward.server;

/**
 * Thrown inside a feature job when it does something a worker cannot do safely (reads or writes outside its window,
 * places a tile entity, spawns an entity, schedules a tick). An {@link Error}, not an exception, so a decoration's
 * own {@code catch (Exception)} does not swallow it. The job runner also checks {@link FeatureScope#abortReason()},
 * which is set before the throw, in case something caught it anyway. Carries no stack trace.
 */
final class FeatureAbort extends Error {
    private static final long serialVersionUID = 1L;

    FeatureAbort(String reason) {
        super(reason, null, false, false);
    }
}
