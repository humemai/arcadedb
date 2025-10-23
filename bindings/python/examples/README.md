# ArcadeDB Python Examples

This directory contains hands-on examples demonstrating ArcadeDB Python bindings in action.

## Quick Start

**⚠️ Important: Run examples from the `examples/` directory for proper file paths and database creation.**

```bash
# Navigate to the examples directory first
cd bindings/python/examples

# Then run the basic document store example
python 01_simple_document_store.py
```

## Available Examples

### 📄 01_simple_document_store.py
**Document Types | CRUD Operations | Rich Data Types**

Perfect introduction to ArcadeDB basics:
- Creating embedded databases (no server needed)
- Document types with rich schema (STRING, BOOLEAN, DATE, DECIMAL, etc.)
- CRUD operations with ArcadeDB SQL
- Transactions and data validation
- Built-in functions (`uuid()`, `date()`, `sysdate()`)

**Learn:** Document storage, SQL dialect, schema design

---

### 🔗 02_social_network_graph.py ✅ **COMPLETE**
**Vertex Types | Edge Types | Graph Traversal | SQL MATCH vs Cypher**

Complete social network modeling with graph database:
- Creating vertex types (Person) and edge types (FRIEND_OF) with properties
- Bidirectional relationships with metadata (since, closeness)
- Graph traversal patterns (friends, friends-of-friends, mutual connections)
- Comparing SQL MATCH vs Cypher query languages
- Variable-length path queries and graph aggregations
- Rich property access and relationship modeling

**Learn:** Graph schema design, relationship modeling, multi-language querying

**Status:** ✅ Fully functional with comprehensive graph operations

---

### 🔍 03_vector_search.py ⚠️ **EXPERIMENTAL**
**Vector Embeddings | HNSW Index | Semantic Search | Performance Analysis**

Semantic similarity search with AI/ML (under active development):
- Vector storage with 384D embeddings (mimicking sentence-transformers)
- HNSW indexing for nearest-neighbor search
- Cosine distance similarity queries
- Index population strategies (batch vs incremental)
- Filtering approaches (oversampling, multiple indexes, hybrid)
- Performance characteristics and best practices

**Learn:** Vector databases, HNSW algorithm, semantic search patterns, index architecture

**Implementation note:** Currently uses jelmerk/hnswlib. Future migration to datastax/jvector planned for better performance.

**Status:** ⚠️ API demonstration - not production-ready yet

---

### 📥 04_data_import.py *(Coming Soon)*
**CSV | JSON | JSONL Import**

Import data from common file formats:
- CSV as documents, vertices, or edges
- JSONL for semi-structured data (logs, events)
- JSON for nested/hierarchical data
- When to use each format and import mode
- Type inference and schema creation
- Building graphs from relational files

**Learn:** Data migration, ETL patterns, format selection, graph transformation

---

### 🏗️ 05_ecommerce_multimodel.py *(Coming Soon)*
**Multi-Model | Documents + Graph + Vectors**

Real-world e-commerce system:
- Products as documents
- Customer purchase graph for recommendations
- Vector search for similar products
- Mixed query patterns

**Learn:** Multi-model architecture, production patterns

---

### 🌐 06_server_mode_rest_api.py *(Coming Soon)*
**HTTP Server | Studio UI | REST API**

Embedded server with remote access:
- Start ArcadeDB HTTP server
- Access Studio web interface
- REST API integration
- Simultaneous embedded + HTTP access

**Learn:** Server deployment, HTTP API, Studio UI

---

### 📊 07_timeseries_iot_sensors.py *(Coming Soon)*
**Time Series | IoT Data | Aggregations**

High-volume sensor data storage:
- Time-indexed data insertion
- Bulk insert patterns
- Range queries and aggregations
- Performance optimization

**Learn:** Time series patterns, bulk operations

---

### 🔒 08_acid_transactions_banking.py *(Coming Soon)*
**ACID | Transactions | Rollback**

Banking operations with guarantees:
- Atomic transfers
- Transaction isolation
- Rollback on errors
- Consistency checks

**Learn:** Transaction management, data integrity

---

### 🔎 09_fulltext_search_documents.py *(Coming Soon)*
**Full-Text Search | Indexing | Ranking**

Document management with search:
- Full-text indexing
- Search queries with wildcards
- Result ranking
- Content management patterns

**Learn:** Full-text search, document indexing

---

### ⚙️ 10_production_patterns.py *(Coming Soon)*
**Best Practices | Error Handling | Performance**

Production deployment patterns:
- Resource management
- Thread safety
- Error handling and retries
- JVM memory tuning
- Backup and restore

**Learn:** Production readiness, reliability patterns

---

## 📚 Complete Documentation

For comprehensive guides, API reference, and advanced topics:

**🔗 [Full Python Documentation](../docs/)**

Includes:
- Installation & setup guides
- Complete API reference
- Advanced patterns & best practices
- Performance optimization
- Troubleshooting guides

## 🚀 Getting Started

1. **Install ArcadeDB Python bindings:**
   ```bash
   pip install arcadedb-embedded
   ```

2. **Navigate to examples directory:**
   ```bash
   cd bindings/python/examples
   ```

3. **Run an example:**
   ```bash
   python 01_simple_document_store.py
   ```

4. **Explore the results:**
   - Check `./my_test_databases/` for database files
   - Review output logs for operation details
   - Inspect the code to understand patterns

## 💡 Tips

- **Run from examples/ directory** - Always execute examples from `bindings/python/examples/` for correct file paths
- **Start with Example 01** - Foundation for all ArcadeDB concepts
- **Database files persist** - Examples preserve data for inspection
- **Output is educational** - Check console output to understand operations
- **Experiment freely** - Examples clean up and recreate on each run

## 🔗 Learn More

- **[ArcadeDB Documentation](https://docs.arcadedb.com/)**
- **[Python API Reference](../docs/api/)**
- **[GitHub Repository](https://github.com/ArcadeData/arcadedb)**

---

*Examples are designed to be self-contained and educational. Each includes detailed comments and step-by-step explanations.*
