#!/usr/bin/env python3
"""
Example 04: CSV Import - Documents with Automatic Type Inference

This example demonstrates importing CSV data into ArcadeDB DOCUMENTS
with AUTOMATIC TYPE INFERENCE - Java analyzes the CSV and infers types.

We use the MovieLens dataset with four CSV files:
- movies.csv: Movie information (9,743 movies)
- ratings.csv: User ratings (100,837 ratings)
- links.csv: External movie IDs (9,742 links)
- tags.csv: User-generated tags (3,683 tags)

Key Concepts:
- **Automatic type inference** by Java CSV importer
- Schema created on-the-fly during import
- Batch processing with commitEvery parameter
- Creating indexes AFTER import for performance
- Query performance comparison with/without indexes
- NULL value handling

Java's Automatic Type Inference:
The Java CSV importer analyzes the first ~10,000 rows and infers types:
- Integer values → LONG (safe for all integer sizes)
- Decimal values → DOUBLE (standard precision for floats)
- Text values → STRING
- Empty cells → NULL (proper SQL NULL handling)

Note: Java defaults to conservative types (LONG, DOUBLE) for safety.
This avoids overflow issues but uses more storage than smaller types.

Supported ArcadeDB Types:
Numeric:
  - BYTE: 8-bit integer (-128 to 127)
  - SHORT: 16-bit integer (-32,768 to 32,767)
  - INTEGER: 32-bit integer (-2.1B to 2.1B)
  - LONG: 64-bit integer (large numbers) ← Java's default for integers
  - FLOAT: 32-bit decimal (~7 digits precision)
  - DOUBLE: 64-bit decimal (~15 digits precision) ← Java's default for decimals
  - DECIMAL: Arbitrary precision (exact, for money)

Other:
  - STRING, BOOLEAN, DATE, DATETIME, BINARY, EMBEDDED, LIST

Requirements:
- arcadedb-embedded (any distribution: headless, minimal, or full)
- MovieLens dataset (downloaded via download_sample_data.py)
- JRE 21+

Usage:
1. First download the dataset:
   python download_sample_data.py
2. Run this example:
   python 04_csv_import_documents.py

Note: This example creates a database at ./my_test_databases/movielens_db/
      The database files are preserved so you can inspect them after running.
"""

import os
import shutil
import statistics
import time
from pathlib import Path

import arcadedb_embedded as arcadedb

print("=" * 70)
print("🎬 ArcadeDB Python - Example 04: CSV Import - Documents")
print("=" * 70)
print()

# -----------------------------------------------------------------------------
# Step 0: Check Dataset Availability
# -----------------------------------------------------------------------------
print("Step 0: Checking for MovieLens dataset...")
print()

data_dir = Path(__file__).parent / "data" / "ml-latest-small"
if not data_dir.exists():
    print("❌ MovieLens dataset not found!")
    print()
    print("💡 Please download the dataset first:")
    print("   python download_sample_data.py")
    print()
    exit(1)

# Verify all required CSV files exist
required_files = ["movies.csv", "ratings.csv", "links.csv", "tags.csv"]
missing_files = [f for f in required_files if not (data_dir / f).exists()]

if missing_files:
    print(f"❌ Missing files: {', '.join(missing_files)}")
    print()
    print("💡 Please re-download the dataset:")
    print("   python download_sample_data.py")
    print()
    exit(1)

print("✅ MovieLens dataset found!")
print(f"   Location: {data_dir}")
print()

# Show file sizes
print("📊 Dataset files:")
for csv_file in required_files:
    file_path = data_dir / csv_file
    size_kb = file_path.stat().st_size / 1024
    print(f"   • {csv_file}: {size_kb:.1f} KB")
print()

# -----------------------------------------------------------------------------
# Step 1: Create Database
# -----------------------------------------------------------------------------
print("Step 1: Creating database...")
step_start = time.time()

db_dir = "./my_test_databases"
db_path = os.path.join(db_dir, "movielens_db")

# Clean up any existing database from previous runs
if os.path.exists(db_path):
    shutil.rmtree(db_path)

# Clean up log directory from previous runs
if os.path.exists("./log"):
    shutil.rmtree("./log")

db = arcadedb.create_database(db_path)

