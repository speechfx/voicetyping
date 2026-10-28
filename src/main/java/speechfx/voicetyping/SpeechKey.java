package speechfx.voicetyping;

import datapotter.datahelper.Data;

/** One speech API key from ~/speechfx/keys/&lt;provider&gt;_keys.xml. id is a short hash, safe to log; value never is. */
@Data
public final class SpeechKey extends SpeechKey_A {

    String provider;
    String account;
    String value;
    String id;

    public String label() {
        return provider + " " + (account == null || account.isBlank() ? "(no account)" : account) + " #" + id;
    }

    @Override
    public String toString() {
        return label();
    }
}
