package speechfx.voicetyping;

import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.*;

/** A read-only table of text rows with fixed column titles and widths; arrows move the selection. */
public final class RowTable {

    public final TableView<List<String>> view = new TableView<>();

    public RowTable(String[] titles, double... widths) {
        for (var k = 0; k < titles.length; k++) {
            var i = k;
            var col = new TableColumn<List<String>, String>(titles[k]);
            col.setCellValueFactory(c -> new SimpleStringProperty(i < c.getValue().size() ? c.getValue().get(i) : ""));
            col.setSortable(false);
            col.setReorderable(false);
            if (k < widths.length) col.setPrefWidth(widths[k]);
            view.getColumns().add(col);
        }
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        view.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    }

    /** Replaces the rows and selects one (clamped; nothing selected when there are no rows). */
    public void fill(List<List<String>> rows, int select) {
        view.setItems(FXCollections.observableArrayList(rows));
        if (rows.isEmpty()) return;
        var i = Math.max(0, Math.min(select, rows.size() - 1));
        view.getSelectionModel().select(i);
        view.scrollTo(i);
    }

    public int selected() { return view.getSelectionModel().getSelectedIndex(); }
}
