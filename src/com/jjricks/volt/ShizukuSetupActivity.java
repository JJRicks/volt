package com.jjricks.volt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit, optional phone-only setup. An unexported Activity, separate from normal BLE operation. */
public final class ShizukuSetupActivity extends Activity {
    public static final int REQUEST = 204;
    /** Result when the owner chose to import a backup file instead of capturing here. */
    public static final int RESULT_IMPORT_BACKUP = RESULT_FIRST_USER;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    /** This screen's own status line, kept English in capture.message like the client's; shown by statusText. */
    static final String NO_BROWSER = "No browser found";
    private Ui ui;
    private LinearLayout content;
    private ShizukuCaptureClient capture;
    private boolean consent;
    private android.window.OnBackInvokedCallback systemBack;
    private final Runnable update = this::render;
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override public void onCreate(Bundle saved) {
        SharedPreferences settings = getSharedPreferences("app_settings", MODE_PRIVATE);
        String theme = settings.getString("theme", "dark");
        boolean dark = "dark".equals(theme) || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.VoltDark : R.style.VoltLight);
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 33) {
            systemBack = this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemBack);
        }
        ui = new Ui(this, dark, settings.getFloat("text_scale", 1f));
        capture = ShizukuCaptureClient.get(this);
        render();
    }
    @Override protected void onResume() { super.onResume(); capture.listen(update); render(); }
    @Override protected void onPause() { capture.unlisten(update); super.onPause(); }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && systemBack != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        capture.unlisten(update);
        if (isFinishing()) { capture.cancel(); capture.discard(); }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (capture.busy) {
            new AlertDialog.Builder(this).setTitle(T.ownerShizukuStopTitle())
                    .setMessage(T.ownerShizukuStopMessage())
                    .setNegativeButton(T.ownerShizukuKeepWaiting(), null).setPositiveButton(T.ownerShizukuStop(), (d, w) -> capture.cancel()).show();
        } else super.onBackPressed();
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(ui.bg);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.systemBars()) : null;
            scroll.setPadding(bars == null ? 0 : bars.left, bars == null ? insets.getSystemWindowInsetTop() : bars.top,
                    bars == null ? 0 : bars.right, bars == null ? insets.getSystemWindowInsetBottom() : bars.bottom);
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        text(T.ownerShizukuTitle(), 28, true);
        if (android.os.Process.myUid() >= 100000) {
            text(T.ownerShizukuMainProfileOnly(), 16, false);
            button(T.ownerShizukuImportBackup(), () -> { setResult(RESULT_IMPORT_BACKUP); finish(); });
            button(T.ownerShizukuBackToChoices(), this::finish); return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            // Setup disables this path below Android 11; kept only as a guard.
            text(T.ownerShizukuNeedsAndroid11(), 16, false);
            button(T.ownerShizukuBackToChoices(), this::finish); return;
        }
        if (capture.busy) {
            boolean connecting = capture.phase == OwnerCaptureEngine.CONNECT_NAVEE;
            String title = connecting ? T.ownerShizukuConnectingTitle()
                    : capture.phase == OwnerCaptureEngine.CLEANING ? T.ownerShizukuRestoringTitle() : T.ownerShizukuPreparingTitle();
            text(title, 22, true);
            if (connecting && capture.captureSinceMs > 0) {
                Countdown ring = new Countdown(capture.captureSinceMs);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ui.dp(132), ui.dp(132));
                rp.gravity = Gravity.CENTER_HORIZONTAL; rp.bottomMargin = ui.dp(18);
                content.addView(ring, rp);
            }
            text(connecting ? T.ownerShizukuConnectingHint() : T.ownerShizukuKeepRunning(), 16, false);
            button(T.ownerShizukuCancelCapture(), capture::cancel); return;
        }
        if (capture.pendingCleanup()) {
            text(T.ownerShizukuCleanupTitle(), 22, true);
            text(T.ownerShizukuCleanupMessage(), 16, false);
            status(); setupLinks();
            if (!capture.permitted()) permissionButton();
            else button(T.ownerShizukuRestoreLogging(), () -> capture.start(true));
            button(T.ownerShizukuBack(), this::finish); return;
        }
        if (capture.owner != null) {
            text(T.ownerShizukuReadyTitle(), 22, true);
            LinearLayout checks = group();
            checkRow(checks, capture.owner.role == 1 ? T.ownerShizukuSharedIdentityFound() : T.ownerShizukuOwnerIdentityFound(), true);
            checkRow(checks, T.ownerShizukuNaveeRestored(), false);
            button(T.ownerShizukuImportOwnerSetup(), () -> {
                if (capture.approveImport()) { setResult(RESULT_OK); finish(); }
            });
            button(T.ownerShizukuDiscard(), capture::discard);
            return;
        }
        if (!capture.available()) {
            steps(1);
            text(T.ownerShizukuStartTitle(), 22, true);
            status();
            boolean installed = shizukuInstalled(), running = installed && capture.running();
            LinearLayout checks = group();
            stateRow(checks, T.ownerShizukuInstalled(), null, installed, T.ownerShizukuInstall(), () -> open("https://shizuku.rikka.app/download/"), true);
            stateRow(checks, T.ownerShizukuRunning(), running ? capture.unavailableReason() : null, false,
                    T.ownerShizukuOpen(), this::openShizuku, false);
            button(T.ownerShizukuSetupGuide(), () -> open("https://shizuku.rikka.app/guide/setup/"));
            button(T.ownerShizukuBackToChoices(), this::finish); return;
        }
        if (!capture.permitted()) {
            steps(2);
            text(T.ownerShizukuAllowTitle(), 22, true);
            status();
            text(T.ownerShizukuAllowWarning(), 16, false);
            if (capture.allowInShizuku) {
                TextView openShizuku = button(T.ownerShizukuOpenShizuku(), this::openShizuku);
                openShizuku.setContentDescription(T.ownerShizukuOpenShizukuDescription());
            } else permissionButton();
            button(T.ownerShizukuHelp(), () -> open("https://shizuku.rikka.app/guide/setup/"));
            button(T.ownerShizukuBackToChoices(), this::finish); return;
        }
        steps(3);
        text(T.ownerShizukuCaptureTitle(), 22, true);
        status();
        text(T.ownerShizukuCaptureParked(), 16, false);
        text(T.ownerShizukuCaptureWarning(), 15, false);
        CheckBox agree = new CheckBox(this); agree.setText(T.ownerShizukuConsent());
        agree.setTextColor(ui.text); agree.setTextSize(15 * ui.textScale); agree.setChecked(consent);
        agree.setPadding(0, ui.dp(12), 0, ui.dp(12)); agree.setMinHeight(ui.dp(48)); content.addView(agree);
        TextView start = button(T.ownerShizukuStartCapture(), () -> { if (consent) { consent = false; capture.start(false); } });
        start.setEnabled(consent); start.setAlpha(consent ? 1f : .45f);
        agree.setOnCheckedChangeListener((view, checked) -> {
            consent = checked; start.setEnabled(checked); start.setAlpha(checked ? 1f : .45f);
        });
        button(T.ownerShizukuBackToChoices(), this::finish);
    }

    private void setupLinks() {
        button(T.ownerShizukuSetupGuide(), () -> open("https://shizuku.rikka.app/guide/setup/"));
        button(T.ownerShizukuInstallShizuku(), () -> open("https://shizuku.rikka.app/download/"));
        button(T.ownerShizukuOpenShizuku(), this::openShizuku);
    }
    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (launch != null) startActivity(launch); else open("https://shizuku.rikka.app/download/");
    }
    private boolean shizukuInstalled() {
        try { getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException | RuntimeException missing) { return false; }
    }
    private void permissionButton() {
        TextView grant = button(capture.available() ? T.ownerShizukuAllowAccess() : T.ownerShizukuCheckConnection(), () -> {
            if (capture.available()) capture.requestPermission(); else { capture.message = capture.unavailableReason(); render(); }
        });
        grant.setContentDescription(capture.available() ? T.ownerShizukuAllowAccessDescription() : T.ownerShizukuCheckConnectionDescription());
    }
    private void status() { if (!capture.message.isEmpty()) text(statusText(capture.message), 15, true); }
    /** A status line in the current language (ShizukuStatus, or this screen's NO_BROWSER). */
    private static String statusText(String message) {
        return NO_BROWSER.equals(message) ? T.ownerStatusNoBrowser() : ShizukuStatus.display(message);
    }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException ignored) { capture.message = NO_BROWSER; render(); }
    }
    private void text(String value, float size, boolean bold) {
        TextView view = ui.text(value, size, bold); view.setLineSpacing(ui.dp(3), 1);
        if (!bold) view.setTextColor(ui.muted);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(16);
        content.addView(view, p);
        if (bold && size >= 20 && Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
    private TextView button(String label, Runnable action) {
        TextView button = ui.button(label, action); button.setGravity(Gravity.CENTER); button.setMinHeight(ui.dp(52));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(12);
        content.addView(button, p); return button;
    }

    /** Three-step progress, drawn like setup's own segmented bar. */
    private void steps(int current) {
        Steps bar = new Steps(current);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(20)); p.bottomMargin = ui.dp(14);
        content.addView(bar, p);
    }
    private LinearLayout group() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(ui.shape(ui.surface, 18, 0));
        box.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(18);
        content.addView(box, p);
        return box;
    }
    /** A result row with a green check, e.g. "NAVEE restored". */
    private void checkRow(LinearLayout parent, String label, boolean first) {
        if (!first) ui.divider(parent);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(52));
        TextView name = ui.text(label, 15.5f, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(checkMark(), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        row.setFocusable(true); row.setContentDescription(T.ownerStepDone(label));
        parent.addView(row);
    }
    /** A live checklist row: a check when done, otherwise a small button that does the step. */
    private void stateRow(LinearLayout parent, String label, String problem, boolean done, String action, Runnable run, boolean first) {
        problem = statusText(problem);
        if (!first) ui.divider(parent);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56)); row.setPadding(0, ui.dp(6), 0, ui.dp(6));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(label, 15.5f, false));
        if (problem != null && !problem.isEmpty()) {
            TextView why = ui.caption(problem); why.setTextColor(ui.danger); why.setPadding(0, ui.dp(2), 0, 0);
            words.addView(why);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.rightMargin = ui.dp(10);
        row.addView(words, wp);
        if (done) {
            row.addView(checkMark(), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
            row.setFocusable(true); row.setContentDescription(T.ownerStepDone(label));
        } else {
            TextView go = ui.secondaryButton(action, run);
            go.setMinWidth(ui.dp(76)); go.setPadding(ui.dp(14), 0, ui.dp(14), 0);
            go.setContentDescription(action + " " + label.replace("Shizuku ", "Shizuku, ").toLowerCase(java.util.Locale.ROOT));
            row.addView(go, new LinearLayout.LayoutParams(-2, ui.dp(48)));
            words.setFocusable(true);
            words.setContentDescription(problem == null || problem.isEmpty() ? T.ownerStepNotYet(label) : T.ownerStepNotYetProblem(label, problem));
        }
        parent.addView(row);
    }
    private View checkMark() {
        FrameLayout chip = new FrameLayout(this);
        chip.setBackground(ui.shape(Ui.withAlpha(ui.ok, ui.dark ? 50 : 36), 100, 0));
        chip.addView(ui.icon("check", 16, ui.ok), new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return chip;
    }

    private final class Steps extends View {
        private final int current; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Steps(int current) {
            super(ShizukuSetupActivity.this); this.current = current;
            setContentDescription(T.ownerStepsDescription(current));
            setFocusable(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            float gap = ui.dp(4), h = ui.dp(5), w = (getWidth() - gap * 2) / 3f, top = (getHeight() - h) / 2f;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            for (int k = 1; k <= 3; k++) {
                float left = rtl ? getWidth() - k * w - (k - 1) * gap : (k - 1) * (w + gap);
                RectF bar = new RectF(left, top, left + w, top + h);
                paint.setColor(k < current ? ui.accent : ui.raised);
                canvas.drawRoundRect(bar, h / 2, h / 2, paint);
                if (k == current) {
                    canvas.save();
                    float half = w / 2;
                    canvas.clipRect(rtl ? bar.right - half : bar.left, bar.top, rtl ? bar.right : bar.left + half, bar.bottom);
                    paint.setColor(ui.accent); canvas.drawRoundRect(bar, h / 2, h / 2, paint);
                    canvas.restore();
                }
            }
        }
    }

    /** Ring that empties over the 90-second capture window, with the seconds left in the middle. */
    private final class Countdown extends View {
        private final long since; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG), unit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int spoken = -1;
        private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); postDelayed(this, 1000); } };
        Countdown(long since) {
            super(ShizukuSetupActivity.this); this.since = since;
            number.setTextAlign(Paint.Align.CENTER); number.setTypeface(ui.bold()); number.setColor(ui.text);
            unit.setTextAlign(Paint.Align.CENTER); unit.setTypeface(ui.regular()); unit.setColor(ui.muted);
            setFocusable(true);
        }
        private int secondsLeft() {
            long elapsed = System.nanoTime() / 1000000L - since;
            return (int) Math.max(0, (OwnerCaptureEngine.CAPTURE_MS - elapsed + 999) / 1000);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas canvas) {
            int left = secondsLeft();
            if (left != spoken) { spoken = left; setContentDescription(T.ownerCountdownSecondsLeft(left, left)); }
            float s = Math.min(getWidth(), getHeight()), stroke = ui.dp(8), cx = getWidth() / 2f, cy = getHeight() / 2f;
            RectF ring = new RectF(cx - s / 2 + stroke / 2, cy - s / 2 + stroke / 2, cx + s / 2 - stroke / 2, cy + s / 2 - stroke / 2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.raised); canvas.drawOval(ring, paint);
            float fraction = left / (OwnerCaptureEngine.CAPTURE_MS / 1000f);
            paint.setColor(ui.accent);
            if (fraction > 0) canvas.drawArc(ring, -90, 360 * fraction, false, paint);
            number.setTextSize(ui.sp(30)); unit.setTextSize(ui.sp(12.5f));
            canvas.drawText(Integer.toString(left), cx, cy + ui.sp(4), number);
            canvas.drawText(T.ownerCountdownSecondsLeftShort(), cx, cy + ui.sp(24), unit);
        }
    }
}
