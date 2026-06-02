CREATE TABLE foreign_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL UNIQUE,
    origin TEXT NOT NULL DEFAULT '',
    addendum TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);

CREATE TABLE replacement_options (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    foreign_term_id INTEGER NOT NULL,
    replacement_word TEXT NOT NULL,
    normalized_replacement_word TEXT NOT NULL,
    explanation TEXT NOT NULL DEFAULT '',
    weight INTEGER NOT NULL DEFAULT 1,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(foreign_term_id)
        REFERENCES foreign_terms(id)
        ON DELETE CASCADE,
    UNIQUE(foreign_term_id, normalized_replacement_word)
);

CREATE INDEX index_foreign_terms_word ON foreign_terms(word);
CREATE INDEX index_replacement_options_word ON replacement_options(replacement_word);
CREATE INDEX index_replacement_options_normalized_word ON replacement_options(normalized_replacement_word);

PRAGMA user_version = 7;
