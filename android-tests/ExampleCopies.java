package com.jjricks.volt.tests;

import com.jjricks.volt.DashboardConfig;
import java.lang.reflect.Method;
import java.util.List;

/**
 * What a duplicate of a dashboard widget shows, for DashboardSlotsValidation. AllWidgetsDashboard and Msg are
 * package-private in the app, so this reaches them by reflection.
 */
final class ExampleCopies {
    private ExampleCopies() {}

    /** Null when every copy of the example's widgets keeps its words and an owner's stays the owner's. */
    @SuppressWarnings("unchecked")
    static String problem() throws Exception {
        for (DashboardConfig.Widget widget : (List<DashboardConfig.Widget>) example("create", null)) {
            DashboardConfig.Widget copy = (DashboardConfig.Widget) example("emptyCopy", widget);
            copy.title = widget.title;
            copy.note = widget.note;
            if (!copy.type.equals(widget.type) || copy.id.equals(widget.id))
                return "A copy of " + widget.type + " is not the same type with its own id";
            if (!words(example("exampleTitle", copy)).equals(words(example("exampleTitle", widget)))
                    || !words(example("exampleNote", copy)).equals(words(example("exampleNote", widget))))
                return "A copy of the example's " + widget.type + " does not show its original's words";
        }
        DashboardConfig.Widget mine = new DashboardConfig.Widget("note");
        mine.note = "Check tyre pressure";
        DashboardConfig.Widget copy = (DashboardConfig.Widget) example("emptyCopy", mine);
        copy.note = mine.note;
        if (example("exampleNote", copy) != null || (Boolean) example("isExample", copy))
            return "A copy of the owner's widget became the example's";
        return null;
    }

    private static Object example(String name, DashboardConfig.Widget widget) throws Exception {
        Class<?> type = Class.forName("com.jjricks.volt.AllWidgetsDashboard");
        Method method = widget == null ? type.getDeclaredMethod(name) : type.getDeclaredMethod(name, DashboardConfig.Widget.class);
        method.setAccessible(true);
        return widget == null ? method.invoke(null) : method.invoke(null, widget);
    }

    private static String words(Object message) throws Exception {
        if (message == null) return "";
        Method english = message.getClass().getDeclaredMethod("english");
        english.setAccessible(true);
        return (String) english.invoke(message);
    }
}
