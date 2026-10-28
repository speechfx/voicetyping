package speechfx.voicetyping;

/** Which desktop vt is running on; the only place that asks. */
public final class Platform {

    public static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
    public static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private Platform() {}
}
