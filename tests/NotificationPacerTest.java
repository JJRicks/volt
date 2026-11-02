package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NotificationPacerTest {
    private static int checks;
    private static final int CONNECTION = 71, ALERT = 76, PAUSED = 77;
    public static void main(String[] args) {
        long gap = NotificationPacer.GAP_MS;
        NotificationPacer<String> pacer = new NotificationPacer<>(ALERT);
        require(pacer.take(0) == null && pacer.delay(0) == -1, "Nothing waits at first");

        // The first notice goes at once.
        pacer.offer(CONNECTION, "c1");
        require(pacer.delay(5) == 0, "First notice is due at once");
        Map.Entry<Integer, String> e = pacer.take(5);
        require(e.getKey() == CONNECTION && "c1".equals(e.getValue()), "First notice taken");

        // A burst: the connection notice changes twice and an alert fires. The alert goes first, the connection notice
        // keeps only its latest text, and each post waits a full gap.
        pacer.offer(CONNECTION, "c2"); pacer.offer(ALERT, "alert"); pacer.offer(CONNECTION, "c3");
        require(pacer.take(6) == null && pacer.delay(6) == gap - 1, "Within the gap, nothing goes");
        e = pacer.take(5 + gap);
        require(e.getKey() == ALERT && "alert".equals(e.getValue()), "The alert goes ahead of the queue");
        require(pacer.take(5 + gap + 1) == null, "The next post waits another gap");
        e = pacer.take(5 + 2 * gap);
        require(e.getKey() == CONNECTION && "c3".equals(e.getValue()), "Only the latest connection notice posts");
        require(pacer.take(5 + 3 * gap) == null && pacer.delay(5 + 3 * gap) == -1, "Nothing else waits");

        // Cancelling drops a waiting notice for good; waiting() tells a queued one apart.
        pacer.offer(PAUSED, "paused");
        require(pacer.waiting(PAUSED), "A queued notice is waiting");
        pacer.cancel(PAUSED);
        require(!pacer.waiting(PAUSED) && pacer.take(10_000) == null, "A cancelled notice never posts");

        // A post made outside the pacer (startForeground) counts toward the gap and replaces its id's waiting notice.
        pacer.offer(CONNECTION, "stale"); pacer.offer(PAUSED, "p2");
        pacer.posted(CONNECTION, 20_000);
        require(!pacer.waiting(CONNECTION) && pacer.take(20_000 + gap - 1) == null, "startForeground resets the gap");
        e = pacer.take(20_000 + gap);
        require(e.getKey() == PAUSED, "The other notice follows a gap later");

        // The service ending takes everything still waiting at once, the alert first.
        pacer.offer(CONNECTION, "c"); pacer.offer(ALERT, "a"); pacer.offer(PAUSED, "p");
        List<Map.Entry<Integer, String>> all = pacer.takeAll();
        require(all.size() == 3 && all.get(0).getKey() == ALERT && all.get(1).getKey() == CONNECTION && all.get(2).getKey() == PAUSED, "takeAll: alert first, then in order");
        require(pacer.delay(25_000) == -1, "takeAll empties the queue");

        // Clearing drops everything.
        pacer.offer(ALERT, "a"); pacer.offer(CONNECTION, "c"); pacer.clear();
        require(pacer.delay(30_000) == -1, "Clear empties the queue");

        // Android's estimator (RateEstimator: est = alpha·est + (1-alpha)·dt, shed while 1/est > 5) never trips on
        // the paced stream, even with an unpaced startForeground right after a paced post, for alpha 0.7 and 0.8.
        for (double alpha : new double[] {0.7, 0.8}) {
            NotificationPacer<String> p = new NotificationPacer<>(ALERT);
            List<Long> posts = new ArrayList<>();
            long now = 0;
            for (int round = 0; round < 40; round++) {
                for (int i = 0; i < 5; i++) p.offer(70 + i, "n");
                if (round % 7 == 3) { p.posted(CONNECTION, now); posts.add(now); }
                for (int step = 0; step < 4000 && p.delay(now) >= 0; step++, now++) if (p.take(now) != null) posts.add(now);
                now += 50;
            }
            double est = 1000; Long previous = null; double worst = 0;
            for (long t : posts) {
                if (previous != null) {
                    double dt = Math.max(0.0005, (t - previous) / 1000.0), check = alpha * est + (1 - alpha) * dt;
                    worst = Math.max(worst, 1 / check);
                    est = check;
                }
                previous = t;
            }
            require(posts.size() > 150, "The simulation posted a long stream");
            require(worst <= 5, "Paced posts stay at or under 5 a second (alpha " + alpha + ", worst " + worst + ")");
        }
        // Unpaced, a burst of six posts a couple of milliseconds apart, after one a second, is what Android sheds.
        double est = 1.0, unpaced = 0;
        for (int i = 0; i < 6; i++) { double check = 0.7 * est + 0.3 * 0.002; unpaced = Math.max(unpaced, 1 / check); est = check; }
        require(unpaced > 5, "Unpaced, a short burst exceeds the limit");

        System.out.println("NotificationPacerTest: " + checks + " checks passed");
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
