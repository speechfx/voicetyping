package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import datapotter.datahelper.Data;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class ApiKeys extends ApiKeys_A {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JsonProperty("key")
    List<ApiKey> keys = new ArrayList<>();
}
