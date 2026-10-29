package com.atakmap.android.ghostmaps.plugin.engine;

import com.atakmap.android.ghostmaps.plugin.core.DeviceState;

import java.io.File;
import java.io.IOException;

/**
 * Everything the engine needs from ATAK, behind one interface so the engine
 * is plain Java and testable without a device. {@code AtakPlatform} is the
 * real implementation.
 *
 * <p>All methods are called on the engine's state thread. Implementations
 * hop to the UI thread themselves where ATAK requires it. Requests are
 * fire-and-forget; the engine confirms results through {@link #probe()}.</p>
 */
public interface Platform {

    /** What ATAK has loaded now, or null if it can't be read at the moment. */
    DeviceState probe();

    /** Asks ATAK's KML importer to load {@code file} where it is. */
    void importMapLayer(File file, boolean notifyUser);

    /** Asks ATAK to unload a map layer it has loaded; ATAK deletes the file. */
    void removeMapLayer(File file);

    /** Asks ATAK to add the icon set in {@code zip} (it copies the icons in). */
    void installIconSet(File zip);

    /** Asks ATAK to delete an icon set. */
    void removeIconSet(String uid);

    /** The UID ATAK will give the icon set in {@code zip}. */
    String iconSetUid(File zip, String declaredUid) throws IOException;

    /** Canonical form of a path, for comparing with {@link DeviceState}. */
    String canonical(File file);
}
