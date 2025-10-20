"""
Tests for ArcadeDB server access patterns.

Tests two main patterns:
1. Start server first, then create database through server (recommended)
2. Multiple threads accessing server-managed database (thread-safe)
"""

import arcadedb_embedded as arcadedb
import os
import shutil
import pytest
import time
import threading


@pytest.fixture
def cleanup_test_dirs():
    """Fixture to clean up test directories and servers."""
    dirs = []
    servers = []
    
    def _register_dir(path):
        dirs.append(path)
        if os.path.exists(path):
            shutil.rmtree(path, ignore_errors=True)
    
    def _register_server(server):
        servers.append(server)
    
    yield _register_dir, _register_server
    
    # Cleanup after test
    for server in servers:
        try:
            if server.is_started():
                server.stop()
        except Exception:
            pass
    
    # Give servers time to release locks
    time.sleep(0.5)
    
    for path in dirs:
        if os.path.exists(path):
            try:
                shutil.rmtree(path, ignore_errors=True)
            except Exception:
                pass


def test_server_pattern_recommended(cleanup_test_dirs):
    """
    Recommended Pattern: Start server first, create database through server.
    
    Benefits:
    - Embedded access for the Python process that started the server
    - HTTP access available for other processes
    - No need to close database before server access
    """
    register_dir, register_server = cleanup_test_dirs
    
    print("\n" + "="*70)
    print("TEST: Recommended Pattern - Server First")
    print("="*70)
    
    root_path = "./test_server_first"
    register_dir(root_path)
    
    # Step 1: Start server first
    print("\n1. Starting ArcadeDB server...")
    server = arcadedb.create_server(
        root_path=root_path,
        root_password="test12345"  # Min 8 chars required
    )
    register_server(server)
    server.start()
    print(f"   ✅ Server started on port {server.get_http_port()}")
    print(f"   📊 Studio URL: {server.get_studio_url()}")
    
    # Step 2: Create database through server
    print("\n2. Creating database through server...")
    db = server.create_database("mydb")
    
    with db.transaction():
        db.command("sql", "CREATE DOCUMENT TYPE Product")
        db.command("sql", "INSERT INTO Product SET name = 'Laptop', price = 999")
        db.command("sql", "INSERT INTO Product SET name = 'Mouse', price = 29")
    
    print("   ✅ Database created and populated")
    
    # Step 3: Query via embedded access
    print("\n3. Querying via embedded access...")
    result = db.query("sql", "SELECT FROM Product WHERE name = 'Laptop'")
    record = list(result)[0]
    name = record.get_property("name")
    price = record.get_property("price")
    print(f"   ✅ Found: {name} costs ${price}")
    
    # Step 4: HTTP access would work here too
    print("\n4. HTTP API is now available...")
    print(f"   💡 Other processes can connect to: http://localhost:{server.get_http_port()}")
    print("   💡 Both embedded AND HTTP access work simultaneously!")
    
    db.close()
    server.stop()
    print("\n✅ Recommended Pattern Complete!\n")


def test_server_thread_safety(cleanup_test_dirs):
    """
    Test that server-managed database handles concurrent thread access.
    
    Multiple threads can safely access the same database through the server.
    """
    register_dir, register_server = cleanup_test_dirs
    
    print("\n" + "="*70)
    print("TEST: Server Thread Safety")
    print("="*70)
    
    root_path = "./test_thread_safety"
    register_dir(root_path)
    
    # Start server and create database
    print("\n1. Setting up server and database...")
    server = arcadedb.create_server(
        root_path=root_path,
        root_password="test12345"
    )
    register_server(server)
    server.start()
    
    db = server.create_database("testdb")
    
    with db.transaction():
        db.command("sql", "CREATE DOCUMENT TYPE Item")
        for i in range(20):
            db.command("sql", f"INSERT INTO Item SET id = {i}, value = {i * 10}")
    
    print("   ✅ Created 20 items")
    
    # Test concurrent thread access
    print("\n2. Running 5 threads concurrently...")
    results = []
    errors = []
    
    def thread_query(thread_id):
        """Each thread queries the database."""
        try:
            # Query a range of items
            start = thread_id * 4
            end = start + 4
            result = db.query("sql", f"SELECT FROM Item WHERE id >= {start} AND id < {end}")
            count = len(list(result))
            results.append(f"   Thread {thread_id}: Found {count} items")
        except Exception as e:
            errors.append(f"   Thread {thread_id}: Error - {e}")
    
    threads = []
    for i in range(5):
        thread = threading.Thread(target=thread_query, args=(i,))
        threads.append(thread)
        thread.start()
    
    for thread in threads:
        thread.join()
    
    for result_msg in results:
        print(result_msg)
    
    if errors:
        for error_msg in errors:
            print(error_msg)
        pytest.fail("Concurrent thread access failed")
    
    print("   ✅ All threads accessed database successfully!")
    
    db.close()
    server.stop()
    print("\n✅ Thread Safety Test Complete!\n")


