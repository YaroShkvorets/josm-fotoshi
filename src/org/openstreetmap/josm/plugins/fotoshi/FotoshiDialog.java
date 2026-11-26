// License: GPL-2.0-or-later. For details, see LICENSE.
package org.openstreetmap.josm.plugins.fotoshi;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ObjIntConsumer;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager.FireMode;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * A panel showing the photos in the selected object's image:fotoshi tag (photo ids separated by ;), one at a
 * time, straight from Fotoshi's CDN: no key needed. A click on the photo opens its page on fotoshi.com; the mouse
 * wheel zooms in and out, and dragging moves a zoomed-in photo. Move to front makes the shown photo the first in the
 * tag, and Remove takes it out of the tag, as edits that can be undone.
 */
public class FotoshiDialog extends ToggleDialog implements DataSelectionListener {
    private static final String TAG = "image:fotoshi";
    private static final String IMAGE = "https://cdn.fotoshi.com/images/%s-1280.jpeg";
    private static final String PAGE = "https://fotoshi.com/photo/%s";
    private static final int CACHED = 30;

    private final JLabel caption = new JLabel(" ", SwingConstants.CENTER);
    private final StepAction previous = new StepAction(-1, "previous", "Previous photo");
    private final StepAction next = new StepAction(1, "next", "Next photo");
    private final OpenAction open = new OpenAction();
    private final ToFrontAction toFront = new ToFrontAction();
    private final RemoveAction remove = new RemoveAction();
    private final Photo photo = new Photo(() -> open.actionPerformed(null));
    // the tag can change under us: an edit in the tag editor, Move to front, Remove, or an undo
    private final DataSetListenerAdapter changes = new DataSetListenerAdapter(e -> showPhotosOfSelection());

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fotoshi-photos");
        t.setDaemon(true);
        return t;
    });
    // the most recently shown photos, by id
    private final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > CACHED;
        }
    };

    private OsmPrimitive source; // the object the photos are from
    private List<String> ids = new ArrayList<>();
    private int index;
    private int request; // so a photo that comes in late doesn't replace a newer one

    public FotoshiDialog() {
        super("Fotoshi", "fotoshi", "Photos in the selected object's image:fotoshi tag",
                Shortcut.registerShortcut("subwindow:fotoshi", "Toggle: Fotoshi", KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                250);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(photo, BorderLayout.CENTER);
        panel.add(caption, BorderLayout.SOUTH);
        Collection<SideButton> buttons = Arrays.asList(new SideButton(previous), new SideButton(next), new SideButton(toFront),
                new SideButton(remove), new SideButton(open));
        createLayout(panel, false, buttons);
        showPhotosOf(List.of());
    }

    @Override
    public void showNotify() {
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this);
        DatasetEventManager.getInstance().addDatasetListener(changes, FireMode.IN_EDT_CONSOLIDATED);
        showPhotosOfSelection();
    }

    @Override
    public void hideNotify() {
        SelectionEventManager.getInstance().removeSelectionListener(this);
        DatasetEventManager.getInstance().removeDatasetListener(changes);
    }

    @Override
    public void destroy() {
        super.destroy();
        loader.shutdownNow();
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        showPhotosOf(event.getSelection());
    }

    private void showPhotosOfSelection() {
        DataSet data = MainApplication.getLayerManager().getActiveDataSet();
        showPhotosOf(data == null ? List.of() : data.getSelected());
    }

    /** Shows the photos of the first selected object that has the tag. */
    private void showPhotosOf(Collection<? extends OsmPrimitive> selection) {
        OsmPrimitive before = source;
        List<String> found = List.of();
        source = null;
        for (OsmPrimitive p : selection) {
            found = idsIn(p.get(TAG));
            if (!found.isEmpty()) {
                source = p;
                break;
            }
        }
        if (found.equals(ids) && !ids.isEmpty()) { // the same photos again: stay on the one shown
            updateActions();
            return;
        }
        String shown = ids.isEmpty() ? null : ids.get(index);
        ids = found;
        if (source != null && source == before) {
            // the same object, its tag edited: stay on the photo shown, or show the one now in its place
            int at = ids.indexOf(shown);
            index = at >= 0 ? at : Math.min(index, ids.size() - 1);
        } else {
            index = 0;
        }
        load();
    }

    private static boolean isId(String s) {
        return s.matches("[A-Za-z0-9_-]+");
    }

    /** The photo ids in a tag value, in order. */
    private static List<String> idsIn(String value) {
        List<String> found = new ArrayList<>();
        if (value != null) for (String part : value.split(";")) if (isId(part.trim())) found.add(part.trim());
        return found;
    }

    /**
     * Edits the source's tag, as a command that can be undone. The edit gets the ;-separated parts of the value,
     * trimmed and without empty ones, and where the shown photo is among them; anything that isn't a photo id stays.
     * The tag is removed when no part is left.
     */
    private void editTag(ObjIntConsumer<List<String>> edit) {
        List<String> parts = new ArrayList<>();
        for (String part : source.get(TAG).split(";")) if (!part.trim().isEmpty()) parts.add(part.trim());
        for (int i = 0, seen = -1; i < parts.size(); i++) {
            if (isId(parts.get(i)) && ++seen == index) {
                edit.accept(parts, i);
                String value = parts.isEmpty() ? null : String.join(";", parts);
                // the dataset listener then shows the photos as they are now
                UndoRedoHandler.getInstance().add(new ChangePropertyCommand(source, TAG, value));
                return;
            }
        }
    }

    private boolean editable() {
        return source != null && source.getDataSet() != null && !source.getDataSet().isLocked();
    }

    private void updateActions() {
        previous.setEnabled(ids.size() > 1);
        next.setEnabled(ids.size() > 1);
        open.setEnabled(!ids.isEmpty());
        toFront.setEnabled(index > 0 && editable());
        remove.setEnabled(!ids.isEmpty() && editable());
    }

    private void load() {
        int mine = ++request;
        boolean any = !ids.isEmpty();
        updateActions();
        if (!any) {
            photo.display(null, "Select an object with " + TAG);
            caption.setText(" ");
            return;
        }
        String id = ids.get(index);
        caption.setText((ids.size() > 1 ? (index + 1) + " / " + ids.size() + " · " : "") + "Fotoshi · click to open");
        BufferedImage cached = cache.get(id);
        if (cached != null) {
            photo.display(cached, null);
            return;
        }
        photo.display(null, "Loading…");
        loader.submit(() -> {
            BufferedImage image = null;
            String error = null;
            try {
                image = fetch(id);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            BufferedImage got = image;
            String problem = error;
            GuiHelper.runInEDT(() -> {
                if (got != null) cache.put(id, got);
                if (mine == request) photo.display(got, got == null ? "Could not load photo " + id + ": " + problem : null);
            });
        });
    }

    private static BufferedImage fetch(String id) throws IOException {
        HttpClient.Response response = HttpClient.create(URI.create(String.format(IMAGE, id)).toURL())
                .setConnectTimeout(15000).setReadTimeout(30000).connect();
        try {
            if (response.getResponseCode() != 200) throw new IOException("HTTP " + response.getResponseCode());
            try (InputStream in = response.getContent()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) throw new IOException("not an image");
                return image;
            }
        } finally {
            response.disconnect();
        }
    }

    private class StepAction extends AbstractAction {
        private final int by;

        StepAction(int by, String icon, String tooltip) {
            this.by = by;
            putValue(SHORT_DESCRIPTION, tooltip);
            new ImageProvider("dialogs", icon).getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (ids.size() < 2) return;
            index = (index + by + ids.size()) % ids.size();
            load();
        }
    }

    private class ToFrontAction extends AbstractAction {
        ToFrontAction() {
            putValue(NAME, "Move to front");
            putValue(SHORT_DESCRIPTION, "Make this the first photo in the object's " + TAG + " tag");
            new ImageProvider("dialogs", "first").getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (isEnabled()) editTag((parts, at) -> parts.add(0, parts.remove(at)));
        }
    }

    private class RemoveAction extends AbstractAction {
        RemoveAction() {
            putValue(NAME, "Remove");
            putValue(SHORT_DESCRIPTION, "Remove this photo from the object's " + TAG + " tag");
            new ImageProvider("dialogs", "delete").getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (isEnabled()) editTag((parts, at) -> parts.remove(at));
        }
    }

    private class OpenAction extends AbstractAction {
        OpenAction() {
            putValue(NAME, "Open");
            putValue(SHORT_DESCRIPTION, "Open the photo's page on fotoshi.com");
            new ImageProvider("help", "internet").getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (!ids.isEmpty()) OpenBrowser.displayUrl(String.format(PAGE, ids.get(index)));
        }
    }

    /**
     * The photo, scaled to fit and centred; or a line of text when there is none. The mouse wheel zooms in and out
     * around the pointer, dragging moves a zoomed-in photo, and a click (not a drag) runs onClick.
     */
    private static class Photo extends JLabel {
        private static final double MAX_SCALE = 4; // screen pixels per photo pixel, when zoomed all the way in
        private static final double STEP = 1.25; // zoom per wheel notch

        private final Runnable onClick;
        private BufferedImage image;
        private double zoom = 1; // 1 fits the whole photo
        private double cx, cy; // the photo pixel at the panel's centre
        private Point dragFrom;
        private double dragCx, dragCy;
        private boolean dragged;

        Photo(Runnable onClick) {
            this.onClick = onClick;
            setHorizontalAlignment(SwingConstants.CENTER);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragFrom = e.getPoint();
                    dragCx = cx;
                    dragCy = cy;
                    dragged = false;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (image == null || dragFrom == null || !dragged && dragFrom.distance(e.getPoint()) < 3) return;
                    dragged = true;
                    double s = scale();
                    cx = dragCx - (e.getX() - dragFrom.x) / s;
                    cy = dragCy - (e.getY() - dragFrom.y) / s;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!dragged) onClick.run();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    // shift + wheel, and a sideways swipe on a trackpad, scroll sideways: not a zoom
                    if (image != null && !e.isShiftDown()) zoomAt(e.getPoint(), Math.pow(STEP, -e.getPreciseWheelRotation()));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void display(BufferedImage image, String text) {
            this.image = image;
            zoom = 1;
            setText(text == null ? null : "<html><div style='text-align:center'>" + text + "</div></html>");
            setToolTipText(image == null ? null : "Click to open the photo's page on fotoshi.com; scroll to zoom, drag to move");
            setCursor(Cursor.getPredefinedCursor(image == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            repaint();
        }

        /** Zooms by a factor, keeping the photo pixel under p where it is. */
        private void zoomAt(Point p, double by) {
            keepInView();
            double dx = p.x - getWidth() / 2.0;
            double dy = p.y - getHeight() / 2.0;
            double s = scale();
            double x = cx + dx / s;
            double y = cy + dy / s;
            zoom = Math.max(1, Math.min(zoom * by, MAX_SCALE / fit()));
            s = scale();
            cx = x - dx / s;
            cy = y - dy / s;
            repaint();
        }

        /** Screen pixels per photo pixel, at zoom 1. */
        private double fit() {
            return Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
        }

        private double scale() {
            return fit() * zoom;
        }

        /** Moves the centre so the photo fills the panel, or is centred on an axis where it is smaller. */
        private void keepInView() {
            double s = scale();
            cx = centre(cx, image.getWidth(), getWidth() / s);
            cy = centre(cy, image.getHeight(), getHeight() / s);
        }

        /** Where to centre a view span photo pixels long, as near c as keeps it on a photo size pixels long. */
        private static double centre(double c, double size, double span) {
            return span >= size ? size / 2 : Math.max(span / 2, Math.min(c, size - span / 2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (image == null) {
                super.paintComponent(g);
                return;
            }
            if (getWidth() == 0 || getHeight() == 0) return;
            keepInView();
            double s = scale();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, (int) Math.round(getWidth() / 2.0 - cx * s), (int) Math.round(getHeight() / 2.0 - cy * s),
                    (int) Math.round(image.getWidth() * s), (int) Math.round(image.getHeight() * s), null);
            g2.dispose();
        }
    }
}