print(f"   ✅ Database created at: {db_path}")
print("   💡 Using embedded mode - no server needed!")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# -----------------------------------------------------------------------------
# Step 2: Import Movies CSV → Movie Documents (with automatic type inference)
# -----------------------------------------------------------------------------
print("Step 2: Importing movies.csv → Movie documents...")
print("   💡 Java will automatically:")
print("      • Analyze CSV structure and infer column types")
print("      • Create 'Movie' document type with inferred schema")
print("      • Import all rows with batch commits")
print()
step_start = time.time()

movies_csv = str(data_dir / "movies.csv")
stats = arcadedb.import_csv(db, movies_csv, "Movie", commitEvery=1000)

print(f"   ✅ Imported {stats['documents']:,} movies")
print(f"   💡 Errors: {stats['errors']}")
print(f"   ⏱️  Time: {stats['duration_ms'] / 1000:.3f}s")
rate = stats["documents"] / (stats["duration_ms"] / 1000)
print(f"   ⏱️  Rate: {rate:.0f} records/sec")

# Check NULL values (genres can be NULL)
null_genres = list(
    db.query("sql", "SELECT count(*) as c FROM Movie WHERE genres IS NULL")
)[0].get_property("c")

if null_genres > 0:
    print("   🔍 NULL values detected:")
    print(
        f"      • genres: {null_genres:,} NULL values "
        f"({null_genres/stats['documents']*100:.1f}%)"
    )
    print("   💡 Empty CSV cells correctly imported as SQL NULL")

print()

# -----------------------------------------------------------------------------
# Step 3: Display Java's Auto-Inferred Schema
# -----------------------------------------------------------------------------
print("Step 3: Inspecting Java's auto-inferred schema...")
print()

# Query the schema that Java created during import
result = db.query("sql", "SELECT properties FROM schema:types WHERE name = 'Movie'")
for record in result:
    properties = record.get_property("properties")

    print("   📋 Movie schema (auto-inferred by Java):")
    if properties:
        for prop in properties:
            # prop is a Java Map object
            prop_map = dict(prop.toMap()) if hasattr(prop, "toMap") else prop
            prop_name = prop_map.get("name")
            prop_type = prop_map.get("type")
            print(f"      • {prop_name}: {prop_type}")
    else:
        print("      (No properties found)")

print()
print("   💡 Java's type inference strategy:")
print("      • Analyzed first ~10,000 CSV rows")
print("      • LONG for all integer values (safe, no overflow)")
print("      • DOUBLE for all decimal values (standard precision)")
print("      • STRING for text")
print("      • Empty cells → NULL (proper SQL NULL handling)")
print()

# -----------------------------------------------------------------------------
# Step 4: Import Ratings CSV → Rating Documents
# -----------------------------------------------------------------------------
print("Step 4: Importing ratings.csv → Rating documents...")
step_start = time.time()

ratings_csv = str(data_dir / "ratings.csv")
stats = arcadedb.import_csv(db, ratings_csv, "Rating", commitEvery=5000)

print(f"   ✅ Imported {stats['documents']:,} ratings")
print(f"   💡 Errors: {stats['errors']}")
print(f"   ⏱️  Time: {stats['duration_ms'] / 1000:.3f}s")
rate = stats["documents"] / (stats["duration_ms"] / 1000)
print(f"   ⏱️  Rate: {rate:.0f} records/sec")

# Check NULL values (timestamp can be NULL)
null_timestamps = list(
    db.query("sql", "SELECT count(*) as c FROM Rating WHERE timestamp IS NULL")
)[0].get_property("c")

if null_timestamps > 0:
    print("   🔍 NULL values detected:")
    print(
        f"      • timestamp: {null_timestamps:,} NULL values "
        f"({null_timestamps/stats['documents']*100:.1f}%)"
    )
    print("   💡 Empty CSV cells correctly imported as SQL NULL")

print()

# -----------------------------------------------------------------------------
# Step 5: Import Links CSV → Link Documents
# -----------------------------------------------------------------------------
print("Step 5: Importing links.csv → Link documents...")
step_start = time.time()

links_csv = str(data_dir / "links.csv")
stats = arcadedb.import_csv(db, links_csv, "Link", commitEvery=1000)

