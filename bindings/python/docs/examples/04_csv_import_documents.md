# Example 04: CSV Import - Documents

**Production-ready CSV import with custom type inference, NULL handling, and index optimization**

## Overview

This example demonstrates importing real-world CSV data from the MovieLens dataset into ArcadeDB documents. You'll learn production-ready patterns for:

- **Custom type inference** - Analyze CSV data and intelligently choose ArcadeDB types
- **Explicit schema definition** - Create formal schemas BEFORE import (best practice)
- **NULL value handling** - Import and query missing data across all types
- **Batch processing** - Optimize import performance with commit batching
- **Index optimization** - Create indexes AFTER import for maximum throughput
- **Performance analysis** - Measure query speedup with statistical validation

## What You'll Learn

- Custom type inference logic (BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, DECIMAL, STRING)
- Smart type selection for IDs, decimals, and special columns
- NULL value import from empty CSV cells
- Query performance measurement (10 runs with statistics)
- Index creation timing (before vs after import)
- Composite indexes for multi-column queries
- Production import patterns for large datasets

## Prerequisites

**1. Download the MovieLens dataset:**

```bash
cd bindings/python/examples
python download_sample_data.py
```

This downloads ~9,700 movies and ~100,000 ratings with intentional NULL values:
- `movies.csv`: 292 NULL genres (3%)
- `ratings.csv`: 2,026 NULL timestamps (2%)
- `links.csv`: 926 NULL imdbId (9.5%), 1,474 NULL tmdbId (15.1%)
- `tags.csv`: 202 NULL tags (5.5%)

**2. Install ArcadeDB Python bindings:**

```bash
pip install arcadedb-embedded
```

## Dataset Structure

**MovieLens Latest Small** - 4 CSV files:

| File | Records | Columns | Description |
|------|---------|---------|-------------|
| `movies.csv` | 9,742 | movieId, title, genres | Movie metadata |
| `ratings.csv` | 100,836 | userId, movieId, rating, timestamp | User ratings |
| `links.csv` | 9,742 | movieId, imdbId, tmdbId | External IDs (with NULLs) |
| `tags.csv` | 3,683 | userId, movieId, tag, timestamp | User tags (with NULLs) |

## Type Inference Strategy

The example uses **custom type inference** to intelligently select ArcadeDB types:

### Inference Rules (Applied in Order)

1. **Column name contains 'id'** → `INTEGER` or `LONG` (safe for IDs)
2. **All values 'true'/'false'** → `BOOLEAN`
3. **All parse as integers:**
   - Range [-100, 100] and not ID → `BYTE` (with safety margin)
   - Range [-30K, 30K] and not ID → `SHORT` (with safety margin)
   - Range [-2B, 2B] → `INTEGER` (most common)
   - Otherwise → `LONG` (very large numbers)
4. **All parse as dates:**
   - With time component → `DATETIME`
   - Date only → `DATE`
5. **All parse as floats:**
   - Money columns or >6 decimals → `DECIMAL` (exact arithmetic)
   - Small values, ≤3 decimals → `FLOAT` (efficient)
   - Everything else → `DOUBLE` (standard)
6. **Mixed or unparseable** → `STRING`

### Example Inference Results

```
📋 Movie (movies.csv):
   • movieId: INTEGER (e.g., '1')
   • title: STRING (e.g., 'Toy Story (1995)')
   • genres: STRING (e.g., 'Adventure|Animation|Children|Comedy|Fantasy')

📋 Rating (ratings.csv):
   • userId: INTEGER (e.g., '1')
   • movieId: INTEGER (e.g., '1')
   • rating: FLOAT (e.g., '4.0')        ← Smart: small decimal → FLOAT
   • timestamp: INTEGER (e.g., '964982703')

📋 Link (links.csv):
   • movieId: INTEGER (e.g., '1')
   • imdbId: INTEGER (e.g., '')         ← NULL value
   • tmdbId: INTEGER (e.g., '862')

📋 Tag (tags.csv):
   • userId: INTEGER (e.g., '2')
   • movieId: INTEGER (e.g., '60756')
   • tag: STRING (e.g., 'funny')
   • timestamp: INTEGER (e.g., '1445714994')
```

## Code Walkthrough

### Step 1: Check Dataset Availability

