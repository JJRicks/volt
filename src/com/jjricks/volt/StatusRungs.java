package com.jjricks.volt;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

/**
 * One line's ladder (the owner's rule for cramped text): the full label, then its shorter rungs, then the icon
 * alone (the view's start icon, or one beside it), never a smaller size than the floor or a word cut in two.
 * A shorter rung or the icon alone keeps the full line as the spoken text unless the caller set its own.
 */
final class StatusRungs implements View.OnLayoutChangeListener {
    private static final CharSequence NONE = "";
    final TextView view;
    boolean iconBeside;
    private CharSequence[] rungs = {NONE};
    private CharSequence shown, spoken;
    StatusRungs(TextView view) { this.view = view; }
    void show(CharSequence[] next) {
        rungs = next.length == 0 ? new CharSequence[]{NONE} : next.clone();
        for (int i = 0; i < rungs.length; i++) if (rungs[i] == null) rungs[i] = NONE;
        fit();
    }
    @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        // Text set on the view directly starts a ladder of its own.
        if (shown == null || !TextUtils.equals(shown, view.getText())) rungs = new CharSequence[]{view.getText()};
        fit();
    }
    private void fit() {
        int room = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        boolean icon = iconBeside || view.getCompoundDrawablesRelative()[0] != null;
        CharSequence next = rungs[0];
        if (view.getWidth() > 0 && rungs[0].length() > 0) {
            android.text.TextPaint paint = new android.text.TextPaint(view.getPaint());
            paint.setTextSize(view.getAutoSizeTextType() == TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM
                    ? view.getAutoSizeMinTextSize() : view.getTextSize());
            float[] widths = new float[rungs.length];
            for (int i = 0; i < rungs.length; i++) widths[i] = paint.measureText(rungs[i], 0, rungs[i].length());
            int pick = TileRung.first(widths, room);
            next = pick >= 0 ? rungs[pick] : icon ? NONE : rungs[rungs.length - 1];
        }
        CharSequence description = view.getContentDescription();
        if (description == null || description == spoken) {
            CharSequence full = next == rungs[0] ? null : rungs[0].toString();
            if (description != full) view.setContentDescription(full);
            spoken = full;
        }
        if (!TextUtils.equals(next, view.getText())) view.setText(next);
        shown = view.getText();
    }
}
