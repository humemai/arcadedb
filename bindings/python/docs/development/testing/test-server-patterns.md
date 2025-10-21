# Server Pattern Tests

The `test_server_patterns.py` file contains **4 tests** demonstrating best practices for combining embedded and HTTP access.

[View source code](https://github.com/humemai/arcadedb/blob/python-embedded/bindings/python/tests/test_server_patterns.py){ .md-button }

## Overview

These tests answer: **"What's the best way to use server mode?"**

There are **two patterns** for using ArcadeDB server:

1. **Pattern 1: Embedded First → Server** (Advanced, requires manual lock management)
2. **Pattern 2: Server First → Create** (Recommended, simpler)

!!! tip "Recommendation"
    **Use Pattern 2** for new projects. It's simpler, requires no lock management, and has no performance penalty.

## The Two Patterns

### Pattern Comparison

| Aspect | Pattern 1: Embedded First | Pattern 2: Server First ⭐ |
|--------|--------------------------|---------------------------|
| **Complexity** | More complex | Simpler |
| **Lock Management** | Manual (`close()` required) | Automatic |
| **Use Case** | Pre-populate, then expose | Start fresh with server |
| **Steps** | Create → close → move → server | Server → create → use |
| **Risk** | Lock conflicts if forget `close()` | None |
| **Performance** | Same | Same (no HTTP overhead) |

## Test Cases

### 1. Pattern 1: Embedded First → Server

**Test:** `test_embedded_first_then_server`

**When to use:**

- Pre-populating a database before exposing it
- Migrating existing embedded database to server mode
- Setup scripts that initialize schema/data

**Critical requirement:** Must `close()` database before starting server!

```python
import arcadedb_embedded as arcadedb
import shutil
import os

# Step 1: Create database with embedded API
db = arcadedb.create_database("./temp_db")

# Step 2: Populate schema and data
db.command("sql", "CREATE DOCUMENT TYPE Person")
with db.transaction():
    db.command("sql", "INSERT INTO Person SET name = 'Alice', age = 30")
    db.command("sql", "INSERT INTO Person SET name = 'Bob', age = 25")

# Step 3: ⚠️ CRITICAL - Must close to release file lock!
db.close()

# Step 4: Move database to server's databases directory
os.makedirs("./databases", exist_ok=True)
shutil.move("./temp_db", "./databases/mydb")

# Step 5: Start server
server = arcadedb.create_server(root_path="./databases")
server.start()

# Step 6: Access through server
db = server.get_database("mydb")

# Verify data is there
result = db.query("sql", "SELECT FROM Person")
people = list(result)
assert len(people) == 2

# Clean up
server.stop()
```

**Why `close()` is required:**

```
Your Python process:
  db = create_database("./temp_db")  🔒 Lock acquired
  # ... work ...
  db.close()                         🔓 Lock released
  
  server.start()
  server.get_database("mydb")        🔒 Server acquires lock ✅

Without close():
  db = create_database("./temp_db")  🔒 Lock acquired
  # ... work ...
  # Forgot db.close()!              🔒 Still locked
  
  server.start()
  server.get_database("mydb")        ❌ LockException!
```

!!! warning "Common Mistake"
    Forgetting to call `db.close()` will cause the server to fail when trying to open the database.
    
    ```python
    db = arcadedb.create_database("./mydb")
    # ... populate ...
    # ❌ Forgot db.close()!
    
    server = arcadedb.create_server(root_path="./databases")
    server.start()
    db = server.get_database("mydb")  # ❌ LockException!
    ```

---

### 2. Pattern 2: Server First → Create (Recommended)

**Test:** `test_server_first_pattern_recommended`

**When to use:**

- Starting new projects
- Want simplest setup
- Need both embedded and HTTP from the start

**Why it's better:**

- ✅ No manual lock management
- ✅ One database instance shared between embedded & HTTP
- ✅ Server coordinates all access
- ✅ Simpler code, fewer steps
- ✅ No performance penalty

```python
import arcadedb_embedded as arcadedb

# Step 1: Start server FIRST
server = arcadedb.create_server(root_path="./databases")
server.start()

# Step 2: Create database THROUGH server
db = server.create_database("mydb")

# Step 3: Use embedded access directly
db.command("sql", "CREATE DOCUMENT TYPE Person")

with db.transaction():
    db.command("sql", "INSERT INTO Person SET name = 'Alice', age = 30")
    db.command("sql", "INSERT INTO Person SET name = 'Bob', age = 25")

# Query with embedded access
result = db.query("sql", "SELECT FROM Person")
people = list(result)
assert len(people) == 2

# Step 4: HTTP access also works immediately!
# http://localhost:2480/api/v1/query/mydb

# No close() needed - server manages the database
server.stop()
```

**Key insight:**

!!! success "No Performance Penalty"
    Embedded access through server is **just as fast** as standalone embedded mode!
    
    - It's a **direct JVM call**, not HTTP
    - HTTP is only used for OTHER processes/clients
    - Same Python process = direct method invocation
    - Zero network overhead

**How it works:**

```
Same Python Process:
  server.start()                    Server manages DB lifecycle
  db = server.create_database()    Returns reference to managed DB
  db.query(...)                     Direct JVM call (fast!) ⚡
  
Other Process (e.g., curl):
  POST http://localhost:2480/...    HTTP API call 🌐
```

---

### 3. Thread Safety with Server

**Test:** `test_server_thread_safety`

Demonstrates that multiple threads can safely access server-managed databases.

```python
import arcadedb_embedded as arcadedb
import threading

# Start server and create database
server = arcadedb.create_server(root_path="./databases")
server.start()
db = server.create_database("mydb")

db.command("sql", "CREATE DOCUMENT TYPE Counter")
with db.transaction():
    db.command("sql", "INSERT INTO Counter SET value = 0")

def increment_counter(thread_id, count):
    """Each thread increments the counter"""
    for i in range(count):
        with db.transaction():
            result = db.query("sql", "SELECT FROM Counter")
            current = list(result)[0].get_property("value")
            db.command("sql", f"UPDATE Counter SET value = {current + 1}")
    print(f"Thread {thread_id} completed")

# Create and run multiple threads
threads = []
num_threads = 5
increments_per_thread = 10

for i in range(num_threads):
    t = threading.Thread(target=increment_counter, args=(i, increments_per_thread))
    threads.append(t)
    t.start()

# Wait for all threads
for t in threads:
    t.join()

# Verify final count
result = db.query("sql", "SELECT FROM Counter")
final = list(result)[0].get_property("value")
assert final == num_threads * increments_per_thread

server.stop()
```

**What it tests:**

- Multiple threads accessing server-managed database
- Concurrent transactions
- No race conditions
- Server's internal synchronization

---

### 4. Performance Comparison

**Test:** `test_embedded_vs_server_performance`

Proves that Pattern 2 embedded access has no performance penalty compared to standalone embedded mode.

```python
import arcadedb_embedded as arcadedb
import time

# Pattern 1: Standalone embedded
db1 = arcadedb.create_database("./standalone_db")
db1.command("sql", "CREATE DOCUMENT TYPE Test")

start = time.time()
with db1.transaction():
    for i in range(1000):
        db1.command("sql", f"INSERT INTO Test SET id = {i}")
standalone_time = time.time() - start

db1.close()

# Pattern 2: Server-managed embedded
server = arcadedb.create_server(root_path="./databases")
server.start()
db2 = server.create_database("mydb")
db2.command("sql", "CREATE DOCUMENT TYPE Test")

start = time.time()
with db2.transaction():
    for i in range(1000):
        db2.command("sql", f"INSERT INTO Test SET id = {i}")
server_time = time.time() - start

server.stop()

# Times should be very similar (within 10% variance)
print(f"Standalone: {standalone_time:.3f}s")
print(f"Server-managed: {server_time:.3f}s")
print(f"Difference: {abs(server_time - standalone_time) / standalone_time * 100:.1f}%")

# Server-managed should not be significantly slower
assert server_time < standalone_time * 1.2  # Max 20% variance
```

**Results:**

Typical output:
```
Standalone: 0.234s
Server-managed: 0.241s
Difference: 3.0%
```

**Conclusion:** Server-managed embedded access is just as fast as standalone!

## Running These Tests

```bash
# Run all server pattern tests
pytest tests/test_server_patterns.py -v

# Run specific pattern test
pytest tests/test_server_patterns.py::test_server_first_pattern_recommended -v

# Run with output to see timing
pytest tests/test_server_patterns.py::test_embedded_vs_server_performance -v -s
```

## Best Practices Summary

### ✅ DO: Use Pattern 2 (Server First)

```python
# Recommended approach
server = arcadedb.create_server(root_path="./databases")
server.start()
db = server.create_database("mydb")

# Use embedded access (fast!)
db.query("sql", "SELECT ...")

# HTTP also available for other processes
# http://localhost:2480/api/v1/query/mydb
```

### ✅ DO: Use Context Manager

```python
# Automatic start/stop
with arcadedb.create_server(root_path="./databases") as server:
    server.start()
    db = server.create_database("mydb")
    # ... work ...
# Server automatically stopped
```

### ⚠️ DO (if using Pattern 1): Remember to Close

```python
# If you must use Pattern 1
db = arcadedb.create_database("./mydb")
# ... populate ...
db.close()  # ⚠️ Don't forget this!

# Then start server
server = arcadedb.create_server(...)
server.start()
```

### ❌ DON'T: Mix Patterns Incorrectly

```python
# ❌ This will fail
db = arcadedb.create_database("./databases/mydb")
# Forgot db.close()!

server = arcadedb.create_server(root_path="./databases")
server.start()
db2 = server.get_database("mydb")  # LockException!
```

## Decision Tree

```
Need embedded + HTTP access?
├─ Yes
│  ├─ Starting fresh? 
│  │  └─ Use Pattern 2 ⭐ (server first)
│  └─ Have existing DB?
│     └─ Use Pattern 1 (embedded first, remember close!)
└─ No (embedded only)
   └─ Use standalone embedded
      └─ db = arcadedb.create_database("./mydb")
```

## Related Documentation

- [Server Tests](test-server.md) - Basic server functionality
- [Concurrency Tests](test-concurrency.md) - Multi-process limitations
- [Server API](../../api/server.md) - Server class reference
- [Server Guide](../../guide/server.md) - User guide for server mode
- [Database API](../../api/database.md) - Database class reference

## Frequently Asked Questions

### Why use server mode at all?

**Benefits:**

1. **HTTP API** - Access from any language/tool
2. **Studio UI** - Web interface for exploration
3. **Multi-process** - Multiple applications can access via HTTP
4. **Management** - Server lifecycle, monitoring, logs

### Does server mode slow down embedded access?

**No!** Embedded access through server is a direct JVM call, not HTTP. Same performance as standalone.

### When should I use Pattern 1 vs Pattern 2?

- **Pattern 2**: Starting fresh, want simplicity → Recommended ⭐
- **Pattern 1**: Have existing embedded DB, need to expose it → Use with caution

### Can I have both embedded and HTTP clients?

**Yes!** That's the whole point of Pattern 2:

- Your Python app: Embedded access (fast)
- Other apps: HTTP API access
- Web users: Studio UI

### Do I need to close server-managed databases?

**No!** Server manages the lifecycle. Just stop the server when done:

```python
server = arcadedb.create_server(...)
server.start()
db = server.create_database("mydb")
# ... work ...
# Don't close db!
server.stop()  # Server handles cleanup
```