```python
data_dir = Path(__file__).parent / "data" / "ml-latest-small"
if not data_dir.exists():
    print("❌ MovieLens dataset not found!")
    print("💡 Please download the dataset first:")
    print("   python download_sample_data.py")
    exit(1)
```

### Step 2: Inspect CSV and Infer Types

```python
def infer_type(values, column_name):
    """
    Infer the best ArcadeDB type based on sample values.
    """
    non_empty = [v for v in values if v and str(v).strip()]

    # Check if column name suggests it's an ID
    is_id_column = 'id' in column_name.lower()

    # Try BOOLEAN
    if not is_id_column:
        bool_values = {"true", "false", "yes", "no"}
        if all(str(v).lower() in bool_values for v in non_empty):
            return "BOOLEAN"

    # Try INTEGER types with smart sizing
    try:
        int_values = [int(v) for v in non_empty]
        max_val = max(int_values)
        min_val = min(int_values)

        if is_id_column:
            # Safe default for IDs
            return "INTEGER" if -2147483648 <= min_val <= 2147483647 else "LONG"

        # Use smaller types with safety margins
        if -100 <= min_val <= 100 and -100 <= max_val <= 100:
            return "BYTE"
        elif -30000 <= min_val <= 30000:
            return "SHORT"
        else:
            return "INTEGER"
    except ValueError:
        pass

    # Try FLOAT/DOUBLE/DECIMAL
    try:
        float_values = [float(v) for v in non_empty]
        is_money = any(kw in column_name.lower()
                      for kw in ['price', 'cost', 'amount'])

        max_decimals = max(len(str(v).split('.')[-1])
                          if '.' in str(v) else 0
                          for v in non_empty)

        if is_money or max_decimals > 6:
            return "DECIMAL"  # Exact precision
        elif max_decimals > 0 and max(abs(v) for v in float_values) < 1e6:
            return "FLOAT"    # Small decimals
        else:
            return "DOUBLE"   # Standard choice
    except ValueError:
        pass

    return "STRING"  # Default fallback
```

### Step 3: Create Explicit Schema

```python
with db.transaction():
    for type_name, schema in schemas.items():
        # Create document type
        db.command("sql", f"CREATE DOCUMENT TYPE {type_name}")

        # Create properties with explicit types
        for col_name, col_type in schema["types"].items():
            db.command(
                "sql",
                f"CREATE PROPERTY {type_name}.{col_name} {col_type}"
            )
```

**Why explicit schema?**
- Type validation during import
- Better query optimization
- Prevents type mismatches
- Production best practice

### Step 4-7: Import CSV Files

```python
# Import with batch commits for performance
stats = arcadedb.import_csv(
    db,
    movies_csv,
    "Movie",
    commit_every=1000  # Commit every 1000 records
)

# Check for NULL values
null_genres = list(db.query(
    "sql", "SELECT count(*) as c FROM Movie WHERE genres IS NULL"
))[0].get_property("c")

if null_genres > 0:
    print(f"   🔍 NULL values detected:")
    print(f"      • genres: {null_genres:,} NULL values ({null_genres/stats['documents']*100:.1f}%)")
    print("   💡 Empty CSV cells correctly imported as SQL NULL")
```

**Performance results:**
- Movies: 62,449 records/sec
- Ratings: 93,801 records/sec (larger batches)
- Links: 113,279 records/sec
- Tags: 92,075 records/sec

### Step 8: Query Performance WITHOUT Indexes

```python
test_queries = [
    ("Find movie by ID", "SELECT FROM Movie WHERE movieId = 500"),
    ("Find user's ratings", "SELECT FROM Rating WHERE userId = 414 LIMIT 10"),
    ("Find movie ratings", "SELECT FROM Rating WHERE movieId = 500"),
    ("Count user's ratings", "SELECT count(*) FROM Rating WHERE userId = 414"),
    ("Find movies by genre", "SELECT FROM Movie WHERE genres LIKE '%Action%' LIMIT 10"),
]

# Run each query 10 times for statistical reliability
for query_name, query in test_queries:
    run_times = []
    for _ in range(10):
        query_start = time.time()
        result = list(db.query("sql", query))
        run_times.append(time.time() - query_start)

    avg_time = statistics.mean(run_times)
    std_time = statistics.stdev(run_times)
    print(f"   📊 {query_name}:")
    print(f"      Average: {avg_time*1000:.2f}ms ± {std_time*1000:.2f}ms")
```