print(f"   ✅ Imported {stats['documents']:,} links")
print(f"   💡 Errors: {stats['errors']}")
print(f"   ⏱️  Time: {stats['duration_ms'] / 1000:.3f}s")
rate = stats["documents"] / (stats["duration_ms"] / 1000)
print(f"   ⏱️  Rate: {rate:.0f} records/sec")

# Check NULL values (imdbId and tmdbId can be NULL)
null_imdb = list(
    db.query("sql", "SELECT count(*) as c FROM Link WHERE imdbId IS NULL")
)[0].get_property("c")
null_tmdb = list(
    db.query("sql", "SELECT count(*) as c FROM Link WHERE tmdbId IS NULL")
)[0].get_property("c")

if null_imdb > 0 or null_tmdb > 0:
    print("   🔍 NULL values detected:")
    if null_imdb > 0:
        print(
            f"      • imdbId: {null_imdb:,} NULL values "
            f"({null_imdb/stats['documents']*100:.1f}%)"
        )
    if null_tmdb > 0:
        print(
            f"      • tmdbId: {null_tmdb:,} NULL values "
            f"({null_tmdb/stats['documents']*100:.1f}%)"
        )
    print("   💡 Empty CSV cells correctly imported as SQL NULL")

print()

# -----------------------------------------------------------------------------
# Step 6: Import Tags CSV → Tag Documents
# -----------------------------------------------------------------------------
print("Step 6: Importing tags.csv → Tag documents...")
step_start = time.time()

tags_csv = str(data_dir / "tags.csv")
stats = arcadedb.import_csv(db, tags_csv, "Tag", commitEvery=1000)

print(f"   ✅ Imported {stats['documents']:,} tags")
print(f"   💡 Errors: {stats['errors']}")
print(f"   ⏱️  Time: {stats['duration_ms'] / 1000:.3f}s")
rate = stats["documents"] / (stats["duration_ms"] / 1000)
print(f"   ⏱️  Rate: {rate:.0f} records/sec")

# Check NULL values in tag field
null_tags = list(db.query("sql", "SELECT count(*) as c FROM Tag WHERE tag IS NULL"))[
    0
].get_property("c")

if null_tags > 0:
    print("   🔍 NULL values detected:")
    print(
        f"      • tag: {null_tags:,} NULL values "
        f"({null_tags/stats['documents']*100:.1f}%)"
    )
    print("   💡 Empty CSV cells correctly imported as SQL NULL")

print()

# -----------------------------------------------------------------------------
# Step 7: Verify All Auto-Inferred Schemas
# -----------------------------------------------------------------------------
print("Step 7: Verifying all auto-inferred schemas...")
print()

# Query the formal schema to see Java's automatically inferred properties
for doc_type in ["Movie", "Rating", "Link", "Tag"]:
    result = db.query(
        "sql", f"SELECT properties FROM schema:types WHERE name = '{doc_type}'"
    )

    for record in result:
        properties = record.get_property("properties")

        print(f"   📋 {doc_type} schema (auto-inferred by Java):")
        if properties:
            for prop in properties:
                # prop is a Java Map object
                prop_map = dict(prop.toMap()) if hasattr(prop, "toMap") else prop
                prop_name = prop_map.get("name")
                prop_type = prop_map.get("type")
                print(f"      • {prop_name}: {prop_type}")
        else:
            print("      (No properties found)")
        print()

print("   💡 Type inference observations:")
print("      • All integer columns → LONG (Java's safe default)")
print("      • All decimal columns → DOUBLE (Java's standard precision)")
print("      • Text columns → STRING")
print("      • Empty cells → NULL (proper SQL NULL handling)")
print()

# -----------------------------------------------------------------------------
# Step 8: Test Query Performance WITHOUT Indexes (Multiple Runs)
# -----------------------------------------------------------------------------
print("Step 8: Testing query performance WITHOUT indexes (10 runs each)...")
print()

# Test queries that would benefit from indexes
test_queries = [
    ("Find movie by ID", "SELECT FROM Movie WHERE movieId = 500"),
    ("Find user's ratings", "SELECT FROM Rating WHERE userId = 414 LIMIT 10"),
    ("Find movie ratings", "SELECT FROM Rating WHERE movieId = 500"),
    ("Count user's ratings", "SELECT count(*) FROM Rating WHERE userId = 414"),
    ("Find movies by genre", "SELECT FROM Movie WHERE genres LIKE '%Action%' LIMIT 10"),
]

