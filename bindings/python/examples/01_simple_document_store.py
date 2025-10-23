#!/usr/bin/env python3
"""
Example 01: Simple Document Store

This example demonstrates the absolute basics of ArcadeDB Python bindings:
- Creating an embedded database (no server needed!)
- Defining document types (schema) with various data types
- CRUD operations (Create, Read, Update, Delete)
- Using transactions safely
- Querying with ArcadeDB SQL (a dialect of SQL)

Perfect for: Configuration storage, logs, simple app data, desktop applications
Think of it as: SQLite but for documents with flexible schema

Record Types Used:
- DOCUMENT TYPE only (simplest data storage, like SQL tables but more flexible)
- Not using VERTEX/EDGE types (those are for graph operations)

Requirements:
- arcadedb-embedded (any distribution: headless, minimal, or full)
- JRE 21+

Note: This example creates a database at ./my_test_databases/task_db/
      The database files are preserved so you can inspect them after running.

About ArcadeDB SQL:
This example uses ArcadeDB's SQL dialect, which extends standard SQL with:
- Document operations (INSERT/UPDATE with JSON-like syntax)
- Built-in functions like date(), sysDate(), uuid()
- Rich data type support (DATE, DATETIME, DECIMAL, BINARY, etc.)
- Schema-flexible operations alongside typed properties
- No JOINs - relationships use LINKS with dot notation (e.g., user.address.city)
"""

import os
import shutil

import arcadedb_embedded as arcadedb

print("=" * 70)
print("🎮 ArcadeDB Python - Example 01: Simple Document Store")
print("=" * 70)
print()

# -----------------------------------------------------------------------------
# Step 1: Create Database
# -----------------------------------------------------------------------------
print("Step 1: Creating database...")
print()

# Create database in a local directory so you can inspect the files
# This creates: ./my_test_databases/task_db/
db_dir = "./my_test_databases"
db_path = os.path.join(db_dir, "task_db")

# Clean up any existing database from previous runs
if os.path.exists(db_path):
    shutil.rmtree(db_path)

# Clean up log directory from previous runs
if os.path.exists("./log"):
    shutil.rmtree("./log")

db = arcadedb.create_database(db_path)

print(f"   ✅ Database created at: {db_path}")
print("   💡 Using embedded mode - no server needed!")
print("   💡 Database files are kept so you can inspect them!")
print()

# -----------------------------------------------------------------------------
# Step 2: Create Schema (Document Type with Rich Data Types)
# -----------------------------------------------------------------------------
print("Step 2: Creating schema with various data types...")
print()

# ArcadeDB has 3 record types: Document, Vertex, and Edge
# - Document: Simple data storage (like SQL tables but more flexible)
# - Vertex: Graph nodes for graph operations
# - Edge: Graph connections between vertices
# This example uses DOCUMENT TYPE - the simplest way to store data
print("   💡 ArcadeDB Record Types:")
print("      • Document: Simple data storage (what we're using)")
print("      • Vertex: Graph nodes for graph operations")
print("      • Edge: Graph connections between vertices")
print()

# While ArcadeDB is schema-flexible, defining types is recommended
# It provides better performance, validation, and indexing
with db.transaction():
    db.command("sql", "CREATE DOCUMENT TYPE Task")

    # Define properties with various ArcadeDB data types
    db.command("sql", "CREATE PROPERTY Task.title STRING")  # Text
    db.command("sql", "CREATE PROPERTY Task.priority STRING")  # Text
    db.command("sql", "CREATE PROPERTY Task.completed BOOLEAN")  # True/False
    db.command("sql", "CREATE PROPERTY Task.created_date DATE")  # Date only
    db.command("sql", "CREATE PROPERTY Task.due_datetime DATETIME")  # Date + time
    db.command("sql", "CREATE PROPERTY Task.estimated_hours FLOAT")  # Decimal
    db.command("sql", "CREATE PROPERTY Task.priority_score INTEGER")  # Integer
    db.command("sql", "CREATE PROPERTY Task.cost DECIMAL")  # Precision
    db.command("sql", "CREATE PROPERTY Task.task_id STRING")  # UUID as string

