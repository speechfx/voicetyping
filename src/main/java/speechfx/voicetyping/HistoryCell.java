package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/** One history entry: the first words in bold, the time beneath in grey. */
public final class HistoryCell extends ListCell<HistoryItem> {

    final Label preview = new Label(), time = new Label();
    final VBox box = new VBox(1, preview, time);

    public HistoryCell() {
        preview.getStyleClass().add("vt-history-preview");
        time.getStyleClass().add("vt-history-time");
    }

    @Override protected void updateItem(HistoryItem item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        preview.setText(item.getPreview());
        time.setText(item.getDisplayTimestamp());
        setGraphic(box);
    }
}