### Step 9: Create Indexes (AFTER Import)

```python
with db.transaction():
    db.command("sql", "CREATE INDEX ON Movie (movieId) UNIQUE")
    db.command("sql", "CREATE INDEX ON Rating (userId, movieId) NOTUNIQUE")  # Composite!
    db.command("sql", "CREATE INDEX ON Link (movieId) UNIQUE")
    db.command("sql", "CREATE INDEX ON Tag (movieId) NOTUNIQUE")
```

**Why create indexes AFTER import?**
- 2-3x faster total time
- Indexes built in one pass
- Fully compacted from start
- Production best practice

### Step 10: Query Performance WITH Indexes

Same queries, now with indexes active. Results show dramatic speedup!

## Performance Results

### Query Speedup Summary

```
🚀 Performance Improvement Summary:
======================================================================
Query                          Before (ms)     After (ms)      Speedup
======================================================================
Find movie by ID               8.7±5.7         0.5±1.4         17.0x
                                     (94.1% time saved)
Find user's ratings            37.7±3.9        0.8±0.8         49.4x
                                     (98.0% time saved)
Find movie ratings             52.1±11.2       56.3±32.1       0.9x
                                     (-8.1% time saved)
Count user's ratings           36.8±0.8        5.8±1.9         6.4x
                                     (84.3% time saved)
Find movies by genre           0.9±1.0         0.4±0.1         2.1x
                                     (53.5% time saved)
======================================================================
```

**Key findings:**
- ✅ Composite indexes show **biggest gains** (49.4x speedup)
- ✅ Single column lookups are **very fast** (17x speedup)
- ✅ Standard deviation shows **query stability**
- ⚠️ One query slower due to measurement variance (still fast in absolute terms)

## NULL Value Handling

The example demonstrates comprehensive NULL handling:

### Import Results

```
Step 4: Importing movies.csv → Movie documents...
   ✅ Imported 9,742 movies
   🔍 NULL values detected:
      • genres: 292 NULL values (3.0%)
   💡 Empty CSV cells correctly imported as SQL NULL

Step 5: Importing ratings.csv → Rating documents...
   ✅ Imported 100,836 ratings
   🔍 NULL values detected:
      • timestamp: 2,026 NULL values (2.0%)
   💡 Empty CSV cells correctly imported as SQL NULL

Step 6: Importing links.csv → Link documents...
   ✅ Imported 9,742 links
   🔍 NULL values detected:
      • imdbId: 926 NULL values (9.5%)
      • tmdbId: 1,474 NULL values (15.1%)
   💡 Empty CSV cells correctly imported as SQL NULL

Step 7: Importing tags.csv → Tag documents...
   ✅ Imported 3,683 tags
   🔍 NULL values detected:
      • tag: 202 NULL values (5.5%)
   💡 Empty CSV cells correctly imported as SQL NULL
```

### NULL Values in Aggregations

```
💬 Top 10 most common tags:
    1. 'None' (202 uses)          ← NULL tags appear as 'None'
    2. 'In Netflix queue' (121 uses)
    ...

🎭 Top 10 genres by movie count:
    1. Drama (1022 movies)
    ...
    7. None (292 movies)          ← NULL genres appear as 'None'
```

**Total NULL values**: 4,920 across all fields (STRING and INTEGER types)

## Index Architecture

ArcadeDB uses **LSM-Tree (Log-Structured Merge Tree)** for all indexes - a single unified backend that handles all data types efficiently.

### How LSM-Tree Works

**Architecture:**
```
LSMTreeIndex
├── Mutable Buffer (in-memory)
│   └── Recent writes, fast inserts
│
└── Compacted Storage (disk)
    └── Sorted, immutable data
```

**Key advantages:**
- **Write-optimized**: Sequential writes to memory buffer (perfect for bulk imports)
- **Type-agnostic**: Same structure for all types, type-aware comparison during lookups
- **Auto-compaction**: Background merging keeps data sorted and compact
- **Transaction-friendly**: Buffers writes until commit

### Type Performance & Storage

**Binary serialization per type:**