print("   ✅ Created 'Task' document type with rich data types")
print("   💡 ArcadeDB supports: STRING, BOOLEAN, INTEGER, LONG, FLOAT, DOUBLE,")
print("                        DATE, DATETIME, DATETIME_MICROS, DATETIME_NANOS,")
print("                        DECIMAL, BINARY, EMBEDDED, LINK, and Arrays")
print("   💡 Note: UUIDs are stored as STRING type, RIDs use LINK type")
print()

# -----------------------------------------------------------------------------
# Step 3: INSERT - Create Documents
# -----------------------------------------------------------------------------
print("Step 3: Inserting documents...")
print()

# IMPORTANT: All write operations must be inside a transaction!
# Transactions ensure ACID guarantees (Atomicity, Consistency, Isolation, Durability)
with db.transaction():
    # Insert a single task
    db.command(
        "sql",
        """INSERT INTO Task SET
           title = 'Buy groceries',
           priority = 'high',
           completed = false,
           tags = ['shopping', 'urgent']
        """,
    )

    # Insert multiple tasks
    db.command(
        "sql",
        """INSERT INTO Task SET
           title = 'Write documentation',
           priority = 'medium',
           completed = false,
           tags = ['work', 'writing']
        """,
    )

    db.command(
        "sql",
        """INSERT INTO Task SET
           title = 'Call dentist',
           priority = 'low',
           completed = true,
           tags = ['personal', 'health']
        """,
    )

    # Note: Arrays (lists) work naturally - no need for JSON serialization!
    # Documents can contain: strings, numbers, booleans, arrays, nested objects

print("   ✅ Inserted 3 tasks")
print("   💡 Transaction committed automatically at end of 'with' block")
print()

# -----------------------------------------------------------------------------
# Step 4: QUERY - Read Documents
# -----------------------------------------------------------------------------
print("Step 4: Querying documents...")
print()

# Query all tasks
print("   📋 All tasks:")
result = db.query("sql", "SELECT FROM Task ORDER BY priority DESC")

for record in result:
    # Access properties using get_property() method
    title = str(record.get_property("title"))
    priority = str(record.get_property("priority"))
    completed = record.get_property("completed")
    status = "✅" if completed else "⏳"

    print(f"      {status} [{priority:6}] {title}")

print()

# Query with WHERE clause - find incomplete high priority tasks
print("   🔥 High priority incomplete tasks:")
result = db.query(
    "sql", "SELECT FROM Task WHERE priority = 'high' AND completed = false"
)

count = 0
for record in result:
    title = str(record.get_property("title"))
    tags = record.get_property("tags")
    print(f"      • {title}")
    # Convert Java array to Python list for display
    tag_list = [str(tag) for tag in tags]
    print(f"        Tags: {', '.join(tag_list)}")
    count += 1

if count == 0:
    print("      (none found)")

print()

# -----------------------------------------------------------------------------
# Step 5: UPDATE - Modify Documents
# -----------------------------------------------------------------------------
print("Step 5: Updating documents...")
print()

# Mark 'Buy groceries' as completed
with db.transaction():
    db.command("sql", "UPDATE Task SET completed = true WHERE title = 'Buy groceries'")

print("   ✅ Marked 'Buy groceries' as completed")
print()

# Verify the update
print("   📊 Updated task list:")
result = db.query("sql", "SELECT FROM Task ORDER BY completed, priority DESC")

for record in result:
    title = str(record.get_property("title"))
    priority = str(record.get_property("priority"))
    completed = record.get_property("completed")
    status = "✅" if completed else "⏳"
    print(f"      {status} [{priority:6}] {title}")

print()

# -----------------------------------------------------------------------------
# Step 6: Advanced Queries
# -----------------------------------------------------------------------------
print("Step 6: Running aggregation queries...")
print()

# Count total tasks
result = db.query("sql", "SELECT count(*) as total FROM Task")
total = list(result)[0].get_property("total")
print(f"   📊 Total tasks: {total}")