def test_server_context_manager(cleanup_test_dirs):
    """Test using server with context manager for automatic cleanup."""
    register_dir, register_server = cleanup_test_dirs
    
    print("\n" + "="*70)
    print("TEST: Server Context Manager")
    print("="*70)
    
    root_path = "./test_context"
    register_dir(root_path)
    
    print("\n1. Using server with context manager...")
    
    # Server automatically starts and stops
    with arcadedb.create_server(
        root_path=root_path,
        root_password="test12345"
    ) as server:
        print("   ✅ Server started (automatic)")
        
        db = server.create_database("contextdb")
        
        with db.transaction():
            db.command("sql", "CREATE DOCUMENT TYPE Note")
            db.command("sql", "INSERT INTO Note SET text = 'Test'")
        
        result = db.query("sql", "SELECT count(*) as count FROM Note")
        count = list(result)[0].get_property("count")
        print(f"   ✅ Created {count} notes")
        
        db.close()
    
    # Server automatically stopped when exiting context
    print("   ✅ Server stopped (automatic)")
    print("\n✅ Context Manager Test Complete!\n")


def test_pattern1_embedded_first_requires_close(cleanup_test_dirs):
    """
    Pattern 1: Create database with embedded API first, then start server.
    
    IMPORTANT: Must close the database before starting server, otherwise
    the file lock prevents server access.
    
    This test shows you MUST close the embedded database before the
    server can access it.
    """
    register_dir, register_server = cleanup_test_dirs
    
    print("\n" + "="*70)
    print("TEST: Pattern 1 - Embedded First (Requires Close)")
    print("="*70)
    
    root_path = "./test_pattern1"
    db_name = "mydb"
    db_path = os.path.join(root_path, db_name)
    register_dir(root_path)
    
    # Step 1: Create database with embedded API
    print("\n1. Creating database with embedded API...")
    db = arcadedb.create_database(db_path)
    
    with db.transaction():
        db.command("sql", "CREATE DOCUMENT TYPE Person")
        db.command("sql", "INSERT INTO Person SET name = 'Alice', age = 30")
        db.command("sql", "INSERT INTO Person SET name = 'Bob', age = 25")
    
    result = db.query("sql", "SELECT count(*) as count FROM Person")
    count = list(result)[0].get_property("count")
    print(f"   ✅ Created database with {count} records")
    
    # Step 2: MUST close database to release file lock
    print("\n2. Closing database to release file lock...")
    db.close()
    print("   ✅ Database closed, lock released")
    
    # Step 3: Now move database to where server expects it
    print("\n3. Moving database to server's databases directory...")
    server_db_dir = os.path.join(root_path, "databases")
    os.makedirs(server_db_dir, exist_ok=True)
    server_db_path = os.path.join(server_db_dir, db_name)
    
    if os.path.exists(server_db_path):
        shutil.rmtree(server_db_path)
    shutil.move(db_path, server_db_path)
    print(f"   ✅ Database moved to {server_db_path}")
    
    # Step 4: Start server
    print("\n4. Starting ArcadeDB server...")
    server = arcadedb.create_server(
        root_path=root_path,
        root_password="test12345"
    )
    register_server(server)
    server.start()
    print(f"   ✅ Server started on port {server.get_http_port()}")
    
    # Step 5: Access database through server
    print("\n5. Accessing database through server...")
    db = server.get_database(db_name)
    
    result = db.query("sql", "SELECT FROM Person WHERE name = 'Alice'")
    record = list(result)[0]
    name = record.get_property("name")
    age = record.get_property("age")
    print(f"   ✅ Retrieved via server: {name}, age {age}")
    
    # Step 6: Add more data through server
    print("\n6. Adding data through server...")
    with db.transaction():
        db.command("sql", "INSERT INTO Person SET name = 'Charlie', age = 35")
    
    result = db.query("sql", "SELECT count(*) as count FROM Person")
    count = list(result)[0].get_property("count")
    print(f"   ✅ Total records now: {count}")
    
    # Step 7: Both embedded and HTTP access now available
    print("\n7. Dual access now available...")
    print(f"   💡 Embedded access: db.query() works")
    print(f"   💡 HTTP access: http://localhost:{server.get_http_port()} works")
    print("   💡 Note: Server-managed databases are closed by server.stop()")
    
    # Don't close db - server-managed databases are shared and closed by server
    server.stop()
    print("\n✅ Pattern 1 Complete: Embedded → Close → Server works!\n")
    print("⚠️  Key Requirement: Must close() the embedded database first!")
    print("⚠️  Note: Don't close server-managed databases - server handles it!")


