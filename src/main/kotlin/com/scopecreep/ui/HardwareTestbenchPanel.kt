package com.scopecreep.ui

import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Pillar 2 — Hardware Testbench.
 *
 * Groups firmware job management and the instrument-profile database under
 * one tool-window tab. Sub-tabs keep the two concerns visually distinct
 * since they back different Supabase tables.
 */
class HardwareTestbenchPanel : JPanel(BorderLayout()) {

    init {
        val tabs = JBTabbedPane().apply {
            addTab("Firmware jobs", FirmwarePanel())
            addTab("Instrument database", ProfilesPanel().root)
        }
        add(tabs, BorderLayout.CENTER)
    }
}
