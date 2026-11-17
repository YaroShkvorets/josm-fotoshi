// License: GPL-2.0-or-later. For details, see LICENSE.
package org.openstreetmap.josm.plugins.fotoshi;

import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/** Adds the Fotoshi panel, which shows the photos in the selected object's image:fotoshi tag. */
public class FotoshiPlugin extends Plugin {
    public FotoshiPlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) newFrame.addToggleDialog(new FotoshiDialog());
    }
}