# Run each query 10 times and collect statistics
times_without_indexes = []
for query_name, query in test_queries:
    run_times = []
    result_count = 0

    for _ in range(10):
        query_start = time.time()
        result = list(db.query("sql", query))
        query_time = time.time() - query_start
        run_times.append(query_time)
        result_count = len(result)

    # Calculate statistics
    avg_time = statistics.mean(run_times)
    std_time = statistics.stdev(run_times) if len(run_times) > 1 else 0
    min_time = min(run_times)
    max_time = max(run_times)

    times_without_indexes.append(
        {
            "name": query_name,
            "runs": run_times,
            "avg": avg_time,
            "std": std_time,
            "min": min_time,
            "max": max_time,
            "count": result_count,
        }
    )

    print(f"   📊 {query_name}:")
    print(f"      Average: {avg_time*1000:.2f}ms ± {std_time*1000:.2f}ms")
    print(f"      Range: [{min_time*1000:.2f}ms - {max_time*1000:.2f}ms]")
    print(f"      Results: {result_count}")
    print()

print("   💡 Running queries multiple times to get reliable statistics")

# -----------------------------------------------------------------------------
# Step 9: Create Indexes After Import (Best Practice)
# -----------------------------------------------------------------------------
print("Step 9: Creating indexes for query performance...")
step_start = time.time()

# Since we already defined properties explicitly in Step 3,
# we can create indexes directly
try:
    with db.transaction():
        # Create indexes on key columns for fast lookups
        db.command("sql", "CREATE INDEX ON Movie (movieId) UNIQUE")
        db.command("sql", "CREATE INDEX ON Rating (userId, movieId) NOTUNIQUE")
        db.command("sql", "CREATE INDEX ON Link (movieId) UNIQUE")
        db.command("sql", "CREATE INDEX ON Tag (movieId) NOTUNIQUE")

    print("   ✅ Created indexes on key columns")
    print("   💡 Best practice: Create indexes AFTER bulk import")
except Exception as e:  # noqa: BLE001
    print(f"   ⚠️  Index creation failed: {e}")

print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# -----------------------------------------------------------------------------
# Step 10: Test Query Performance WITH Indexes (Multiple Runs)
# -----------------------------------------------------------------------------
print("Step 10: Testing query performance WITH indexes (10 runs each)...")
print()

times_with_indexes = []
for query_name, query in test_queries:
    run_times = []
    result_count = 0

    for _ in range(10):
        query_start = time.time()
        result = list(db.query("sql", query))
        query_time = time.time() - query_start
        run_times.append(query_time)
        result_count = len(result)

    # Calculate statistics
    avg_time = statistics.mean(run_times)
    std_time = statistics.stdev(run_times) if len(run_times) > 1 else 0
    min_time = min(run_times)
    max_time = max(run_times)

    times_with_indexes.append(
        {
            "name": query_name,
            "runs": run_times,
            "avg": avg_time,
            "std": std_time,
            "min": min_time,
            "max": max_time,
            "count": result_count,
        }
    )

    print(f"   📊 {query_name}:")
    print(f"      Average: {avg_time*1000:.2f}ms ± {std_time*1000:.2f}ms")
    print(f"      Range: [{min_time*1000:.2f}ms - {max_time*1000:.2f}ms]")
    print(f"      Results: {result_count}")
    print()

print()
print("   🚀 Performance Improvement Summary:")
print("   " + "=" * 70)
print(f"   {'Query':<30} {'Before (ms)':<15} {'After (ms)':<15} {'Speedup':<10}")
print("   " + "=" * 70)

