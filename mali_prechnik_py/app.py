from __future__ import annotations

import html
import re
import subprocess
import sys
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QColor, QIcon, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QAbstractItemView,
    QFileDialog,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QSplitter,
    QStackedWidget,
    QTableWidget,
    QTextBrowser,
    QTextEdit,
    QPlainTextEdit,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

try:
    from .remote import RemoteProposalClient, RemoteProposalError, RemoteVotingGroup, RemoteVotingOption, proposal_text_from_entry
    from .model import (
        BUILD_PDF_SCRIPT,
        BUILD_SEED_SCRIPT,
        DEFAULT_OFFICIAL_JSON,
        APP_ICON_PATH,
        IS_FROZEN,
        PROJECT_ROOT,
        Entry,
        Option,
        clean_text,
        ensure_default_official_json,
        entry_count_text,
        find_entry_index,
        foreign_word_with_initial_capital,
        initial_letter,
        normalize_entries,
        normalize_word,
        read_entries,
        serbian_sort_key,
        write_entries_to_json,
    )
except ImportError:
    from remote import RemoteProposalClient, RemoteProposalError, RemoteVotingGroup, RemoteVotingOption, proposal_text_from_entry
    from model import (
        BUILD_PDF_SCRIPT,
        BUILD_SEED_SCRIPT,
        DEFAULT_OFFICIAL_JSON,
        APP_ICON_PATH,
        IS_FROZEN,
        PROJECT_ROOT,
        Entry,
        Option,
        clean_text,
        ensure_default_official_json,
        entry_count_text,
        find_entry_index,
        foreign_word_with_initial_capital,
        initial_letter,
        normalize_entries,
        normalize_word,
        read_entries,
        serbian_sort_key,
        write_entries_to_json,
    )

def apply_dark_theme(app: QApplication) -> None:
    app.setStyle("Fusion")

    palette = QPalette()
    palette.setColor(QPalette.ColorRole.Window, QColor(30, 31, 34))
    palette.setColor(QPalette.ColorRole.WindowText, QColor(242, 242, 242))
    palette.setColor(QPalette.ColorRole.Base, QColor(38, 39, 43))
    palette.setColor(QPalette.ColorRole.AlternateBase, QColor(47, 48, 54))
    palette.setColor(QPalette.ColorRole.ToolTipBase, QColor(47, 48, 54))
    palette.setColor(QPalette.ColorRole.ToolTipText, QColor(242, 242, 242))
    palette.setColor(QPalette.ColorRole.Text, QColor(242, 242, 242))
    palette.setColor(QPalette.ColorRole.Button, QColor(47, 48, 54))
    palette.setColor(QPalette.ColorRole.ButtonText, QColor(242, 242, 242))
    palette.setColor(QPalette.ColorRole.BrightText, QColor(255, 255, 255))
    palette.setColor(QPalette.ColorRole.Highlight, QColor(79, 140, 255))
    palette.setColor(QPalette.ColorRole.HighlightedText, QColor(255, 255, 255))
    app.setPalette(palette)

    app.setStyleSheet("""
        QWidget {
            background-color: #1e1f22;
            color: #f2f2f2;
        }
        QLineEdit, QTextEdit, QPlainTextEdit, QTextBrowser, QTreeWidget, QTableWidget, QListWidget {
            background-color: #26272b;
            color: #f2f2f2;
            border: 1px solid #4a4c54;
            selection-background-color: #4f8cff;
            selection-color: #ffffff;
        }
        QPushButton {
            background-color: #2f3036;
            color: #f2f2f2;
            border: 1px solid #565963;
            padding: 6px 10px;
        }
        QPushButton:hover {
            background-color: #3b3d45;
        }
        QPushButton:checked {
            background-color: #4f8cff;
            color: #ffffff;
        }
        QHeaderView::section {
            background-color: #2f3036;
            color: #f2f2f2;
            border: 1px solid #4a4c54;
            padding: 4px;
        }
        QStatusBar, QMenuBar, QMenu {
            background-color: #25262a;
            color: #f2f2f2;
        }
        QSplitter::handle {
            background-color: #3b3d45;
        }
    """)

USER_ROLE_KEY = Qt.ItemDataRole.UserRole


