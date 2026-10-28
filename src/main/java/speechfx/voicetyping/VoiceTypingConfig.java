package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import datapotter.datahelper.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "voice-typing-settings")
@Data
public final class VoiceTypingConfig extends VoiceTypingConfig_A {

    @JsonProperty("provider")
    String provider = "nvidia-streaming";

    @JsonProperty("model")
    String model = "gemini-2.5-flash";

    @JsonProperty("deepgram-model")
    String deepgramModel = "nova-3";

    @JsonProperty("nvidia-function-id")
    String nvidiaFunctionId = "bb0837de-8c7b-481f-9ec8-ef5663e9c1fa";

    @JsonProperty("nvidia-language")
    String nvidiaLanguage = "en-US";

    /** The dictation-window key, e.g. "Ctrl+Z"; empty means the platform default (Win+Y / Ctrl+Option+Y). */
    @JsonProperty("hotkey")
    String hotkey;

    /** The dictate-into-an-app key; empty means the platform default (Win+J / Ctrl+Option+J). */
    @JsonProperty("on-spot-hotkey")
    String onSpotHotkey;

    @JsonProperty("api-keys")
    ApiKeys apiKeys;

    @JsonProperty("recording")
    RecordingSettings recording;

    @JsonProperty("long-recording")
    LongRecordingSettings longRecording;

    @JsonProperty("speech")
    SpeechSettings speech;

    @JsonProperty("on-spot")
    OnSpotSettings onSpot;

    @JsonProperty("microphones")
    MicrophoneSettings microphones;
}
