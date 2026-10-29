package speechfx.voicetyping;

import java.nio.file.*;
import speechfx.dictation.*;
import speechfx.dictation.controls.*;

/** Runs the first-start settings import against a copy of the real settings in a scratch home: args[0]. */
public class SettingsImportCheck {
    public static void main(String[] args) throws Exception {
        var realApp = VoiceTypingSettings.getAppHome();
        var home = Path.of(args[0]);
        var app = home.resolve("speechfx").resolve("voicetyping");
        Files.createDirectories(app);
        for (var f : new String[]{"VoiceTypingSettings.xml", "key-freezes.json"})
            if (Files.exists(realApp.resolve(f))) Files.copy(realApp.resolve(f), app.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        System.setProperty("user.home", home.toString());
        VoiceTypingSettings.init();
        System.out.println("provider    " + DictationSettings.getProvider());
        System.out.println("deepgram    " + DictationSettings.getDeepgramModel());
        System.out.println("mics        " + DictationSettings.getMicrophoneSettings().preferred());
        System.out.println("long seg    " + DictationSettings.getLongRecordingSettings().segmentSeconds());
        System.out.println("gemini keys " + DictationSettings.getApiKeys().size());
        System.out.println("recordings  " + DictationSettings.getTempDir());
        System.out.println("freezes     " + Files.exists(DictationSettings.getHome().resolve("key-freezes.json")));
        System.out.println("bar         " + VoiceTypingSettings.getOnSpotSettings().barX() + "," + VoiceTypingSettings.getOnSpotSettings().barY());
        VoiceTypingSettings.setOnSpotBar(1, 2);
        System.out.println("app file after a save:\n" + Files.readString(VoiceTypingSettings.getSettingsFilePath()).replaceAll("(?s)<!--.*?-->", ""));
    }
}
