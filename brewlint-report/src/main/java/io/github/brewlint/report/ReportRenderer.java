package io.github.brewlint.report;

import io.github.brewlint.core.model.AnalysisResult;

import java.io.IOException;

/**
 * Renders an {@link AnalysisResult} in one output format.
 *
 * <p>Defined in {@code brewlint-core} and implemented in {@code brewlint-report}, so that adding a
 * format never means touching the engine or the finding model. Hito 1 ships the terminal renderer;
 * Hito 5 adds a PDF one; Hito 6 reads JSON through a path of its own.
 *
 * <p>Renderers are presentation only. They must not add findings, reorder them by anything but the
 * order they arrive in, or fail the run: an unreadable report is a bug, not a reason to abandon the
 * analysis.
 */
public interface ReportRenderer {

    /** The format identifier, e.g. {@code terminal} or {@code pdf}. */
    String format();

    /**
     * Writes the report.
     *
     * @param result  the analysis, already sorted by descending severity
     * @param options presentation options
     * @param target  where to write. Terminal and file renderers use this directly.
     * @throws IOException if the destination cannot be written
     */
    void render(AnalysisResult result, ReportOptions options, Appendable target) throws IOException;
}