# Count by priority
result = db.query(
    "sql", "SELECT priority, count(*) as count FROM Task GROUP BY priority"
)
print("   📊 Tasks by priority:")
for record in result:
    priority = str(record.get_property("priority"))
    count = record.get_property("count")
    print(f"      • {priority}: {count}")

# Count completed vs incomplete
result = db.query(
    "sql", "SELECT completed, count(*) as count FROM Task GROUP BY completed"
)
print("   📊 Completion status:")
for record in result:
    completed = record.get_property("completed")
    count = record.get_property("count")
    status = "Completed" if completed else "Incomplete"
    print(f"      • {status}: {count}")

print()

# -----------------------------------------------------------------------------
# 💡 ArcadeDB SQL Key Difference: No JOINs
# -----------------------------------------------------------------------------
print("💡 ArcadeDB SQL Key Difference: No JOINs")
print()
print("   ArcadeDB uses LINKS instead of JOINs for relationships.")
print("   Instead of traditional JOIN syntax, use dot notation:")
print()
print("   ❌ Traditional SQL (not supported):")
print("      SELECT * FROM Employee A, City B")
print("      WHERE A.city = B.id AND B.name = 'Rome'")
print()
print("   ✅ ArcadeDB SQL (dot notation):")
print("      SELECT * FROM Employee WHERE city.name = 'Rome'")
print()
print("   This makes queries simpler and more intuitive!")
print()

# -----------------------------------------------------------------------------
# Step 7: DELETE - Remove Documents
# -----------------------------------------------------------------------------
print("Step 7: Deleting documents...")
print()

# Delete completed tasks
with db.transaction():
    result = db.command("sql", "DELETE FROM Task WHERE completed = true")

print("   🗑️  Deleted all completed tasks")
print()

# Verify deletion
result = db.query("sql", "SELECT count(*) as remaining FROM Task")
remaining = list(result)[0].get_property("remaining")
print(f"   📊 Remaining tasks: {remaining}")
print()

# Show remaining tasks
print("   📋 Remaining tasks:")
result = db.query("sql", "SELECT FROM Task ORDER BY priority DESC")

for record in result:
    title = str(record.get_property("title"))
    priority = str(record.get_property("priority"))
    print(f"      ⏳ [{priority:6}] {title}")

print()

# -----------------------------------------------------------------------------
# Step 8: Cleanup
# -----------------------------------------------------------------------------
print("Step 8: Cleanup...")
print()

# Close database connection
db.close()
print("   ✅ Database closed")

# Note: We're NOT deleting the database directory
# You can inspect the files in ./my_test_databases/task_db/
print(f"   💡 Database files preserved at: {db_path}")
print("   💡 Inspect the database structure and files!")
print("   💡 Re-run this script to recreate the database")

print()
print("=" * 70)
print("✅ Example Complete!")
print("=" * 70)
print()
print("📚 What you learned:")
print("   • Creating embedded databases (no server needed)")
print("   • Using context managers for transactions")
print("   • CRUD operations with SQL")
print("   • Querying and filtering data")
print("   • Working with flexible document schema")
print()
print("💡 Files created:")
print("   • ./my_test_databases/task_db/ - Database files:")
print("     - configuration.json - Database configuration")
print("     - schema.json - Schema definition (types & properties)")
print("     - Task_0.1.65536.v0.bucket - Data bucket with Task documents")
print("     - dictionary.0.327680.v0.dict - String compression dictionary")
print("     - statistics.json - Database statistics")
print("   • ./log/ - ArcadeDB and JVM log files")
print()
print("💡 Next steps:")
print("   • Try example 02: Graph operations with vertices and edges")
print("   • Try example 03: Vector search for semantic similarity")
print("   • See docs/examples/ for detailed explanations")
print("   • See docs/guide/operations.md for file structure and logging")
print()
print("🔗 API Documentation:")
print("   • Database: docs/api/database.md")
print("   • Transactions: docs/api/transactions.md")
print("   • Results: docs/api/results.md")
print()
