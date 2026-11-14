package com.jjricks.volt;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static com.jjricks.volt.Ui.DriveMode.ECO;
import static com.jjricks.volt.Ui.DriveMode.SPORT;
import static com.jjricks.volt.Ui.DriveMode.WALK;

/**
 * Small, dependency-free native design system ("Volt"). Shared by compact and unfolded layouts.
 * Dark-first: near-black page, soft rounded cards, Saira type and one scooter-red accent.
 * Red marks things a rider can change or act on; grey marks information and last-seen values.
 */
public final class Ui {
    public final Context context;
    public final boolean dark;
    /** Page background, card surface, raised control fill, text, secondary text and the red accent. */
    public final int bg, surface, text, muted, accent, blue, orange, outline, danger, raised;
    /** Deeper red for gradients and pressed states, white text on red, confirmed-good green, faint last-seen grey. */
    public final int accentDeep, onAccent, ok, dim;
    public final float textScale;
    private final float density;
    private final Typeface REGULAR, MEDIUM, SEMIBOLD, BOLD;

    public Ui(Context context, boolean dark, float textScale) {
        this.context = context; this.dark = dark;
        this.textScale = Math.max(0.75f, Math.min(1.8f, textScale));
        density = context.getResources().getDisplayMetrics().density;
        bg = Color.parseColor(dark ? "#111211" : "#F3F3F0");
        surface = Color.parseColor(dark ? "#1F201E" : "#FFFFFF");
        raised = Color.parseColor(dark ? "#2A2B28" : "#ECECE8");
        text = Color.parseColor(dark ? "#F4F5F0" : "#161716");
        muted = Color.parseColor(dark ? "#9A9C95" : "#62645E");
        // Last-seen grey: quieter than muted but still about 4.5:1 on cards for small text.
        dim = Color.parseColor(dark ? "#878982" : "#7A7C76");
        accent = Color.parseColor(dark ? "#E32B2F" : "#D3252A");
        accentDeep = Color.parseColor(dark ? "#B81B22" : "#A8161C");
        onAccent = Color.WHITE;
        ok = Color.parseColor(dark ? "#5FCB7E" : "#1E8A47");
        blue = Color.parseColor(dark ? "#8FC2FF" : "#2F6DB5");
        orange = Color.parseColor(dark ? "#E8A23A" : "#A8650F");
        outline = Color.parseColor(dark ? "#3A3B37" : "#DADAD5");
        danger = Color.parseColor(dark ? "#FF6B6E" : "#C21F2E");
        REGULAR = font(context, R.font.saira_regular, Typeface.NORMAL);
        MEDIUM = font(context, R.font.saira_medium, Typeface.NORMAL);
        SEMIBOLD = font(context, R.font.saira_semibold, Typeface.NORMAL);
        BOLD = font(context, R.font.saira_bold, Typeface.BOLD);
    }
    private static Typeface font(Context context, int id, int fallbackStyle) {
        try { return context.getResources().getFont(id); }
        catch (RuntimeException e) { return Typeface.create("sans-serif", fallbackStyle); }
    }
    public Typeface regular() { return REGULAR; }
    public Typeface medium() { return MEDIUM; }
    public Typeface semibold() { return SEMIBOLD; }
    public Typeface bold() { return BOLD; }
    public int dp(float value) { return Math.round(value * density); }

