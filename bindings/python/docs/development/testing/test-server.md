# Server Tests

The `test_server.py` file contains **6 tests** covering basic server functionality.

[View source code](https://github.com/humemai/arcadedb/blob/python-embedded/bindings/python/tests/test_server.py){ .md-button }

## Overview

These tests validate:

- ✅ Server creation and startup
- ✅ Database operations through server
- ✅ Custom configuration
- ✅ Context manager usage
- ✅ Multiple databases
- ✅ Server info retrieval

For advanced server patterns (embedded + HTTP), see [Server Patterns](test-server-patterns.md).

## Quick Example

```python
import arcadedb_embedded as arcadedb

# Create and start server
server = arcadedb.create_server(
    root_path="./databases",
    root_password="mypassword"
)
server.start()

# Create database
db = server.create_database("mydb")

# Use it
db.command("sql", "CREATE DOCUMENT TYPE Person")
with db.transaction():
    db.command("sql", "INSERT INTO Person SET name = 'Alice'")

# Query
result = db.query("sql", "SELECT FROM Person")
for person in result:
    print(person.get_property("name"))

# Stop server
server.stop()
```

## Test Cases

### 1. Server Creation and Startup

```python
server = arcadedb.create_server(root_path="./databases")
server.start()

assert server.is_running()

server.stop()
assert not server.is_running()
```

### 2. Context Manager

```python
with arcadedb.create_server(root_path="./databases") as server:
    server.start()
    # Server automatically stopped on exit
```

### 3. Custom Configuration

```python
server = arcadedb.create_server(
    root_path="./databases",
    root_password="secure_password",
    config={
        "http_port": 2480,
        "host": "0.0.0.0",
        "mode": "development"
    }
)
server.start()

assert server.get_http_port() == 2480
```

### 4. Multiple Databases

```python
server = arcadedb.create_server(root_path="./databases")
server.start()

# Create multiple databases
db1 = server.create_database("db1")
db2 = server.create_database("db2")

# Each is independent
db1.command("sql", "CREATE DOCUMENT TYPE TypeA")
db2.command("sql", "CREATE DOCUMENT TYPE TypeB")

# Verify isolation
result1 = db1.query("sql", "SELECT FROM schema:types WHERE name = 'TypeA'")
assert len(list(result1)) == 1

result2 = db2.query("sql", "SELECT FROM schema:types WHERE name = 'TypeB'")
assert len(list(result2)) == 1

server.stop()
```

### 5. Server Information

```python
server = arcadedb.create_server(root_path="./databases")
server.start()

# Get server info
port = server.get_http_port()
url = server.get_studio_url()

print(f"Server running on port: {port}")
print(f"Studio URL: {url}")

server.stop()
```

### 6. Database Listing

```python
server = arcadedb.create_server(root_path="./databases")
server.start()

# Create databases
server.create_database("db1")
server.create_database("db2")

# List databases
databases = server.list_databases()
assert "db1" in databases
assert "db2" in databases

server.stop()
```

## Running These Tests

```bash
# Run all server tests
pytest tests/test_server.py -v

# Run specific test
pytest tests/test_server.py::test_server_creation -v
```

!!! note "Distribution Support"
    Server tests are skipped in the **headless** distribution (no server module).

## Related Documentation

- [Server Patterns](test-server-patterns.md) - Advanced patterns
- [Server API Reference](../../api/server.md)
- [Server Guide](../../guide/server.md)
- [Concurrency Tests](test-concurrency.md) - Multi-process access
