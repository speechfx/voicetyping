package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import datapotter.datahelper.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class RecordingSettings extends RecordingSettings_A {

    @JacksonXmlProperty(isAttribute = true)
    int maxDurationSeconds = 120;

    @JacksonXmlProperty(isAttribute = true)
    int sampleRate = 44100;
}
