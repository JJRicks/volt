package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The automatic update check's run ownership (UpdateRuns, used by UpdateCheckJob): a run the owner turned off,
 * or one JobScheduler stopped, sends no request, saves nothing and posts no notice once that happens, and only
 * the newest run reports itself finished. Turning the check back on lets the next run work. Listed in
 * tests/l10n/update-checks.sources; tests/run.sh runs it.
 */
public final class UpdateRunsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long NOW = 1_780_000_000_000L;

    /**
     * A fake phone: the setting, the record, a GitHub that can hold its answer, and a notification count. Like
     * UpdateCheckJob's adapter, its request registers an abort hook (closing the connection) before it goes out.
     */
    static final class Phone implements UpdateRuns.Host {
        volatile boolean on = true;
        volatile long last;
        volatile String told = "";
        volatile String release = "9.9.9";
        volatile int calls, requests, saves, posts, aborts;
        volatile CountDownLatch fetching, answer, inLast, leaveLast, beforeHook, connect;
        @Override public boolean enabled() { return on; }
        @Override public long last() {
            if (inLast != null) { inLast.countDown(); await(leaveLast); }
            return last;
        }
        @Override public String newer(UpdateRuns.Run run) throws Exception {
            calls++;
            if (beforeHook != null) { beforeHook.countDown(); await(connect); }
            CountDownLatch held = answer;
            boolean[] closed = {false};
            if (!run.onAbort(() -> { aborts++; closed[0] = true; if (held != null) held.countDown(); }))
                throw new java.util.concurrent.CancellationException();
            requests++;
            if (fetching != null) fetching.countDown();
            if (held != null) await(held);
            if (closed[0]) throw new java.io.IOException("closed");
            return release;
        }
        @Override public void saveLast(long now) { saves++; last = now; }
        @Override public String told() { return told; }
        @Override public boolean post(String latest) { posts++; return true; }
        @Override public void seen(String latest) { told = UpdateSchedule.told(latest, told); }
        void hold() { fetching = new CountDownLatch(1); answer = new CountDownLatch(1); }
        private static void await(CountDownLatch latch) {
            try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("a held step was never released"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }

    public static void main(String[] args) throws Exception {
        oneRun(); optOutBeforeFetch(); optOutMidFetch(); stopMidFetchThenRetry(); backOn(); onceADayAndPerVersion(); manualCheckApart();
        optOutBeforeAdmission(); stopBeforeAdmission(); voidBeforeConnecting(); abortAfterAdmission(); stopAfterAdmission();
        System.out.println("UpdateRunsTest: " + checks + " checks passed (opt-out and stop void a run before or after its request, retry owns its own)");
    }

    private static void oneRun() {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        runs.run(run, NOW);
        check(phone.requests == 1 && phone.saves == 1 && phone.posts == 1, "an ordinary run: one request, one save, one notice");
        check("9.9.9".equals(phone.told) && phone.last == NOW, "it records the time and the version announced");
        check(runs.finish(run), "it finishes its own job");
    }

    private static void optOutBeforeFetch() {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        phone.on = false; runs.revoke();
        runs.run(run, NOW);
        check(phone.requests == 0, "turned off after the job started but before the fetch: no request");
        check(phone.saves == 0 && phone.posts == 0, "and nothing saved or posted");
        check(!runs.finish(run), "a revoked run leaves jobFinished and the next schedule alone");
        // The setting alone (no revoke yet) also stops a run before its request.
        Phone quiet = new Phone(); UpdateRuns other = new UpdateRuns(quiet);
        UpdateRuns.Run later = other.start(); quiet.on = false;
        other.run(later, NOW);
        check(quiet.requests == 0, "a setting already off sends no request");
    }

    private static void optOutMidFetch() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.hold();
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW));
        worker.start();
        check(phone.fetching.await(10, TimeUnit.SECONDS), "the request went out");
        phone.on = false; runs.revoke();
        phone.answer.countDown(); worker.join(10_000);
        check(!worker.isAlive(), "the worker ends");
        check(phone.requests == 1 && phone.saves == 0 && phone.posts == 0, "turned off mid-fetch: no save and no notice");
        check("".equals(phone.told) && phone.last == 0, "no record either");
        // The setting turned off without a revoke (another path writing it) is caught as well.
        Phone other = new Phone(); UpdateRuns owner = new UpdateRuns(other);
        other.hold();
        UpdateRuns.Run second = owner.start();
        Thread again = new Thread(() -> owner.run(second, NOW));
        again.start();
        check(other.fetching.await(10, TimeUnit.SECONDS), "the second request went out");
        other.on = false; other.answer.countDown(); again.join(10_000);
        check(other.saves == 0 && other.posts == 0, "the setting off mid-fetch: no save and no notice");
    }

    private static void stopMidFetchThenRetry() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.hold();
        UpdateRuns.Run first = runs.start();
        Thread worker = new Thread(() -> runs.run(first, NOW));
        worker.start();
        check(phone.fetching.await(10, TimeUnit.SECONDS), "the first run's request went out");
        check(runs.stop(first), "stopped while the check is on: a retry is wanted");
        // JobScheduler's retry: a new run while the first worker is still waiting on GitHub.
        CountDownLatch firstAnswer = phone.answer;
        phone.fetching = null; phone.answer = null;
        UpdateRuns.Run retry = runs.start();
        runs.run(retry, NOW + 1);
        check(phone.saves == 1 && phone.posts == 1 && phone.last == NOW + 1, "the retry does the work once");
        check(runs.finish(retry), "the retry owns jobFinished and the next schedule");
        firstAnswer.countDown(); worker.join(10_000);
        check(!worker.isAlive(), "the stopped worker ends");
        check(phone.requests == 2 && phone.saves == 1 && phone.posts == 1, "only one run's side effects");
        check(phone.last == NOW + 1, "the stopped run doesn't overwrite the retry's record");
        check(!runs.finish(first), "the stopped run never calls jobFinished or schedules");
        // Stopped while off: no retry.
        Phone off = new Phone(); UpdateRuns quiet = new UpdateRuns(off);
        UpdateRuns.Run run = quiet.start(); off.on = false;
        check(!quiet.stop(run), "stopped after the switch went off: no retry");
        // A newer run alone (no stop) also takes ownership from an older one.
        Phone two = new Phone(); UpdateRuns pair = new UpdateRuns(two);
        UpdateRuns.Run old = pair.start(); UpdateRuns.Run fresh = pair.start();
        pair.run(old, NOW);
        check(two.requests == 0 && !pair.finish(old) && pair.finish(fresh), "an older run yields to a newer one");
    }

    private static void backOn() {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run before = runs.start();
        phone.on = false; runs.revoke();
        runs.run(before, NOW);
        check(phone.requests == 0, "off: nothing");
        phone.on = true;
        UpdateRuns.Run next = runs.start();
        runs.run(next, NOW);
        check(phone.requests == 1 && phone.saves == 1 && phone.posts == 1, "turned back on: the next run works");
        check(runs.finish(next), "and finishes its job");
    }

    private static void onceADayAndPerVersion() {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        runs.run(runs.start(), NOW);
        runs.run(runs.start(), NOW + 1000);
        check(phone.requests == 1, "a second run the same day sends no request");
        runs.run(runs.start(), NOW + UpdateSchedule.DAY_MS);
        check(phone.requests == 2 && phone.posts == 1, "a day later: asks again, but the same version isn't announced twice");
        phone.release = "10.0.0";
        runs.run(runs.start(), NOW + 2 * UpdateSchedule.DAY_MS);
        check(phone.posts == 2 && "10.0.0".equals(phone.told), "a newer version is announced");
        Phone none = new Phone(); none.release = null; UpdateRuns quiet = new UpdateRuns(none);
        runs = quiet; runs.run(runs.start(), NOW);
        check(none.saves == 1 && none.posts == 0, "no newer release: the time is saved, nothing posted");
    }

    /** The manual Check for updates keeps its own path: it never goes through UpdateRuns or the job's guard. */
    private static void manualCheckApart() throws Exception {
        String more = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MoreScreen.java")), StandardCharsets.UTF_8);
        check(!more.contains("UpdateRuns") && !more.contains("UpdateCheckJob.runs(") && !more.contains("UpdateCheck.opened"), "MoreScreen's manual check doesn't share the job's run guard");
        String job = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/UpdateCheckJob.java")), StandardCharsets.UTF_8);
        int cancel = job.indexOf("static void cancel(Context context) {");
        check(cancel > 0 && job.indexOf("runs(context).revoke();", cancel) > cancel
                && job.indexOf("runs(context).revoke();", cancel) < job.indexOf("jobs.cancel(JOB_ID)", cancel), "turning off revokes the running check before cancelling the job");
        check(job.contains("UpdateCheck.opened.set(") && job.contains("finally { UpdateCheck.opened.remove(); }"), "the abort hook is set only around the job's own request");
        check(job.contains("if (!owner.finish(mine)) return;") && job.contains("runs(this).stop(stopped)"), "the job finishes and retries only through UpdateRuns");
    }

    /** Codex's interleaving: the worker read the setting's record, then the owner turned it off before the request. */
    private static void optOutBeforeAdmission() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.inLast = new CountDownLatch(1); phone.leaveLast = new CountDownLatch(1);
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW)); worker.start();
        check(phone.inLast.await(10, TimeUnit.SECONDS), "the worker is paused reading the record");
        phone.on = false; runs.revoke();
        phone.leaveLast.countDown(); worker.join(10_000);
        check(!worker.isAlive() && phone.calls == 0 && phone.requests == 0, "turned off while the worker read the record: no request");
        check(phone.saves == 0 && phone.posts == 0, "and nothing saved or posted");
    }
    private static void stopBeforeAdmission() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.inLast = new CountDownLatch(1); phone.leaveLast = new CountDownLatch(1);
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW)); worker.start();
        check(phone.inLast.await(10, TimeUnit.SECONDS), "paused before admission");
        check(runs.stop(run), "stopped while on: a retry is wanted");
        phone.leaveLast.countDown(); worker.join(10_000);
        check(phone.calls == 0 && phone.requests == 0 && phone.saves == 0, "stopped before admission: no request");
    }
    /** Admitted, then revoked before the connection opened: the hook is refused and the request never goes out. */
    private static void voidBeforeConnecting() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.beforeHook = new CountDownLatch(1); phone.connect = new CountDownLatch(1);
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW)); worker.start();
        check(phone.beforeHook.await(10, TimeUnit.SECONDS), "admitted, about to connect");
        phone.on = false; runs.revoke();
        phone.connect.countDown(); worker.join(10_000);
        check(phone.calls == 1 && phone.requests == 0, "revoked between admission and connecting: the request doesn't go out");
        check(phone.saves == 0 && phone.posts == 0 && phone.aborts == 0, "nothing saved, posted or left to close");
    }
    /** Revoked right after admission, with the request out: revoke returns at once and closes the request. */
    private static void abortAfterAdmission() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.hold();
        CountDownLatch held = phone.answer;
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW)); worker.start();
        check(phone.fetching.await(10, TimeUnit.SECONDS), "the request is out");
        // The UI thread's revoke must not wait on the network: hold GitHub's answer for good and time the call.
        long started = System.nanoTime();
        phone.on = false; runs.revoke();
        long took = (System.nanoTime() - started) / 1_000_000;
        check(took < 2000, "revoke returns without waiting on the fetch (" + took + " ms)");
        check(phone.aborts == 1 && held.getCount() == 0, "revoke closed the request through its hook");
        worker.join(10_000);
        check(!worker.isAlive() && phone.requests == 1, "one request, closed");
        check(phone.saves == 0 && phone.posts == 0, "no save and no notice");
    }
    private static void stopAfterAdmission() throws Exception {
        Phone phone = new Phone(); UpdateRuns runs = new UpdateRuns(phone);
        phone.hold();
        UpdateRuns.Run run = runs.start();
        Thread worker = new Thread(() -> runs.run(run, NOW)); worker.start();
        check(phone.fetching.await(10, TimeUnit.SECONDS), "the request is out");
        check(runs.stop(run), "stopped mid-request while on: retry wanted");
        check(phone.aborts == 1, "stop closed the request through its hook");
        worker.join(10_000);
        check(!worker.isAlive() && phone.saves == 0 && phone.posts == 0 && !runs.finish(run), "the stopped run saves, posts and finishes nothing");
    }
}
