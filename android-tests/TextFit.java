package com.jjricks.volt.tests;

import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Text fit checks shared by the validations: moved unchanged out of ResponsiveDashboardValidation so
 * LocaleValidation (and later each language's checks) measure text exactly the same way. A line must fit
 * its view without clipping, losing lines or breaking a word, except the lines that ellipsize by design.
 */
final class TextFit {
    private TextFit() {}

    static void collectNarrowTextProblems(TextView text, String type, List<String> problems) {
        if (!text.isShown() || text.length() == 0) return;
        android.text.Layout layout = text.getLayout();
        if (layout == null) { problems.add(type + " has no text layout: " + text.getText()); return; }
        String value = text.getText().toString();
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount(); line++) {
            // Deliberate title/status ellipses have their own full accessibility text.
            if (layout.getEllipsisCount(line) != 0) continue;
            if (layout.getLineMax(line) > width + 2)
                problems.add(type + " clips text horizontally: " + value);
            int end = layout.getLineEnd(line);
            if (line + 1 < layout.getLineCount() && end > 0 && end < value.length()
                    && Character.isLetter(value.charAt(end - 1)) && Character.isLetter(value.charAt(end)))
                problems.add(type + " breaks a word: " + value.substring(0, end) + " / " + value.substring(end));
        }
    }

    /**
     * Lines that ellipsize by design: the Lock bar's one-line "Last verified" detail, and the Lock
     * bar's title, which wraps onto two lines first, once Connect has already collapsed to its 48 dp
     * icon (a cell too narrow for two lines; a phone's full-width bar is budgeted to fit). Every other
     * line must fit.
     */
    static boolean ellipsizedByDesign(Object activity, TextView text) {
        if (text.getEllipsize() == null || text.getMaxLines() > 2) return false;
        for (Object lock : (List<?>)field(activity, "dashboardLocks")) {
            if (!Boolean.TRUE.equals(field(lock, "compact"))) continue;
            if (text == field(lock, "offlineDetail")) return text.getMaxLines() == 1;
            if (text == field(lock, "offlineTitle") && iconOnlyAction((TextView)field(lock, "connectionAction"))) return true;
        }
        return false;
    }
    /** The Lock bar's narrow Connect/Cancel: a 48 dp icon whose text stays for accessibility only. */
    static boolean iconOnlyAction(TextView text) {
        android.text.method.TransformationMethod words = text.getTransformationMethod();
        return words != null && words.getTransformation(text.getText(), text).length() == 0
                && text.getCompoundDrawablesRelative()[0] != null;
    }
    static void assertText(TextView text, View ancestor, String context) { assertText(text, ancestor, context, false); }
    static void assertText(TextView text, View ancestor, String context, boolean mayEllipsize) {
        if (!text.isShown() || text.length() == 0) return;
        android.text.Layout layout = text.getLayout(); require(layout != null && layout.getLineCount() > 0, context + " text has no layout: " + text.getText());
        Rect rect = relativeBounds(text, ancestor);
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= ancestor.getWidth() + 1 && rect.bottom <= ancestor.getHeight() + 1,
                context + " text outside viewport: " + text.getText() + " " + rect);
        require(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2,
                context + " text clipped vertically: " + text.getText());
        require(layout.getLineEnd(layout.getLineCount() - 1) >= text.length(), context + " text lost lines: " + text.getText());
        for (int line = 0; line < layout.getLineCount(); line++) require(mayEllipsize || layout.getEllipsisCount(line) == 0, context + " text ellipsized: " + text.getText());
    }

    static Rect relativeBounds(View view, View ancestor) {
        Rect box = new Rect(0, 0, view.getWidth(), view.getHeight());
        while (view != ancestor) {
            require(view.getParent() instanceof View, "Missing ancestor");
            View parent = (View)view.getParent();
            // A child's internal text scroll does not move its hit target; its parent's scroll does.
            box.offset(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY()); view = parent;
        }
        return box;
    }

    private static Object field(Object target, String name) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Exception error) { throw new IllegalStateException("Field " + name,error); } }
    private static void require(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
