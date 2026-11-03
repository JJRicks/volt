// Measures text widths with java.awt, headless, for tools/l10n/fontmetrics.py (plan 7.2 step 3).
// Not app code: run as a single-file source program, `java -Djava.awt.headless=true TextWidth.java <font.ttf>`.
// Input, one request per line on stdin: <size in px> TAB <text as hex UTF-8>. Output, one line per request:
// the advance width in px (with kerning and ligatures on, as Android lays text out), or "error".
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class TextWidth {
    public static void main(String[] args) throws Exception {
        Font base = Font.createFont(Font.TRUETYPE_FONT, new File(args[0]));
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attributes.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        Font shaped = base.deriveFont(attributes);
        FontRenderContext context = new FontRenderContext(null, true, true);
        Map<Float, Font> sized = new HashMap<>();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            int tab = line.indexOf('\t');
            try {
                float size = Float.parseFloat(line.substring(0, tab));
                String text = new String(hex(line.substring(tab + 1)), StandardCharsets.UTF_8);
                if (text.isEmpty()) {
                    out.println("0");
                    continue;
                }
                Font font = sized.computeIfAbsent(size, shaped::deriveFont);
                out.println(new TextLayout(text, font, context).getAdvance());
            } catch (RuntimeException e) {
                out.println("error");
            }
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
