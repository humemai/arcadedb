import arcadedb_embedded as arcadedb
import os
import shutil

print("\n" + "=" * 70)
print(
    f"DEMO: Create a database, define schema, insert and query data using ArcadeDB "
    f"Python bindings."
)
print("=" * 70)

root_path = "./my_test_databases"

# Cleanup before starting
if os.path.exists(root_path):
    shutil.rmtree(root_path, ignore_errors=True)
    print(f"🧹 Cleaned up existing directory: {root_path}")

print("\n1️⃣  Starting ArcadeDB server...")

server = arcadedb.create_server(
    root_path=root_path,
    root_password="test12345",
)
server.start()

print(f"   ✅ Server started on port {server.get_http_port()}")
print(f"   📊 Studio URL: {server.get_studio_url()}")

# Step 2: Create database
print("\n2️⃣  Creating database...")
db = server.create_database("mydb")
print(f"   ✅ Database 'mydb' created")

# Step 3: Create schema
print("\n3️⃣  Creating schema...")
db.command("sql", "CREATE DOCUMENT TYPE Product")
print("   ✅ Created Product type")

# Step 4: Insert data
print("\n4️⃣  Inserting products...")
with db.transaction():
    db.command(
        "sql", "INSERT INTO Product SET name = 'Laptop', price = 999, stock = 10"
    )
    db.command("sql", "INSERT INTO Product SET name = 'Mouse', price = 29, stock = 50")
    db.command(
        "sql", "INSERT INTO Product SET name = 'Keyboard', price = 79, stock = 25"
    )
print("   ✅ Inserted 3 products")

# Step 5: Query all products
print("\n5️⃣  Querying all products...")
result = db.query("sql", "SELECT FROM Product ORDER BY price DESC")

print("   📦 Products in database:")
for record in result:
    name = str(record.get_property("name"))
    price = record.get_property("price")
    stock = record.get_property("stock")
    print(f"      • {name:12} - ${price:4} (stock: {stock})")

# Step 6: Count query
print("\n6️⃣  Running count query...")
result = db.query("sql", "SELECT count(*) as total FROM Product")
total = list(result)[0].get_property("total")
print(f"   📊 Total products: {total}")

# Step 7: Filtered query
print("\n7️⃣  Finding affordable products (< $100)...")
result = db.query("sql", "SELECT FROM Product WHERE price < 100")
affordable = list(result)
print(f"   💰 Found {len(affordable)} affordable products:")
for record in affordable:
    name = str(record.get_property("name"))
    price = record.get_property("price")
    print(f"      • {name} - ${price}")

# Step 8: Sum query
print("\n8️⃣  Calculating total inventory...")
result = db.query("sql", "SELECT sum(stock) as total_stock FROM Product")
total_stock = list(result)[0].get_property("total_stock")
print(f"   📦 Total inventory: {total_stock} units")

print("\n✅ Demo Complete! All logs in one place!")

# Cleanup
print("\n🧹 Cleaning up...")
server.stop()
print("   ✅ Server stopped")

if os.path.exists(root_path):
    shutil.rmtree(root_path, ignore_errors=True)
    print(f"   🧹 Cleaned up {root_path}")


print("\n👋 Done!\n")
