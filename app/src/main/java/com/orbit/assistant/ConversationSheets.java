package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * The temporary Orbit sheets behind Conversation Control's quiet indicators (0.8.3.0-beta.3+):
 * the context-window details, what is kept in the chat, and the Continue in new chat confirmation.
 *
 * <p>Each opens only on request and shows amounts and names, never Orbit's instructions, a hidden
 * prompt, or anything else a user did not put into the chat themselves.
 */
final class ConversationSheets {
    private ConversationSheets() {}

    // ---- context window --------------------------------------------------------------------------

    /**
     * The context details. {@code onContinue} is offered when the chat has something to continue
     * from; null leaves the action out.
     */
    

    static AlertDialog showContext(Activity a, ContextEstimate estimate, Runnable onContinue) {
        LinearLayout body = column(a);
        if (estimate == null) {
            body.addView(note(a, a.getString(R.string.convsheet_measuring)));
        } else {
            TextView headline = UiKit.text(a, estimate.knowsLimit()
                    ? a.getString(R.string.convsheet_tokens_of, ContextEstimate.approx(estimate.tokens),
                            ContextEstimate.exact(estimate.limit))
                    : a.getString(R.string.convsheet_tokens, ContextEstimate.approx(estimate.tokens)), 18, UiKit.TEXT, true);
            body.addView(headline);
            String sub = estimate.auto ? a.getString(R.string.convsheet_auto_line)
                    : estimate.knowsLimit()
                    ? (estimate.modelName.isEmpty()
                    ? a.getString(R.string.convsheet_used, estimate.percent())
                    : a.getString(R.string.convsheet_used_model, estimate.percent(), estimate.modelName))
                    : (estimate.modelName.isEmpty() ? a.getString(R.string.convsheet_window_unknown)
                    : a.getString(R.string.convsheet_model_window_unknown, estimate.modelName));
            TextView subline = UiKit.text(a, sub, 13, UiKit.MUTED, false);
            subline.setPadding(0, UiKit.dp(a, 2), 0, UiKit.dp(a, 10));
            body.addView(subline);
            if (estimate.knowsLimit()) {
                ProgressBar bar = UiKit.horizontalProgress(a);
                bar.setMax(1000);
                bar.setProgress(Math.max(estimate.tokens > 0 ? 6 : 0,
                        Math.round(estimate.fraction() * 1000)));
                bar.setProgressTintList(ColorStateList.valueOf(
                        ContextMeterView.colorFor(a, estimate.level() == ContextEstimate.Level.NORMAL
                                ? ContextEstimate.Level.FILLING : estimate.level())));
                body.addView(bar, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(a, 6)));
            }
            Map<ContextLedger.Category, Integer> rows = estimate.breakdown;
            if (!rows.isEmpty()) {
                View gap = new View(a);
                body.addView(gap, new LinearLayout.LayoutParams(1, UiKit.dp(a, 10)));
                for (Map.Entry<ContextLedger.Category, Integer> row : rows.entrySet()) {
                    body.addView(row(a, row.getKey().label(a), ContextEstimate.approx(row.getValue())));
                }
            }
            StringBuilder notes = new StringBuilder(a.getString(R.string.convsheet_estimated));
            if (estimate.olderMessagesNotSent > 0) {
                notes.append(' ').append(a.getResources().getQuantityString(
                        R.plurals.convsheet_older_not_sent, estimate.olderMessagesNotSent,
                        estimate.olderMessagesNotSent));
            }
            if (estimate.auto) {
                notes.append(' ').append(a.getString(R.string.convsheet_note_auto));
            } else if (estimate.fittedByProvider) {
                notes.append(' ').append(a.getString(R.string.convsheet_note_local));
            } else if (!estimate.knowsLimit()) {
                notes.append(' ').append(a.getString(R.string.convsheet_note_unknown));
            }
            TextView footnote = note(a, notes.toString());
            footnote.setPadding(0, UiKit.dp(a, 12), 0, 0);
            body.addView(footnote);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.convsheet_title))
                .setView(scroll(a, body))
                .setPositiveButton(R.string.ui_done_11a6767d, null);
        if (onContinue != null) builder.setNeutralButton(a.getString(R.string.convsheet_continue_new), (d, w) -> onContinue.run());
        AlertDialog dialog = builder.create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
        return dialog;
    }

    // ---- kept in this chat -----------------------------------------------------------------------

    interface KeptActions {
        void remove(KeptContext item);
    }

    /** What this chat keeps, each item inspectable and removable. Rebuilt in place on removal. */
    static AlertDialog showKept(Activity a, List<KeptContext> items, KeptActions actions) {
        LinearLayout body = column(a);
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.convsheet_kept_title))
                .setView(scroll(a, body))
                .setPositiveButton(R.string.ui_done_11a6767d, null)
                .create();
        fillKept(a, body, new java.util.ArrayList<>(items), actions, dialog);
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
        return dialog;
    }

    private static void fillKept(Activity a, LinearLayout body, List<KeptContext> items,
                                 KeptActions actions, AlertDialog dialog) {
        body.removeAllViews();
        if (items.isEmpty()) {
            body.addView(note(a, a.getString(R.string.convsheet_nothing_kept)));
            return;
        }
        body.addView(note(a, a.getString(R.string.convsheet_kept_info)));
        for (KeptContext item : items) {
            LinearLayout line = new LinearLayout(a);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, UiKit.dp(a, 8), 0, UiKit.dp(a, 2));
            LinearLayout text = new LinearLayout(a);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = UiKit.text(a, item.label, 14, UiKit.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(name);
            text.addView(UiKit.text(a, a.getString(R.string.convsheet_kept_line, item.typeLabel(a),
                    ContextEstimate.approx(ContextLedger.tokensFor(item.text))), 12, UiKit.MUTED, false));
            text.setContentDescription(a.getString(R.string.convsheet_kept_desc, item.label, item.typeLabel(a)));
            text.setOnClickListener(v -> inspect(a, item));
            text.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(a), 10, a));
            line.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton remove = new ImageButton(a);
            remove.setImageResource(R.drawable.ic_close);
            remove.setColorFilter(UiKit.MUTED);
            remove.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(a), 14, a));
            int pad = UiKit.dp(a, 13);
            remove.setPadding(pad, pad, pad, pad);
            remove.setContentDescription(a.getString(R.string.convsheet_stop_keeping, item.label));
            remove.setOnClickListener(v -> {
                actions.remove(item);
                items.remove(item);
                if (items.isEmpty()) dialog.dismiss();
                else fillKept(a, body, items, actions, dialog);
            });
            line.addView(remove, new LinearLayout.LayoutParams(UiKit.dp(a, 44), UiKit.dp(a, 44)));
            body.addView(line);
        }
    }

    /** A read-only look at what an item will send. */
    private static void inspect(Activity a, KeptContext item) {
        String text = item.text.trim();
        String preview = text.length() > 1600 ? text.substring(0, 1600).trim() + "…" : text;
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(item.label)
                .setMessage(item.typeLabel(a) + "\n\n" + preview)
                .setPositiveButton(R.string.ui_done_11a6767d, null)
                .create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
    }

    // ---- continue in new chat --------------------------------------------------------------------

    /** Says plainly what Continue in new chat does before it does it. */
    static void confirmContinue(Activity a, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.convsheet_continue_q))
                .setMessage(a.getString(R.string.convsheet_continue_msg))
                .setNegativeButton(R.string.ui_cancel_19766ed6, null)
                .setPositiveButton(R.string.ui_continue_31fbef16, (d, w) -> onConfirm.run())
                .create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
    }

    // ---- pieces ----------------------------------------------------------------------------------

    private static LinearLayout column(Activity a) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(a, 22), UiKit.dp(a, 6), UiKit.dp(a, 22), UiKit.dp(a, 4));
        return body;
    }

    private static ScrollView scroll(Activity a, View content) {
        ScrollView scroll = new ScrollView(a);
        scroll.addView(content);
        return scroll;
    }

    private static LinearLayout row(Activity a, String label, String value) {
        LinearLayout line = new LinearLayout(a);
        line.setPadding(0, UiKit.dp(a, 4), 0, UiKit.dp(a, 4));
        line.addView(UiKit.text(a, label, 14, UiKit.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(UiKit.text(a, value, 14, UiKit.TEXT, false));
        return line;
    }

    private static TextView note(Activity a, String text) {
        TextView note = UiKit.text(a, text, 12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.12f);
        return note;
    }
}
