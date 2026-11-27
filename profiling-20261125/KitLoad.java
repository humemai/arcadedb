import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Adds the eight write types of the pg14 kit (MemP..MemZ, ChkP..ChkZ, each seeded with the persons) to a loaded database, so that the
 * schema the planner sees has the size of the kit's: usage KitLoad <dataDir> <dbDir>. */
public class KitLoad {
  public static void main(final String[] a) throws Exception {
    final List<String> persons = Files.readAllLines(Path.of(a[0]).resolve("persons.tsv"));
    final DatabaseFactory f = new DatabaseFactory(a[1]);
    final Database db = f.open();
    for (final String tbl : new String[] { "MemP", "MemS", "MemC", "MemZ", "ChkP", "ChkS", "ChkC", "ChkZ" }) {
      db.command("sql", "CREATE VERTEX TYPE " + tbl);
      for (final String p : new String[] { "id LONG", "name STRING", "age INTEGER", "city STRING", "created LONG" })
        db.command("sql", "CREATE PROPERTY " + tbl + "." + p);
      db.command("sql", "CREATE INDEX ON " + tbl + " (id) UNIQUE");
      for (int s = 0; s < persons.size(); s += 5000) {
        db.begin();
        for (final String line : persons.subList(s, Math.min(persons.size(), s + 5000))) {
          final String[] c = line.split("\t");
          db.newVertex(tbl).set("id", Long.parseLong(c[0]), "name", c[1], "age", Integer.parseInt(c[2]), "city", c[3], "created",
              Long.parseLong(c[4])).save();
        }
        db.commit();
      }
    }
    System.out.println("kit types added: " + db.getSchema().getTypes().size() + " types in all");
    db.close();
    f.close();
  }
}
