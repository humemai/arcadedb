# Examples Overview

Hands-on examples demonstrating ArcadeDB Python bindings in real-world scenarios. Each example is self-contained, well-documented, and ready to run.

## Available Examples

### 🏁 Getting Started

**[01 - Simple Document Store](01_simple_document_store.md)**
Foundation example covering document types, CRUD operations, comprehensive data types (DATE, DATETIME, DECIMAL, FLOAT, INTEGER, STRING, BOOLEAN, LIST OF STRING), and NULL value handling (INSERT NULL, UPDATE to NULL, IS NULL queries).

**[02 - Social Network Graph](02_social_network_graph.md)** ✅
Complete graph modeling with vertices, edges, NULL handling, and dual query languages (SQL MATCH vs Cypher). Demonstrates 8 people with optional fields, 24 bidirectional edges, graph traversal, and comprehensive queries.

**[03 - Vector Search](03_vector_search.md)** ⚠️ **EXPERIMENTAL**
Semantic similarity search with HNSW indexing. Demonstrates API but not production-ready yet.

**[04 - CSV Import - Documents](04_csv_import_documents.md)** ✅
Production CSV import with custom type inference, NULL handling, and index optimization. Imports MovieLens dataset (124,003 records) with comprehensive performance analysis.

**[05 - CSV Import - Graph](05_csv_import_graph.md)** 🚧 **COMING SOON**
Import CSV data as vertices and edges. Map entities to nodes, relationships to edges, resolve foreign keys.

### 🔍 Coming Soon

**06 - E-commerce Multi-Model**
Documents, graphs, and vectors in one simplified system.

**08 - Server Mode & HTTP API**
Embedded server with Studio UI and REST API.

**09 - ACID Transactions & Concurrency**
Banking operations with rollback, isolation, and concurrent access.

**10 - Production Patterns**
Best practices for deployment, error handling, and performance tuning.

## Quick Start

**⚠️ Important: Always run examples from the `examples/` directory.**

```bash
cd bindings/python/examples/
python 01_simple_document_store.py
```

## Learning Path

1. **Document Store** (01) - Learn fundamentals
2. **Graph Operations** (02) - Understand relationships
3. **Vector Search** (03) ⚠️ - AI/ML integration (experimental)
4. **CSV Import - Documents** (04) - ETL to documents
5. **CSV Import - Graph** (05) 🚧 - ETL to vertices/edges (coming soon)
6. **Multi-Model** (06) 🚧 - Integrate documents + graph + vectors (coming soon)
7. **Server Mode** (07) 🚧 - Production deployment (coming soon)
8. **ACID & Concurrency** (08) 🚧 - Reliability patterns (coming soon)
9. **Production Patterns** (09) 🚧 - Best practices (coming soon)

## Support

- [GitHub Issues](https://github.com/humemai/arcadedb-embedded-python/issues)
- [Documentation](https://docs.arcadedb.com/)
- [Community Forum](https://github.com/humemai/arcadedb-embedded-python/discussions)

---

*Start with [Simple Document Store](01_simple_document_store.md)!*
