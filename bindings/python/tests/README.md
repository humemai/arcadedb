# ArcadeDB Python Bindings - Tests

This directory contains comprehensive pytest tests for the ArcadeDB Python bindings.

📚 **[View full documentation](https://humemai.github.io/arcadedb/)** | [Testing Guide](https://humemai.github.io/arcadedb/development/testing/)

## Test Overview

The test suite covers:
- ✅ **Core database operations** - CRUD, transactions, queries
- ✅ **Server mode** - HTTP API, multi-client access
- ✅ **Concurrency patterns** - File locking, thread safety, multi-process
- ✅ **Graph operations** - Vertices, edges, traversals
- ✅ **Query languages** - SQL, Cypher, Gremlin
- ✅ **Vector search** - HNSW indexes, similarity search
- ✅ **Server usage patterns** - Embedded vs HTTP access, best practices
- ✅ **Data import** - CSV, JSON, JSONL, Neo4j exports
- ✅ **Unicode support** - International characters, emoji
- ✅ **Schema introspection** - Querying database metadata
- ✅ **Type conversions** - Python/Java type mapping
- ✅ **Large datasets** - Handling 1000+ records efficiently

**Total: 41 tests** (all passing)

## Test Files

### Core Tests
- [`test_core.py`](test_core.py) - Core database operations and API tests (13 tests)
  - Database creation and opening
  - CRUD operations (Create, Read, Update, Delete)
  - Transaction management
  - Graph operations (vertices, edges)
  - Error handling
  - Query result handling
  - Cypher queries (if available)
  - Vector search with HNSW indexes
  - **Unicode support** - International characters (Spanish, Chinese, Japanese, Arabic, Emoji)
  - **Schema queries** - Database metadata introspection
  - **Large result sets** - Efficiently handling 1000+ records
  - **Type conversions** - Python ↔ Java type mapping (str, int, float, bool, null, date)
  - **Complex graph traversal** - Multi-hop paths, mixed edge types

### Server Tests
- [`test_server.py`](test_server.py) - Server mode tests
  - Server creation and startup
  - Database operations through server
  - Custom configuration
  - Context manager usage
  - Basic HTTP API functionality

### Query Language Tests
- [`test_gremlin.py`](test_gremlin.py) - Gremlin query language tests
  - Gremlin queries
  - Graph traversals
  - Note: Requires Gremlin support (not in minimal distribution)

### Concurrency Tests
- [`test_concurrency.py`](test_concurrency.py) - Database concurrency behavior
  - **File locking mechanism** - Prevents concurrent process access
  - **Thread-safe operations** - Multiple threads in same process
  - **Sequential access** - Open, close, reopen patterns
  - **Multi-process limitations** - Why concurrent processes fail
  - Demonstrates when to use server mode for multi-process scenarios

### Server Pattern Tests
- [`test_server_patterns.py`](test_server_patterns.py) - Server usage patterns and best practices
  
  **Pattern 1: Embedded First → Server** (More Complex)
  - Create database with standalone embedded API
  - ⚠️ Must `close()` database to release file lock
  - Move database to server's `databases/` directory
  - Start server and access via `server.get_database()`
  - Use case: Pre-populate database, then expose via HTTP
  
  **Pattern 2: Server First → Create** (Recommended)
  - Start server first
  - Create databases through `server.create_database()`
  - Automatic dual access: embedded + HTTP simultaneously
  - Server manages database lifecycle
  - ✅ Simpler, no manual lock management needed
  
  **Key Differences:**
  - Pattern 1: Two separate database opens → requires `close()` between them
  - Pattern 2: Single server-managed database → shared between embedded & HTTP
  - Pattern 2 is recommended for new projects
  
  **Thread Safety:**
  - Multiple threads can safely access server-managed databases
  - Server's internal synchronization handles concurrent access
  
  **Context Manager:**
  - Use `with` statement for automatic server start/stop
  
  **Performance Comparison:**
  - Demonstrates that Pattern 2 embedded access is just as fast as standalone
  - Key insight: Server-managed embedded access = direct JVM call
  - NO HTTP overhead when accessing from same Python process
  - HTTP is only used for OTHER processes/clients
  - Proves Pattern 2 doesn't sacrifice performance for convenience

### Data Import Tests
- [`test_importer.py`](test_importer.py) - Comprehensive data import functionality (13 tests)
  
  **CSV Import Tests:**
  - Import as documents (default behavior)
  - Import as vertices (graph nodes)
  - Import as edges (graph relationships)
  - Custom delimiters and options
  - Type inference (string → int/float/bool/None)
  
  **JSONL Import Tests:**
  - Import as documents
  - Import as vertices
  - Line-by-line streaming for memory efficiency
  
  **JSON Import Tests:**
  - Import using Java JSONImporterFormat
  - Creates documents only (no vertices/edges)
  
  **Neo4j Import Tests:**
  - Import Neo4j APOC exports
  - Handles nodes (vertices) and relationships (edges)
  
  **Performance & Error Handling:**
  - Batch transaction commits (default 1000 records)
  - Error handling and validation
  - Import statistics tracking
  
  **Key Insights:**
  - CSV: Can import documents, vertices, OR edges
  - JSONL: Can import documents OR vertices (not edges)
  - JSON: Documents only (uses Java importer)
  - Type inference: Auto-converts CSV strings to proper Python types

## Running Tests

### Run all tests:
```bash
pytest
```

### Run specific test file:
```bash
pytest test_core.py
pytest test_concurrency.py
```

### Run with verbose output:
```bash
pytest -v
pytest -v -s  # Also show print statements
```

### Run specific test:
```bash
pytest test_concurrency.py::test_file_lock_mechanism
pytest test_core.py::test_database_creation
pytest test_server_patterns.py::test_server_pattern_recommended
```

## Test Categories Explained

### Understanding Concurrency Tests

The concurrency tests answer the key question: **"Can multiple Python instances access the same database?"**

**Short Answer:** 
- ❌ Multiple **processes** cannot (file lock prevents it)
- ✅ Multiple **threads** can (thread-safe within same process)
- ✅ Use **server mode** for true multi-process access

**File Lock Behavior:**
```python
# Process 1
db1 = arcadedb.create_database("./mydb")  # 🔒 Lock acquired

# Process 2 (different Python instance)
db2 = arcadedb.open_database("./mydb")    # ❌ LockException!
```

**Thread Safety:**
```python
# Same process, multiple threads
db = arcadedb.create_database("./mydb")

def thread_work():
    result = db.query("sql", "SELECT FROM Person")  # ✅ Works!

# All threads share the same database instance
threads = [Thread(target=thread_work) for i in range(5)]
```

**Multi-Process Solution:**
```python
# Use server mode for multi-process access
server = arcadedb.create_server(root_path="./databases")
server.start()

# Process 1: Embedded access
db = server.get_database("mydb")
db.query("sql", "SELECT FROM Person")  # ✅ Works

# Process 2: HTTP access
import requests
response = requests.post(
    'http://localhost:2480/api/v1/query/mydb',
    json={'language': 'sql', 'command': 'SELECT FROM Person'}
)  # ✅ Works!
```

### Understanding Server Pattern Tests

These tests demonstrate the **two ways to combine embedded and HTTP access**.

#### Pattern 1: Embedded First → Server (Advanced)

**When to use:**
- You need to pre-populate a database before exposing it
- You want to set up schema/data, then make it available via HTTP
- Migrating existing embedded database to server mode

**Critical Steps:**
1. Create database with embedded API
2. **⚠️ MUST call `db.close()`** to release file lock
3. Move database to server's `databases/` directory
4. Start server
5. Access via `server.get_database(name)`

**Why `close()` is required:**
- Your embedded code holds the file lock
- Server tries to open same database
- OS-level file lock blocks the second open
- `close()` releases the lock so server can acquire it

**Code Example:**
```python
# Step 1: Create and populate
db = arcadedb.create_database("./mydb")
db.command("sql", "CREATE DOCUMENT TYPE Person")
db.command("sql", "INSERT INTO Person SET name = 'Alice'")

# Step 2: MUST close!
db.close()  # Release lock

# Step 3: Move to server location
shutil.move("./mydb", "./databases/mydb")

# Step 4: Start server
server = arcadedb.create_server(root_path="./databases")
server.start()

# Step 5: Now both work
db = server.get_database("mydb")  # Embedded
# HTTP also works at http://localhost:2480
```

#### Pattern 2: Server First → Create (Recommended)

**When to use:**
- Starting new projects
- Want simplest setup
- Need both embedded and HTTP from the start

**Why it's simpler:**
- Server manages the database from the beginning
- No manual lock management needed
- One database instance shared between embedded and HTTP
- Server coordinates all access

**Code Example:**
```python
# Step 1: Start server first
server = arcadedb.create_server(root_path="./databases")
server.start()

# Step 2: Create database through server
db = server.create_database("mydb")

# Step 3: Use embedded access
db.command("sql", "CREATE DOCUMENT TYPE Person")
db.command("sql", "INSERT INTO Person SET name = 'Alice'")
result = db.query("sql", "SELECT FROM Person")

# Step 4: HTTP access also works immediately
# http://localhost:2480/api/v1/query/mydb

# Both embedded and HTTP share the same database instance!
```

**Key Difference:**
- **Pattern 1**: Two separate opens → need `close()` between them
- **Pattern 2**: Single server-managed instance → no lock conflicts

## Best Practices

### For Single Process Applications
```python
# Use embedded mode - simplest and fastest
db = arcadedb.create_database("./mydb")
# ... use db ...
db.close()
```

### For Multi-Process or HTTP Access
```python
# Use Pattern 2 - server first
server = arcadedb.create_server(root_path="./databases")
server.start()
db = server.create_database("mydb")
# ... use db ...
# Don't close server-managed databases - server handles it
server.stop()

# Note: Embedded access through server has NO performance penalty!
# It's a direct JVM call, not HTTP. HTTP is only for other processes.
```

### For Thread Safety
```python
# Both embedded and server modes are thread-safe
db = arcadedb.create_database("./mydb")

# Multiple threads can safely use the same db instance
def worker():
    result = db.query("sql", "SELECT FROM MyType")
    # Process results...

threads = [Thread(target=worker) for _ in range(10)]
# All threads share the db instance safely
```

## Running the Full Test Suite

```bash
# Run all tests
pytest

# Run with output to see detailed test descriptions
pytest -v -s

# Run specific category
pytest tests/test_concurrency.py -v
pytest tests/test_server_patterns.py -v

# Run and see which tests are related to what you're working on
pytest -k "server" -v      # All server-related tests
pytest -k "concurrency" -v  # All concurrency tests
pytest -k "thread" -v       # All thread safety tests
```

## Test Results

All tests should pass. Some tests may be skipped if certain features aren't available in your distribution:
- `test_cypher_queries` - Skipped in minimal distribution
- `test_gremlin_*` - Skipped if Gremlin support not available

Expected output:
```
======================== 41 passed in 9.67s =========================
```

Or with some skips:
```
======================== 38 passed, 3 skipped in 8.50s =========================
```
