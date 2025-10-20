# Basic Examples

This page demonstrates the fundamental operations with ArcadeDB Python bindings.

## Database Creation and Basic CRUD

### Create a Database

```python
import arcadedb_embedded as arcadedb

# Create new database
with arcadedb.create_database("/tmp/mydb") as db:
    print(f"Created database at: {db.get_database_path()}")
    # Database automatically closed when exiting context
```

### Open Existing Database

```python
# Open existing database
with arcadedb.open_database("/tmp/mydb") as db:
    result = db.query("sql", "SELECT count(*) as total FROM Person")
    print(f"Total records: {result[0].get_property('total')}")
```

For more examples, see the [Quick Start Guide](../getting-started/quickstart.md).
