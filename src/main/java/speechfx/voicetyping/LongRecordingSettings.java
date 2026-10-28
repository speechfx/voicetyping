package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import datapotter.datahelper.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class LongRecordingSettings extends LongRecordingSettings_A {

    @JacksonXmlProperty(isAttribute = true)
    int sampleRate = 16000;

    @JacksonXmlProperty(isAttribute = true)
    int segmentSeconds = 240;

    @JacksonXmlProperty(isAttribute = true)
    int segmentHardMaxSeconds = 330;

    @JacksonXmlProperty(isAttribute = true)
    int silenceGapMillis = 400;

    @JacksonXmlProperty(isAttribute = true)
    int silenceRmsThreshold = 350;

    @JacksonXmlProperty(isAttribute = true)
    int safetyStopMinutes = 90;

    @JacksonXmlProperty(isAttribute = true)
    int autosaveSeconds = 30;

    @JacksonXmlProperty(isAttribute = true)
    int finalizeTimeoutSeconds = 45;

    @JacksonXmlProperty(isAttribute = true)
    boolean keepAudio = true;
}
