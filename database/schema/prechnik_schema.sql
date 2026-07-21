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

CREATE TABLE old_words (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL UNIQUE,
    addendum TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);

CREATE TABLE old_word_synonyms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    old_word_id INTEGER NOT NULL,
    synonym TEXT NOT NULL,
    normalized_synonym TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(old_word_id)
        REFERENCES old_words(id)
        ON DELETE CASCADE,
    UNIQUE(old_word_id, normalized_synonym)
);

CREATE INDEX index_old_words_word ON old_words(word);
CREATE INDEX index_old_word_synonyms_normalized ON old_word_synonyms(normalized_synonym);

PRAGMA user_version = 8;