    /**
     * Whether this window gets Volt's wide layout: its width over the text scale (Text size times the
     * system font scale) reaches 650 dp. Every wide-or-phone choice asks this, so the side rail, More's
     * two columns, Rides, Stats, the dashboard and onboarding always agree. See {@link WideLayout}.
     */
    public boolean wideLayout() {
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        return WideLayout.wide(configuration.screenWidthDp, textScale * configuration.fontScale);
    }
    public float sp(float value) { return value * context.getResources().getDisplayMetrics().scaledDensity * textScale; }
    /** Shared motion policy, including Android's accessibility/remove-animation setting. */
    public static boolean motionEnabled() { return android.animation.ValueAnimator.areAnimatorsEnabled(); }
    /**
     * The view's left edge in its window from layout positions alone. Unlike getLocationInWindow,
     * a page that is still gliding in (a translated ancestor) does not shift the answer, so
     * window-aligned splits are measured where the page will rest.
     */
    public static int layoutLeftInWindow(View view) {
        int x = 0;
        for (View v = view; v != null; ) {
            x += v.getLeft();
            if (!(v.getParent() instanceof View)) break;
            View parent = (View) v.getParent();
            x -= parent.getScrollX();
            v = parent;
        }
        return x;
    }
    /** Native pressed feedback leaves layout sizes unchanged. */
    public void pressFeedback(View view) {
        if (!motionEnabled()) return;
        android.animation.StateListAnimator states = new android.animation.StateListAnimator();
        android.animation.ObjectAnimator down = android.animation.ObjectAnimator.ofPropertyValuesHolder(view,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, .98f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, .98f));
        down.setDuration(90);
        android.animation.ObjectAnimator up = android.animation.ObjectAnimator.ofPropertyValuesHolder(view,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1));
        up.setDuration(140); up.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
        states.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_pressed}, down);
        states.addState(new int[0], up); view.setStateListAnimator(states);
        states.jumpToCurrentState();
    }
    /** The readable text floor in sp (times the app's text scale): compact labels never auto-size below it. */
    public static final float READABLE_FLOOR_SP = 12;
    /**
     * Keep compact status lines on one line at a readable size. Auto-size may shrink a line only to the readable
     * floor; a line wider than that falls to the next rung its caller gave {@link #statusRungs}, then to its start
     * icon alone when it has one. Only the last rung of a line without an icon is ever ellipsized.
     */
    public void fitStatusLine(TextView view) {
        int maximum = Math.round(view.getTextSize());
        // Keep the original line's height as auto-size settles, including across Edit rebuilds.
        Paint.FontMetrics metrics = view.getPaint().getFontMetrics();
        view.setMinHeight((int)Math.ceil(metrics.descent - metrics.ascent));
        view.setGravity((view.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        view.setMaxLines(1);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int floor = Math.round(sp(READABLE_FLOOR_SP));
        // A line already at the floor keeps its size (auto-size needs a range).
        if (floor < maximum) view.setAutoSizeTextTypeUniformWithConfiguration(
                floor, maximum, 1, TypedValue.COMPLEX_UNIT_PX);
        ladder(view, false);
    }
    /**
     * Shows a status line's narrow-label ladder: rungs[0] is the full line, each next one shorter. The line shows
     * the first rung that fits at the readable floor and picks again whenever its width changes. A view
     * {@link #fitStatusLine} prepared also refits text set on it directly, as a ladder of that text alone.
     */
    public void statusRungs(TextView view, CharSequence... rungs) {
        StatusRungs ladder = ladders.get(view);
        if (ladder == null) ladder = ladder(view, false);
        ladder.show(rungs);
    }
    /** Each line's ladder; the view holds it as its layout listener, the cache holds neither strongly. */
    private final LadderCache<TextView, StatusRungs> ladders = new LadderCache<>();
    private StatusRungs ladder(TextView view, boolean iconBeside) {
        StatusRungs ladder = ladders.of(view, line -> {
            StatusRungs made = new StatusRungs(line);
            line.addOnLayoutChangeListener(made);
            return made;
        });
        ladder.iconBeside = iconBeside;
        return ladder;
    }
    public TextView text(String value, float sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * textScale);
        view.setTextColor(text); view.setTypeface(bold ? BOLD : REGULAR);
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(3), 1);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return view;
    }
    /**
     * {@link #text(String, float, boolean)} for a deferred catalog message (a Msg), rendered now: for a
     * view kept in a field, whose text is built once (catalog rule R2 keeps rendered text out of fields).
     */
    public TextView text(CharSequence value, float sp, boolean bold) {
        return text(value == null ? null : value.toString(), sp, bold);
    }
    public TextView caption(String value) {
        TextView view = text(value, 12, false); view.setTextColor(muted); return view;
    }
    /** Search spacing is shared without changing each field's focus or input behavior. */
    public void styleSearch(EditText search) {
        search.setTextColor(text); search.setHintTextColor(muted);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * textScale);
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        search.setMinimumHeight(dp(52));
        search.setTypeface(REGULAR);
        search.setBackground(shape(surface, 14, 0));
        // A muted magnifier at the start, drawn with the same line icons as the rest of the app.
        Drawable glass = new Drawable() {
            private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                canvas.save(); canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                drawSymbol(canvas, "search", muted, glyph); canvas.restore();
            }
            @Override public void setAlpha(int alpha) { glyph.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { glyph.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        int glassSize = dp(18); glass.setBounds(0, 0, glassSize, glassSize);
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
    }
    public LinearLayout.LayoutParams searchLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4); params.bottomMargin = dp(12); return params;
    }
    public LinearLayout card() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(shape(surface, 20, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10); layout.setLayoutParams(params); return layout;
    }
    public TextView button(String label, Runnable action) {
        TextView button = text(label, 14, true);
        button.setTypeface(SEMIBOLD); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), dp(12), dp(18), dp(12));
        button.setMinHeight(dp(48));
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {dim, onAccent}));
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, shape(raised, 14, 0));
        fill.addState(new int[] {}, shape(accent, 14, 0));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 45)), fill, null));
        button.setClickable(true); button.setFocusable(true);
        button.setContentDescription(label);
        button.setOnClickListener(v -> { if (action != null) action.run(); });
        pressFeedback(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6); params.bottomMargin = dp(6); button.setLayoutParams(params);
        return button;
    }
    public TextView secondaryButton(String label, Runnable action) {
        TextView button = button(label, action);
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {dim, text}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)), shape(raised, 14, outline), null));
        return button;
    }
    /** Destructive action: outlined in red, never filled, so it reads differently from the main action. */
    public TextView dangerButton(String label, Runnable action) {
        TextView button = button(label, action);
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {dim, danger}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(danger, 30)),
                shape(Color.TRANSPARENT, 14, withAlpha(danger, 110)), null));
        return button;
    }
    /** Small uppercase group label placed above a group card, e.g. RIDE, BATTERY. */
    public TextView sectionLabel(String label) {
        TextView view = text(label == null ? "" : label.toUpperCase(Fmt.locale(Locale.ROOT)), 11, false);
        view.setTypeface(SEMIBOLD); view.setTextColor(muted); view.setLetterSpacing(0.08f);
        view.setPadding(dp(4), dp(14), dp(4), dp(6));
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }
    /** A card that holds divided rows: tight vertical padding so rows sit edge to edge. */
    public LinearLayout groupCard() {
        LinearLayout layout = card();
        layout.setPadding(dp(12), dp(2), dp(12), dp(2));
        return layout;
    }
    /** Selectable pill; red when selected, dark otherwise. Call {@link #styleChip} to change state. */
    public TextView chip(String label, boolean selected, Runnable action) {
        TextView chip = text(label, 12.5f, false);
        chip.setGravity(Gravity.CENTER); chip.setMinHeight(dp(36)); chip.setMinWidth(dp(48));
        chip.setPadding(dp(14), dp(6), dp(14), dp(6)); chip.setFocusable(true); chip.setClickable(true);
        chip.setContentDescription(label);
        styleChip(chip, selected);
        chip.setOnClickListener(v -> { if (action != null) action.run(); });
        return chip;
    }
    public void styleChip(TextView chip, boolean selected) {
        chip.setSelected(selected);
        chip.setTypeface(selected ? SEMIBOLD : REGULAR);
        chip.setTextColor(selected ? onAccent : text);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 40)),
                shape(selected ? accent : raised, 100, 0), null));
        if (Build.VERSION.SDK_INT >= 30) chip.setStateDescription(selected ? T.commonSelected() : "");
    }
    /** A rounded square holding an icon, used at the start of rows and cards. */
    public View iconSquare(String key, int color) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(context);
        box.setBackground(shape(dark ? bg : raised, 10, 0));
        android.widget.FrameLayout.LayoutParams inner = new android.widget.FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER);
        box.addView(icon(key, 17, color), inner);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }
    public View icon(String key, int size, int color) { return new IconView(key, size, color); }
    /** Repaints a view made by {@link #icon} as another glyph. */
    public void setIcon(View icon, String key) { if (icon instanceof IconView) ((IconView) icon).key(key); }
    /** Repaints a view made by {@link #icon} as another glyph in another colour. */
    public void setIcon(View icon, String key, int color) { if (icon instanceof IconView) { ((IconView) icon).key(key); ((IconView) icon).color(color); } }
    public RadioDot radioDot() { return new RadioDot(); }
    /** Radio indicator: a grey ring, or a thick red ring when selected. */
    public final class RadioDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); boolean checked;
        RadioDot() { super(context); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(boolean value) { if (checked != value) { checked = value; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            float width = dp(checked ? 5 : 2), radius = Math.min(getWidth(), getHeight()) / 2f - width / 2f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setColor(checked ? accent : isEnabled() ? muted : dim);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }
    }
    /** A compound symbol preserves row child indices and the label's spoken text. */
    public void decorate(TextView label, String iconKey) {
        if (iconKey == null || iconKey.isEmpty()) return;
        Drawable symbol = new LabelIcon(label, iconKey, true);
        int size = dp(30); symbol.setBounds(0, 0, size, size);
        Drawable[] old = label.getCompoundDrawablesRelative();
        label.setCompoundDrawablesRelative(symbol, old[1], old[2], old[3]);
        label.setCompoundDrawablePadding(dp(12));
        IconBalance.watch(label);
    }
    /**
     * A view's measured width without an icon label's balance spacer, for a parent that measures labels to pick a
     * layout (Stats' StackingRow): the balance then never changes the layout it picks.
     */
    static int unbalancedWidth(View view) {
        if (!(view instanceof TextView)) return view.getMeasuredWidth();
        TextView label = (TextView) view;
        Drawable end = label.getCompoundDrawablesRelative()[2];
        int spacer = end instanceof BalanceSpacer ? end.getBounds().width() : IconLabelBalance.NONE;
        return IconLabelBalance.unbalanced(label.getMeasuredWidth(), spacer, label.getCompoundDrawablePadding());
    }
    /** The invisible end drawable that centres a centred label's text past its start icon (IconLabelBalance). */
    private static final class BalanceSpacer extends Drawable {
        BalanceSpacer(int width) { setBounds(0, 0, width, 0); }
        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int value) {}
        @Override public void setColorFilter(ColorFilter value) {}
        @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
    }
    /**
     * Balances a decorated label at layout time, when its gravity, padding, gap, icon and width are final: a centred
     * label gets the widest end spacer (up to the full balance) that keeps its text on one line, and any other label
     * loses it. It decides before
     * the frame draws (and lays the label out again first), so the text never jumps. One per label: decorate may
     * run more than once on the same label, and a caller may swap the start icon (Stats' spinner) in between.
     */
    private static final class IconBalance implements View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnPreDrawListener {
        private static final java.util.WeakHashMap<TextView, Boolean> WATCHED = new java.util.WeakHashMap<>();
        private final TextView label;
        private android.view.ViewTreeObserver observer;
        private CharSequence measuredText; private float measuredSize, measuredSpacing, measuredWidth; private Typeface measuredFace;

        private IconBalance(TextView label) { this.label = label; }

        static void watch(TextView label) {
            if (WATCHED.put(label, Boolean.TRUE) != null) return;
            IconBalance balance = new IconBalance(label);
            label.addOnAttachStateChangeListener(balance);
            if (label.isAttachedToWindow()) balance.onViewAttachedToWindow(label);
        }
        @Override public void onViewAttachedToWindow(View view) {
            if (observer != null && observer.isAlive()) return;
            observer = label.getViewTreeObserver(); observer.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        @Override public boolean onPreDraw() {
            if (!label.isLaidOut() || label.isLayoutRequested()) return true;
            Drawable[] drawables = label.getCompoundDrawablesRelative();
            Drawable end = drawables[2];
            if (end != null && !(end instanceof BalanceSpacer)) return true; // the end belongs to the caller
            int have = end == null ? IconLabelBalance.NONE : end.getBounds().width(), want = wanted(drawables[0]);
            if (want == have) return true;
            // A spacer of width 0 still adds its gap: a tight label's partial balance.
            label.setCompoundDrawablesRelative(drawables[0], drawables[1],
                    want == IconLabelBalance.NONE ? null : new BalanceSpacer(want), drawables[3]);
            return false; // lay out again before this frame draws
        }
        private int wanted(Drawable icon) {
            int align = label.getTextAlignment();
            boolean centred = align == View.TEXT_ALIGNMENT_CENTER || align == View.TEXT_ALIGNMENT_GRAVITY
                    && (label.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL;
            // Autosize could shrink the text into the balanced room; such a label keeps today's layout.
            if (!centred || icon == null || label.getAutoSizeTextType() != TextView.AUTO_SIZE_TEXT_TYPE_NONE) return IconLabelBalance.NONE;
            return IconLabelBalance.spacer(true, label.getWidth(), label.getPaddingStart(), label.getPaddingEnd(),
                    icon.getBounds().width(), label.getCompoundDrawablePadding(), textWidth());
        }
        /** The shown text's widest line, unwrapped; measured again only when the text or its paint changes. */
        private float textWidth() {
            CharSequence shown = label.getText();
            if (label.getTransformationMethod() != null) shown = label.getTransformationMethod().getTransformation(shown, label);
            android.text.TextPaint paint = label.getPaint();
            if (shown != measuredText || paint.getTextSize() != measuredSize || paint.getLetterSpacing() != measuredSpacing
                    || paint.getTypeface() != measuredFace) {
                measuredText = shown; measuredSize = paint.getTextSize(); measuredSpacing = paint.getLetterSpacing();
                measuredFace = paint.getTypeface();
                measuredWidth = android.text.Layout.getDesiredWidth(shown, paint);
            }
            return measuredWidth;
        }
    }
    /**
     * Display-only mode cues from the shown text, which must be Telemetry's English label; callers
     * that know the mode pass it to {@link #styleDriveMode(TextView, DriveMode, boolean)} instead.
     */
    public void styleDriveMode(TextView value, String formattedMode, boolean live) {
        styleDriveMode(value, DriveMode.of(formattedMode), live);
    }
    /** Display-only mode cues; callers supply the formatted text, base color and spoken context. */
    public void styleDriveMode(TextView value, DriveMode mode, boolean live) {
        value.setCompoundDrawablesRelative(null, null, null, null);
        value.setCompoundDrawablePadding(0);
        // Volt: Sport is red, Eco is green and Walk is plain text; last-known modes stay grey.
        if (mode == SPORT) value.setTextColor(live ? accent : muted);
        else if (mode == ECO) value.setTextColor(live ? ok : muted);
        else if (mode == WALK) {
            value.setTextColor(live ? text : muted);
            CharSequence spoken = value.getContentDescription();
            if (spoken == null || spoken.length() == 0) value.setContentDescription(T.uiDriveModeWalk());
            value.setText("");
            Drawable walker = new LabelIcon(value, "walk", false);
            float fontScale = context.getResources().getConfiguration().fontScale;
            int size = dp(Math.max(24, Math.min(36, 24 * textScale * fontScale)));
            walker.setBounds(0, 0, size, size);
            value.setCompoundDrawablesRelative(walker, null, null, null);
        }
    }
    public String featureIcon(String key) {
        if (key == null) return "";
        if (key.contains("speed") || key.equals("cruise_control") || key.equals("driving_mode")) return "speed";
        if (key.contains("charge") || key.contains("battery") || key.equals("low_power") || key.equals("long_range_mode")) return "battery";
        if (key.equals("energy_recovery")) return "recovery";
        if (key.equals("headlight") || key.equals("auto_sensor") || key.startsWith("line_")) return "lights";
        if (key.startsWith("star_")) return "star";
        if (key.contains("lock") || key.equals("pin") || key.equals("proximity_key")) return "lock";
        if (key.contains("sound") || key.equals("prompt_tone")) return "sound";
        if (key.equals("traction_control")) return "shield";
        if (key.contains("assist") || key.contains("hill")) return "hill";
        if (key.contains("unit") || key.contains("distance") || key.contains("odometer")) return "ruler";
        if (key.contains("temperature")) return "temperature";
        if (key.contains("power") || key.contains("voltage") || key.contains("current")) return "energy";
        if (key.contains("range")) return "route";
        return "tune";
    }
    public String labelIcon(String label) {
        if (label == null) return "";
        String value = label.toLowerCase(Locale.ROOT);
        if (value.contains("history") || value.contains("storage")) return "history";
        if (value.equals("ride") || value.contains("my scooter") || value.contains("scooter setting") || value.contains("scooter name")) return "scooter";
        if (value.contains("light")) return "lights";
        if (value.contains("battery") || value.contains("charging")) return "battery";
        if (value.contains("recovery")) return "recovery";
        if (value.contains("proximity") || value.contains("pin") || value.contains("security")) return "lock";
        if (value.contains("sound")) return "sound";
        if (value.contains("unit") || value.contains("distance & speed")) return "ruler";
        if (value.contains("appearance")) return "appearance";
        if (value.contains("text size")) return "text";
        if (value.contains("stats") || value.contains("readings")) return "stats";
        if (value.contains("telemetry refresh") || value.contains("clock")) return "clock";
        if (value.contains("connect") || value.contains("nearby")) return "bluetooth";
        if (value.contains("screen awake")) return "screen";
        if (value.contains("phone sensor")) return "sensors";
        if (value.contains("map")) return "route";
        if (value.contains("app preference")) return "settings";
        if (value.contains("experimental")) return "lab";
        if (value.contains("about") || value.contains("permission")) return "info";
        return "";
    }
    private final class LabelIcon extends Drawable {
        private final TextView owner; private final String key; private final boolean boxed;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        LabelIcon(TextView owner, String key, boolean boxed) { this.owner = owner; this.key = key; this.boxed = boxed; }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            int color = owner.getCurrentTextColor();
            if (boxed) {
                // A dark rounded square with a red glyph; disabled or last-seen labels keep a grey glyph.
                float w = getBounds().width(), h = getBounds().height();
                paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(dark ? bg : raised, alpha));
                canvas.drawRoundRect(new RectF(0, 0, w, h), dp(10), dp(10), paint);
                boolean quiet = color == muted || color == dim || !owner.isEnabled();
                color = quiet ? dim : accent;
                float glyph = w * .56f; canvas.translate((w - glyph) / 2, (h - glyph) / 2);
                canvas.scale(glyph / 24f, glyph / 24f);
            } else canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            drawSymbol(canvas, key, withAlpha(color, Color.alpha(color) * alpha / 255), paint);
            canvas.restore();
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    }

    public LinearLayout metric(String label, String value, String hint, int color) {
        LinearLayout card = card();
        TextView title = caption(label); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * textScale);
        card.addView(title);
        TextView amount = text(value == null ? "—" : value, 30, true);
        amount.setTextColor(color); amount.setPadding(0, dp(9), 0, dp(7));
        card.addView(amount);
        if (hint != null && !hint.isEmpty()) card.addView(caption(hint));
        card.setContentDescription(hint == null || hint.isEmpty()
                ? T.uiMetricDescription(label, (value == null ? T.commonNotReported() : value))
                : T.uiMetricDescriptionHint(label, (value == null ? T.commonNotReported() : value), hint));
        return card;
    }
    /**
     * {@link #toggleRow(String, String, String, Boolean, boolean, Consumer)} with the icon guessed from
     * the label's English words ({@link #labelIcon}). Kept for callers that have not moved to an
     * explicit icon; a translated label finds no icon here.
     */
    public View toggleRow(String label, String description, Boolean value, boolean enabled,
            Consumer<Boolean> onChange) {
        return toggleRow(label, labelIcon(label), description, value, enabled, onChange);
    }
    /**
     * Only a performed click dispatches a change; programmatic setChecked never sends commands.
     * iconKey names the row's icon (empty for none).
     */
    public View toggleRow(String label, String iconKey, String description, Boolean value, boolean enabled,
            Consumer<Boolean> onChange) {
        LinearLayout row = card(); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(label, 15, true); decorate(heading, iconKey); words.addView(heading);
        if (description != null && !description.isEmpty()) {
            TextView detail = caption(description); detail.setPadding(0, dp(6), 0, 0); words.addView(detail);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Haptics.FeedbackSwitch(context);
        toggle.setShowText(false); toggle.setTextOn(""); toggle.setTextOff("");
        toggle.setChecked(Boolean.TRUE.equals(value));
        toggle.setEnabled(enabled && value != null);
        toggle.setMinimumWidth(dp(56)); toggle.setMinimumHeight(dp(48));
        styleSwitch(toggle);
        toggle.setContentDescription(value == null ? T.uiToggleStateNotReported(label) : String.valueOf(label));
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(value == null ? T.commonNotReported()
                : value ? T.commonOn() : T.commonOff());
        toggle.setOnClickListener(v -> { if (toggle.isEnabled() && onChange != null) onChange.accept(toggle.isChecked()); });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.leftMargin = dp(14); row.addView(toggle, toggleParams);
        row.setOnClickListener(v -> { if (toggle.isEnabled()) toggle.performClick(); });
        row.setFocusable(false);
        if (!enabled || value == null) heading.setTextColor(muted);
        return row;
    }
    /** Opaque tracks keep both states legible, independent of OEM switch artwork and its alpha. */
    public void styleSwitch(Switch toggle) {
        GradientDrawable track = shape(Color.WHITE, 15, 0);
        track.setSize(dp(56), dp(30));
        // Draw the thumb's breathing room without InsetDrawable optical insets: native
        // Switch subtracts those from the track too, leaving its thumb outside the ends.
        GradientDrawable thumb = new GradientDrawable() {
            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                if (bounds.width() <= dp(4) || bounds.height() <= dp(6)) return;
                int save = canvas.save();
                canvas.scale((bounds.width() - dp(4)) / (float) bounds.width(),
                        (bounds.height() - dp(6)) / (float) bounds.height(), bounds.exactCenterX(), bounds.exactCenterY());
                super.draw(canvas); canvas.restoreToCount(save);
            }
        };
        thumb.setColor(Color.WHITE); thumb.setCornerRadius(dp(15)); thumb.setSize(dp(28), dp(30));
        toggle.setTrackDrawable(track);
        toggle.setThumbDrawable(thumb);
        toggle.setShowText(false);
        toggle.setSwitchMinWidth(dp(56));
        toggle.setSplitTrack(false);
        toggle.setThumbTintList(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled},
                new int[] {}}, new int[] {dark ? Color.parseColor("#AEB0A8") : Color.WHITE, Color.WHITE}));
        toggle.setTrackTintList(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {
                Color.parseColor(dark ? "#4F514C" : "#BFC1B9"), accent,
                Color.parseColor(dark ? "#777A74" : "#73766F")}));
        toggle.setThumbTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        toggle.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
    }
    /**
     * {@link #settingRow(String, String, String, Runnable)} with the icon guessed from the label's
     * English words ({@link #labelIcon}). Kept for callers that have not moved to an explicit icon; a
     * translated label finds no icon here.
     */
    public LinearLayout settingRow(String label, String value, Runnable action) {
        return settingRow(label, labelIcon(label), value, action);
    }
    /** Quiet, aligned navigation row. Child 1 is the value text for live updates. iconKey names its icon (empty for none). */
    public LinearLayout settingRow(String label, String iconKey, String value, Runnable action) {
        LinearLayout row = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                if (getChildCount() > 1 && available > 0)
                    ((TextView)getChildAt(1)).setMaxWidth(Math.min(dp(148), (int)(available * .42f)));
                super.onMeasure(widthSpec, heightSpec);
            }
        }; row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(56));
        row.setPadding(dp(2), dp(10), dp(2), dp(10));
        TextView title = text(label, 15, false); decorate(title, iconKey);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = text(value == null ? "" : value, 14, false);
        amount.setTextColor(muted); amount.setGravity(Gravity.END); amount.setMaxLines(2);
        amount.setMaxWidth(dp(148));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.leftMargin = dp(12);
        row.addView(amount, vp);
        if (action != null) {
            View chevron = icon("chevron", 15, muted);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(15), dp(15)); cp.leftMargin = dp(10);
            row.addView(chevron, cp); row.setFocusable(true);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 26)), shape(Color.TRANSPARENT, 10, 0), null));
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }
    public void divider(LinearLayout parent) {
        View line = new View(context); line.setBackgroundColor(raised);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    /** A rail tab: its icon over one line of caption, the full name, else shortLabel, else the icon alone. */
    public View nav(String label, String shortLabel, String iconKey, boolean selected, Runnable action) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(8), dp(2), dp(8)); item.setMinimumHeight(dp(64));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)),
                shape(selected ? withAlpha(accent, dark ? 40 : 26) : Color.TRANSPARENT, 18, 0), null));
        item.addView(icon(iconKey, 24, selected ? text : muted), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView caption = text(label, 10, selected); caption.setMaxLines(2); caption.setTextColor(selected ? text : muted);
        caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(5), 0, 0); item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        caption.setEllipsize(TextUtils.TruncateAt.END); caption.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        // Up to two lines of whole words: the name, then its short rung, then the icon alone.
        StatusRungs rungs = ladder(caption, true); rungs.maxLines = 2; rungs.show(new CharSequence[]{label, shortLabel});
        item.setContentDescription(label); item.setSelected(selected); item.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 30) item.setStateDescription(selected ? T.commonSelected() : "");
        item.setOnClickListener(v -> { if (!selected) Haptics.tick(v); if (action != null) action.run(); });
        pressFeedback(item);
        return item;
    }
    /** Draws one of the shared line icons centred at (cx, cy), for custom-drawn controls. */
    public void drawIcon(Canvas canvas, String key, int color, float cx, float cy, float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.save(); canvas.translate(cx - size / 2, cy - size / 2); canvas.scale(size / 24f, size / 24f);
        drawSymbol(canvas, key, color, paint); canvas.restore();
    }
    /** The icon id of a row with no icon, for callers that name it rather than leave it to {@link #labelIcon}. */
    public static final String NO_ICON = "";
    /**
     * Floating tab bar: a rounded pill with a raised red button for the centre destination.
     * The selected side tab shows white with a small red dot; the selected centre button gains a ring.
     */
    public View floatingNav(String[] labels, String[] icons, int selected, int center, java.util.function.IntConsumer onSelect) {
        return floatingNav(labels, icons, selected, -1, center, onSelect);
    }
    /** {@code previous} is the tab being left; the selection dot glides over from it. */
    public View floatingNav(String[] labels, String[] icons, int selected, int previous, int center, java.util.function.IntConsumer onSelect) {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(context);
        bar.setClipChildren(false); bar.setClipToPadding(false);
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClipChildren(false); // the selection dot travels across tabs
        pill.setBackground(shape(surface, 26, raised));
        pill.setElevation(dp(6));
        bar.addView(pill, new android.widget.FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM));
        for (int i = 0; i < labels.length; i++) {
            final int index = i; boolean on = i == selected;
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setClipChildren(false);
            if (i == center) {
                // A plain spacer under the raised button, which carries the label and the action.
                item.setFocusable(false); item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            } else {
                item.setContentDescription(labels[i]); item.setFocusable(true); item.setSelected(on);
                if (Build.VERSION.SDK_INT >= 30) item.setStateDescription(on ? T.commonSelected() : "");
            }
            if (i != center) {
                item.addView(icon(icons[i], 21, on ? text : muted), new LinearLayout.LayoutParams(dp(21), dp(21)));
                View dot = new View(context); dot.setBackground(shape(accent, 3, 0));
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(5), dp(5)); dp.topMargin = dp(4);
                dot.setVisibility(on ? View.VISIBLE : View.INVISIBLE); item.addView(dot, dp);
                if (on && previous >= 0 && previous != selected && motionEnabled()) {
                    final int steps = previous - selected;
                    Motion.beforeNextDraw(item, () -> {
                        // Equal-width tabs: the dot starts under the tab being left and settles here.
                        // Right to left, the tabs run the other way.
                        int side = item.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                        dot.setTranslationX(side * steps * item.getWidth());
                        dot.animate().translationX(0).setDuration(Motion.ENTER_MS).setInterpolator(Motion.STANDARD).start();
                    }, null);
                }
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)), null, shape(Color.WHITE, 22, 0)));
                item.setOnClickListener(v -> { if (index != selected) Haptics.tick(v); if (onSelect != null) onSelect.accept(index); });
                pressFeedback(item);
            }
            pill.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        if (center >= 0 && center < labels.length) {
            final boolean on = center == selected;
            android.widget.FrameLayout button = new android.widget.FrameLayout(context);
            GradientDrawable disc = new GradientDrawable(); disc.setShape(GradientDrawable.OVAL);
            disc.setColor(accent); disc.setStroke(dp(5), bg);
            GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Color.TRANSPARENT); ring.setStroke(dp(2), on ? withAlpha(accent, 150) : Color.TRANSPARENT);
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new Drawable[] {ring, disc});
            layers.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 50)), layers, null));
            button.addView(icon(icons[center], 25, onAccent), new android.widget.FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER));
            button.setContentDescription(labels[center]); button.setFocusable(true); button.setSelected(on);
            if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(on ? T.commonSelected() : "");
            button.setElevation(dp(8));
            button.setOnClickListener(v -> { if (!on) Haptics.tick(v); if (onSelect != null) onSelect.accept(center); });
            pressFeedback(button);
            bar.addView(button, new android.widget.FrameLayout.LayoutParams(dp(68), dp(68), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }
        return bar;
    }
    /** Height the floating tab bar needs, so scrolling pages can pad their bottom edge. */
    public int floatingNavHeight() { return dp(72); }
    /**
     * The round pencil that opens an editor in a page header, or the red {@code done} pill that replaces it while
     * editing (Home's dashboard, the Scooter tab's menu). {@code target} is the 48 dp touch target; its content
     * description is the caller's.
     */
    public void editToggle(android.widget.FrameLayout target, boolean editing, String done) {
        target.removeAllViews();
        if (editing) {
            TextView pill = text(done, 14, true);
            pill.setTextColor(onAccent); pill.setGravity(Gravity.CENTER); pill.setMinHeight(dp(38));
            pill.setPadding(dp(20), dp(6), dp(20), dp(6)); pill.setBackground(shape(accent, 100, 0));
            target.addView(pill, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            target.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 45)), null, shape(Color.WHITE, 100, 0)));
        } else {
            android.widget.FrameLayout disc = new android.widget.FrameLayout(context);
            disc.setBackground(editDisc());
            disc.addView(icon("pencil", 17, text), new android.widget.FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
            target.addView(disc, new android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
            target.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 35)), null, editDisc()));
        }
    }
    private GradientDrawable editDisc() {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL); disc.setColor(dark ? surface : raised);
        return disc;
    }
    public GradientDrawable shape(int fill, float radius, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border); return drawable;
    }
    public static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24); }

    /**
     * The drive modes Volt gives a colour or symbol. Each keeps Telemetry's English label for the mode
     * as its id, which only the legacy {@link #styleDriveMode(TextView, String, boolean)} compares.
     * Deferred to B3: the plan gives Telemetry a DrivingMode enum; that batch maps it onto this one,
     * or replaces this one, so the app has a single mode type.
     */
    public enum DriveMode {
        SPORT("SPORT"), ECO("ECO"), WALK("WALK"), OTHER("");
        /** Telemetry's English label for this mode: data, never shown by this class. */
        public final String id;
        DriveMode(String id) { this.id = id; }
        /** The mode Telemetry's English label names, or OTHER. */
        public static DriveMode of(String label) {
            for (DriveMode mode : values()) if (mode != OTHER && mode.id.equals(label)) return mode;
            return OTHER;
        }
    }

    public class BatteryRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double percent = Double.NaN;
        // The default caption is a deferred Msg, rendered when read, so the field keeps no rendered
        // text (R2). Uses that need a String take subtitle.toString(): CharSequence.isEmpty() needs API 35.
        private CharSequence subtitle = M.commonNotReported();
        private boolean charging, live = true;
        public void setLive(boolean value) { live = value; describe(); invalidate(); }
        public BatteryRing() {
            super(context); setMinimumHeight(dp(140)); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(176)));
        }
        public BatteryRing(Context ignored) { this(); }
        public void set(double percent, String subtitle, boolean charging) {
            this.percent = !Double.isNaN(percent) && !Double.isInfinite(percent) && percent >= 0 && percent <= 100 ? percent : Double.NaN;
            this.subtitle = subtitle == null ? "" : subtitle; this.charging = charging;
            describe(); invalidate();
        }
        private void describe() {
            // One whole template per state (strings_ui.xml), filled with the level and the caption.
            setContentDescription(((java.util.function.Function<String, String>) level -> charging
                    ? live ? (subtitle.toString().isEmpty() ? T.uiBatteryDescriptionCharging(level) : T.uiBatteryDescriptionChargingCaption(level, subtitle))
                            : (subtitle.toString().isEmpty() ? T.uiBatteryDescriptionChargingLastSeen(level) : T.uiBatteryDescriptionChargingLastSeenCaption(level, subtitle))
                    : live ? (subtitle.toString().isEmpty() ? T.uiBatteryDescription(level) : T.uiBatteryDescriptionCaption(level, subtitle))
                            : (subtitle.toString().isEmpty() ? T.uiBatteryDescriptionLastSeen(level) : T.uiBatteryDescriptionLastSeenCaption(level, subtitle)))
                    .apply(Double.isNaN(percent) ? T.uiBatteryNotReported() : T.uiBatteryPercent(Math.round(percent), Math.round(percent))));
        }
        public void set(Double percent, String subtitle, boolean charging) { set(percent == null ? Double.NaN : percent.doubleValue(), subtitle, charging); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(dp(160), width), resolveSize(dp(176), height));
        }
        @Override protected void onDraw(Canvas canvas) {
            String label = charging ? (subtitle.toString().isEmpty() ? T.uiBatteryRingCharging() : T.uiBatteryRingChargingCaption(subtitle)) : subtitle.toString();
            float drawableHeight = Math.max(1, getHeight() - (label.isEmpty() ? 0 : dp(20)));
            float radius = Math.min(getWidth(), drawableHeight) / 2f - dp(13);
            if (radius <= 0) return;
            // The open lower quarter makes the painted arc shorter below the circle
            // center. Center its visible bounds, not the invisible full-circle square.
            float cx = getWidth() / 2f, cy = drawableHeight / 2f + radius * .1464466f;
            RectF circle = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            float stroke = Math.min(dp(12.5f), radius * .25f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(raised); canvas.drawArc(circle, 135, 270, false, paint);
            // The ring is red (the accent) normally; a low charge switches to amber so it reads as a warning.
            int color = !live ? muted : !Double.isNaN(percent) && percent < 15 ? orange : accent;
            if (!Double.isNaN(percent)) { paint.setColor(color); canvas.drawArc(circle, 135, (float) (270 * percent / 100), false, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            String amount = Double.isNaN(percent) ? "—" : Long.toString(Math.round(percent));
            String unit = Double.isNaN(percent) ? "" : "%";
            float ringScale = Math.max(1f, radius / dp(58));
            float amountSize = Math.min(sp(34) * ringScale, radius * .76f);
            float unitSize = Math.min(sp(14) * ringScale, radius * .30f);
            float gap = unit.isEmpty() ? 0 : dp(5);
            Rect amountBounds = new Rect(), unitBounds = new Rect();
            paint.setTypeface(BOLD); paint.setTextSize(amountSize);
            paint.getTextBounds(amount, 0, amount.length(), amountBounds);
            float amountWidth = paint.measureText(amount);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize);
            paint.getTextBounds(unit, 0, unit.length(), unitBounds);
            float blockWidth = Math.max(amountWidth, paint.measureText(unit));
            float blockHeight = amountBounds.height() + unitBounds.height() + gap;
            // Keep the whole text block inside a padded square inscribed in the ring. This
            // also fits three digits and larger accessibility text in the compact Home gauge.
            float safeSide = Math.max(1, (radius - stroke / 2 - dp(6)) * 1.4142f);
            float fit = Math.min(1, Math.min(safeSide / Math.max(1, blockWidth), safeSide / Math.max(1, blockHeight)));
            amountSize *= fit; unitSize *= fit; gap *= fit;
            paint.setTypeface(BOLD); paint.setTextSize(amountSize);
            paint.getTextBounds(amount, 0, amount.length(), amountBounds);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize);
            paint.getTextBounds(unit, 0, unit.length(), unitBounds);
            float top = cy - (amountBounds.height() + unitBounds.height() + gap) / 2f;
            paint.setTypeface(BOLD); paint.setTextSize(amountSize); paint.setColor(live ? text : muted);
            canvas.drawText(amount, cx, top - amountBounds.top, paint);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize); paint.setColor(muted);
            if (!unit.isEmpty()) canvas.drawText(unit, cx, top + amountBounds.height() + gap - unitBounds.top, paint);
            paint.setTextSize(Math.min(sp(11), dp(14))); paint.setColor(charging && live ? accent : muted);
            label = TextUtils.ellipsize(label, new android.text.TextPaint(paint), Math.max(dp(40), getWidth() - dp(8)), TextUtils.TruncateAt.END).toString();
            canvas.drawText(label, cx, getHeight() - dp(9), paint);
        }
    }
    public final class CustomBatteryRing extends BatteryRing {
        public CustomBatteryRing() { super(); }
        public CustomBatteryRing(Context ignored) { super(); }
    }

    /** Ui.ChartView's date and time patterns, which English and every unshipped language keep (Fmt.datePattern). */
    private static final String CHART_TIME_24 = "HH:mm:ss", CHART_TIME_12 = "h:mm:ss a";
    private static final String CHART_CLOCK_24 = "HH:mm", CHART_CLOCK_12 = "h:mm a";
    private static final String CHART_DATE = "MMM d";
    private static final String CHART_DATE_CLOCK_24 = "MMM d HH:mm", CHART_DATE_CLOCK_12 = "MMM d h:mm a";
    private static final String CHART_DETAIL_24 = "MMM d, yyyy HH:mm:ss", CHART_DETAIL_12 = "MMM d, yyyy h:mm:ss a";

    /**
     * A chart pattern: the legacy pattern while Volt is English, else the display language's best
     * pattern for the same fields. The legacy pattern's letters are its skeleton; a 12-hour pattern's
     * "h" and "a" keep the user's 12-hour choice.
     */
    private static String chartPattern(String legacy) {
        return Fmt.datePattern(legacy, skeleton(legacy), android.text.format.DateFormat::getBestDateTimePattern);
    }

    /** A date pattern's fields as a skeleton: its pattern letters, without the spaces and punctuation between them. */
    private static String skeleton(String pattern) {
        StringBuilder letters = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) if (Character.isLetter(pattern.charAt(i))) letters.append(pattern.charAt(i));
        return letters.toString();
    }

    public static final class Point {
        public final long millis;
        public final double value;
        public final boolean gapBefore;
        public Point(long millis, double value) { this(millis, value, false); }
        public Point(long millis, double value, boolean gapBefore) { this.millis = millis; this.value = value; this.gapBefore = gapBefore; }
    }
    /** Actual sensor values only. Gaps break the path; touch reads a measured point. */
    public final class ChartView extends View {
        public static final int MAX_POINTS = 1600;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Point> points = new ArrayList<Point>();
        // The default empty-chart text, here and in set(), is a deferred Msg rendered when read (R2).
        private String unit = "", axisMetric = "";
        private CharSequence empty = M.uiChartEmpty();
        private boolean absoluteAxis;
        private ChartScale.Range range = ChartScale.range("", "", false, 0, 1);
        private int color = accent, selected = -1;
        private double min = 0, max = 1;
        private long first = 0, last = 1, gapThresholdMs = 3500;
        private boolean historyMode;
        private float left, top, right, bottom;
        private final boolean use24Hour = DisplayTime.is24Hour(context,
                context.getSharedPreferences("app_settings", Context.MODE_PRIVATE));
        // English (and every unshipped language) keeps these patterns and the phone's locale (Fmt).
        private final Locale dateLocale = Fmt.locale(Locale.getDefault());
        private final SimpleDateFormat time = new SimpleDateFormat(chartPattern(use24Hour ? CHART_TIME_24 : CHART_TIME_12), dateLocale);
        private final SimpleDateFormat historyTime = new SimpleDateFormat(chartPattern(use24Hour ? CHART_CLOCK_24 : CHART_CLOCK_12), dateLocale);
        private final SimpleDateFormat historyDate = new SimpleDateFormat(chartPattern(CHART_DATE), dateLocale);
        private final SimpleDateFormat historyDateTime = new SimpleDateFormat(chartPattern(use24Hour ? CHART_DATE_CLOCK_24 : CHART_DATE_CLOCK_12), dateLocale);
        private final SimpleDateFormat historyDetail = new SimpleDateFormat(chartPattern(use24Hour ? CHART_DETAIL_24 : CHART_DETAIL_12), dateLocale);
        public ChartView() {
            super(context); setMinimumHeight(dp(210)); setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        }
        public ChartView(Context ignored) { this(); }
        public void setGapThresholdMs(long value) { gapThresholdMs = Math.max(1, value); invalidate(); }
        /** Historical aggregation intervals rely on explicit gaps, rather than the live 3.5 s rule. */
        public void setHistoryMode(boolean value) { historyMode = value; gapThresholdMs = value ? Long.MAX_VALUE : 3500; invalidate(); }
        /** Optional widget preference; existing Live Stats callers retain relative scaling. */
        public void setAxis(String metric, boolean absolute) {
            String key = metric == null ? "" : metric;
            if (axisMetric.equals(key) && absoluteAxis == absolute) return;
            axisMetric = key; absoluteAxis = absolute; updateScale(); invalidate();
        }
        private String axisTime(long millis, int count) {
            long span = last - first;
            SimpleDateFormat format = !historyMode ? time : span / Math.max(1, count - 1) >= 86400000L ? historyDate
                    : span >= 86400000L ? historyDateTime : span < 120000L ? time : historyTime;
            return format.format(new Date(millis));
        }
        private String detailTime(long millis) { return (historyMode ? historyDetail : time).format(new Date(millis)); }
        public void set(List<Point> source, String unit, int color, String empty) {
            points.clear(); this.unit = unit == null ? "" : unit; this.color = color;
            this.empty = empty == null ? M.uiChartEmpty() : empty;
            if (source != null && !source.isEmpty()) {
                int count = Math.min(MAX_POINTS, source.size());
                int previousIndex = -1;
                for (int i = 0; i < count; i++) {
                    int index = count <= 1 ? 0 : (int) ((long) i * (source.size() - 1) / (count - 1));
                    Point point = source.get(index);
                    boolean gap = false;
                    for (int skipped = previousIndex + 1; skipped <= index; skipped++) {
                        Point candidate = source.get(skipped);
                        if (candidate == null || candidate.gapBefore || !Double.isFinite(candidate.value)) gap = true;
                    }
                    if (point != null && Double.isFinite(point.value)) { points.add(new Point(point.millis, point.value, gap)); previousIndex = index; }
                }
                Collections.sort(points, new Comparator<Point>() { public int compare(Point a, Point b) {
                    return a.millis < b.millis ? -1 : a.millis > b.millis ? 1 : 0;
                }});
                for (int i = points.size() - 2; i >= 0; i--) if (points.get(i).millis == points.get(i + 1).millis) points.remove(i);
            }
            if (!points.isEmpty()) {
                first = points.get(0).millis; last = points.get(points.size() - 1).millis;
                if (last == first) last = first + 1000;
            }
            selected = Math.min(selected, points.size() - 1);
            updateScale();
            invalidate();
        }
        private void updateScale() {
            double low = 0, high = 1;
            if (!points.isEmpty()) {
                low = high = points.get(0).value;
                for (Point point : points) { low = Math.min(low, point.value); high = Math.max(high, point.value); }
            }
            range = ChartScale.range(axisMetric, unit, absoluteAxis, low, high); min = range.min; max = range.max;
            setContentDescription(points.isEmpty() ? this.empty : T.uiChartDescription(points.size(), this.unit, points.size(),
                    axisNumber(min), axisNumber(max)));
        }
        public int pointCount() { return points.size(); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(dp(300), width), resolveSize(dp(220), height));
        }
        private float x(long millis) { return left + (float) ((millis - first) / (double) (last - first)) * (right - left); }
        private float y(double value) { return bottom - (float) range.fraction(value) * (bottom - top); }
        @Override protected void onDraw(Canvas canvas) {
            left = dp(44); right = getWidth() - dp(14); top = dp(40); bottom = getHeight() - dp(35);
            if (right <= left || bottom <= top) return;
            paint.setTypeface(REGULAR); paint.setTextSize(Math.min(sp(10), dp(14)));
            paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.FILL);
            int intervals = absoluteAxis ? 4 : 3;
            for (int i = 0; i <= intervals; i++) {
                float yy = top + (bottom - top) * i / intervals;
                paint.setColor(raised); canvas.drawLine(left, yy, right, yy, paint);
                if (!points.isEmpty()) {
                    paint.setColor(muted); paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(axisNumber(range.at(1 - i / (double) intervals)), left - dp(7), yy + dp(3), paint);
                }
            }
            if (points.isEmpty()) {
                paint.setColor(muted); paint.setTextSize(Math.min(sp(13), dp(17))); paint.setTextAlign(Paint.Align.CENTER);
                String message = TextUtils.ellipsize(empty, new android.text.TextPaint(paint), right - left, TextUtils.TruncateAt.END).toString();
                canvas.drawText(message, (left + right) / 2, (top + bottom) / 2, paint); return;
            }
            paint.setTextSize(Math.min(sp(10), dp(14))); paint.setColor(muted); paint.setTextAlign(Paint.Align.LEFT);
            drawTimeLabels(canvas);
            // Each filled segment closes independently, preserving visible data gaps.
            float zero = Math.max(top, Math.min(bottom, y(0)));
            Path line = new Path(), fill = new Path();
            Point previous = null;
            for (Point p : points) {
                float xx = x(p.millis), yy = y(p.value);
                if (previous == null || p.gapBefore || p.millis - previous.millis > gapThresholdMs) {
                    if (previous != null) { fill.lineTo(x(previous.millis), zero); fill.close(); }
                    line.moveTo(xx, yy); fill.moveTo(xx, zero); fill.lineTo(xx, yy);
                } else { line.lineTo(xx, yy); fill.lineTo(xx, yy); }
                previous = p;
            }
            if (previous != null) { fill.lineTo(x(previous.millis), zero); fill.close(); }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, top, 0, bottom, withAlpha(color, 42), withAlpha(color, 4), Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint); paint.setShader(null);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            if (points.size() == 1) canvas.drawCircle(x(points.get(0).millis), y(points.get(0).value), dp(4), paint);
            else for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                boolean gapBefore = i == 0 || p.gapBefore || p.millis - points.get(i - 1).millis > gapThresholdMs;
                boolean gapAfter = i == points.size() - 1 || points.get(i + 1).gapBefore || points.get(i + 1).millis - p.millis > gapThresholdMs;
                if (gapBefore && gapAfter) canvas.drawCircle(x(p.millis), y(p.value), dp(2.5f), paint);
            }
            if (selected >= 0 && selected < points.size()) {
                Point p = points.get(selected); float xx = x(p.millis), yy = y(p.value);
                paint.setColor(withAlpha(color, 75)); paint.setStrokeWidth(dp(1)); canvas.drawLine(xx, top, xx, bottom, paint);
                paint.setColor(surface); canvas.drawCircle(xx, yy, dp(6), paint);
                paint.setColor(color); canvas.drawCircle(xx, yy, dp(3.5f), paint);
                paint.setTypeface(MEDIUM); paint.setTextSize(Math.min(sp(12), dp(16))); paint.setTextAlign(Paint.Align.LEFT);
                String tooltip = unit.isEmpty() ? T.uiChartTooltipUnitless(number(p.value), detailTime(p.millis))
                        : T.uiChartTooltip(number(p.value), unit, detailTime(p.millis));
                float width = Math.min(right - left, paint.measureText(tooltip) + dp(20));
                float start = Math.max(left, Math.min(right - width, xx - width / 2));
                paint.setColor(raised); canvas.drawRoundRect(new RectF(start, dp(4), start + width, dp(30)), dp(10), dp(10), paint);
                paint.setColor(text); canvas.drawText(TextUtils.ellipsize(tooltip, new android.text.TextPaint(paint), width - dp(16), TextUtils.TruncateAt.END).toString(), start + dp(8), dp(21), paint);
            } else {
                paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(Math.min(sp(11), dp(15))); paint.setColor(muted);
                canvas.drawText(unit, left, dp(22), paint);
            }
        }
        private final ArrayList<String> timeLabels = new ArrayList<String>();
        private float[] timeLabelStarts = new float[0], timeLabelWidths = new float[0];
        private void drawTimeLabels(Canvas canvas) {
            float width = right - left;
            timeLabels.clear(); timeLabelStarts = timeLabelWidths = new float[0];
            // Try dense labels first, measuring actual localized glyphs and accessibility scale.
            for (int count = historyMode ? 6 : 2; count >= 2; count--) {
                ArrayList<String> labels = new ArrayList<String>(); float[] widths = new float[count];
                boolean duplicate = false;
                for (int i = 0; i < count; i++) {
                    long millis = first + (long)((last - first) * (i / (double)(count - 1)));
                    String label = axisTime(millis, count);
                    if (labels.contains(label)) duplicate = true;
                    labels.add(label); widths[i] = paint.measureText(label);
                }
                float[] starts = ChartScale.labelStarts(width, widths, dp(12));
                if (!duplicate && starts != null) {
                    timeLabels.addAll(labels); timeLabelStarts = starts; timeLabelWidths = widths; break;
                }
            }
            // A tiny card cannot fit two full timestamps; preserve its most recent label.
            if (timeLabels.isEmpty()) {
                String label = TextUtils.ellipsize(axisTime(last, 2), new android.text.TextPaint(paint),
                        Math.max(1, width), TextUtils.TruncateAt.END).toString();
                timeLabels.add(label); timeLabelWidths = new float[] {paint.measureText(label)};
                timeLabelStarts = new float[] {width - timeLabelWidths[0]};
            }
            paint.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < timeLabels.size(); i++)
                canvas.drawText(timeLabels.get(i), left + timeLabelStarts[i], bottom + dp(21), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (points.isEmpty()) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float xx = Math.max(left, Math.min(right, event.getX()));
                long requested = first + (long) ((xx - left) / Math.max(1, right - left) * (last - first));
                int low = 0, high = points.size() - 1;
                while (low < high) { int mid = (low + high) / 2; if (points.get(mid).millis < requested) low = mid + 1; else high = mid; }
                selected = low;
                if (low > 0 && Math.abs(points.get(low - 1).millis - requested) <= Math.abs(points.get(low).millis - requested)) selected = low - 1;
                Point p = points.get(selected); setContentDescription(T.uiChartPointDescription(number(p.value), unit, detailTime(p.millis)));
                invalidate(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true; }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        // Axis labels and number()'s values under 1000 format with Fmt.locale(Locale.US), the legacy
        // locale while Volt is English; the axis keeps its own "k" placement. Display only: these
        // values go to the axis, the spoken description, the tooltip and the touched point's description.
        private String axisNumber(double value) {
            double scale = Math.max(Math.abs(min), Math.abs(max)) >= 1000 ? 1000 : 1;
            double step = (max / scale / 2 - min / scale / 2) * (2.0 / (absoluteAxis ? 4 : 3));
            int decimals = Math.max(0, Math.min(6, (int) Math.ceil(-Math.log10(step))));
            double scaled = value / scale;
            if (Math.abs(scaled) < Math.pow(10, -decimals) / 2) scaled = 0;
            return String.format(Fmt.locale(Locale.US), "%." + decimals + "f%s", scaled, scale == 1000 ? T.uiChartThousandsSuffix() : "");
        }
        private String number(double value) {
            double absolute = Math.abs(value);
            return absolute >= 10000 ? T.uiChartThousands(Fmt.decimal(Locale.US, value / 1000, 0), T.uiChartThousandsSuffix())
                    : absolute >= 1000 ? T.uiChartThousands(Fmt.decimal(Locale.US, value / 1000, 1), T.uiChartThousandsSuffix())
                    : absolute >= 100 ? String.format(Fmt.locale(Locale.US), "%.0f", value)
                    : String.format(Fmt.locale(Locale.US), "%.1f", value == 0 ? 0 : value);
        }
    }

    private final class IconView extends View {
        String key;
        final int size;
        int color;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(String key, int size, int color) {
            super(context); this.key = key == null ? "info" : key; this.size = size; this.color = color;
            setContentDescription(this.key.replace('_', ' ')); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void key(String next) {
            if (next == null || next.equals(key)) return;
            key = next; invalidate();
        }
        void color(int next) {
            if (next == color) return;
            color = next; invalidate();
        }
        @Override protected void onMeasure(int width, int height) { setMeasuredDimension(resolveSize(dp(size), width), resolveSize(dp(size), height)); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - 24 * scale) / 2, (getHeight() - 24 * scale) / 2); canvas.scale(scale, scale);
            drawSymbol(canvas, key, color, paint);
            canvas.restore();
        }
    }
    private void drawSymbol(Canvas canvas, String key, int color, Paint paint) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();
            if (key.equals("home") || key.equals("dashboard")) {
                p.moveTo(3, 10); p.lineTo(12, 3); p.lineTo(21, 10); p.moveTo(5, 9); p.lineTo(5, 21); p.lineTo(10, 21); p.lineTo(10, 15); p.lineTo(14, 15); p.lineTo(14, 21); p.lineTo(19, 21); p.lineTo(19, 9); canvas.drawPath(p, paint);
            } else if (key.equals("scooter")) {
                canvas.drawCircle(5, 19, 2.6f, paint); canvas.drawCircle(20, 19, 2.6f, paint);
                p.moveTo(5, 17); p.lineTo(8, 17); p.lineTo(11, 20); p.lineTo(17, 20);
                p.moveTo(20, 17); p.lineTo(16, 4); p.lineTo(12, 4); canvas.drawPath(p, paint);
            } else if (key.equals("walk")) {
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(13.5f, 3.5f, 2, paint);
                paint.setStyle(Paint.Style.STROKE);
                p.moveTo(6, 12); p.lineTo(8, 8); p.lineTo(12, 7);
                p.lineTo(15, 11); p.lineTo(19, 12);
                p.moveTo(12, 7); p.lineTo(10.5f, 14); p.lineTo(7, 21);
                p.moveTo(10.5f, 14); p.lineTo(15, 16); p.lineTo(16.5f, 21);
                canvas.drawPath(p, paint);
            } else if (key.equals("speed") || key.equals("speed_average") || key.equals("speed_max")) {
                // The speedometer. Average and Max speed mark it as Halo's ring marks the ride: the needle points up to a
                // dot on the dial for the average, and out to a solid marker past the dial's upper right for the max.
                boolean average = key.equals("speed_average"), max = key.equals("speed_max");
                canvas.drawArc(new RectF(3, 4, 21, 22), 160, 220, false, paint);
                p.moveTo(5, 18); p.lineTo(19, 18); p.moveTo(12, 15);
                if (average) p.lineTo(12, 8); else p.lineTo(max ? 16.6f : 17, max ? 9.2f : 8);
                canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 15, 1.7f, paint);
                if (average) canvas.drawCircle(12, 4, 2.4f, paint);
                if (max) {
                    // A solid marker just outside the dial's upper right, pointing in at the needle.
                    Path mark = new Path(); mark.moveTo(18.9f, 6.1f); mark.lineTo(22.5f, 5.3f); mark.lineTo(19.7f, 2.5f); mark.close();
                    paint.setStyle(Paint.Style.FILL_AND_STROKE); canvas.drawPath(mark, paint);
                }
            } else if (key.equals("trip")) {
                // The distance ridden: from a start point along the way to a flag where the rider is now. (Range keeps the
                // route, two points with the way between them.)
                canvas.drawCircle(5, 19, 2.5f, paint);
                p.moveTo(7.5f, 19); p.cubicTo(12, 19, 11, 15, 16, 15);
                p.moveTo(16, 15); p.lineTo(16, 3); p.lineTo(21, 5.5f); p.lineTo(16, 8);
                canvas.drawPath(p, paint);
            } else if (key.equals("stopwatch")) {
                // Ride time: a stopwatch, its crown on top and its side button, so it isn't taken for the clock.
                canvas.drawCircle(12, 13.5f, 7.5f, paint);
                p.moveTo(12, 6); p.lineTo(12, 3.5f); p.moveTo(9.5f, 2.5f); p.lineTo(14.5f, 2.5f);
                p.moveTo(17.6f, 7.9f); p.lineTo(19.2f, 6.3f); p.moveTo(12, 13.5f); p.lineTo(15, 10.5f);
                canvas.drawPath(p, paint);
            } else if (key.equals("efficiency")) {
                // Energy per distance: a leaf, its midrib running from the stem to the tip.
                p.moveTo(5, 19); p.cubicTo(3, 9, 10, 3, 20, 4); p.cubicTo(21, 14, 15, 21, 5, 19); p.close();
                p.moveTo(5, 19); p.lineTo(14, 10); p.moveTo(3, 21); p.lineTo(5, 19); canvas.drawPath(p, paint);
            } else if (key.equals("voltage") || key.equals("current")) {
                // The meter symbols of a circuit diagram: a circle around a V (voltmeter) or an A (ammeter).
                canvas.drawCircle(12, 12, 9, paint);
                if (key.equals("voltage")) { p.moveTo(8.5f, 7.5f); p.lineTo(12, 16.5f); p.lineTo(15.5f, 7.5f); }
                else { p.moveTo(8.3f, 16.5f); p.lineTo(12, 7.5f); p.lineTo(15.7f, 16.5f); p.moveTo(9.6f, 13.4f); p.lineTo(14.4f, 13.4f); }
                canvas.drawPath(p, paint);
            } else if (key.equals("mode")) {
                // Drive mode: a gear-shift pattern, the gates of a gearbox with a knob at each end.
                for (float x = 6; x <= 18; x += 6) canvas.drawCircle(x, 5, 2, paint);
                canvas.drawCircle(6, 19, 2, paint); canvas.drawCircle(12, 19, 2, paint);
                p.moveTo(6, 7); p.lineTo(6, 17); p.moveTo(12, 7); p.lineTo(12, 17); p.moveTo(18, 7); p.lineTo(18, 12);
                p.moveTo(6, 12); p.lineTo(18, 12); canvas.drawPath(p, paint);
            } else if (key.equals("distance")) {
                // The odometer's distance: an arrow spanning two end marks.
                p.moveTo(3, 7); p.lineTo(3, 17); p.moveTo(21, 7); p.lineTo(21, 17); p.moveTo(6, 12); p.lineTo(18, 12);
                p.moveTo(9, 9); p.lineTo(6, 12); p.lineTo(9, 15); p.moveTo(15, 9); p.lineTo(18, 12); p.lineTo(15, 15);
                canvas.drawPath(p, paint);
            } else if (key.equals("recording")) {
                // Recording time: the record mark, a ring around a solid dot.
                canvas.drawCircle(12, 12, 8.5f, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 4.5f, paint);
            } else if (key.equals("gps_accuracy")) {
                // GPS accuracy: a location crosshair, the fix as a dot inside the ring of how far off it may be.
                canvas.drawCircle(12, 12, 6.5f, paint);
                p.moveTo(12, 2); p.lineTo(12, 5.5f); p.moveTo(12, 18.5f); p.lineTo(12, 22);
                p.moveTo(2, 12); p.lineTo(5.5f, 12); p.moveTo(18.5f, 12); p.lineTo(22, 12); canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 2.2f, paint);
            } else if (key.equals("appearance")) {
                canvas.drawCircle(12, 12, 8, paint); paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(new RectF(4, 4, 20, 20), 90, 180, true, paint);
            } else if (key.equals("ruler")) {
                canvas.drawRoundRect(new RectF(2, 7, 22, 17), 2, 2, paint);
                for (int i = 6; i < 22; i += 4) canvas.drawLine(i, 7, i, i % 8 == 2 ? 12 : 10, paint);
            } else if (key.equals("globe")) {
                // App language: a globe with its equator, two parallels and one meridian.
                canvas.drawCircle(12, 12, 9, paint);
                canvas.drawOval(new RectF(8, 3, 16, 21), paint);
                p.moveTo(3, 12); p.lineTo(21, 12); p.moveTo(4.6f, 7.5f); p.lineTo(19.4f, 7.5f);
                p.moveTo(4.6f, 16.5f); p.lineTo(19.4f, 16.5f); canvas.drawPath(p, paint);
            } else if (key.equals("text")) {
                p.moveTo(3, 19); p.lineTo(8, 5); p.lineTo(13, 19); p.moveTo(5, 14); p.lineTo(11, 14);
                p.moveTo(15, 12); p.lineTo(22, 12); p.moveTo(18.5f, 12); p.lineTo(18.5f, 19); canvas.drawPath(p, paint);
            } else if (key.equals("clock") || key.equals("history")) {
                canvas.drawArc(new RectF(4, 4, 21, 21), key.equals("history") ? -75 : 0, key.equals("history") ? 305 : 360, false, paint);
                p.moveTo(12.5f, 8); p.lineTo(12.5f, 13); p.lineTo(16, 15);
                if (key.equals("history")) { p.moveTo(2, 4); p.lineTo(2, 10); p.lineTo(8, 10); }
                canvas.drawPath(p, paint);
            } else if (key.equals("screen")) {
                canvas.drawRoundRect(new RectF(6, 2, 18, 22), 2, 2, paint); canvas.drawLine(10, 19, 14, 19, paint);
            } else if (key.equals("vibrate")) {
                // A slim phone between two short buzz marks.
                canvas.drawRoundRect(new RectF(8, 3, 16, 21), 2, 2, paint);
                p.moveTo(4, 8); p.lineTo(2.5f, 10); p.lineTo(4, 12); p.lineTo(2.5f, 14); p.lineTo(4, 16);
                p.moveTo(20, 8); p.lineTo(21.5f, 10); p.lineTo(20, 12); p.lineTo(21.5f, 14); p.lineTo(20, 16);
                canvas.drawPath(p, paint);
            } else if (key.equals("sensors")) {
                canvas.drawCircle(12, 12, 2, paint); canvas.drawArc(new RectF(6, 6, 18, 18), -55, 110, false, paint);
                canvas.drawArc(new RectF(6, 6, 18, 18), 125, 110, false, paint);
                canvas.drawArc(new RectF(2, 2, 22, 22), -50, 100, false, paint); canvas.drawArc(new RectF(2, 2, 22, 22), 130, 100, false, paint);
            } else if (key.equals("lab")) {
                p.moveTo(9, 3); p.lineTo(15, 3); p.moveTo(10, 3); p.lineTo(10, 10); p.lineTo(4, 20);
                p.lineTo(20, 20); p.lineTo(14, 10); p.lineTo(14, 3); p.moveTo(7, 15); p.lineTo(17, 15); canvas.drawPath(p, paint);
            } else if (key.equals("sound")) {
                p.moveTo(3, 9); p.lineTo(7, 9); p.lineTo(12, 5); p.lineTo(12, 19); p.lineTo(7, 15); p.lineTo(3, 15); p.close(); canvas.drawPath(p, paint);
                canvas.drawArc(new RectF(8, 6, 20, 18), -60, 120, false, paint); canvas.drawArc(new RectF(7, 2, 25, 22), -45, 90, false, paint);
            } else if (key.equals("recovery")) {
                canvas.drawArc(new RectF(4, 4, 20, 20), 20, 285, false, paint); p.moveTo(20, 3); p.lineTo(20, 8); p.lineTo(15, 8);
                p.moveTo(13, 8); p.lineTo(9, 13); p.lineTo(13, 13); p.lineTo(11, 17); canvas.drawPath(p, paint);
            } else if (key.equals("hill")) {
                p.moveTo(2, 20); p.lineTo(10, 6); p.lineTo(14, 13); p.lineTo(17, 8); p.lineTo(22, 20); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("temperature")) {
                p.moveTo(9, 15); p.lineTo(9, 5); p.cubicTo(9, 1, 15, 1, 15, 5); p.lineTo(15, 15); canvas.drawPath(p, paint);
                canvas.drawArc(new RectF(7, 12, 17, 22), -45, 270, false, paint); canvas.drawLine(12, 8, 12, 18, paint);
            } else if (key.equals("star")) {
                for (int i = 0; i < 10; i++) { double angle = Math.PI * i / 5 - Math.PI / 2; float radius = i % 2 == 0 ? 9 : 4;
                    float x = 12 + radius * (float)Math.cos(angle), y = 12 + radius * (float)Math.sin(angle); if (i == 0) p.moveTo(x,y); else p.lineTo(x,y); }
                p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("lights")) {
                p.moveTo(13, 5); p.cubicTo(21, 4, 22, 20, 13, 19); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(3, 6, 9, 8, paint); canvas.drawLine(2, 12, 9, 12, paint); canvas.drawLine(3, 18, 9, 16, paint);
            } else if (key.equals("stats") || key.equals("chart") || key.equals("analytics")) {
                canvas.drawLine(4, 3, 4, 20, paint); canvas.drawLine(4, 20, 21, 20, paint);
                p.moveTo(7, 15); p.lineTo(11, 10); p.lineTo(15, 13); p.lineTo(21, 5); canvas.drawPath(p, paint);
            } else if (key.equals("battery")) {
                canvas.drawRoundRect(new RectF(3, 6, 20, 18), 2, 2, paint); canvas.drawLine(22, 10, 22, 14, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(6, 9, 14, 15), 1, 1, paint);
            } else if (key.equals("heart")) {
                p.moveTo(12, 21); p.cubicTo(10, 19, 2, 13, 2, 8);
                p.cubicTo(2, 2, 9, 1, 12, 7); p.cubicTo(15, 1, 22, 2, 22, 8);
                p.cubicTo(22, 13, 14, 19, 12, 21); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("gear")) {
                for (int i = 0; i < 32; i++) {
                    double angle = i * Math.PI / 16 - Math.PI / 2;
                    float radius = i % 4 == 0 || i % 4 == 3 ? 10 : 7.8f;
                    float x = 12 + radius * (float) Math.cos(angle);
                    float y = 12 + radius * (float) Math.sin(angle);
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close(); canvas.drawPath(p, paint); canvas.drawCircle(12, 12, 3.3f, paint);
            } else if (key.equals("settings") || key.equals("tune")) {
                for (int i = 0; i < 3; i++) { float yy = 5 + i * 7, xx = i == 1 ? 15 : 8; canvas.drawLine(3, yy, 21, yy, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(surface); canvas.drawCircle(xx, yy, 3, paint); paint.setStyle(Paint.Style.STROKE); paint.setColor(color); canvas.drawCircle(xx, yy, 2.5f, paint); }
            } else if (key.equals("rides") || key.equals("route")) {
                canvas.drawCircle(6, 5, 2.5f, paint); canvas.drawCircle(18, 19, 2.5f, paint);
                p.moveTo(6, 8); p.cubicTo(6, 14, 19, 7, 19, 12); p.cubicTo(19, 16, 9, 12, 9, 18); p.lineTo(15, 18); canvas.drawPath(p, paint);
            } else if (key.equals("bluetooth") || key.equals("scan")) {
                p.moveTo(12, 2); p.lineTo(18, 7); p.lineTo(6, 17); p.moveTo(6, 7); p.lineTo(18, 17); p.lineTo(12, 22); p.lineTo(12, 2); canvas.drawPath(p, paint);
            } else if (key.equals("lock") || key.equals("unlock")) {
                canvas.drawRoundRect(new RectF(5, 10, 19, 21), 2, 2, paint);
                RectF arc = new RectF(8, 3, 16, 15); canvas.drawArc(arc, key.equals("unlock") ? 210 : 180, key.equals("unlock") ? 150 : 180, false, paint);
                canvas.drawLine(12, 14, 12, 17, paint);
            } else if (key.equals("bolt") || key.equals("energy")) {
                p.moveTo(14, 2); p.lineTo(5, 14); p.lineTo(11, 14); p.lineTo(10, 22); p.lineTo(20, 10); p.lineTo(13, 10); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("power")) {
                canvas.drawArc(new RectF(4, 4, 20, 20), -45, 270, false, paint); canvas.drawLine(12, 2, 12, 11, paint);
            } else if (key.equals("download") || key.equals("export")) {
                canvas.drawLine(12, 3, 12, 15, paint); p.moveTo(7, 10); p.lineTo(12, 15); p.lineTo(17, 10); p.moveTo(4, 16); p.lineTo(4, 21); p.lineTo(20, 21); p.lineTo(20, 16); canvas.drawPath(p, paint);
            } else if (key.equals("pencil") || key.equals("edit")) {
                p.moveTo(4, 16); p.lineTo(15.5f, 4.5f); p.quadTo(17, 3, 18.5f, 4.5f);
                p.lineTo(20, 6); p.quadTo(21.5f, 7.5f, 20, 9); p.lineTo(8, 20);
                p.lineTo(3, 21); p.close(); p.moveTo(14, 6); p.lineTo(19, 11);
                p.moveTo(4, 16); p.lineTo(8, 20); canvas.drawPath(p, paint);
            } else if (key.equals("grip") || key.equals("drag")) {
                paint.setStyle(Paint.Style.FILL);
                for (int x = 8; x <= 16; x += 8)
                    for (int y = 5; y <= 19; y += 7) canvas.drawCircle(x, y, 1.5f, paint);
            } else if (key.equals("resize") || key.startsWith("resize_")) {
                canvas.save();
                if (key.equals("resize_nw")) canvas.rotate(180, 12, 12);
                else if (key.equals("resize_ne")) canvas.rotate(270, 12, 12);
                else if (key.equals("resize_sw")) canvas.rotate(90, 12, 12);
                p.moveTo(8, 20); p.lineTo(20, 8); p.moveTo(14, 20); p.lineTo(20, 14);
                canvas.drawPath(p, paint); canvas.restore();
            } else if (key.equals("trash")) {
                canvas.drawLine(4, 6, 20, 6, paint);
                p.moveTo(9, 6); p.lineTo(9, 3); p.lineTo(15, 3); p.lineTo(15, 6);
                p.moveTo(6, 6); p.lineTo(7, 21); p.lineTo(17, 21); p.lineTo(18, 6);
                p.moveTo(10, 10); p.lineTo(10.5f, 17);
                p.moveTo(14, 10); p.lineTo(13.5f, 17); canvas.drawPath(p, paint);
            } else if (key.equals("copy")) {
                canvas.drawRoundRect(new RectF(8, 8, 21, 21), 2, 2, paint);
                p.moveTo(16, 5); p.lineTo(16, 3); p.lineTo(3, 3); p.lineTo(3, 16); p.lineTo(5, 16);
                canvas.drawPath(p, paint);
            } else if (key.equals("more")) {
                paint.setStyle(Paint.Style.FILL);
                for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.8f, paint);
            } else if (key.equals("check")) {
                p.moveTo(4, 12); p.lineTo(9, 17); p.lineTo(20, 6); canvas.drawPath(p, paint);
            } else if (key.equals("record")) {
                // A ring around a filled dot, as the Rides tab's Record button draws it.
                canvas.drawCircle(12, 12, 8.5f, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 4.5f, paint);
            } else if (key.equals("plus")) { canvas.drawLine(12, 4, 12, 20, paint); canvas.drawLine(4, 12, 20, 12, paint); }
            else if (key.equals("close")) { canvas.drawLine(5, 5, 19, 19, paint); canvas.drawLine(5, 19, 19, 5, paint); }
            else if (key.equals("reorder")) {
                // Up and down arrows side by side: "change the order".
                p.moveTo(8, 19); p.lineTo(8, 5); p.moveTo(4.5f, 8.5f); p.lineTo(8, 5); p.lineTo(11.5f, 8.5f);
                p.moveTo(16, 5); p.lineTo(16, 19); p.moveTo(12.5f, 15.5f); p.lineTo(16, 19); p.lineTo(19.5f, 15.5f);
                canvas.drawPath(p, paint);
            } else if (key.equals("chevron") || key.equals("back")) {
                p.moveTo(key.equals("back") ? 15 : 9, 5); p.lineTo(key.equals("back") ? 8 : 16, 12); p.lineTo(key.equals("back") ? 15 : 9, 19); canvas.drawPath(p, paint);
            } else if (key.equals("search")) {
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, paint);
            } else if (key.equals("eye") || key.equals("eye_off")) {
                p.moveTo(2, 12); p.quadTo(12, 2.5f, 22, 12); p.quadTo(12, 21.5f, 2, 12); p.close(); canvas.drawPath(p, paint);
                canvas.drawCircle(12, 12, 3.2f, paint);
                if (key.equals("eye_off")) canvas.drawLine(4, 4, 20, 20, paint);
            } else if (key.equals("restore")) {
                canvas.drawArc(new RectF(4, 4, 20, 20), -150, 150, false, paint); canvas.drawArc(new RectF(4, 4, 20, 20), 30, 150, false, paint);
                p.moveTo(20, 3.5f); p.lineTo(20, 9); p.lineTo(14.5f, 9); p.moveTo(4, 20.5f); p.lineTo(4, 15); p.lineTo(9.5f, 15); canvas.drawPath(p, paint);
            } else if (key.equals("shield")) {
                p.moveTo(12, 2); p.lineTo(21, 6); p.lineTo(19, 15); p.quadTo(17, 20, 12, 22); p.quadTo(7, 20, 5, 15); p.lineTo(3, 6); p.close(); canvas.drawPath(p, paint);
            } else {
                canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 11, 12, 17, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 7, 1, paint);
            }
    }

}