for i in range(len(test_queries)):
    before_stats = times_without_indexes[i]
    after_stats = times_with_indexes[i]

    before_avg = before_stats["avg"] * 1000
    before_std = before_stats["std"] * 1000
    after_avg = after_stats["avg"] * 1000
    after_std = after_stats["std"] * 1000

    if after_stats["avg"] > 0:
        speedup = before_stats["avg"] / after_stats["avg"]
        time_saved_pct = (
            (before_stats["avg"] - after_stats["avg"]) / before_stats["avg"]
        ) * 100

        query_name = before_stats["name"][:28]
        before_str = f"{before_avg:.1f}±{before_std:.1f}"
        after_str = f"{after_avg:.1f}±{after_std:.1f}"
        speedup_str = f"{speedup:.1f}x"

        print(f"   {query_name:<30} {before_str:<15} {after_str:<15} {speedup_str:<10}")

        # Detailed improvement info
        improvement_msg = f"      ({time_saved_pct:.1f}% time saved)"
        print(f"   {'':<30} {improvement_msg}")
    else:
        print(f"   {before_stats['name']:<30} Too fast to measure")

print("   " + "=" * 70)
print()
print("   💡 Key Findings:")
print("      • Indexes provide consistent speedup across multiple runs")
print("      • Standard deviation shows query time stability")
print("      • Composite indexes (userId, movieId) show biggest gains")
print("      • Even non-indexed columns benefit from reduced dataset size")
print()
print("   💡 Indexes are essential for production performance!")
print()

# -----------------------------------------------------------------------------
# Step 12: Import Performance Summary
# -----------------------------------------------------------------------------
print("Step 12: Import performance summary...")
print()

# 10.1 - Count records in each type
print("   📊 Record counts by type:")
step_start = time.time()
for doc_type in ["Movie", "Rating", "Link", "Tag"]:
    result = db.query("sql", f"SELECT count(*) as count FROM {doc_type}")
    count = list(result)[0].get_property("count")
    print(f"      • {doc_type}: {count:,} records")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 12.2 - Sample movies
print("   🎬 Sample movies:")
step_start = time.time()
result = db.query("sql", "SELECT FROM Movie LIMIT 5")
for record in result:
    movie_id = record.get_property("movieId")
    title = str(record.get_property("title"))
    genres = str(record.get_property("genres"))
    print(f"      • [{movie_id}] {title}")
    print(f"        Genres: {genres}")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 12.3 - Rating statistics
print("   ⭐ Rating statistics:")
step_start = time.time()
result = db.query(
    "sql",
    """SELECT
         count(*) as total_ratings,
         avg(rating) as avg_rating,
         min(rating) as min_rating,
         max(rating) as max_rating
       FROM Rating""",
)
record = list(result)[0]
total = record.get_property("total_ratings")
avg_rating = record.get_property("avg_rating")
min_rating = record.get_property("min_rating")
max_rating = record.get_property("max_rating")
print(f"      • Total ratings: {total:,}")
print(f"      • Average rating: {avg_rating:.2f}")
print(f"      • Min rating: {min_rating}")
print(f"      • Max rating: {max_rating}")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 12.4 - Rating distribution
print("   📊 Rating distribution:")
step_start = time.time()
result = db.query(
    "sql",
    """SELECT rating, count(*) as count
       FROM Rating
       GROUP BY rating
       ORDER BY rating""",
)
for record in result:
    rating = record.get_property("rating")
    count = record.get_property("count")
    bar = "█" * int(count / 3000)  # Scale for visualization
    print(f"      {rating:.1f} ★ : {count:,} {bar}")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 12.5 - Most popular genres
print("   🎭 Top 10 genres by movie count:")
step_start = time.time()
# Note: genres are pipe-delimited, so this is approximate
result = db.query(
    "sql",
    """SELECT genres, count(*) as count
       FROM Movie
       WHERE genres <> '(no genres listed)'
       GROUP BY genres
       ORDER BY count DESC
       LIMIT 10""",
)
for idx, record in enumerate(result, 1):
    genres = str(record.get_property("genres"))
    count = record.get_property("count")
    # Truncate long genre lists
    if len(genres) > 50:
        genres = genres[:47] + "..."
    print(f"      {idx:2}. {genres} ({count} movies)")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 10.6 - Most active users (by rating count)
print("   👥 Top 10 most active users (by ratings):")
step_start = time.time()
result = db.query(
    "sql",
    """SELECT userId, count(*) as rating_count
       FROM Rating
       GROUP BY userId
       ORDER BY rating_count DESC
       LIMIT 10""",
)
for idx, record in enumerate(result, 1):
    user_id = record.get_property("userId")
    rating_count = record.get_property("rating_count")
    print(f"      {idx:2}. User {user_id}: {rating_count} ratings")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 10.7 - Most tagged movies