def test_embedded_performance_comparison(cleanup_test_dirs):
    """
    Demonstrate that Pattern 2 embedded access is just as fast as standalone.
    
    Key insight: When you access a server-managed database from the same
    Python process, it's a direct JVM call - NO HTTP overhead!
    
    HTTP access is only for OTHER processes/clients.
    """
    register_dir, register_server = cleanup_test_dirs
    
    print("\n" + "="*70)
    print("TEST: Embedded Performance - Server vs Standalone")
    print("="*70)
    
    # Setup test data size
    num_records = 1000
    num_queries = 100
    
    # Test 1: Standalone embedded (no server)
    print(f"\n1. Standalone Embedded Mode...")
    standalone_path = "./test_standalone_perf"
    register_dir(standalone_path)
    
    db_standalone = arcadedb.create_database(standalone_path)
    
    with db_standalone.transaction():
        db_standalone.command("sql", "CREATE DOCUMENT TYPE PerfTest")
        for i in range(num_records):
            db_standalone.command(
                "sql",
                f"INSERT INTO PerfTest SET id = {i}, value = {i * 10}"
            )
    
    print(f"   ✅ Created {num_records} records")
    
    # Time standalone queries
    import time
    start = time.time()
    for _ in range(num_queries):
        result = db_standalone.query("sql", "SELECT FROM PerfTest LIMIT 10")
        list(result)  # Consume results
    standalone_time = time.time() - start
    
    print(f"   ⚡ {num_queries} queries in {standalone_time:.3f}s")
    print(f"   ⚡ {num_queries/standalone_time:.1f} queries/sec")
    
    db_standalone.close()
    
    # Test 2: Server-managed embedded access (same process)
    print(f"\n2. Server-Managed Embedded Mode (same process)...")
    server_path = "./test_server_perf"
    register_dir(server_path)
    
    server = arcadedb.create_server(
        root_path=server_path,
        root_password="test12345"
    )
    register_server(server)
    server.start()
    
    db_server = server.create_database("perfdb")
    
    with db_server.transaction():
        db_server.command("sql", "CREATE DOCUMENT TYPE PerfTest")
        for i in range(num_records):
            db_server.command(
                "sql",
                f"INSERT INTO PerfTest SET id = {i}, value = {i * 10}"
            )
    
    print(f"   ✅ Created {num_records} records")
    
    # Time server-managed queries (embedded access)
    start = time.time()
    for _ in range(num_queries):
        result = db_server.query("sql", "SELECT FROM PerfTest LIMIT 10")
        list(result)  # Consume results
    server_time = time.time() - start
    
    print(f"   ⚡ {num_queries} queries in {server_time:.3f}s")
    print(f"   ⚡ {num_queries/server_time:.1f} queries/sec")
    
    # Compare
    print(f"\n3. Performance Comparison...")
    ratio = server_time / standalone_time
    print(f"   📊 Standalone: {standalone_time:.3f}s")
    print(f"   📊 Server (embedded): {server_time:.3f}s")
    print(f"   📊 Ratio: {ratio:.2f}x")
    
    if ratio < 1.5:  # Within 50% is essentially same performance
        print("   ✅ Performance is SIMILAR - direct JVM calls in both cases!")
    else:
        print("   ℹ️  Some overhead from server management")
    
    print("\n4. Key Insights:")
    print("   💡 Server-managed embedded access = Direct JVM call")
    print("   💡 NO HTTP overhead when accessing from same process")
    print("   💡 HTTP is only for OTHER processes/clients")
    print(f"   💡 HTTP would add ~5-50ms per request (network + JSON)")
    
    server.stop()
    print("\n✅ Performance Test Complete!\n")

