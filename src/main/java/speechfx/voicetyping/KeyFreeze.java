package speechfx.voicetyping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import datapotter.datahelper.Data;

/** A key set aside until a moment in time, and why. until is an ISO-8601 instant. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public final class KeyFreeze extends KeyFreeze_A {

    String until;
    String reason;
    String account;
}
