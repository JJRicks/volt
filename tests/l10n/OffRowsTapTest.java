package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Council F04 on #627: a settings row refused because the scooter is switched off says why when tapped, inline
 * choices included (Line lights and Star lights patterns, colour swatches), read from MainActivity's source.
 */
public final class OffRowsTapTest {
    private static int checks;
    public static void main(String[] args) throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MainActivity.java")), StandardCharsets.UTF_8).replace("\r", "");
        int refusals = src.split("if\\(offWords!=null\\)\\{toast\\(offWords\\);return;\\}", -1).length - 1;
        check(refusals == 3, "the pattern selector and both kinds of swatch say the off refusal on tap: " + refusals);
        check(src.contains("if(offWords!=null){toast(offWords);return;}\n                        chooseInline(feature,feature.optionValues[index]);"), "the pattern selector");
        check(src.contains("boolean tappable=usable||offWords!=null;") && src.contains("patternSelector.setEnabled(tappable);") && src.contains("view.setEnabled(tappable);"),
                "the choices stay enabled (greyed) while off, so the tap reaches them");
        check(src.contains("else if(offWords!=null)toast(offWords);else if(toggle.isEnabled())") && src.contains("else if(offWords!=null)toast(offWords);else editFeature(feature);"),
                "switch and chevron rows, as before");
        System.out.println("OffRowsTapTest: " + checks + " checks passed");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }
}