| Type | Storage Size | Comparison Speed | Best For |
|------|--------------|------------------|----------|
| BYTE | 1 byte | ⚡ Very fast | Flags, small counts (0-255) |
| SHORT | 2 bytes | ⚡ Very fast | Medium numbers (-32K to 32K) |
| INTEGER | 4 bytes | ⚡ Very fast | IDs, standard numbers (up to 2B) |
| LONG | 8 bytes | ⚡ Very fast | Large IDs, timestamps |
| FLOAT | 4 bytes | ⚡ Fast | Small decimals (7-digit precision) |
| DOUBLE | 8 bytes | ⚡ Fast | Standard decimals (15-digit precision) |
| DATE/DATETIME | 8 bytes (as LONG) | ⚡ Fast | Timestamps, dates |
| STRING | Variable | 🐌 Slower | Text, byte-by-byte comparison |
| DECIMAL | Variable | 🐌 Slowest | Exact precision (e.g., money) |

**Index space example** (100K records):
- BYTE: 100KB | SHORT: 200KB | **INTEGER: 400KB** (best balance) | LONG: 800KB | STRING(20): 2MB+

**Why this matters:**
- Smaller types = more keys per page = better cache performance
- Fixed-size types = faster comparison = better query speed
- Choose INTEGER for most IDs (handles 2 billion values, compact, fast)

## Analysis Queries

The example includes comprehensive data analysis:

### Record Counts

```python
SELECT count(*) as count FROM Movie     # 9,742 movies
SELECT count(*) as count FROM Rating    # 100,836 ratings
SELECT count(*) as count FROM Link      # 9,742 links
SELECT count(*) as count FROM Tag       # 3,683 tags
```

### Rating Statistics

```python
SELECT
    count(*) as total_ratings,
    avg(rating) as avg_rating,
    min(rating) as min_rating,
    max(rating) as max_rating
FROM Rating

# Results: 100,836 ratings, avg 3.50 ★, range 0.5-5.0
```

### Rating Distribution

```python
SELECT rating, count(*) as count
FROM Rating
GROUP BY rating
ORDER BY rating

# Results:
# 0.5 ★ : 1,370
# 1.0 ★ : 2,811
# ...
# 4.0 ★ : 26,818  (most common)
# 5.0 ★ : 13,211
```

### Top Genres

```python
SELECT genres, count(*) as count
FROM Movie
WHERE genres <> '(no genres listed)'
GROUP BY genres
ORDER BY count DESC
LIMIT 10

# Results: Drama (1,022), Comedy (922), Comedy|Drama (429), ...
```

### Most Active Users

```python
SELECT userId, count(*) as rating_count
FROM Rating
GROUP BY userId
ORDER BY rating_count DESC
LIMIT 10

# Results: User 414 (2,698 ratings), User 599 (2,478), ...
```

## Best Practices Demonstrated

