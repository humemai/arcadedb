# Testing

The ArcadeDB Python bindings have a comprehensive test suite with **41 tests** covering all major functionality.

!!! success "Test Results by Distribution"
    - **Headless**: ✅ 34 passed, 7 skipped (Cypher, Gremlin, Server tests)
    - **Minimal**: ✅ 38 passed, 3 skipped (Cypher, Gremlin tests)
    - **Full**: ✅ 41 passed, 0 skipped (all features available)
    
    Tests are skipped when features aren't available in that distribution.

## Quick Start

```bash
# Install test dependencies
pip install pytest

# Run all tests
pytest

# Run specific test file
pytest tests/test_core.py

# Run with verbose output
pytest -v -s
```

## Test Coverage

The test suite covers:

- ✅ **Core database operations** - CRUD, transactions, queries
- ✅ **Server mode** - HTTP API, multi-client access
- ✅ **Concurrency patterns** - File locking, thread safety, multi-process
- ✅ **Graph operations** - Vertices, edges, traversals
- ✅ **Query languages** - SQL, Cypher, Gremlin
- ✅ **Vector search** - HNSW indexes, similarity search
- ✅ **Data import** - CSV, JSON, JSONL, Neo4j exports
- ✅ **Unicode support** - International characters
- ✅ **Type conversions** - Python/Java type mapping
- ✅ **Large datasets** - Handling 1000+ records

## Test Files

| File | Tests | Description |
|------|-------|-------------|
| `test_core.py` | 13 | Core database operations, CRUD, transactions, queries |
| `test_server.py` | 6 | Server mode, HTTP API, multi-client access |
| `test_concurrency.py` | 4 | File locking, thread safety, multi-process behavior |
| `test_server_patterns.py` | 4 | Best practices for embedded + server mode |
| `test_importer.py` | 13 | CSV, JSON, JSONL, Neo4j import |
| `test_gremlin.py` | 1 | Gremlin query language (if available) |

## Understanding Concurrency

!!! warning "Multi-Process Limitations"
    Multiple **processes** cannot access the same database file directly due to file locking.
    Use server mode for multi-process access.

**What works:**

- ✅ Multiple **threads** in the same process (thread-safe)
- ✅ Server mode with multiple **HTTP clients**
- ✅ Server mode with embedded + HTTP access simultaneously

**What doesn't work:**

- ❌ Multiple **processes** opening the same database file
- ❌ Two Python scripts accessing the same database directly

**Solution:** Use server mode for multi-process scenarios.

## Server Access Patterns

There are two ways to use server mode. **Pattern 2 is recommended** for new projects.

### Pattern 1: Embedded First → Server (Advanced)

Create database standalone, then expose via server:

```python
# 1. Create and populate
db = arcadedb.create_database("./mydb")
db.command("sql", "CREATE DOCUMENT TYPE Person")

# 2. MUST close to release file lock
db.close()

# 3. Move to server location
import shutil
shutil.move("./mydb", "./databases/mydb")

# 4. Start server and access
server = arcadedb.create_server(root_path="./databases")
server.start()
db = server.get_database("mydb")
```

### Pattern 2: Server First (Recommended) ⭐

Create database through server from the start:

```python
# 1. Start server
server = arcadedb.create_server(root_path="./databases")
server.start()

# 2. Create database through server
db = server.create_database("mydb")

# Both embedded and HTTP work immediately!
# No manual lock management needed
```

!!! tip "Performance Note"
    Embedded access through server has **zero HTTP overhead**—it's a direct JVM call.
    HTTP is only used for external processes/clients.

## Running Specific Tests

```bash
# Test concurrency behavior
pytest tests/test_concurrency.py -v

# Test server patterns
pytest tests/test_server_patterns.py -v

# Test data import
pytest tests/test_importer.py -v

# Test vector search
pytest tests/test_core.py::test_vector_search -v

# Run tests matching a keyword
pytest -k "thread" -v
pytest -k "server" -v
```

## Detailed Test Documentation

For comprehensive test documentation including:

- Detailed explanation of each test
- Concurrency behavior deep-dive
- Server pattern comparison
- Import functionality details
- Best practices and examples

See the **[tests/README.md](https://github.com/humemai/arcadedb/blob/python-embedded/bindings/python/tests/README.md)** in the repository.