def marked_text_to_html(value: str) -> str:
    """Мали приказ markdown ознака које Android апликација већ разуме."""
    escaped = html.escape(clean_text(value)).replace("\n", "<br>")
    escaped = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", escaped)
    escaped = re.sub(r"__(.+?)__", r"<u>\1</u>", escaped)
    escaped = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", escaped)
    escaped = re.sub(r"(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", r"<i>\1</i>", escaped)
    return escaped


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.entries: list[Entry] = []
        self.current_path = ensure_default_official_json()
        self.current_edit_key: str | None = None
        self.search_direction_index = 0
        self.unsaved_entries = False
        self.remote_client = RemoteProposalClient()
        self.voting_groups: list[RemoteVotingGroup] = []
        self.current_voting_group: RemoteVotingGroup | None = None
        self.current_voting_option: RemoteVotingOption | None = None
        self.proposal_rules_accepted = False

        self.setWindowTitle("Мали пречник" if IS_FROZEN else "Мали пречник - уредник базе")
        self.resize(1180, 760)
        self.build_ui()
        self.load_entries_from_path(self.current_path, mark_unsaved=False)

    def build_ui(self) -> None:
        self.nav = QListWidget()
        self.nav.setFixedWidth(190)
        for title in ("Претрага", "Туђице", "Додавање/Измена", "Гласање", "Складиште"):
            self.nav.addItem(QListWidgetItem(title))

        self.stack = QStackedWidget()
        self.stack.addWidget(self.build_search_page())
        self.stack.addWidget(self.build_words_page())
        self.stack.addWidget(self.build_editor_page())
        self.stack.addWidget(self.build_voting_page())
        self.stack.addWidget(self.build_storage_page())

        layout = QHBoxLayout()
        layout.addWidget(self.nav)
        layout.addWidget(self.stack, 1)

        root = QWidget()
        root.setLayout(layout)
        self.setCentralWidget(root)
        self.nav.currentRowChanged.connect(self.stack.setCurrentIndex)
        self.nav.setCurrentRow(0)

        save_action = QAction("Сачувај JSON", self)
        save_action.setShortcut("Ctrl+S")
        save_action.triggered.connect(self.save_current_json)
        self.addAction(save_action)

        new_action = QAction("Нова туђица", self)
        new_action.setShortcut("Ctrl+N")
        new_action.triggered.connect(self.new_entry)
        self.addAction(new_action)

        self.statusBar().showMessage("Спремно.")

    def build_search_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        controls = QHBoxLayout()
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("Унеси туђицу или српскословенску реч")
        self.foreign_to_replacement_button = QPushButton("туђица -> српслв")
        self.replacement_to_foreign_button = QPushButton("српслв -> туђица")
        for direction_button in (self.foreign_to_replacement_button, self.replacement_to_foreign_button):
            direction_button.setCheckable(True)
        self.foreign_to_replacement_button.clicked.connect(lambda _checked=False: self.set_search_direction(0))
        self.replacement_to_foreign_button.clicked.connect(lambda _checked=False: self.set_search_direction(1))
        controls.addWidget(self.search_input, 1)
        controls.addWidget(self.foreign_to_replacement_button)
        controls.addWidget(self.replacement_to_foreign_button)
        layout.addLayout(controls)

        splitter = QSplitter(Qt.Orientation.Vertical)
        self.search_results = QTreeWidget()
        self.search_results.setHeaderLabels(("Туђица", "Порекло туђице", "Српскословенска реч", "Појашњење"))
        self.search_results.setRootIsDecorated(False)
        self.search_results.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.search_results.header().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.search_detail = QTextBrowser()
        splitter.addWidget(self.search_results)
        splitter.addWidget(self.search_detail)
        splitter.setStretchFactor(0, 3)
        splitter.setStretchFactor(1, 2)
        layout.addWidget(splitter, 1)

        buttons = QHBoxLayout()
        edit_button = QPushButton("Измени")
        delete_button = QPushButton("Уклони")
        suggest_button = QPushButton("Предложи измену")
        edit_button.clicked.connect(lambda: self.edit_selected_item(self.search_results))
        delete_button.clicked.connect(lambda: self.delete_selected_item(self.search_results))
        suggest_button.clicked.connect(lambda: self.suggest_selected_item(self.search_results))
        buttons.addStretch(1)
        buttons.addWidget(edit_button)
        buttons.addWidget(delete_button)
        buttons.addWidget(suggest_button)
        layout.addLayout(buttons)

        self.search_input.textChanged.connect(self.refresh_search)
        self.search_results.itemSelectionChanged.connect(self.update_search_detail)
        self.search_results.itemDoubleClicked.connect(lambda item, _column: self.edit_item(item))
        self.set_search_direction(self.search_direction_index, refresh=False)
        return page

    def set_search_direction(self, index: int, refresh: bool = True) -> None:
        self.search_direction_index = index
        if hasattr(self, "foreign_to_replacement_button"):
            self.foreign_to_replacement_button.setChecked(index == 0)
            self.replacement_to_foreign_button.setChecked(index == 1)
        if refresh:
            self.refresh_search()

    def build_words_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        label = QLabel("Туђице су груписане по почетном слову. Двоклик на туђицу отвара измену.")
        layout.addWidget(label)

        self.words_tree = QTreeWidget()
        self.words_tree.setHeaderLabels(("Туђица", "Порекло туђице", "Број предлога"))
        self.words_tree.header().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        layout.addWidget(self.words_tree, 1)

        self.words_tree.itemExpanded.connect(self.collapse_other_letters)
        self.words_tree.itemDoubleClicked.connect(self.open_word_item)
        return page

    def build_editor_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        form = QFormLayout()
        self.foreign_input = QLineEdit()
        self.origin_input = QLineEdit()
        self.addendum_input = QTextEdit()
        self.addendum_input.setFixedHeight(72)
        form.addRow("Туђица", self.foreign_input)
        form.addRow("Порекло туђице", self.origin_input)
        form.addRow("Додатак", self.addendum_input)
        layout.addLayout(form)

        proposal_label = QLabel("Предлози")
        proposal_label.setObjectName("proposalLabel")
        layout.addWidget(proposal_label)

        self.options_table = QTableWidget(0, 3)
        self.options_table.setHorizontalHeaderLabels(("", "Српскословенска реч", "Појашњење"))
        self.options_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Fixed)
        self.options_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.Interactive)
        self.options_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        self.options_table.setColumnWidth(0, 48)
        self.options_table.setColumnWidth(1, 300)
        self.options_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.options_table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        layout.addWidget(self.options_table, 1)

        option_buttons = QHBoxLayout()
        add_option_button = QPushButton("Додај предлог")
        remove_option_button = QPushButton("Уклони одабрани предлог")
        add_option_button.clicked.connect(lambda: self.add_option_row())
        remove_option_button.clicked.connect(self.remove_selected_option)
        option_buttons.addWidget(add_option_button)
        option_buttons.addWidget(remove_option_button)
        option_buttons.addStretch(1)
        layout.addLayout(option_buttons)

        line = QFrame()
        line.setFrameShape(QFrame.Shape.HLine)
        line.setFrameShadow(QFrame.Shadow.Sunken)
        layout.addWidget(line)

        buttons = QHBoxLayout()
        new_button = QPushButton("Нова туђица")
        save_button = QPushButton("Сачувај реч у JSON")
        delete_button = QPushButton("Уклони туђицу")
        self.suggest_editor_button = QPushButton("Предложи као нову реч")
        new_button.clicked.connect(self.new_entry)
        save_button.clicked.connect(self.save_editor_entry)
        delete_button.clicked.connect(self.delete_current_editor_entry)
        self.suggest_editor_button.clicked.connect(self.suggest_editor_entry)
        buttons.addWidget(new_button)
        buttons.addWidget(save_button)
        buttons.addWidget(delete_button)
        buttons.addWidget(self.suggest_editor_button)
        buttons.addStretch(1)
        layout.addLayout(buttons)
        return page

    def build_voting_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        self.voting_status_label = QLabel("Кликни „Освежи предлоге” да се учитају предлози за гласање.")
        self.voting_status_label.setWordWrap(True)
        layout.addWidget(self.voting_status_label)

        refresh_button = QPushButton("Освежи предлоге")
        refresh_button.clicked.connect(self.refresh_voting_proposals)
        layout.addWidget(refresh_button)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        self.voting_tree = QTreeWidget()
        self.voting_tree.setHeaderLabels(("Туђица", "Предлога"))
        self.voting_tree.header().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.voting_options = QListWidget()
        self.voting_detail = QTextBrowser()
        splitter.addWidget(self.voting_tree)
        splitter.addWidget(self.voting_options)
        splitter.addWidget(self.voting_detail)
        splitter.setStretchFactor(0, 2)
        splitter.setStretchFactor(1, 2)
        splitter.setStretchFactor(2, 3)
        layout.addWidget(splitter, 1)

        buttons = QHBoxLayout()
        vote_button = QPushButton("Гласај")
        report_button = QPushButton("Пријави предлог и предлагача")
        block_button = QPushButton("Блокирај предлагача")
        vote_button.clicked.connect(self.vote_for_selected_option)
        report_button.clicked.connect(self.report_selected_option)
        block_button.clicked.connect(self.block_selected_author)
        buttons.addStretch(1)
        buttons.addWidget(vote_button)
        buttons.addWidget(report_button)
        buttons.addWidget(block_button)
        layout.addLayout(buttons)

        self.voting_tree.itemSelectionChanged.connect(self.update_voting_group_selection)
        self.voting_tree.itemExpanded.connect(self.collapse_other_voting_letters)
        self.voting_options.itemSelectionChanged.connect(self.update_voting_option_selection)
        return page

    def build_storage_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        self.path_label = QLabel()
        self.stats_label = QLabel()
        self.path_label.setWordWrap(True)
        layout.addWidget(self.path_label)
        layout.addWidget(self.stats_label)

        buttons = QVBoxLayout()
        commands = [
            ("Учитај службени JSON", lambda: self.load_entries_from_path(DEFAULT_OFFICIAL_JSON, mark_unsaved=False)),
            ("Отвори JSON или DB", self.open_entries_file),
            ("Сачувај JSON", self.save_current_json),
            ("Сачувај JSON као...", self.save_json_as),
        ]
        if not IS_FROZEN:
            commands.extend(
                (
                    ("Направи Android seed DB/JSON", self.build_android_seed),
                    ("Направи A5 PDF мини речник", self.build_a5_pdf),
                )
            )
        for text, handler in commands:
            button = QPushButton(text)
            button.clicked.connect(handler)
            buttons.addWidget(button)
        buttons.addStretch(1)
        layout.addLayout(buttons)
        return page

    def load_entries_from_path(self, path: Path, mark_unsaved: bool) -> None:
        try:
            entries = read_entries(path)
        except Exception as exc:
            QMessageBox.critical(self, "Учитавање није успело", str(exc))
            return

        self.entries = normalize_entries(entries)
        if path.suffix.casefold() == ".json":
            self.current_path = path
        self.current_edit_key = None
        self.unsaved_entries = mark_unsaved
        self.refresh_all()
        self.new_entry()
        self.statusBar().showMessage(f"Учитано: {path}")

    def refresh_all(self) -> None:
        self.entries = normalize_entries(self.entries)
        self.refresh_search()
        self.refresh_words_tree()
        self.refresh_storage_info()

    def refresh_storage_info(self) -> None:
        suffix = " (има несачуваних измена)" if self.unsaved_entries else ""
        self.path_label.setText(f"Тренутни JSON: {self.current_path}{suffix}")
        self.stats_label.setText(entry_count_text(self.entries))

    def refresh_search(self) -> None:
        query = normalize_word(self.search_input.text()) if hasattr(self, "search_input") else ""
        if not hasattr(self, "search_results"):
            return
        self.search_results.clear()
        self.search_detail.clear()
        if not query:
            return

        replacement_direction = self.search_direction_index == 1
        for entry in self.entries:
            if replacement_direction:
                for option in entry.normalized_options():
                    if query in normalize_word(option.replacement_word):
                        self.add_search_item(entry, option)
            elif query in normalize_word(entry.foreign_word):
                self.add_search_item(entry, None)

    def add_search_item(self, entry: Entry, option: Option | None) -> None:
        option_words = option.replacement_word if option else ", ".join(item.replacement_word for item in entry.normalized_options())
        explanation = option.explanation if option else ""
        item = QTreeWidgetItem((entry.foreign_word, entry.origin, option_words, explanation))
        item.setData(0, USER_ROLE_KEY, normalize_word(entry.foreign_word))
        self.search_results.addTopLevelItem(item)

    def update_search_detail(self) -> None:
        selected = self.search_results.selectedItems()
        if not selected:
            self.search_detail.clear()
            return
        entry = self.entry_from_item(selected[0])
        self.search_detail.setHtml(self.entry_to_html(entry) if entry else "")

    def refresh_words_tree(self) -> None:
        if not hasattr(self, "words_tree"):
            return
        self.words_tree.clear()
        groups: dict[str, list[Entry]] = {}
        for entry in self.entries:
            groups.setdefault(initial_letter(entry.foreign_word), []).append(entry)

        for letter in sorted(groups, key=serbian_sort_key):
            parent = QTreeWidgetItem((letter, "", str(len(groups[letter]))))
            self.words_tree.addTopLevelItem(parent)
            for entry in sorted(groups[letter], key=lambda item: serbian_sort_key(item.foreign_word)):
                child = QTreeWidgetItem((entry.foreign_word, entry.origin, str(len(entry.normalized_options()))))
                child.setData(0, USER_ROLE_KEY, normalize_word(entry.foreign_word))
                parent.addChild(child)

    def collapse_other_letters(self, expanded_item: QTreeWidgetItem) -> None:
        if expanded_item.parent() is not None:
            return
        for index in range(self.words_tree.topLevelItemCount()):
            item = self.words_tree.topLevelItem(index)
            if item is not expanded_item:
                item.setExpanded(False)

    def open_word_item(self, item: QTreeWidgetItem, _column: int) -> None:
        if item.parent() is None:
            item.setExpanded(not item.isExpanded())
            return
        self.edit_item(item)

    def entry_from_item(self, item: QTreeWidgetItem) -> Entry | None:
        key = item.data(0, USER_ROLE_KEY)
        if not key:
            return None
        index = find_entry_index(self.entries, str(key))
        return self.entries[index] if index is not None else None

    def edit_selected_item(self, tree: QTreeWidget) -> None:
        selected = tree.selectedItems()
        if selected:
            self.edit_item(selected[0])

    def edit_item(self, item: QTreeWidgetItem) -> None:
        entry = self.entry_from_item(item)
        if entry:
            self.load_entry_into_editor(entry)

    def delete_selected_item(self, tree: QTreeWidget) -> None:
        selected = tree.selectedItems()
        if selected:
            entry = self.entry_from_item(selected[0])
            if entry:
                self.delete_entry(entry)

    def suggest_selected_item(self, tree: QTreeWidget) -> None:
        selected = tree.selectedItems()
        if not selected:
            return
        entry = self.entry_from_item(selected[0])
        if entry:
            self.suggest_entry_change(entry)

    def new_entry(self) -> None:
        self.current_edit_key = None
        if hasattr(self, "foreign_input"):
            self.foreign_input.clear()
            self.origin_input.clear()
            self.addendum_input.clear()
            self.options_table.setRowCount(0)
            self.add_option_row()
            self.suggest_editor_button.setText("Предложи као нову реч")
        self.nav.setCurrentRow(2)

    def load_entry_into_editor(self, entry: Entry) -> None:
        self.current_edit_key = normalize_word(entry.foreign_word)
        self.foreign_input.setText(entry.foreign_word)
        self.origin_input.setText(entry.origin)
        self.addendum_input.setPlainText(entry.addendum)
        self.options_table.setRowCount(0)
        for option in entry.normalized_options():
            self.add_option_row(option.replacement_word, option.explanation, option.weight)
        if self.options_table.rowCount() == 0:
            self.add_option_row()
        self.suggest_editor_button.setText("Предложи као измену")
        self.nav.setCurrentRow(2)

    def add_option_row(self, replacement_word: str = "", explanation: str = "", weight: int | str | None = None) -> None:
        row = self.options_table.rowCount()
        self.options_table.insertRow(row)

        controls = QWidget()
        controls_layout = QVBoxLayout(controls)
        controls_layout.setContentsMargins(2, 2, 2, 2)
        controls_layout.setSpacing(2)
        up_button = QPushButton("^")
        down_button = QPushButton("v")
        up_button.setFixedWidth(34)
        down_button.setFixedWidth(34)
        up_button.clicked.connect(lambda _checked=False, widget=controls: self.move_option_row_by_widget(widget, -1))
        down_button.clicked.connect(lambda _checked=False, widget=controls: self.move_option_row_by_widget(widget, 1))
        controls_layout.addWidget(up_button)
        controls_layout.addWidget(down_button)

        word_input = QLineEdit(replacement_word)
        word_input.setPlaceholderText("нпр. записје")

        explanation_input = QPlainTextEdit()
        explanation_input.setPlainText(explanation)
        explanation_input.setPlaceholderText("Појашњење за овај предлог")
        explanation_input.setMinimumHeight(78)
        explanation_input.setTabChangesFocus(True)

        self.options_table.setCellWidget(row, 0, controls)
        self.options_table.setCellWidget(row, 1, word_input)
        self.options_table.setCellWidget(row, 2, explanation_input)
        self.options_table.setRowHeight(row, 92)

    def option_row_values(self) -> list[tuple[str, str]]:
        rows: list[tuple[str, str]] = []
        for row in range(self.options_table.rowCount()):
            word_widget = self.options_table.cellWidget(row, 1)
            explanation_widget = self.options_table.cellWidget(row, 2)
            rows.append(
                (
                    word_widget.text() if isinstance(word_widget, QLineEdit) else "",
                    explanation_widget.toPlainText() if isinstance(explanation_widget, QPlainTextEdit) else "",
                )
            )
        return rows

    def rebuild_option_rows(self, rows: list[tuple[str, str]], selected_row: int | None = None) -> None:
        self.options_table.setRowCount(0)
        for replacement_word, explanation in rows:
            self.add_option_row(replacement_word, explanation)
        if selected_row is not None and 0 <= selected_row < self.options_table.rowCount():
            self.options_table.selectRow(selected_row)

    def row_for_cell_widget(self, widget: QWidget) -> int:
        for row in range(self.options_table.rowCount()):
            for column in range(self.options_table.columnCount()):
                cell_widget = self.options_table.cellWidget(row, column)
                if cell_widget is widget or cell_widget.isAncestorOf(widget):
                    return row
        return -1

    def move_option_row_by_widget(self, widget: QWidget, offset: int) -> None:
        row = self.row_for_cell_widget(widget)
        target_row = row + offset
        if row < 0 or target_row < 0 or target_row >= self.options_table.rowCount():
            return
        rows = self.option_row_values()
        rows[row], rows[target_row] = rows[target_row], rows[row]
        self.rebuild_option_rows(rows, selected_row=target_row)

    def focused_option_row(self) -> int:
        focused_widget = QApplication.focusWidget()
        if focused_widget is None:
            return -1

        for row in range(self.options_table.rowCount()):
            for column in range(self.options_table.columnCount()):
                cell_widget = self.options_table.cellWidget(row, column)
                if cell_widget is focused_widget or cell_widget.isAncestorOf(focused_widget):
                    return row
        return -1

    def remove_selected_option(self) -> None:
        row = self.focused_option_row()
        if row < 0:
            row = self.options_table.currentRow()
        if row >= 0:
            self.options_table.removeRow(row)
        if self.options_table.rowCount() == 0:
            self.add_option_row()

    def collect_editor_entry(self) -> Entry:
        foreign_word = foreign_word_with_initial_capital(self.foreign_input.text())
        if not foreign_word:
            raise ValueError("Поље Туђица мора бити попуњено.")

        options: list[Option] = []
        for row in range(self.options_table.rowCount()):
            word_widget = self.options_table.cellWidget(row, 1)
            explanation_widget = self.options_table.cellWidget(row, 2)
            replacement_word = clean_text(word_widget.text() if isinstance(word_widget, QLineEdit) else "")
            explanation = clean_text(
                explanation_widget.toPlainText() if isinstance(explanation_widget, QPlainTextEdit) else ""
            )
            if replacement_word:
                options.append(
                    Option(
                        replacement_word=replacement_word,
                        explanation=explanation,
                        weight=len(options) + 1,
                    )
                )

        if not options:
            raise ValueError("Потребан је бар један српскословенски предлог.")

        return Entry(
            foreign_word=foreign_word,
            origin=clean_text(self.origin_input.text()),
            addendum=clean_text(self.addendum_input.toPlainText()),
            options=options,
        )

    def save_editor_entry(self) -> None:
        try:
            entry = self.collect_editor_entry()
            new_key = normalize_word(entry.foreign_word)
            existing_index = find_entry_index(self.entries, entry.foreign_word)
            if existing_index is not None and new_key != self.current_edit_key:
                raise ValueError(f"Туђица већ постоји: {entry.foreign_word}")

            if self.current_edit_key is None:
                self.entries.append(entry)
            else:
                old_index = find_entry_index(self.entries, self.current_edit_key)
                if old_index is None:
                    self.entries.append(entry)
                else:
                    self.entries[old_index] = entry

            self.entries = normalize_entries(self.entries)
            self.current_edit_key = new_key
            self.save_current_json()
            self.refresh_all()
            self.load_entry_into_editor(self.entries[find_entry_index(self.entries, entry.foreign_word) or 0])
            self.statusBar().showMessage(f"Сачувано: {entry.foreign_word}")
        except Exception as exc:
            QMessageBox.warning(self, "Реч није сачувана", str(exc))

    def delete_current_editor_entry(self) -> None:
        if self.current_edit_key is None:
            self.new_entry()
            return
        index = find_entry_index(self.entries, self.current_edit_key)
        if index is not None:
            self.delete_entry(self.entries[index])

    def delete_entry(self, entry: Entry) -> None:
        answer = QMessageBox.question(
            self,
            "Уклањање туђице",
            f"Да ли сте сигурни да желите да уклоните туђицу „{entry.foreign_word}” и све њене предлоге?",
        )
        if answer != QMessageBox.StandardButton.Yes:
            return

        self.entries = [item for item in self.entries if normalize_word(item.foreign_word) != normalize_word(entry.foreign_word)]
        self.current_edit_key = None
        self.save_current_json()
        self.refresh_all()
        self.new_entry()
        self.statusBar().showMessage(f"Уклоњено: {entry.foreign_word}")

    def ensure_proposal_rules_accepted(self) -> bool:
        if self.proposal_rules_accepted:
            return True
        answer = QMessageBox.question(
            self,
            "Правила за предлоге",
            "Да би послао предлог, прихвати правила: без увредљивог, незаконитог, "
            "вулгарног или обмањујућег садржаја; без личних и осетљивих података; "
            "предлози пролазе уреднички преглед пре јавног приказа.\n\n"
            "Да ли прихваташ правила и желиш да пошаљеш предлог?",
        )
        self.proposal_rules_accepted = answer == QMessageBox.StandardButton.Yes
        return self.proposal_rules_accepted

    def suggest_entry_change(self, entry: Entry) -> None:
        if not self.ensure_proposal_rules_accepted():
            return
        text, accepted = QInputDialog.getMultiLineText(
            self,
            "Предложи измену",
            "Унеси текст предлога измене:",
            "",
        )
        if not accepted:
            return
        proposal_text = clean_text(text)
        if not proposal_text:
            QMessageBox.warning(self, "Предлог није послат", "Потребно је унети текст предлога.")
            return
        self.send_remote_proposal(entry, "edit_word", proposal_text)

    def suggest_editor_entry(self) -> None:
        if not self.ensure_proposal_rules_accepted():
            return
        try:
            entry = self.collect_editor_entry()
        except Exception as exc:
            QMessageBox.warning(self, "Предлог није послат", str(exc))
            return
        proposal_type = "new_word" if self.current_edit_key is None else "edit_word"
        self.send_remote_proposal(entry, proposal_type, proposal_text_from_entry(entry))

    def send_remote_proposal(self, entry: Entry, proposal_type: str, proposal_text: str) -> None:
        try:
            self.remote_client.submit_proposal(entry, proposal_type, proposal_text)
        except Exception as exc:
            QMessageBox.critical(self, "Предлог није послат", self.remote_error_message(exc))
            return
        QMessageBox.information(
            self,
            "Предлог је послат",
            "Предлог је послат уреднику. Биће видљив у гласању тек ако буде одобрен.",
        )

    def refresh_voting_proposals(self) -> None:
        try:
            self.voting_groups = self.remote_client.load_voting_proposals()
        except Exception as exc:
            self.voting_status_label.setText(self.remote_error_message(exc))
            self.voting_groups = []
        self.current_voting_group = None
        self.current_voting_option = None
        self.populate_voting_tree()
        self.populate_voting_options()

    def populate_voting_tree(self) -> None:
        self.voting_tree.clear()
        groups_by_letter: dict[str, list[RemoteVotingGroup]] = {}
        for group in self.voting_groups:
            groups_by_letter.setdefault(group.first_letter, []).append(group)

        for letter in sorted(groups_by_letter, key=serbian_sort_key):
            letter_groups = sorted(groups_by_letter[letter], key=lambda item: serbian_sort_key(item.foreign_word))
            parent = QTreeWidgetItem((letter, str(len(letter_groups))))
            self.voting_tree.addTopLevelItem(parent)
            for group in letter_groups:
                child = QTreeWidgetItem((group.foreign_word, str(len(group.options))))
                child.setData(0, USER_ROLE_KEY, group.id)
                parent.addChild(child)

        if self.voting_groups:
            self.voting_status_label.setText(f"Предлози су учитани. Број туђица на гласању: {len(self.voting_groups)}.")
        else:
            self.voting_status_label.setText("Тренутно нема предлога за гласање.")

    def collapse_other_voting_letters(self, expanded_item: QTreeWidgetItem) -> None:
        if expanded_item.parent() is not None:
            return
        for index in range(self.voting_tree.topLevelItemCount()):
            item = self.voting_tree.topLevelItem(index)
            if item is not expanded_item:
                item.setExpanded(False)

    def update_voting_group_selection(self) -> None:
        selected = self.voting_tree.selectedItems()
        self.current_voting_group = None
        self.current_voting_option = None
        if selected:
            item = selected[0]
            if item.parent() is not None:
                group_id = item.data(0, USER_ROLE_KEY)
                self.current_voting_group = next(
                    (group for group in self.voting_groups if group.id == group_id),
                    None,
                )
        self.populate_voting_options()

    def populate_voting_options(self) -> None:
        self.voting_options.clear()
        self.voting_detail.clear()
        group = self.current_voting_group
        if group is None:
            return
        for option in group.options:
            item = QListWidgetItem(f"Гласова: {option.votes_count} | {option.proposal_text[:80]}")
            item.setData(USER_ROLE_KEY, option.id)
            self.voting_options.addItem(item)

    def update_voting_option_selection(self) -> None:
        group = self.current_voting_group
        selected = self.voting_options.selectedItems()
        self.current_voting_option = None
        self.voting_detail.clear()
        if group is None or not selected:
            return
        option_id = selected[0].data(USER_ROLE_KEY)
        self.current_voting_option = next((option for option in group.options if option.id == option_id), None)
        if self.current_voting_option is not None:
            current_entry = self.entry_by_foreign_word(group.foreign_word)
            current_state_html = ""
            if current_entry is not None:
                current_state_html = (
                    "<h3>Тренутно стање</h3>"
                    f"{self.entry_to_html(current_entry)}"
                    "<hr>"
                )
            self.voting_detail.setHtml(
                f"<h2>{marked_text_to_html(group.foreign_word)}</h2>"
                f"{current_state_html}"
                "<h3>Предлог измене</h3>"
                f"<p><b>Гласова:</b> {self.current_voting_option.votes_count}</p>"
                f"<p>{marked_text_to_html(self.current_voting_option.proposal_text)}</p>"
            )

    def entry_by_foreign_word(self, foreign_word: str) -> Entry | None:
        index = find_entry_index(self.entries, foreign_word)
        return self.entries[index] if index is not None else None

    def vote_for_selected_option(self) -> None:
        group = self.current_voting_group
        option = self.current_voting_option
        if group is None or option is None:
            return
        try:
            self.remote_client.vote_for_option(group.id, option.id)
        except Exception as exc:
            QMessageBox.critical(self, "Глас није забележен", self.remote_error_message(exc))
            return
        QMessageBox.information(self, "Глас је забележен", "Глас је успешно забележен.")
        self.refresh_voting_proposals()

    def report_selected_option(self) -> None:
        group = self.current_voting_group
        option = self.current_voting_option
        if group is None or option is None:
            return
        answer = QMessageBox.question(
            self,
            "Пријава предлога и предлагача",
            "Да ли желите да пријавите овај предлог и његовог предлагача уреднику као неумесне или неприхватљиве?",
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        try:
            self.remote_client.report_option(group.id, option.id, option.created_by)
        except Exception as exc:
            QMessageBox.critical(self, "Пријава није послата", self.remote_error_message(exc))
            return
        QMessageBox.information(self, "Пријава је послата", "Предлог и предлагач су пријављени уреднику.")

    def block_selected_author(self) -> None:
        option = self.current_voting_option
        if option is None or not option.created_by:
            return
        answer = QMessageBox.question(
            self,
            "Блокирање предлагача",
            "Да ли сте сигурни да желите да сакријете све предлоге овог предлагача? "
            "Ово важи само на овом рачунару.",
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.remote_client.block_author(option.created_by)
        QMessageBox.information(self, "Предлагач је блокиран", "Предлози овог предлагача су сакривени на овом рачунару.")
        self.refresh_voting_proposals()

    def remote_error_message(self, error: Exception) -> str:
        message = str(error)
        if "CONFIGURATION_NOT_FOUND" in message:
            return "Firebase Authentication није довршен: у Firebase Console укључити Anonymous sign-in."
        if "PERMISSION_DENIED" in message:
            return "Firestore правила не дозвољавају овај упис. Проверити да су објављена правила из database/firestore.rules."
        return message or "Онлајн предлог није могао бити обрађен."

    def save_current_json(self) -> None:
        try:
            write_entries_to_json(self.entries, self.current_path)
            self.entries = read_entries(self.current_path)
            self.unsaved_entries = False
            self.refresh_all()
            self.statusBar().showMessage(f"JSON сачуван: {self.current_path}")
        except Exception as exc:
            QMessageBox.critical(self, "Чување није успело", str(exc))

    def save_json_as(self) -> None:
        file_name, _filter = QFileDialog.getSaveFileName(
            self,
            "Сачувај JSON као",
            str(self.current_path),
            "JSON (*.json)",
        )
        if not file_name:
            return
        self.current_path = Path(file_name)
        self.save_current_json()

    def open_entries_file(self) -> None:
        file_name, _filter = QFileDialog.getOpenFileName(
            self,
            "Отвори JSON или SQLite базу",
            str(PROJECT_ROOT),
            "База пречника (*.json *.db);;JSON (*.json);;SQLite DB (*.db)",
        )
        if not file_name:
            return
        path = Path(file_name)
        self.load_entries_from_path(path, mark_unsaved=path.suffix.casefold() == ".db")

    def build_android_seed(self) -> None:
        self.save_current_json()
        self.run_script(
            [sys.executable, str(BUILD_SEED_SCRIPT), "--input", str(self.current_path), "--copy-to-android-assets"],
            "Android seed база је направљена и копирана у assets.",
        )

    def build_a5_pdf(self) -> None:
        self.save_current_json()
        self.run_script(
            [sys.executable, str(BUILD_PDF_SCRIPT), "--input", str(self.current_path)],
            "A5 PDF мини речник је направљен.",
        )

    def run_script(self, command: list[str], success_message: str) -> None:
        try:
            completed = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
        except Exception as exc:
            QMessageBox.critical(self, "Скрипта није покренута", str(exc))
            return

        output = "\n".join(part for part in (completed.stdout, completed.stderr) if part).strip()
        if completed.returncode == 0:
            QMessageBox.information(self, "Готово", success_message + ("\n\n" + output if output else ""))
        else:
            QMessageBox.critical(self, "Скрипта није успела", output or f"Излазни код: {completed.returncode}")

    def entry_to_html(self, entry: Entry) -> str:
        options_html = []
        for option in sorted(entry.normalized_options(), key=lambda item: (item.weight, serbian_sort_key(item.replacement_word))):
            explanation = f" - <i>{marked_text_to_html(option.explanation)}</i>" if option.explanation else ""
            options_html.append(f"<li><b>{marked_text_to_html(option.replacement_word)}</b>{explanation}</li>")

        origin = f"<p><b>Порекло туђице:</b> <i>{marked_text_to_html(entry.origin)}</i></p>" if entry.origin else ""
        addendum = f"<p><b>Додатак:</b><br>{marked_text_to_html(entry.addendum)}</p>" if entry.addendum else ""
        return f"""
        <h2>{marked_text_to_html(entry.foreign_word)}</h2>
        {origin}
        <p><b>Српскословенске речи:</b></p>
        <ul>{''.join(options_html)}</ul>
        {addendum}
        """


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Мали пречник")
    if APP_ICON_PATH.exists():
        app.setWindowIcon(QIcon(str(APP_ICON_PATH)))
    apply_dark_theme(app)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
