package com.scopecreep.ui

import com.scopecreep.service.JsonFields
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Measurements view. The session report exposes per-probe scope stats
 * (v_min/max/mean/pp/rms). This panel renders them as a table with a small
 * hand-rolled sparkline cell. No per-sample waveforms are streamed today;
 * adding a full waveform viewer requires new SSE endpoints on the backend.
 *
 * Wired by [AgentSessionPanel.onResult]; call [loadReport] with the raw JSON
 * returned from GET /agent/sessions/{id}/report.
 */
class WaveformPanel : JPanel(BorderLayout()) {

    private val columns = arrayOf("Probe", "Net", "Verdict", "v_min", "v_max", "v_mean", "v_pp", "v_rms", "trend")

    private val model = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JTable(model).apply {
        rowHeight = 24
    }

    private val status = JLabel("No measurements yet. Run an agent session.")

    init {
        add(status, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)

        val verdictRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                val bg = when (value?.toString()?.uppercase()) {
                    "PASS" -> Color(0x1f, 0x55, 0x1f)
                    "FAIL" -> Color(0x70, 0x1f, 0x1f)
                    "MARGINAL" -> Color(0x66, 0x55, 0x1f)
                    else -> null
                }
                background = bg ?: (if (isSelected) t!!.selectionBackground else t!!.background)
                return c
            }
        }
        table.columnModel.getColumn(2).cellRenderer = verdictRenderer

        val sparkCol = table.columnModel.getColumn(columns.size - 1)
        sparkCol.preferredWidth = 120
        sparkCol.cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                @Suppress("UNCHECKED_CAST")
                val samples = value as? DoubleArray ?: DoubleArray(0)
                return Spark(samples)
            }
        }
    }

    /** Render the JSON returned by GET /agent/sessions/{id}/report. */
    fun loadReport(reportJson: String) {
        model.rowCount = 0
        val resultsArr = JsonFields.objectField(reportJson, "results") ?: return
        val rows = splitTopLevelObjects(resultsArr)
        rows.forEach { obj ->
            val probe = JsonFields.stringField(obj, "probe_point").orEmpty()
            val net = JsonFields.stringField(obj, "net").orEmpty()
            val verdict = JsonFields.stringField(obj, "verdict").orEmpty()
            val meas = JsonFields.objectField(obj, "measurements") ?: "{}"
            val vmin = JsonFields.stringField(meas, "v_min") ?: numberField(meas, "v_min")
            val vmax = JsonFields.stringField(meas, "v_max") ?: numberField(meas, "v_max")
            val vmean = JsonFields.stringField(meas, "v_mean") ?: numberField(meas, "v_mean")
            val vpp = JsonFields.stringField(meas, "v_pp") ?: numberField(meas, "v_pp")
            val vrms = JsonFields.stringField(meas, "v_rms") ?: numberField(meas, "v_rms")
            val spark = doubleArrayOf(
                vmin?.toDoubleOrNull() ?: 0.0,
                vmean?.toDoubleOrNull() ?: 0.0,
                vmax?.toDoubleOrNull() ?: 0.0,
            )
            model.addRow(arrayOf(probe, net, verdict, vmin ?: "—", vmax ?: "—", vmean ?: "—", vpp ?: "—", vrms ?: "—", spark))
        }
        status.text = "Loaded ${model.rowCount} measurement(s)."
    }

    private fun numberField(json: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json) ?: return null
        return m.groupValues[1]
    }

    private fun splitTopLevelObjects(arr: String): List<String> {
        if (arr.isBlank() || arr.first() != '[') return emptyList()
        val out = mutableListOf<String>()
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for (i in arr.indices) {
            val c = arr[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(arr.substring(start, i + 1)); start = -1 } }
            }
        }
        return out
    }

    /** Tiny line renderer: draws min/mean/max as a 3-point polyline. */
    private class Spark(private val samples: DoubleArray) : JPanel() {
        init { preferredSize = Dimension(120, 20); isOpaque = true; background = Color(0x22, 0x22, 0x22) }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (samples.isEmpty()) return
            val g2 = g as Graphics2D
            val min = samples.min(); val max = samples.max()
            val range = (max - min).coerceAtLeast(1e-9)
            val w = width; val h = height
            val xs = IntArray(samples.size) { (it.toDouble() / (samples.size - 1).coerceAtLeast(1) * (w - 4)).toInt() + 2 }
            val ys = IntArray(samples.size) { (h - 2 - ((samples[it] - min) / range) * (h - 4)).toInt() }
            g2.color = Color(0x58, 0xa6, 0xff)
            for (i in 1 until samples.size) g2.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i])
        }
    }
}
