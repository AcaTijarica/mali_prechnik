#!/usr/bin/env python3
"""
Прави мали A5 PDF речник из службене JSON или SQLite базе.

Скрипта је намењена за уредничку употребу на PC рачунару: исти подаци који
улазе у Android апликацију могу се преломити и као кратка књижица за штампу.
Подразумевани улаз је `database/input/official_entries.json`, али се може
проследити и готова SQLite база `prechnik_seed.db`.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = PROJECT_ROOT / "database" / "input" / "official_entries.json"
DEFAULT_OUTPUT = PROJECT_ROOT / "database" / "generated" / "mali_precnik_a5.pdf"
ANDROID_FONT_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res" / "font"

SERBIAN_CYRILLIC_ORDER = "абвгдђежзијклљмнњопрстћуфхцчџш"
RUSSIAN_CYRILLIC_AFTER_E = "ё"
RUSSIAN_CYRILLIC_AFTER_I = "й"
RUSSIAN_CYRILLIC_EXTRA_ORDER = "щыэюя"
CYRILLIC_SORT_ORDER = (
    SERBIAN_CYRILLIC_ORDER
    .replace("е", f"е{RUSSIAN_CYRILLIC_AFTER_E}", 1)
    .replace("и", f"и{RUSSIAN_CYRILLIC_AFTER_I}", 1)
    + RUSSIAN_CYRILLIC_EXTRA_ORDER
)
SERBIAN_ORDER_INDEX = {letter: index for index, letter in enumerate(CYRILLIC_SORT_ORDER)}
GREEK_TEXT_RE = re.compile(r"[\u0370-\u03ff\u1f00-\u1fff]+")


@dataclass(frozen=True)
class Option:
    replacement_word: str
    explanation: str
    weight: int = 1


@dataclass(frozen=True)
class Entry:
    foreign_word: str
    origin: str
    addendum: str
    options: list[Option]


@dataclass(frozen=True)
class OldWord:
    old_word: str
    addendum: str
    synonyms: list[str]


def clean_text(value: Any) -> str:
    """Сређује празнине, али чува намерне преломе редова."""
    if value is None:
        return ""
    lines = [" ".join(line.strip().split()) for line in str(value).replace("\r\n", "\n").split("\n")]
    return "\n".join(line for line in lines if line).strip()


def normalize_word(value: str) -> str:
    return clean_text(value).casefold()


def serbian_sort_key(value: str) -> tuple[list[int], str]:
    lowered = value.strip().casefold()
    return ([SERBIAN_ORDER_INDEX.get(char, 1000 + ord(char)) for char in lowered], lowered)


def read_weight(raw: object) -> int:
    if not isinstance(raw, dict):
        return 1
    value = raw.get("tezina") or raw.get("тежина") or raw.get("weight") or 1
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 1


def initial_letter(value: str) -> str:
    for char in value.strip().casefold():
        if char in SERBIAN_ORDER_INDEX:
            return char.upper()
    return "#"


def unique_options(options: list[Option]) -> list[Option]:
    seen: set[str] = set()
    result: list[Option] = []
    for option in options:
        key = normalize_word(option.replacement_word)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(Option(clean_text(option.replacement_word), clean_text(option.explanation), option.weight))
    return sorted(result, key=lambda item: (item.weight, serbian_sort_key(item.replacement_word)))


def normalize_entries(entries: list[Entry]) -> list[Entry]:
    """Спаја дупле туђице и сортира их по српском ћириличном редоследу."""
    groups: dict[str, Entry] = {}
    for entry in entries:
        foreign_word = clean_text(entry.foreign_word)
        key = normalize_word(foreign_word)
        if not key:
            continue

        existing = groups.get(key)
        merged_options = (existing.options if existing else []) + entry.options
        groups[key] = Entry(
            foreign_word=existing.foreign_word if existing else foreign_word,
            origin=existing.origin if existing and existing.origin else clean_text(entry.origin),
            addendum=existing.addendum if existing and existing.addendum else clean_text(entry.addendum),
            options=unique_options(merged_options),
        )

    return [
        entry
        for entry in sorted(groups.values(), key=lambda item: serbian_sort_key(item.foreign_word))
    ]


def normalize_old_words(old_words: list[OldWord]) -> list[OldWord]:
    """Спаја дупле старе речи и сортира их по српском ћириличном редоследу."""
    groups: dict[str, OldWord] = {}
    for old_word in old_words:
        cleaned_word = clean_text(old_word.old_word)
        key = normalize_word(cleaned_word)
        if not key:
            continue

        existing = groups.get(key)
        synonyms: list[str] = []
        seen_synonyms: set[str] = set()
        for synonym in (existing.synonyms if existing else []) + old_word.synonyms:
            cleaned_synonym = clean_text(synonym)
            synonym_key = normalize_word(cleaned_synonym)
            if synonym_key and synonym_key not in seen_synonyms:
                seen_synonyms.add(synonym_key)
                synonyms.append(cleaned_synonym)

        groups[key] = OldWord(
            old_word=existing.old_word if existing else cleaned_word,
            addendum=existing.addendum if existing and existing.addendum else clean_text(old_word.addendum),
            synonyms=synonyms,
        )

    return [
        old_word
        for old_word in sorted(groups.values(), key=lambda item: serbian_sort_key(item.old_word))
        if old_word.synonyms
    ]


def load_json_with_small_repairs(path: Path) -> Any:
    """Чита JSON, уз поправку честе ручне грешке: сувишан зарез пре `]` или `}`."""
    text = path.read_text(encoding="utf-8-sig")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        repaired_text = re.sub(r",(\s*[\]}])", r"\1", text)
        if repaired_text == text:
            raise
        return json.loads(repaired_text)


def read_entries_from_json(path: Path) -> list[Entry]:
    payload = load_json_with_small_repairs(path)
    raw_entries = payload.get("entries") or payload.get("tudjice") or [] if isinstance(payload, dict) else payload

    entries: list[Entry] = []
    for raw_entry in raw_entries:
        if not isinstance(raw_entry, dict):
            continue
        foreign_word = clean_text(raw_entry.get("tudjica") or raw_entry.get("foreign_word"))
        if not foreign_word:
            continue
        options: list[Option] = []
        for raw_option in raw_entry.get("predlozi") or raw_entry.get("options") or []:
            replacement_word = clean_text(raw_option.get("srpskoslovenski") or raw_option.get("replacement_word"))
            if replacement_word:
                options.append(
                    Option(
                        replacement_word=replacement_word,
                        explanation=clean_text(raw_option.get("pojasnjenje") or raw_option.get("explanation")),
                        weight=read_weight(raw_option),
                    )
                )

        entries.append(
            Entry(
                foreign_word=foreign_word,
                origin=clean_text(raw_entry.get("poreklo") or raw_entry.get("origin")),
                addendum=clean_text(raw_entry.get("dodatak") or raw_entry.get("addendum")),
                options=options,
            )
        )
    return normalize_entries(entries)


def read_old_words_from_json(path: Path) -> list[OldWord]:
    payload = load_json_with_small_repairs(path)
    if not isinstance(payload, dict):
        return []

    old_words: list[OldWord] = []
    raw_old_words = payload.get("stare_reci") or payload.get("old_words") or []
    for raw_old_word in raw_old_words:
        if not isinstance(raw_old_word, dict):
            continue
        old_word = clean_text(raw_old_word.get("stara_rec") or raw_old_word.get("old_word"))
        if not old_word:
            continue

        raw_synonyms = raw_old_word.get("slicnoznacnice") or raw_old_word.get("synonyms") or []
        if isinstance(raw_synonyms, str):
            raw_synonyms = re.split(r"[,;\n]+", raw_synonyms)

        old_words.append(
            OldWord(
                old_word=old_word,
                addendum=clean_text(raw_old_word.get("dodatak") or raw_old_word.get("addendum")),
                synonyms=[clean_text(value) for value in raw_synonyms if clean_text(value)],
            )
        )

    return normalize_old_words(old_words)


def read_entries_from_sqlite(path: Path) -> list[Entry]:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute(
            """
            SELECT id, word, origin, addendum
            FROM foreign_terms
            ORDER BY word COLLATE NOCASE
            """
        ).fetchall()

        entries: list[Entry] = []
        option_columns = {
            row["name"]
            for row in connection.execute("PRAGMA table_info(replacement_options)").fetchall()
        }
        weight_column = "weight" if "weight" in option_columns else "1 AS weight"
        for row in rows:
            option_rows = connection.execute(
                f"""
                SELECT replacement_word, explanation, {weight_column}
                FROM replacement_options
                WHERE foreign_term_id = ?
                ORDER BY weight ASC, replacement_word COLLATE NOCASE
                """,
                (row["id"],),
            ).fetchall()
            entries.append(
                Entry(
                    foreign_word=clean_text(row["word"]),
                    origin=clean_text(row["origin"]),
                    addendum=clean_text(row["addendum"]),
                    options=[
                        Option(
                            replacement_word=clean_text(option["replacement_word"]),
                            explanation=clean_text(option["explanation"]),
                            weight=max(1, int(option["weight"] or 1)),
                        )
                        for option in option_rows
                    ],
                )
            )
    return normalize_entries(entries)


def sqlite_table_exists(connection: sqlite3.Connection, table_name: str) -> bool:
    row = connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def read_old_words_from_sqlite(path: Path) -> list[OldWord]:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        if not sqlite_table_exists(connection, "old_words"):
            return []

        rows = connection.execute(
            """
            SELECT id, word, addendum
            FROM old_words
            ORDER BY word COLLATE NOCASE
            """
        ).fetchall()

        old_words: list[OldWord] = []
        has_synonyms_table = sqlite_table_exists(connection, "old_word_synonyms")
        for row in rows:
            synonyms: list[str] = []
            if has_synonyms_table:
                synonym_rows = connection.execute(
                    """
                    SELECT synonym
                    FROM old_word_synonyms
                    WHERE old_word_id = ?
                    ORDER BY position ASC, synonym COLLATE NOCASE
                    """,
                    (row["id"],),
                ).fetchall()
                synonyms = [clean_text(synonym_row["synonym"]) for synonym_row in synonym_rows]

            old_words.append(
                OldWord(
                    old_word=clean_text(row["word"]),
                    addendum=clean_text(row["addendum"]),
                    synonyms=synonyms,
                )
            )

    return normalize_old_words(old_words)


def read_storage(path: Path) -> tuple[list[Entry], list[OldWord]]:
    suffix = path.suffix.casefold()
    if suffix == ".db":
        return read_entries_from_sqlite(path), read_old_words_from_sqlite(path)
    if suffix == ".json":
        return read_entries_from_json(path), read_old_words_from_json(path)
    raise ValueError(f"Неподржан улазни формат: {path}. Користи .json или .db.")


def read_entries(path: Path) -> list[Entry]:
    entries, _old_words = read_storage(path)
    return entries


def require_reportlab() -> dict[str, Any]:
    """Увоз ReportLab-а држимо овде да би `--help` радио и без пакета."""
    try:
        from reportlab.lib import colors
        from reportlab.lib.enums import TA_CENTER
        from reportlab.lib.pagesizes import A4, A5
        from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer
    except ImportError as exc:
        raise RuntimeError(
            "Недостаје Python пакет reportlab.\n"
            "Инсталирај га једном у Python окружењу:\n"
            "  python -m pip install reportlab"
        ) from exc

    return {
        "A4": A4,
        "A5": A5,
        "PageBreak": PageBreak,
        "Paragraph": Paragraph,
        "ParagraphStyle": ParagraphStyle,
        "SimpleDocTemplate": SimpleDocTemplate,
        "Spacer": Spacer,
        "TA_CENTER": TA_CENTER,
        "TTFont": TTFont,
        "colors": colors,
        "getSampleStyleSheet": getSampleStyleSheet,
        "mm": mm,
        "pdfmetrics": pdfmetrics,
    }


def first_existing_path(paths: list[Path]) -> Path | None:
    for path in paths:
        if path.exists():
            return path
    return None


def default_font_paths() -> dict[str, Path | None]:
    """Тражи уобичајене Windows/Linux фонтове који подржавају ћирилицу."""
    windows_fonts = Path("C:/Windows/Fonts")
    candidates = {
        "regular": [
            windows_fonts / "arial.ttf",
            windows_fonts / "times.ttf",
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"),
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        ],
        "bold": [
            windows_fonts / "arialbd.ttf",
            windows_fonts / "timesbd.ttf",
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"),
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
        ],
        "italic": [
            windows_fonts / "ariali.ttf",
            windows_fonts / "timesi.ttf",
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf"),
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf"),
        ],
        "bold_italic": [
            windows_fonts / "arialbi.ttf",
            windows_fonts / "timesbi.ttf",
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif-BoldItalic.ttf"),
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf"),
        ],
    }
    return {name: first_existing_path(font_paths) for name, font_paths in candidates.items()}


def bundled_font_paths(font_choice: str) -> dict[str, Path | None]:
    """Фонтови који су већ упаковани у Android пројекат."""
    if font_choice == "monomakh":
        regular = ANDROID_FONT_DIR / "monomakh_regular.ttf"
        return {
            "regular": regular,
            "bold": regular,
            "italic": regular,
            "bold_italic": regular,
        }

    if font_choice == "eb_garamond":
        regular = ANDROID_FONT_DIR / "eb_garamond.ttf"
        italic = ANDROID_FONT_DIR / "eb_garamond_italic.ttf"
        return {
            "regular": regular,
            "bold": regular,
            "italic": italic,
            "bold_italic": italic,
        }

    return {}


def register_fonts(reportlab: dict[str, Any], args: argparse.Namespace) -> str:
    paths = bundled_font_paths(args.font_choice) or default_font_paths()
    regular = args.font_regular or paths["regular"]
    bold = args.font_bold or paths["bold"] or regular
    italic = args.font_italic or paths["italic"] or regular
    bold_italic = args.font_bold_italic or paths["bold_italic"] or bold or italic or regular

    if not regular or not Path(regular).exists():
        raise FileNotFoundError(
            "Није пронађен TrueType фонт за ћирилицу. Проследи га преко --font-regular."
        )

    pdfmetrics = reportlab["pdfmetrics"]
    TTFont = reportlab["TTFont"]
    pdfmetrics.registerFont(TTFont("Prechnik", str(regular)))
    pdfmetrics.registerFont(TTFont("Prechnik-Bold", str(bold)))
    pdfmetrics.registerFont(TTFont("Prechnik-Italic", str(italic)))
    pdfmetrics.registerFont(TTFont("Prechnik-BoldItalic", str(bold_italic)))
    pdfmetrics.registerFontFamily(
        "Prechnik",
        normal="Prechnik",
        bold="Prechnik-Bold",
        italic="Prechnik-Italic",
        boldItalic="Prechnik-BoldItalic",
    )

    # Мономах је леп за ћирилицу, али нема грчка слова. Зато грчке делове
    # касније у тексту обавијамо посебним фонтом који има грчке глифове.
    greek_regular = ANDROID_FONT_DIR / "noto_serif.ttf"
    if not greek_regular.exists():
        greek_regular = Path(regular)
    pdfmetrics.registerFont(TTFont("PrechnikGreek", str(greek_regular)))
    return "Prechnik"


def mark_greek_runs(markup: str) -> str:
    """Убацује резервни фонт само око грчких речи, да се не појаве квадратићи."""
    return GREEK_TEXT_RE.sub(lambda match: f'<font name="PrechnikGreek">{match.group(0)}</font>', markup)


def paragraph_markup(value: str) -> str:
    """Претвара једноставан markdown из базе у ReportLab paragraph markup."""
    escaped = html.escape(clean_text(value)).replace("\n", "<br/>")
    escaped = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", escaped)
    escaped = re.sub(r"__(.+?)__", r"<u>\1</u>", escaped)
    escaped = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", escaped)
    escaped = re.sub(r"(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", r"<i>\1</i>", escaped)
    return mark_greek_runs(escaped)


def make_styles(reportlab: dict[str, Any], font_name: str) -> dict[str, Any]:
    ParagraphStyle = reportlab["ParagraphStyle"]
    TA_CENTER = reportlab["TA_CENTER"]
    colors = reportlab["colors"]
    mm = reportlab["mm"]

    return {
        "title": ParagraphStyle(
            "PrechnikTitle",
            fontName=f"{font_name}-Bold",
            fontSize=18,
            leading=22,
            alignment=TA_CENTER,
            spaceAfter=8 * mm,
        ),
        "subtitle": ParagraphStyle(
            "PrechnikSubtitle",
            fontName=font_name,
            fontSize=8.5,
            leading=11,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#555555"),
            spaceAfter=6 * mm,
        ),
        "letter": ParagraphStyle(
            "PrechnikLetter",
            fontName=f"{font_name}-Bold",
            fontSize=14,
            leading=16,
            textColor=colors.HexColor("#8A1F1F"),
            borderWidth=0.5,
            borderColor=colors.HexColor("#8A1F1F"),
            borderPadding=3,
            spaceBefore=5 * mm,
            spaceAfter=3 * mm,
        ),
        "entry": ParagraphStyle(
            "PrechnikEntry",
            fontName=font_name,
            fontSize=9,
            leading=11.5,
            spaceBefore=1.5 * mm,
            spaceAfter=0.8 * mm,
        ),
        "origin": ParagraphStyle(
            "PrechnikOrigin",
            fontName=font_name,
            fontSize=7.8,
            leading=9.6,
            leftIndent=3 * mm,
            textColor=colors.HexColor("#444444"),
            spaceAfter=1.1 * mm,
        ),
        "option": ParagraphStyle(
            "PrechnikOption",
            fontName=font_name,
            fontSize=8.2,
            leading=10.5,
            leftIndent=4 * mm,
            firstLineIndent=-2 * mm,
            spaceAfter=0.7 * mm,
        ),
        "replacement_word": ParagraphStyle(
            "PrechnikReplacementWord",
            fontName=f"{font_name}-Bold",
            fontSize=8.2,
            leading=10.2,
            leftIndent=4 * mm,
            textColor=colors.HexColor("#8A1F1F"),
            spaceBefore=0.8 * mm,
            spaceAfter=0.2 * mm,
        ),
        "option_explanation": ParagraphStyle(
            "PrechnikOptionExplanation",
            fontName=f"{font_name}-Italic",
            fontSize=7.8,
            leading=9.8,
            leftIndent=5 * mm,
            rightIndent=1 * mm,
            textColor=colors.HexColor("#333333"),
            spaceAfter=1.2 * mm,
        ),
        "addendum": ParagraphStyle(
            "PrechnikAddendum",
            fontName=font_name,
            fontSize=7.7,
            leading=9.6,
            leftIndent=4 * mm,
            textColor=colors.HexColor("#444444"),
            spaceAfter=1.5 * mm,
        ),
        "old_word": ParagraphStyle(
            "PrechnikOldWord",
            fontName=font_name,
            fontSize=8.8,
            leading=11.2,
            spaceBefore=1.5 * mm,
            spaceAfter=0.8 * mm,
        ),
        "final": ParagraphStyle(
            "PrechnikFinal",
            fontName=f"{font_name}-Bold",
            fontSize=11,
            leading=14,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#8A1F1F"),
            spaceBefore=8 * mm,
        ),
    }


def page_footer(canvas: Any, document: Any) -> None:
    canvas.saveState()
    canvas.setFont("Prechnik", 7)
    canvas.drawCentredString(document.pagesize[0] / 2, 8 * document.bottomMargin / 12, str(document.page))
    canvas.restoreState()


def build_story(
    reportlab: dict[str, Any],
    entries: list[Entry],
    old_words: list[OldWord],
    styles: dict[str, Any],
    args: argparse.Namespace,
) -> list[Any]:
    Paragraph = reportlab["Paragraph"]
    PageBreak = reportlab["PageBreak"]
    Spacer = reportlab["Spacer"]
    mm = reportlab["mm"]

    subtitle_parts: list[str] = []
    if args.include_foreign_words:
        subtitle_parts.append(
            f"{len(entries)} туђица, {sum(len(entry.options) for entry in entries)} српскословенских предлога"
        )
    if args.include_old_words:
        subtitle_parts.append(f"{len(old_words)} старих речи")

    story: list[Any] = [
        Paragraph(paragraph_markup(args.title), styles["title"]),
        Paragraph(
            "; ".join(subtitle_parts),
            styles["subtitle"],
        ),
    ]

    if args.include_foreign_words and entries:
        story.extend(build_entries_story(reportlab, entries, styles, args))

    if args.include_old_words and old_words:
        if args.include_foreign_words and entries:
            story.append(PageBreak())
        story.extend(build_old_words_story(reportlab, old_words, styles, args))

    story.append(Spacer(1, 5 * mm))
    story.append(Paragraph("Крај и слава Богу!", styles["final"]))
    return story


def build_entries_story(
    reportlab: dict[str, Any],
    entries: list[Entry],
    styles: dict[str, Any],
    args: argparse.Namespace,
) -> list[Any]:
    Paragraph = reportlab["Paragraph"]
    PageBreak = reportlab["PageBreak"]
    Spacer = reportlab["Spacer"]
    mm = reportlab["mm"]
    story: list[Any] = [Paragraph("Туђице", styles["letter"])]

    current_letter = ""
    for entry in entries:
        letter = initial_letter(entry.foreign_word)
        if letter != current_letter:
            if current_letter:
                story.append(PageBreak())
            story.append(Paragraph(letter, styles["letter"]))
            current_letter = letter

        story.append(Paragraph(f"<b>{paragraph_markup(entry.foreign_word)}</b>", styles["entry"]))
        if args.include_origin and entry.origin:
            story.append(Paragraph(f"<b>Порекло:</b> {paragraph_markup(entry.origin)}", styles["origin"]))

        if args.include_addendum and entry.addendum:
            story.append(Paragraph(f"<i>Додатак:</i> {paragraph_markup(entry.addendum)}", styles["addendum"]))

        sorted_options = sorted(entry.options, key=lambda item: (item.weight, serbian_sort_key(item.replacement_word)))
        for option in sorted_options:
            story.append(Paragraph(f"<b>{paragraph_markup(option.replacement_word)}</b>", styles["replacement_word"]))
            if args.include_explanations and option.explanation:
                story.append(Paragraph(f"<i>{paragraph_markup(option.explanation)}</i>", styles["option_explanation"]))
            else:
                story.append(Spacer(1, 0.8 * mm))

    return story


def build_old_words_story(
    reportlab: dict[str, Any],
    old_words: list[OldWord],
    styles: dict[str, Any],
    args: argparse.Namespace,
) -> list[Any]:
    Paragraph = reportlab["Paragraph"]
    Spacer = reportlab["Spacer"]
    mm = reportlab["mm"]
    story: list[Any] = [Paragraph("Старе речи", styles["letter"])]

    current_letter = ""
    for old_word in old_words:
        letter = initial_letter(old_word.old_word)
        if letter != current_letter:
            if current_letter:
                story.append(Spacer(1, 2 * mm))
            story.append(Paragraph(letter, styles["letter"]))
            current_letter = letter

        story.append(Paragraph(f"<b>{paragraph_markup(old_word.old_word)}</b>", styles["old_word"]))
        if args.include_addendum and old_word.addendum:
            story.append(Paragraph(f"<i>Додатак:</i> {paragraph_markup(old_word.addendum)}", styles["addendum"]))
        if old_word.synonyms:
            story.append(
                Paragraph(
                    "• " + paragraph_markup(", ".join(old_word.synonyms)),
                    styles["option"],
                )
            )

    return story


def write_pdf(entries: list[Entry], old_words: list[OldWord], output_path: Path, args: argparse.Namespace) -> None:
    reportlab = require_reportlab()
    font_name = register_fonts(reportlab, args)
    styles = make_styles(reportlab, font_name)
    mm = reportlab["mm"]
    SimpleDocTemplate = reportlab["SimpleDocTemplate"]
    page_size = reportlab[args.page_size]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    document = SimpleDocTemplate(
        str(output_path),
        pagesize=page_size,
        rightMargin=args.margin_mm * mm,
        leftMargin=args.margin_mm * mm,
        topMargin=args.margin_mm * mm,
        bottomMargin=max(args.margin_mm, 12) * mm,
        title=args.title,
        author=args.author,
    )
    story = build_story(reportlab, entries, old_words, styles, args)
    document.build(story, onFirstPage=page_footer, onLaterPages=page_footer)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Прави PDF спис пречника из JSON или SQLite базе.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help=f"Улазни .json или .db: {DEFAULT_INPUT}")
    parser.add_argument("--output", type=Path, help=f"Излазни PDF. Подразумевано: {DEFAULT_OUTPUT}")
    parser.add_argument("--title", default="Мали пречник", help="Наслов на првој страни PDF-а.")
    parser.add_argument("--author", default="Мали пречник", help="PDF author metadata.")
    parser.add_argument("--page-size", choices=("A5", "A4"), default="A5", help="Образац листа. Подразумевано: A5.")
    parser.add_argument("--font-choice", choices=("monomakh", "eb_garamond"), default="monomakh", help="Писмо за PDF.")
    parser.add_argument(
        "--include-foreign-words",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Укључује/искључује туђице.",
    )
    parser.add_argument(
        "--include-old-words",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Укључује/искључује старе речи.",
    )
    parser.add_argument(
        "--include-explanations",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Укључује/искључује појашњења српскословенских предлога.",
    )
    parser.add_argument(
        "--include-origin",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Укључује/искључује порекло туђице.",
    )
    parser.add_argument(
        "--include-addendum",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Укључује/искључује додатке.",
    )
    parser.add_argument("--margin-mm", type=float, default=14.0, help="Маргине у милиметрима. Подразумевано: 14.")
    parser.add_argument("--font-regular", type=Path, help="Путања до основног .ttf фонта.")
    parser.add_argument("--font-bold", type=Path, help="Путања до bold .ttf фонта.")
    parser.add_argument("--font-italic", type=Path, help="Путања до italic .ttf фонта.")
    parser.add_argument("--font-bold-italic", type=Path, help="Путања до bold italic .ttf фонта.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.exists():
        raise FileNotFoundError(f"Улаз не постоји: {args.input}")

    if not args.include_foreign_words and not args.include_old_words:
        raise RuntimeError("PDF спис мора да садржи барем туђице или старе речи.")

    entries, old_words = read_storage(args.input)
    selected_entries = entries if args.include_foreign_words else []
    selected_old_words = old_words if args.include_old_words else []
    if not selected_entries and not selected_old_words:
        raise RuntimeError(f"Нема одабраних уноса за PDF у улазу: {args.input}")

    output_path = args.output or (
        PROJECT_ROOT / "database" / "generated" / f"mali_precnik_{args.page_size.casefold()}.pdf"
    )

    write_pdf(selected_entries, selected_old_words, output_path, args)
    print(f"PDF: {output_path}")
    print(f"Туђица: {len(selected_entries)}")
    print(f"Српскословенских предлога: {sum(len(entry.options) for entry in selected_entries)}")
    print(f"Старих речи: {len(selected_old_words)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
