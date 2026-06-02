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

SERBIAN_CYRILLIC_ORDER = "абвгдђежзијклљмнњопрстћуфхцчџш"
SERBIAN_ORDER_INDEX = {letter: index for index, letter in enumerate(SERBIAN_CYRILLIC_ORDER)}


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
        if entry.options
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
    raw_entries = payload.get("entries", payload) if isinstance(payload, dict) else payload

    entries: list[Entry] = []
    for raw_entry in raw_entries:
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


def read_entries(path: Path) -> list[Entry]:
    suffix = path.suffix.casefold()
    if suffix == ".db":
        return read_entries_from_sqlite(path)
    if suffix == ".json":
        return read_entries_from_json(path)
    raise ValueError(f"Неподржан улазни формат: {path}. Користи .json или .db.")


def require_reportlab() -> dict[str, Any]:
    """Увоз ReportLab-а држимо овде да би `--help` радио и без пакета."""
    try:
        from reportlab.lib import colors
        from reportlab.lib.enums import TA_CENTER
        from reportlab.lib.pagesizes import A5
        from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer
    except ImportError as exc:
        raise RuntimeError(
            "Недостаје Python пакет reportlab.\n"
            "Инсталирај га једном у Python окружењу:\n"
            "  python -m pip install reportlab"
        ) from exc

    return {
        "A5": A5,
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


def register_fonts(reportlab: dict[str, Any], args: argparse.Namespace) -> str:
    paths = default_font_paths()
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
    return "Prechnik"


def paragraph_markup(value: str) -> str:
    """Претвара једноставан markdown из базе у ReportLab paragraph markup."""
    escaped = html.escape(clean_text(value)).replace("\n", "<br/>")
    escaped = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", escaped)
    escaped = re.sub(r"__(.+?)__", r"<u>\1</u>", escaped)
    escaped = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", escaped)
    escaped = re.sub(r"(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", r"<i>\1</i>", escaped)
    return escaped


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
        "option": ParagraphStyle(
            "PrechnikOption",
            fontName=font_name,
            fontSize=8.2,
            leading=10.5,
            leftIndent=4 * mm,
            firstLineIndent=-2 * mm,
            spaceAfter=0.7 * mm,
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
    }


def page_footer(canvas: Any, document: Any) -> None:
    canvas.saveState()
    canvas.setFont("Prechnik", 7)
    canvas.drawCentredString(document.pagesize[0] / 2, 8 * document.bottomMargin / 12, str(document.page))
    canvas.restoreState()


def build_story(reportlab: dict[str, Any], entries: list[Entry], styles: dict[str, Any], title: str) -> list[Any]:
    Paragraph = reportlab["Paragraph"]
    Spacer = reportlab["Spacer"]
    mm = reportlab["mm"]

    story: list[Any] = [
        Paragraph(paragraph_markup(title), styles["title"]),
        Paragraph(
            f"{len(entries)} туђица, {sum(len(entry.options) for entry in entries)} српскословенских предлога",
            styles["subtitle"],
        ),
    ]

    current_letter = ""
    for entry in entries:
        letter = initial_letter(entry.foreign_word)
        if letter != current_letter:
            if current_letter:
                story.append(Spacer(1, 2 * mm))
            story.append(Paragraph(letter, styles["letter"]))
            current_letter = letter

        origin = f" <i>({paragraph_markup(entry.origin)})</i>" if entry.origin else ""
        story.append(Paragraph(f"<b>{paragraph_markup(entry.foreign_word)}</b>{origin}", styles["entry"]))

        for option in sorted(entry.options, key=lambda item: (item.weight, serbian_sort_key(item.replacement_word))):
            explanation = ""
            if option.explanation:
                explanation = f" - <i>{paragraph_markup(option.explanation)}</i>"
            story.append(
                Paragraph(
                    f"• <b>{paragraph_markup(option.replacement_word)}</b>{explanation}",
                    styles["option"],
                )
            )

        if entry.addendum:
            story.append(Paragraph(f"<i>Додатак:</i> {paragraph_markup(entry.addendum)}", styles["addendum"]))

    return story


def write_pdf(entries: list[Entry], output_path: Path, args: argparse.Namespace) -> None:
    reportlab = require_reportlab()
    font_name = register_fonts(reportlab, args)
    styles = make_styles(reportlab, font_name)
    mm = reportlab["mm"]
    SimpleDocTemplate = reportlab["SimpleDocTemplate"]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    document = SimpleDocTemplate(
        str(output_path),
        pagesize=reportlab["A5"],
        rightMargin=args.margin_mm * mm,
        leftMargin=args.margin_mm * mm,
        topMargin=args.margin_mm * mm,
        bottomMargin=max(args.margin_mm, 12) * mm,
        title=args.title,
        author=args.author,
    )
    story = build_story(reportlab, entries, styles, args.title)
    document.build(story, onFirstPage=page_footer, onLaterPages=page_footer)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Прави A5 PDF мини речник из JSON или SQLite базе.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help=f"Улазни .json или .db: {DEFAULT_INPUT}")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help=f"Излазни PDF: {DEFAULT_OUTPUT}")
    parser.add_argument("--title", default="Мали пречник", help="Наслов на првој страни PDF-а.")
    parser.add_argument("--author", default="Мали пречник", help="PDF author metadata.")
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

    entries = read_entries(args.input)
    if not entries:
        raise RuntimeError(f"Нема уноса за PDF у улазу: {args.input}")

    write_pdf(entries, args.output, args)
    print(f"PDF: {args.output}")
    print(f"Туђица: {len(entries)}")
    print(f"Српскословенских предлога: {sum(len(entry.options) for entry in entries)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
