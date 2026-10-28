package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import datapotter.datahelper.Data;

/**
 * Which speech account to use per provider: the key's short id (SpeechKey.id), or empty to rotate among all usable
 * keys. A pinned key that is frozen is skipped for rotation until it thaws.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class SpeechSettings extends SpeechSettings_A {

    @JacksonXmlProperty(isAttribute = true)
    String deepgramKey = "";

    @JacksonXmlProperty(isAttribute = true)
    String nvidiaKey = "";

    String pinned(String provider) {
        var id = provider.startsWith("deepgram") ? deepgramKey : nvidiaKey;
        return id == null ? "" : id;
    }

    void pin(String provider, String id) {
        if (provider.startsWith("deepgram")) deepgramKey = id; else nvidiaKey = id;
    }
}
