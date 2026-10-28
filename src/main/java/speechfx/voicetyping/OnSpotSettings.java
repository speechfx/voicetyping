package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import datapotter.datahelper.Data;

/**
 * Dictating into other apps. Text up to typeMaxChars is typed as keys, longer text is pasted; pasteApps
 * and typeApps (comma-separated exe names without .exe, e.g. "winword, code") force one way for a program where
 * the other misbehaves. barX/barY are where the floating bar was last dragged to; -1 means bottom centre.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class OnSpotSettings extends OnSpotSettings_A {

    @JacksonXmlProperty(isAttribute = true)
    int typeMaxChars = 200;

    @JacksonXmlProperty(isAttribute = true)
    String pasteApps = "";

    @JacksonXmlProperty(isAttribute = true)
    String typeApps = "";

    @JacksonXmlProperty(isAttribute = true)
    double barX = -1;

    @JacksonXmlProperty(isAttribute = true)
    double barY = -1;

    boolean listed(String apps, String app) {
        if (apps == null || app.isEmpty()) return false;
        for (var a : apps.split(",")) if (a.trim().equalsIgnoreCase(app)) return true;
        return false;
    }

    /** Paste rather than type this text into this program. */
    boolean pastes(String app, int length) {
        if (listed(pasteApps, app)) return true;
        return !listed(typeApps, app) && length > typeMaxChars;
    }
}
