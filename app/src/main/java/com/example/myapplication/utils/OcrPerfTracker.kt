package com.example.myapplication.utils

import android.util.Log

/**
 * OCR 推理性能度量工具
 * 记录识别耗时、内存占用增量、识别准确率等指标
 */
data class OcrPerfResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val resolutionLabel: String,
    val inferenceTimeMs: Long,
    val memoryDeltaMB: Double,
    val recognizedTextLength: Int,
    val isCompressed: Boolean
)

object OcrPerfTracker {
    private const val TAG = "OCR_PERF"

    private val results = mutableListOf<OcrPerfResult>()

    /**
     * 记录一次 OCR 推理的性能数据
     */
    fun record(result: OcrPerfResult) {
        results.add(result)
        Log.d(TAG, buildString {
            append("=== OCR Performance ===")
            append("\nResolution: ${result.resolutionLabel} (${result.imageWidth}x${result.imageHeight})")
            append("\nCompressed: ${result.isCompressed}")
            append("\nInference Time: ${result.inferenceTimeMs}ms")
            append("\nMemory Delta: ${"%.2f".format(result.memoryDeltaMB)}MB")
            append("\nText Length: ${result.recognizedTextLength} chars")
        })
    }

    /**
     * 打印性能对比表
     */
    fun printSummary() {
        if (results.isEmpty()) {
            Log.d(TAG, "No OCR performance data recorded")
            return
        }

        Log.d(TAG, buildString {
            append("\n")
            append("=" .repeat(80))
            append("\nOCR Performance Summary")
            append("\n" + "=".repeat(80))
            append("\n${"Resolution".padEnd(15)} ${"Compressed".padEnd(12)} ${"Time(ms)".padEnd(12)} ${"Memory(MB)".padEnd(12)} ${"TextLen".padEnd(10)}")
            append("\n${"-".repeat(61)}")

            results.forEach { r ->
                append("\n${r.resolutionLabel.padEnd(15)} ${r.isCompressed.toString().padEnd(12)} ${r.inferenceTimeMs.toString().padEnd(12)} ${"%.2f".format(r.memoryDeltaMB).padEnd(12)} ${r.recognizedTextLength.toString().padEnd(10)}")
            }

            append("\n" + "=".repeat(80))

            // 计算压缩前后的平均耗时对比
            val uncompressed = results.filter { !it.isCompressed }
            val compressed = results.filter { it.isCompressed }
            if (uncompressed.isNotEmpty() && compressed.isNotEmpty()) {
                val avgUncompressed = uncompressed.map { it.inferenceTimeMs }.average()
                val avgCompressed = compressed.map { it.inferenceTimeMs }.average()
                val speedup = ((avgUncompressed - avgCompressed) / avgUncompressed * 100)
                append("\nAvg Uncompressed: ${"%.0f".format(avgUncompressed)}ms")
                append("\nAvg Compressed: ${"%.0f".format(avgCompressed)}ms")
                append("\nSpeedup: ${"%.1f".format(speedup)}%")
            }
        })
    }

    /**
     * 获取所有记录的结果
     */
    fun getResults(): List<OcrPerfResult> = results.toList()

    /**
     * 清除所有记录
     */
    fun clear() {
        results.clear()
    }
}