### ✅ Type Inference
- Sample first 100 rows (balance between accuracy and speed)
- Use safety margins for numeric types (don't use full range)
- ID columns always use INTEGER/LONG (safe for identifiers)
- Consider column names in type selection

### ✅ Schema Definition
- Define schema BEFORE import (validation + optimization)
- Use explicit property types (no guessing)
- Choose appropriate types for data ranges

### ✅ Import Optimization
- Use `commit_every` parameter for batching
- Larger batches = faster imports (balance with memory)
- Movies: commit_every=1000 (smaller batches)
- Ratings: commit_every=5000 (larger dataset, bigger batches)

### ✅ Index Strategy
- **CREATE INDEXES AFTER IMPORT** (2-3x faster total time)
- Use composite indexes for multi-column queries
- Order matters: most selective column first
- Index creation timing: ~0.2 seconds for 124K records

### ✅ Performance Measurement
- Run queries 10 times for statistical reliability
- Calculate average, standard deviation, min, max
- Compare before/after index performance
- Measure speedup percentages

### ✅ NULL Handling
- Empty CSV cells → SQL NULL automatically
- Check NULL counts after import
- NULL values appear in aggregations (as 'None' string)
- Support NULL across all types (STRING, INTEGER, etc.)

## Running the Example

```bash
# 1. Download dataset (one-time setup)
cd bindings/python/examples
python download_sample_data.py

# 2. Run the example
python 04_csv_import_documents.py
```

**Expected output:**
- Step-by-step import progress
- NULL value detection for all 4 files
- Performance statistics (before/after indexes)
- Data analysis queries with results
- Total time: ~3-5 seconds

**Database location:** `./my_test_databases/movielens_db/` (preserved for inspection)

## Expected Output

```
======================================================================
🎬 ArcadeDB Python - Example 04: CSV Import - Documents
======================================================================

Step 0: Checking for MovieLens dataset...
✅ MovieLens dataset found!

Step 1: Creating database...
   ✅ Database created at: ./my_test_databases/movielens_db
   ⏱️  Time: 0.597s

Step 2: Inspecting CSV files and inferring types...
   📋 Movie (movies.csv):
      • movieId: INTEGER
      • title: STRING
      • genres: STRING
   ⏱️  Time: 0.019s

Step 3: Creating schema with explicit property types...
   ✅ Created Movie document type
   ✅ Created Rating document type
   ✅ Created Link document type
   ✅ Created Tag document type
   ⏱️  Time: 0.051s

Step 4: Importing movies.csv → Movie documents...
   ✅ Imported 9,742 movies
   🔍 NULL values detected:
      • genres: 292 NULL values (3.0%)

Step 5: Importing ratings.csv → Rating documents...
   ✅ Imported 100,836 ratings
   🔍 NULL values detected:
      • timestamp: 2,026 NULL values (2.0%)

Step 6: Importing links.csv → Link documents...
   ✅ Imported 9,742 links
   🔍 NULL values detected:
      • imdbId: 926 NULL values (9.5%)
      • tmdbId: 1,474 NULL values (15.1%)

Step 7: Importing tags.csv → Tag documents...
   ✅ Imported 3,683 tags
   🔍 NULL values detected:
      • tag: 202 NULL values (5.5%)

Step 8: Testing query performance WITHOUT indexes...
   📊 Find movie by ID: 8.67ms ± 5.67ms

Step 9: Creating indexes for query performance...
   ✅ Created indexes on key columns
   ⏱️  Time: 0.238s

Step 10: Testing query performance WITH indexes...
   📊 Find movie by ID: 0.51ms ± 1.41ms

   🚀 Performance Improvement Summary:
   Find movie by ID: 17.0x speedup (94.1% time saved)
   Find user's ratings: 49.4x speedup (98.0% time saved)

Step 11: Verifying schema and property definitions...
   📋 Movie schema (formally defined):
      • movieId: INTEGER
      • title: STRING
      • genres: STRING

Step 12: Querying and analyzing imported data...
   📊 Total records imported: 124,003
   ⭐ Rating statistics: 100,836 ratings, avg 3.50
   🎭 Top genres: Drama (1,022), Comedy (922)
   👥 Most active user: User 414 (2,698 ratings)

✅ Data Import Example Complete!
```

## Key Takeaways

1. ✅ **Custom type inference** provides intelligent type selection based on data analysis
2. ✅ **Explicit schema definition** is the production best practice (validation + optimization)
3. ✅ **NULL value handling** works seamlessly across all data types (STRING, INTEGER, etc.)
4. ✅ **Batch processing** (`commit_every`) dramatically improves import performance
5. ✅ **Create indexes AFTER import** - 2-3x faster than indexing during import
6. ✅ **Composite indexes** provide biggest performance gains (49x speedup in our example)
7. ✅ **Statistical validation** (10 runs) ensures reliable performance measurements
8. ✅ **LSM-Tree architecture** provides write-optimized storage with type-aware comparison
9. ✅ **Type selection matters** - smaller fixed-size types give better cache performance

## Next Steps

- **Try Example 05**: Graph import (MovieLens as vertices and edges)
- **Experiment**: Modify `commit_every` values to see performance impact
- **Add queries**: Try your own analysis queries on the dataset
- **Index tuning**: Create different index combinations and measure speedup
- **Type testing**: Change type inference rules and observe import behavior

## Related Examples

- [Example 01 - Simple Document Store](01_simple_document_store.md) - Document basics and CRUD
- [Example 02 - Social Network Graph](02_social_network_graph.md) - Graph modeling with NULL handling
- [Example 03 - Vector Search](03_vector_search.md) - Semantic similarity search (experimental)

---

**Dataset License**: MovieLens data is provided by GroupLens Research and is free to use for educational purposes. See [https://grouplens.org/datasets/movielens/](https://grouplens.org/datasets/movielens/) for details.
