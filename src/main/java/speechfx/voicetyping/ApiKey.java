package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import datapotter.datahelper.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class ApiKey extends ApiKey_A {

    @JacksonXmlProperty(isAttribute = true)
    String name;

    @JacksonXmlProperty(isAttribute = true)
    String enabled = "y";

    @JacksonXmlText
    String value;

    public ApiKey() {}

    public ApiKey(String name, String value, String enabled) {
        this.name = name;
        this.value = value;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled == null || enabled.isEmpty() || enabled.equalsIgnoreCase("y");
    }
}
