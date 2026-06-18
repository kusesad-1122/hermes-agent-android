"""
Hermes Android Enhanced Memory — structured extraction + dual retrieval.

Builds on memory_system.py with:
- Structured memory entries (content, tag, source_session, timestamp)
- FTS5 keyword search (existing)
- Semantic similarity search (TF-IDF cosine, no external deps)
- Hybrid retrieval: FTS5 + semantic, fused ranking
- Stability: same query returns same top results (deterministic)
"""
import json
import math
import os
import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
from collections import Counter

_db_path: Optional[str] = None
_conn: Optional[sqlite3.Connection] = None
_lock = threading.Lock()
_initialized = False

# TF-IDF index (rebuilt on demand)
_tfidf_index: Dict[str, Dict[str, float]] = {}  # key -> {term: weight}
_idf: Dict[str, float] = {}
_all_keys: List[str] = []


def initialize(db_path: str = None) -> dict:
    global _db_path, _conn, _initialized
    if _initialized and _conn:
        return {"status": "already_initialized"}
    _db_path = db_path or os.path.join(os.environ.get("HOME", "/data"), "hermes_memory.db")
    import pathlib
    pathlib.Path(_db_path).parent.mkdir(parents=True, exist_ok=True)
    with _lock:
        _conn = sqlite3.connect(_db_path, check_same_thread=False, timeout=5.0, isolation_level=None)
        _conn.row_factory = sqlite3.Row
        _conn.execute("PRAGMA journal_mode=WAL")
        # Enhanced memory table
        _conn.execute("""CREATE TABLE IF NOT EXISTS enhanced_memories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            key TEXT NOT NULL,
            content TEXT NOT NULL,
            tag TEXT DEFAULT '',
            source_session TEXT DEFAULT '',
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        )""")
        _conn.execute("CREATE INDEX IF NOT EXISTS idx_em_tag ON enhanced_memories(tag)")
        _conn.execute("CREATE INDEX IF NOT EXISTS idx_em_key ON enhanced_memories(key)")
        # FTS5 for keyword search
        _conn.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS enhanced_memories_fts USING fts5(
            key, content, tag, content='enhanced_memories', content_rowid='id'
        )""")
    _initialized = True
    return {"status": "initialized", "path": _db_path}


def _tokenize(text: str) -> List[str]:
    """Simple tokenizer for TF-IDF."""
    return [w.lower() for w in text.split() if len(w) > 1]


def add_memory(key: str, content: str, tag: str = "", source_session: str = "") -> dict:
    """Add a structured memory entry."""
    if not _conn: return {"error": "not initialized"}
    now = time.time()
    with _lock:
        _conn.execute(
            "INSERT INTO enhanced_memories (key, content, tag, source_session, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            (key, content, tag, source_session, now, now)
        )
        rowid = _conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        _conn.execute("INSERT INTO enhanced_memories_fts (rowid, key, content, tag) VALUES (?, ?, ?, ?)",
                      (rowid, key, content, tag))
    _rebuild_index()
    return {"id": rowid, "key": key, "status": "added"}


def update_memory(mem_id: int, content: str = None, tag: str = None) -> bool:
    if not _conn: return False
    sets = []
    args = []
    if content is not None: sets.append("content = ?"); args.append(content)
    if tag is not None: sets.append("tag = ?"); args.append(tag)
    if not sets: return False
    sets.append("updated_at = ?"); args.append(time.time())
    args.append(mem_id)
    with _lock:
        _conn.execute(f"UPDATE enhanced_memories SET {', '.join(sets)} WHERE id = ?", args)
        # Update FTS
        row = _conn.execute("SELECT key, content, tag FROM enhanced_memories WHERE id = ?", (mem_id,)).fetchone()
        if row:
            _conn.execute("DELETE FROM enhanced_memories_fts WHERE rowid = ?", (mem_id,))
            _conn.execute("INSERT INTO enhanced_memories_fts (rowid, key, content, tag) VALUES (?, ?, ?, ?)",
                          (mem_id, row[0], row[1], row[2]))
    _rebuild_index()
    return True


def delete_memory(mem_id: int) -> bool:
    if not _conn: return False
    with _lock:
        _conn.execute("DELETE FROM enhanced_memories WHERE id = ?", (mem_id,))
        _conn.execute("DELETE FROM enhanced_memories_fts WHERE rowid = ?", (mem_id,))
    _rebuild_index()
    return True


def list_memories(tag: str = None, limit: int = 100) -> List[dict]:
    if not _conn: return []
    if tag:
        rows = _conn.execute("SELECT * FROM enhanced_memories WHERE tag = ? ORDER BY updated_at DESC LIMIT ?", (tag, limit)).fetchall()
    else:
        rows = _conn.execute("SELECT * FROM enhanced_memories ORDER BY updated_at DESC LIMIT ?", (limit,)).fetchall()
    return [dict(r) for r in rows]


def get_tags() -> List[str]:
    if not _conn: return []
    rows = _conn.execute("SELECT DISTINCT tag FROM enhanced_memories WHERE tag != '' ORDER BY tag").fetchall()
    return [r[0] for r in rows]


# ── FTS5 keyword search ─────────────────────────────────────────────────────

def search_fts(query: str, limit: int = 10) -> List[dict]:
    """Full-text search via FTS5."""
    if not _conn: return []
    try:
        rows = _conn.execute(
            "SELECT em.*, bm25(enhanced_memories_fts) as score FROM enhanced_memories_fts "
            "JOIN enhanced_memories em ON em.id = enhanced_memories_fts.rowid "
            "WHERE enhanced_memories_fts MATCH ? ORDER BY score LIMIT ?",
            (query, limit)
        ).fetchall()
        return [dict(r) for r in rows]
    except Exception:
        return []


# ── TF-IDF semantic search ──────────────────────────────────────────────────

def _rebuild_index():
    """Rebuild TF-IDF index from all memories."""
    global _tfidf_index, _idf, _all_keys
    if not _conn: return
    rows = _conn.execute("SELECT id, key, content, tag FROM enhanced_memories").fetchall()
    if not rows:
        _tfidf_index = {}; _idf = {}; _all_keys = []
        return
    docs = {}
    all_terms = set()
    for r in rows:
        doc_id = str(r["id"])
        text = f"{r['key']} {r['content']} {r['tag']}"
        tokens = _tokenize(text)
        tf = Counter(tokens)
        docs[doc_id] = tf
        all_terms.update(tokens)
    # IDF
    N = len(docs)
    _idf = {term: math.log(N / (1 + sum(1 for d in docs.values() if term in d))) for term in all_terms}
    # TF-IDF vectors
    _tfidf_index = {}
    for doc_id, tf in docs.items():
        total = sum(tf.values()) or 1
        _tfidf_index[doc_id] = {term: (count / total) * _idf.get(term, 0) for term, count in tf.items()}
    _all_keys = list(docs.keys())


def _cosine_sim(v1: Dict[str, float], v2: Dict[str, float]) -> float:
    """Cosine similarity between two sparse vectors."""
    dot = sum(v1.get(t, 0) * v2.get(t, 0) for t in v1 if t in v2)
    mag1 = math.sqrt(sum(v * v for v in v1.values())) or 1
    mag2 = math.sqrt(sum(v * v for v in v2.values())) or 1
    return dot / (mag1 * mag2)


def search_semantic(query: str, limit: int = 10) -> List[dict]:
    """Semantic similarity search via TF-IDF cosine."""
    if not _conn or not _tfidf_index: return []
    query_tokens = _tokenize(query)
    query_tf = Counter(query_tokens)
    total = sum(query_tf.values()) or 1
    query_vec = {term: (count / total) * _idf.get(term, 0) for term, count in query_tf.items()}
    scores = []
    for doc_id, doc_vec in _tfidf_index.items():
        sim = _cosine_sim(query_vec, doc_vec)
        if sim > 0:
            scores.append((doc_id, sim))
    scores.sort(key=lambda x: -x[1])
    results = []
    for doc_id, score in scores[:limit]:
        row = _conn.execute("SELECT * FROM enhanced_memories WHERE id = ?", (int(doc_id),)).fetchone()
        if row:
            d = dict(row)
            d["semantic_score"] = score
            results.append(d)
    return results


# ── Hybrid retrieval (FTS5 + semantic, fused) ──────────────────────────────

def search_hybrid(query: str, limit: int = 10) -> List[dict]:
    """Hybrid search: combine FTS5 and semantic results with fused ranking.
    
    Stability guarantee: same query always returns same top results
    because TF-IDF is deterministic and FTS5 BM25 is deterministic.
    """
    fts_results = search_fts(query, limit * 2)
    sem_results = search_semantic(query, limit * 2)
    # Build score map (normalized)
    scores = {}  # id -> {fts_score, sem_score}
    max_fts = max((abs(r.get("score", 0)) for r in fts_results), default=1) or 1
    max_sem = max((r.get("semantic_score", 0) for r in sem_results), default=1) or 1
    for r in fts_results:
        scores[r["id"]] = {"fts": abs(r.get("score", 0)) / max_fts, "sem": 0.0, "data": r}
    for r in sem_results:
        if r["id"] in scores:
            scores[r["id"]]["sem"] = r.get("semantic_score", 0) / max_sem
        else:
            scores[r["id"]] = {"fts": 0.0, "sem": r.get("semantic_score", 0) / max_sem, "data": r}
    # Fuse: weighted average (0.5 FTS + 0.5 semantic)
    fused = []
    for mem_id, s in scores.items():
        final_score = 0.5 * s["fts"] + 0.5 * s["sem"]
        fused.append((mem_id, final_score, s["data"]))
    fused.sort(key=lambda x: -x[1])
    results = []
    for mem_id, score, data in fused[:limit]:
        d = dict(data)
        d.pop("score", None)
        d["hybrid_score"] = score
        results.append(d)
    return results


def get_stats() -> dict:
    if not _conn: return {}
    total = _conn.execute("SELECT COUNT(*) FROM enhanced_memories").fetchone()[0]
    tags = get_tags()
    return {"total": total, "tags": len(tags), "tag_list": tags}
