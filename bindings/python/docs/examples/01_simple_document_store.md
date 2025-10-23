# Simple Document Store Example

This comprehensive example demonstrates ArcadeDB's document capabilities using real-world scenarios. You'll learn about data types, SQL functions, and the differences between document and graph storage models.

## Overview

The example creates a product catalog system showcasing:

- **Rich Data Types** - STRING, BOOLEAN, INTEGER, FLOAT, DECIMAL, DATE, DATETIME, BINARY, EMBEDDED, LINK, Arrays
- **SQL Functions** - uuid(), date(), sysdate() for dynamic data generation
- **Record Types** - Understanding Documents vs Vertices vs Edges
- **Schema Evolution** - Adding typed properties for performance and validation
- **CRUD Operations** - Complete create, read, update, delete workflow

## Source Code

The complete example is available at: [`examples/01_simple_document_store.py`](../../examples/01_simple_document_store.py)

## Key Learning Points

### 1. Data Type Support

ArcadeDB provides comprehensive data type support:

```python
# Schema with typed properties for performance
db.command("sql", """
CREATE DOCUMENT TYPE Product (
    name STRING,
    description STRING,
    price DECIMAL,
    in_stock BOOLEAN,
    category_id INTEGER,
    weight FLOAT,
    created_at DATETIME,
    tags STRING[],
    metadata EMBEDDED
)
""")
```

### 2. SQL Functions

Learn about built-in functions for dynamic data:

```python
# UUID generation and date functions
result = db.command("sql", """
INSERT INTO Product SET
    id = uuid(),
    name = 'Laptop Pro',
    created_at = sysdate(),
    launch_date = date('2024-01-15', 'yyyy-MM-dd')
""")
```

### 3. Record Types Explained

Understanding when to use different record types:

- **Document** - Like database tables, for simple data storage
- **Vertex** - Graph nodes representing entities
- **Edge** - Graph connections representing relationships

### 4. Advanced Features

The example demonstrates:

- **Embedded Documents** - Nested JSON-like structures
- **Arrays** - Collections of values
- **Schema Evolution** - Adding properties dynamically
- **Query Optimization** - Using indexes and typed properties

## Running the Example

```bash
cd bindings/python/examples/
python 01_simple_document_store.py
```

Expected output includes:
- Database creation and schema setup
- Sample data insertion with various types
- Query demonstrations
- File structure explanation

## Database Structure

After running, examine the created files:

```
databases/product_catalog/
├── configuration.json     # Database configuration
├── schema.json           # Type definitions and indexes
├── *.bucket             # Data storage files
└── statistics.json      # Database statistics
```

## Next Steps

After mastering this example:

1. **Explore Graph Operations** - Learn about vertices and edges
2. **Try Vector Search** - Modern AI/ML integration
3. **Review API Documentation** - Deep dive into advanced features

## Common Questions

**Q: Why use typed properties?**
A: They provide better performance, validation, and enable advanced features like indexes.

**Q: When should I use Documents vs Vertices?**
A: Use Documents for simple data storage (like SQL tables). Use Vertices when you need to model relationships between entities.

**Q: Can I mix data types?**
A: Yes! ArcadeDB is schema-flexible. You can add properties dynamically while benefiting from typed properties where defined.

---

*Need help? Check our [troubleshooting guide](../troubleshooting.md) or [open an issue](https://github.com/ArcadeData/arcadedb/issues).*
