# Voice Typing

Desktop speech-to-text from the speechfx project. A tray app that types what you say into the window in front (Win+J), with a long dictation editor (Win+Y) built on the prosemirrorfx editor.

Providers: Deepgram (streaming), NVIDIA Riva / Nemotron (streaming, gRPC), Gemini (batch). Keys are read from `~/speechfx/keys/<provider>_keys.xml`; settings, notes, history and logs live in `~/speechfx/voicetyping/`.

## Build

    mvn package

`target/speechfx-voicetyping-<version>-app.jar` is the runnable app. On Windows, `mvn -Djrexe -Djr.installDir=<folder on PATH> package` also builds the `vt.exe` launcher. `mvn package -Pmacos` builds the Mac app.

## License

Apache License 2.0.
