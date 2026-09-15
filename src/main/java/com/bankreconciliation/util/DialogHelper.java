package com.bankreconciliation.util;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;

/**
 * Utility to show native Windows file dialogs (java.awt.FileDialog)
 * to avoid the OpenJDK Swing JFileChooser bug on Windows
 * (sun.awt.shell.Win32ShellFolder2 NullPointerException).
 */
public class DialogHelper {

    /**
     * Shows an Open File dialog.
     *
     * @param parent Component requesting the dialog
     * @param title Dialog title
     * @param filterPattern File filter pattern (e.g. "*.xls;*.xlsx;*.csv;*.pdf;*.txt")
     * @return Selected File, or null if cancelled
     */
    public static File chooseOpenFile(Component parent, String title, String filterPattern) {
        try {
            Frame frame = getParentFrame(parent);
            FileDialog dialog = new FileDialog(frame, title, FileDialog.LOAD);
            if (filterPattern != null && !filterPattern.isEmpty()) {
                dialog.setFile(filterPattern);
            }
            dialog.setVisible(true);

            String dir = dialog.getDirectory();
            String file = dialog.getFile();
            if (dir != null && file != null) {
                return new File(dir, file);
            }
            return null; // User cancelled
        } catch (Throwable t) {
            // Fallback to Swing JFileChooser if native FileDialog fails
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(title);
            chooser.setFileFilter(new FileNameExtensionFilter("Archivos compatibles", "xls", "xlsx", "csv", "pdf", "txt"));
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                return chooser.getSelectedFile();
            }
            return null;
        }
    }

    /**
     * Shows a Save File dialog.
     *
     * @param parent Component requesting the dialog
     * @param title Dialog title
     * @param defaultFileName Default file name (e.g. "Conciliacion_Bancaria.xlsx")
     * @param defaultExtension Extension to append if missing (e.g. ".xlsx")
     * @return Selected File, or null if cancelled
     */
    public static File chooseSaveFile(Component parent, String title, String defaultFileName, String defaultExtension) {
        try {
            Frame frame = getParentFrame(parent);
            FileDialog dialog = new FileDialog(frame, title, FileDialog.SAVE);
            if (defaultFileName != null) {
                dialog.setFile(defaultFileName);
            }
            dialog.setVisible(true);

            String dir = dialog.getDirectory();
            String file = dialog.getFile();
            if (dir != null && file != null) {
                File target = new File(dir, file);
                if (defaultExtension != null && !target.getName().toLowerCase().endsWith(defaultExtension.toLowerCase())) {
                    target = new File(target.getParentFile(), target.getName() + defaultExtension);
                }
                return target;
            }
            return null;
        } catch (Throwable t) {
            // Fallback to JFileChooser
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(title);
            if (defaultFileName != null) {
                chooser.setSelectedFile(new File(defaultFileName));
            }
            if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                File target = chooser.getSelectedFile();
                if (defaultExtension != null && !target.getName().toLowerCase().endsWith(defaultExtension.toLowerCase())) {
                    target = new File(target.getParentFile(), target.getName() + defaultExtension);
                }
                return target;
            }
            return null;
        }
    }

    private static Frame getParentFrame(Component component) {
        if (component == null) return null;
        Window window = SwingUtilities.getWindowAncestor(component);
        if (window instanceof Frame frame) {
            return frame;
        }
        return null;
    }
}
