package vn.yourhrm.core.internal.infra;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.Pictures;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;

/**
 * The two document engines of ADR-0015: HTML templates rendered to PDF/A-2b (OpenHTMLtoPDF, embedded Noto Sans for
 * Vietnamese) and DOCX templates merged with poi-tl. Placeholders are {@code {{key}}} in both; in DOCX an image is
 * {@code {{@key}}}, in HTML an image key yields a {@code data:} URI for {@code <img src="{{key}}">}. Text values are
 * HTML-escaped. DOCX to PDF conversion (Gotenberg / LibreOffice) is a separate service, not done here.
 */
@Component
public class TemplateRenderer {

    /** A placeholder used by a template; {@code image} = DOCX picture tag ({@code @}). */
    public record Tag(String key, boolean image) {
    }

    private static final Pattern HTML_TAG = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.]*)\\s*}}");
    private static final List<String> FONT_FAMILIES = List.of("Noto Sans", "sans-serif", "serif", "Arial",
            "Times New Roman");
    private static final byte[] SRGB = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();

    /** The placeholders of a template; malformed files are refused by the caller. */
    public Set<Tag> tags(String sourceFormat, byte[] template) {
        Set<Tag> out = new LinkedHashSet<>();
        if ("HTML".equals(sourceFormat)) {
            Matcher m = HTML_TAG.matcher(new String(template, StandardCharsets.UTF_8));
            while (m.find()) {
                out.add(new Tag(m.group(1), false));
            }
            return out;
        }
        try (XWPFTemplate t = XWPFTemplate.compile(new ByteArrayInputStream(template))) {
            for (MetaTemplate meta : t.getElementTemplates()) {
                if (meta instanceof ElementTemplate e) {
                    out.add(new Tag(e.getTagName(), Character.valueOf('@').equals(e.getSign())));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Merges an HTML template and renders PDF/A-2b. Values: String / number (text) or byte[] (image). */
    public byte[] htmlToPdf(byte[] template, Map<String, Object> values) {
        Matcher m = HTML_TAG.matcher(new String(template, StandardCharsets.UTF_8));
        StringBuilder merged = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            String text = v instanceof byte[] b ? dataUri(b) : escape(v == null ? "" : v.toString());
            m.appendReplacement(merged, Matcher.quoteReplacement(text));
        }
        m.appendTail(merged);
        org.jsoup.nodes.Document doc = Jsoup.parse(merged.toString());
        doc.head().prependElement("style").text("html, body { font-family: 'Noto Sans'; }");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_2_B);
            b.useColorProfile(SRGB);
            for (String family : FONT_FAMILIES) {
                b.useFont(() -> font("NotoSans-Regular.ttf"), family, 400, FontStyle.NORMAL, true);
                b.useFont(() -> font("NotoSans-Bold.ttf"), family, 700, FontStyle.NORMAL, true);
            }
            b.withW3cDocument(new W3CDom().fromJsoup(doc), "/");
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Merges a DOCX template (keys with dots address nested values, e.g. {@code le.tax_code}). */
    public byte[] mergeDocx(byte[] template, Map<String, Object> values) {
        Map<String, Object> nested = new HashMap<>();
        values.forEach((k, v) -> put(nested, k.split("\\."), 0,
                v instanceof byte[] b ? Pictures.ofBytes(b).size(160, 160).create() : v == null ? "" : v.toString()));
        try (XWPFTemplate t = XWPFTemplate.compile(new ByteArrayInputStream(template)).render(nested);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            t.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> map, String[] path, int i, Object value) {
        if (i == path.length - 1) {
            map.put(path[i], value);
            return;
        }
        Object child = map.computeIfAbsent(path[i], k -> new HashMap<String, Object>());
        if (child instanceof Map<?, ?> c) {
            put((Map<String, Object>) c, path, i + 1, value);
        }
    }

    private static InputStream font(String file) {
        InputStream in = TemplateRenderer.class.getResourceAsStream("/fonts/" + file);
        if (in == null) {
            throw new IllegalStateException("Font " + file + " is missing from the classpath");
        }
        return in;
    }

    private static String dataUri(byte[] image) {
        String media = image.length > 3 && image[0] == (byte) 0x89 ? "image/png" : "image/jpeg";
        return "data:" + media + ";base64," + Base64.getEncoder().encodeToString(image);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
