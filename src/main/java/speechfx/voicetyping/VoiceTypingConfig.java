package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import datapotter.datahelper.Data;

/** The app's own settings; provider, keys, microphones and recording live in the shared dictation settings. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "voice-typing-settings")
@Data
public final class VoiceTypingConfig extends VoiceTypingConfig_A {

    /** The dictation-window key, e.g. "Ctrl+Z"; empty means the platform default (Win+Y / Ctrl+Option+Y). */
    @JsonProperty("hotkey")
    String hotkey;

    /** The dictate-into-an-app key; empty means the platform default (Win+J / Ctrl+Option+J). */
    @JsonProperty("on-spot-hotkey")
    String onSpotHotkey;

    @JsonProperty("on-spot")
    OnSpotSettings onSpot;
}