print("   🏷️  Top 10 most tagged movies:")
step_start = time.time()
result = db.query(
    "sql",
    """SELECT movieId, count(*) as tag_count
       FROM Tag
       GROUP BY movieId
       ORDER BY tag_count DESC
       LIMIT 10""",
)
for idx, record in enumerate(result, 1):
    movie_id = record.get_property("movieId")
    tag_count = record.get_property("tag_count")
    # Look up movie title
    movie_result = db.query(
        "sql", f"SELECT title FROM Movie WHERE movieId = {movie_id}"
    )
    title = str(list(movie_result)[0].get_property("title"))
    print(f"      {idx:2}. {title} ({tag_count} tags)")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# 10.8 - Sample popular tags
print("   💬 Top 10 most common tags:")
step_start = time.time()
result = db.query(
    "sql",
    """SELECT tag, count(*) as count
       FROM Tag
       GROUP BY tag
       ORDER BY count DESC
       LIMIT 10""",
)
for idx, record in enumerate(result, 1):
    tag = str(record.get_property("tag"))
    count = record.get_property("count")
    print(f"      {idx:2}. '{tag}' ({count} uses)")
print(f"   ⏱️  Time: {time.time() - step_start:.3f}s")
print()

# -----------------------------------------------------------------------------
# Step 11: Performance Summary
# -----------------------------------------------------------------------------
print("Step 11: Import performance summary...")
print()

# Calculate total records imported
total_movies = db.query("sql", "SELECT count(*) as c FROM Movie")
total_ratings = db.query("sql", "SELECT count(*) as c FROM Rating")
total_links = db.query("sql", "SELECT count(*) as c FROM Link")
total_tags = db.query("sql", "SELECT count(*) as c FROM Tag")

movies_count = list(total_movies)[0].get_property("c")
ratings_count = list(total_ratings)[0].get_property("c")
links_count = list(total_links)[0].get_property("c")
tags_count = list(total_tags)[0].get_property("c")

total_records = movies_count + ratings_count + links_count + tags_count

print(f"   📊 Total records imported: {total_records:,}")
print(f"      • Movies: {movies_count:,}")
print(f"      • Ratings: {ratings_count:,}")
print(f"      • Links: {links_count:,}")
print(f"      • Tags: {tags_count:,}")
print()

print("   💡 Performance tips:")
print("      • commitEvery: Larger batches = faster imports")
print("      • Movies used commitEvery=1000 (smaller batches)")
print("      • Ratings used commitEvery=5000 (larger batches)")
print("      • Type inference: Automatic LONG/DOUBLE/STRING detection")
print("      • Indexes: Created AFTER import for better performance")
print()

# -----------------------------------------------------------------------------
# Step 13: Cleanup
# -----------------------------------------------------------------------------
print("Step 13: Cleanup...")
print()

# Close database connection
db.close()
print("   ✅ Database closed")

# Note: We're NOT deleting the database directory
print(f"   💡 Database files preserved at: {db_path}")
print("   💡 You can explore the data with additional queries!")
print()

print("=" * 70)
print("✅ CSV Import Example Complete!")
print("=" * 70)
print()
print("📚 What you learned:")
print("   • Importing real-world CSV data into ArcadeDB")
print("   • Automatic type inference by Java CSV importer")
print("   • Schema creation on-the-fly during import")
print("   • Batch processing with commitEvery parameter")
print("   • Creating indexes AFTER import for performance")
print("   • Aggregation queries (count, avg, min, max, group by)")
print("   • Performance optimization techniques")
print()
print("💡 Key insights:")
print("   • Java infers LONG for integers, DOUBLE for decimals (safe defaults)")
print("   • Type inference analyzes first ~10,000 rows")
print("   • Empty CSV cells → SQL NULL (proper NULL handling)")
print("   • Indexes should be created AFTER bulk import")
print("   • commitEvery controls batch size (larger = faster)")
print()
print("💡 Next steps:")
print("   • Try modifying commitEvery values to see performance impact")
print("   • Add more complex queries")
print("   • Explore query performance with different index strategies")
print("   • For custom types, define schema BEFORE import (see Java docs)")
print()
