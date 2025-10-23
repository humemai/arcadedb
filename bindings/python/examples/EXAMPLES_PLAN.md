# ArcadeDB Python Examples - Planning Document

## Target User Personas

1. **Data Scientists**: Using embeddings, vector search, analytics
2. **Backend Developers**: Building APIs, microservices with embedded DB
3. **Graph Analysts**: Working with relationships, networks, social graphs
4. **ETL Engineers**: Importing data from various sources
5. **Application Developers**: Need simple embedded database for desktop/CLI apps

## 10 Practical Examples

### 1. **Getting Started: Simple Document Store** ✅ (Priority: CRITICAL)
**File**: `01_simple_document_store.py`
**Target**: First-time users, application developers
**Concepts**: Database creation, basic CRUD, context managers, transactions
**Why**: Every user needs this - absolute basics, shows Pythonic API
```python
# Create DB, insert documents, query, close
# Like SQLite but with documents
# Perfect for config storage, logs, simple apps
```

### 2. **Graph Basics: Social Network** (Priority: HIGH)
**File**: `02_social_network_graph.py`
**Target**: Graph analysts, developers new to graph DBs
**Concepts**: Vertices, edges, graph traversals, relationships
**Why**: Graph DB is ArcadeDB's strength, shows core value proposition
```python
# Person vertices, FRIEND_OF edges
# Find friends, friends-of-friends
# Shortest path between people
```

### 3. **Vector Search: Semantic Similarity** (Priority: HIGH)
**File**: `03_vector_embeddings_search.py`
**Target**: Data scientists, AI/ML engineers
**Concepts**: Vector storage, HNSW index, nearest neighbor search
**Why**: Hot topic (RAG, LLMs), differentiates from traditional DBs
```python
# Store text embeddings (OpenAI/sentence-transformers)
# Build HNSW index
# Find similar documents by cosine similarity
```

### 4. **Data Import: CSV, JSON, JSONL** (Priority: HIGH)
**File**: `04_data_import.py`
**Target**: ETL engineers, data analysts
**Concepts**: CSV/JSON/JSONL import, documents vs vertices vs edges, schema creation, format selection
**Why**: Real-world migration scenario, common pain point, covers all practical import formats
```python
# CSV → Documents: Product catalog (unstructured)
# CSV → Vertices: People/Users as graph nodes
# CSV → Edges: Relationships with from/to (friendships, follows)
# JSONL → Documents: Log entries, events, metrics
# JSON → Documents: Configuration, nested/hierarchical data
# Show when to use each format and import mode
# Type inference (automatic int/float/bool/string detection)
# Batch processing with commit_every parameter
```
**Note**: Neo4j import exists but not covered in examples (specialized use case)

### 5. **Multi-Model: E-commerce System** (Priority: MEDIUM)
**File**: `05_ecommerce_multimodel.py`
**Target**: Backend developers, full-stack engineers
**Concepts**: Documents (products, orders), graph (customers, recommendations), vectors (search)
**Why**: Real production use case, shows multi-model flexibility
```python
# Products as documents with full-text search
# Customer purchase graph for recommendations
# Product embeddings for "similar items"
```

### 6. **Server Mode: HTTP API + Studio** (Priority: MEDIUM)
**File**: `06_server_mode_rest_api.py`
**Target**: Backend developers, DevOps engineers
**Concepts**: Server startup, HTTP API access, Studio UI, remote access
**Why**: Production deployment pattern, debugging with Studio
```python
# Start embedded server
# Access via HTTP REST API
# Show Studio URL for visual debugging
# Both embedded + HTTP access simultaneously
```

### 7. **Time Series: IoT Sensor Data** (Priority: MEDIUM)
**File**: `07_timeseries_iot_sensors.py`
**Target**: IoT developers, monitoring systems
**Concepts**: Time-indexed data, bulk inserts, range queries, aggregations
**Why**: Common use case, shows performance for append-heavy workloads
```python
# High-volume sensor readings
# Time-based partitioning
# Aggregation queries (avg, min, max by hour)
```

### 8. **Advanced Transactions: Banking Operations** (Priority: LOW-MEDIUM)
**File**: `08_acid_transactions_banking.py`
**Target**: Backend developers, financial systems
**Concepts**: ACID guarantees, rollback, concurrent access, isolation
**Why**: Critical for trust, shows database reliability
```python
# Bank account transfers
# Ensure atomic debit/credit
# Demonstrate rollback on error
# Balance consistency checks
```

### 9. **Full-Text Search: Document Management** (Priority: MEDIUM)
**File**: `09_fulltext_search_documents.py`
**Target**: Application developers, content management systems
**Concepts**: Full-text indexes, search queries, ranking
**Why**: Common requirement, alternative to Elasticsearch for small-medium apps
```python
# Index documents with text content
# Full-text search with wildcards
# Ranked results by relevance
```

### 10. **Production Patterns: Connection Pooling & Best Practices** (Priority: HIGH)
**File**: `10_production_patterns.py`
**Target**: Backend developers deploying to production
**Concepts**: Error handling, resource cleanup, threading, performance tuning
**Why**: Bridges development to production, critical for reliability
```python
# Proper context managers
# Thread-safe patterns
# Error handling and retries
# JVM memory tuning
# Database backup/restore
```

## Implementation Strategy

### Phase 1: Core Fundamentals (Examples 1-3)
- Simple document store
- Graph basics
- Vector search
These cover the 3 main use cases and should be done first.

### Phase 2: Real-World Scenarios (Examples 4-7)
- CSV import (practical)
- E-commerce (showcase)
- Server mode (deployment)
- Time series (performance)

### Phase 3: Advanced & Production (Examples 8-10)
- Transactions (reliability)
- Full-text search (feature completeness)
- Production patterns (deployment readiness)

## Documentation Structure

Each example should have:

1. **Code file** (`examples/0X_name.py`):
   - Clear comments explaining each step
   - Self-contained (runs without other files)
   - Includes sample data generation
   - Cleanup at the end
   - ~100-300 lines

2. **Documentation page** (`docs/examples/0X_name.md`):
   - What this example demonstrates
   - Real-world use case
   - Key concepts explained
   - Full code with detailed explanations
   - Expected output
   - "Try it yourself" modifications
   - Link to related API docs

3. **MkDocs navigation** (`mkdocs.yml`):
   - New "Examples" section in nav
   - Ordered by complexity
   - Tagged by user persona

## Success Criteria

- ✅ Each example runs without errors
- ✅ Examples are self-contained (no external dependencies except arcadedb)
- ✅ Clear progression from simple to advanced
- ✅ Cover all major features (document, graph, vector, import)
- ✅ Real-world use cases users can adapt
- ✅ Proper resource cleanup (no leaked DB handles)
- ✅ Documented in MkDocs with explanations

## Notes

- Focus on **thin wrapper** nature - we're wrapping Java APIs, not reimplementing
- Show **Java interop** where relevant (e.g., when Java objects are exposed)
- Emphasize **embedded** benefits - no network, fast, self-contained
- Include **performance tips** specific to JPype/JVM integration
- Make examples **copy-pasteable** for quick starts

## Related Test Files (for reference)

- `test_core.py` - Basic operations, transactions, graph, vector
- `test_server.py` - Server mode, HTTP API
- `test_importer.py` - CSV, JSON, JSONL import
- `test_concurrency.py` - Threading, file locking
- `test_server_patterns.py` - Access patterns, embedded vs HTTP
- `test_gremlin.py` - Gremlin query language (full distribution)
