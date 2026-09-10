// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;

import java.util.Objects;

/**
 * Tells the model when its budget is nearly spent.
 *
 * <p>Without this, a run that exhausts its tokens mid-task fails with nothing to show; with it,
 * the model is told once the budget passes a threshold that it should finish rather than start
 * something new. A prompt amendment, which is the one kind of request change a hook may make.
 */
public final class BudgetNoticeHook implements LoopHook {

    public static final String ID = "budget-notice";

    private final double threshold;

    public BudgetNoticeHook(double threshold) {
        if (threshold <= 0 || threshold >= 1) {
            throw new IllegalArgumentException("threshold must be between 0 and 1 exclusive");
        }
        this.threshold = threshold;
    }

    public BudgetNoticeHook() {
        this(0.8);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Outcome<ModelRequest> beforeModel(HookContext context, ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (context.budgetUsed() < threshold) {
            return Outcome.proceed(request);
        }
        int percent = (int) Math.round(context.budgetUsed() * 100);
        String notice = "\n\n## Budget notice\nAbout " + percent + "% of this run's token budget is spent. "
                + "Finish the task with what you have rather than starting new work; if it cannot be "
                + "finished, say what remains.";
        return Outcome.proceed(new ModelRequest(request.model(), request.system() + notice, request.messages(),
                request.tools(), request.maxTokens(), request.temperature()));
    }
}
